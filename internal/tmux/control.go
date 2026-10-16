// Package tmux drives a tmux server through control mode (tmux -C).
package tmux

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
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
	pending []chan result
	closed  bool

	events *queue
	done   chan struct{}
	err    error
}

type result struct {
	lines []string
	err   error
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

func Start(ctx context.Context, opts Options) (*Client, error) {
	var args []string
	switch {
	case strings.Contains(opts.Socket, "/"):
		args = append(args, "-S", opts.Socket)
	case opts.Socket != "":
		args = append(args, "-L", opts.Socket)
	}
	args = append(args, "-C", "attach-session")
	if opts.Session != "" {
		args = append(args, "-t", opts.Session)
	}
	if len(opts.Flags) > 0 {
		args = append(args, "-f", strings.Join(opts.Flags, ","))
	}

	c := &Client{events: newQueue(), done: make(chan struct{})}
	c.cmd = exec.CommandContext(ctx, "tmux", args...)
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

// Run sends one command and waits for its output block.
func (c *Client) Run(command string) ([]string, error) {
	if strings.ContainsAny(command, "\r\n") {
		return nil, fmt.Errorf("tmux: command contains a newline: %q", command)
	}
	ch := make(chan result, 1)
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil, ErrClosed
	}
	c.pending = append(c.pending, ch)
	_, err := io.WriteString(c.stdin, command+"\n")
	c.mu.Unlock()
	if err != nil {
		return nil, err
	}
	r := <-ch
	var cerr *CommandError
	if errors.As(r.err, &cerr) {
		cerr.Command = command
	}
	return r.lines, r.err
}

// Events delivers notifications in order. It is closed when the client exits.
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
					r := result{lines: block}
					if kind == "%error" {
						r = result{err: &CommandError{Output: block}}
					}
					c.deliver(r)
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
			c.events.push(n)
		}
	}

	c.mu.Lock()
	c.closed = true
	for _, ch := range c.pending {
		ch <- result{err: ErrClosed}
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

func (c *Client) deliver(r result) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.pending) == 0 {
		return
	}
	ch := c.pending[0]
	c.pending = c.pending[1:]
	ch <- r
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
