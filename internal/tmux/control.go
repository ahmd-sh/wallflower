// Package tmux drives a tmux server through control mode (tmux -C).
package tmux

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
)

type Options struct {
	Socket  string   // socket name (-L) or path (-S, if it contains a slash); empty uses $TMUX or the default
	Session string   // target session; empty lets tmux pick
	Flags   []string // client flags, see attach-session -f
}

// Client is one control-mode client attached to a tmux server.
// Run may be called from any goroutine, including while handling an event.
type Client struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stderr bytes.Buffer

	mu      sync.Mutex
	pending []*pending
	sent    int
	closed  bool
	seq     uint64 // notifications read so far; owned by the reader

	events *queue
	done   chan struct{}
	err    error
}

// Reply is the output of one command line.
type Reply struct {
	Blocks [][]string // one per command in the line (a parse error is one block)
	Seq    uint64     // notifications with Seq <= this were sent before the reply
}

func (r Reply) Lines() []string {
	var out []string
	for _, b := range r.Blocks {
		out = append(out, b...)
	}
	return out
}

type pending struct {
	marker string
	reply  Reply
	err    error
	done   chan struct{}
}

var ErrClosed = errors.New("tmux: control client closed")

// CommandError is a command that tmux answered with %error.
type CommandError struct {
	Command string
	Output  []string
}

func (e *CommandError) Error() string {
	return fmt.Sprintf("tmux: %s: %s", e.Command, strings.Join(e.Output, "; "))
}

// Binary finds tmux: $PATH first, then Homebrew's prefixes, since ssh
// sessions get a minimal PATH.
func Binary() string {
	if p, err := exec.LookPath("tmux"); err == nil {
		return p
	}
	for _, p := range []string{"/opt/homebrew/bin/tmux", "/usr/local/bin/tmux"} {
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return "tmux"
}

func SocketArgs(socket string) []string {
	switch {
	case strings.Contains(socket, "/"):
		return []string{"-S", socket}
	case socket != "":
		return []string{"-L", socket}
	}
	return nil
}

func Start(ctx context.Context, opts Options) (*Client, error) {
	// -u: without a UTF-8 locale (ssh may not pass one) tmux replaces tabs
	// and non-ASCII in replies with "_".
	args := append(SocketArgs(opts.Socket), "-u", "-C", "attach-session")
	if opts.Session != "" {
		args = append(args, "-t", opts.Session)
	}
	if len(opts.Flags) > 0 {
		args = append(args, "-f", strings.Join(opts.Flags, ","))
	}

	c := &Client{events: newQueue(), done: make(chan struct{})}
	c.cmd = exec.CommandContext(ctx, Binary(), args...)
	c.cmd.Stderr = &c.stderr
	var err error
	if c.stdin, err = c.cmd.StdinPipe(); err != nil {
		return nil, err
	}
	stdout, err := c.cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := c.cmd.Start(); err != nil {
		return nil, err
	}
	go c.read(stdout)
	return c, nil
}

// Command sends one command line, which may be a ;-separated list, and
// waits for all of its output. Commands in one line run back to back, so a
// list is an atomic read of tmux state.
//
// Each line is followed by a marker command whose reply ends the line's
// output; a list produces one block per command, or a single block on a
// parse error, so blocks alone can't be matched to lines.
func (c *Client) Command(line string) (Reply, error) {
	if strings.TrimSpace(line) == "" || strings.ContainsAny(line, "\r\n") {
		return Reply{}, fmt.Errorf("tmux: invalid command line %q", line)
	}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return Reply{}, ErrClosed
	}
	c.sent++
	p := &pending{marker: fmt.Sprintf("wallflower:sync:%d", c.sent), done: make(chan struct{})}
	c.pending = append(c.pending, p)
	_, err := io.WriteString(c.stdin, line+"\ndisplay-message -p '"+p.marker+"'\n")
	c.mu.Unlock()
	if err != nil {
		return Reply{}, err
	}
	<-p.done
	var cerr *CommandError
	if errors.As(p.err, &cerr) {
		cerr.Command = line
	}
	return p.reply, p.err
}

// Run is Command for callers that only want the output lines.
func (c *Client) Run(line string) ([]string, error) {
	r, err := c.Command(line)
	return r.Lines(), err
}

// Events delivers notifications in order. It is closed when the client
// exits, and must be drained.
func (c *Client) Events() <-chan Notification { return c.events.out }

func (c *Client) Done() <-chan struct{} { return c.done }

// Err reports why the client exited, once Done is closed.
func (c *Client) Err() error {
	<-c.done
	return c.err
}

// Close detaches the client and waits for tmux to exit.
func (c *Client) Close() error {
	c.stdin.Close()
	return c.Err()
}

func (c *Client) read(stdout io.Reader) {
	br := bufio.NewReaderSize(stdout, 64<<10)
	var (
		block   []string
		guard   string // arguments of the open %begin; %end repeats them
		inBlock bool
		mine    bool
	)
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			break
		}
		line = strings.TrimSuffix(line, "\n")

		if inBlock {
			kind, args, _ := strings.Cut(line, " ")
			if (kind == "%end" || kind == "%error") && args == guard {
				if mine {
					c.endBlock(kind == "%error", block)
				}
				block, inBlock = nil, false
				continue
			}
			block = append(block, line)
			continue
		}

		if args, ok := strings.CutPrefix(line, "%begin "); ok {
			// The last field is 1 for commands this client sent, 0 for the
			// attach command itself.
			guard, inBlock, mine = args, true, strings.HasSuffix(args, " 1")
			continue
		}
		if n, ok := parseNotification(line); ok {
			c.seq++
			n.Seq = c.seq
			c.events.push(n)
		}
	}

	c.mu.Lock()
	c.closed = true
	for _, p := range c.pending {
		p.err = ErrClosed
		close(p.done)
	}
	c.pending = nil
	c.mu.Unlock()
	c.events.close()

	if err := c.cmd.Wait(); err != nil {
		if msg := strings.TrimSpace(c.stderr.String()); msg != "" {
			err = fmt.Errorf("tmux: %s", msg)
		}
		c.err = err
	}
	close(c.done)
}

func (c *Client) endBlock(failed bool, block []string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.pending) == 0 {
		return
	}
	p := c.pending[0]
	if !failed && len(block) == 1 && block[0] == p.marker {
		c.pending = c.pending[1:]
		close(p.done)
		return
	}
	p.reply.Blocks = append(p.reply.Blocks, block)
	p.reply.Seq = c.seq
	if failed && p.err == nil {
		p.err = &CommandError{Output: block}
	}
}

// queue is an unbounded FIFO between the reader and Events, so a consumer
// that calls Run while handling an event can't stall the reader.
type queue struct {
	mu     sync.Mutex
	cond   *sync.Cond
	items  []Notification
	closed bool
	out    chan Notification
}

func newQueue() *queue {
	q := &queue{out: make(chan Notification)}
	q.cond = sync.NewCond(&q.mu)
	go q.forward()
	return q
}

func (q *queue) push(n Notification) {
	q.mu.Lock()
	q.items = append(q.items, n)
	q.mu.Unlock()
	q.cond.Signal()
}

func (q *queue) close() {
	q.mu.Lock()
	q.closed = true
	q.mu.Unlock()
	q.cond.Signal()
}

func (q *queue) forward() {
	for {
		q.mu.Lock()
		for len(q.items) == 0 && !q.closed {
			q.cond.Wait()
		}
		if len(q.items) == 0 {
			q.mu.Unlock()
			close(q.out)
			return
		}
		n := q.items[0]
		q.items[0] = Notification{}
		q.items = q.items[1:]
		q.mu.Unlock()
		q.out <- n
	}
}
