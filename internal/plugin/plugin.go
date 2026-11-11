// Package plugin runs a mobile view from outside wallflower: a program of
// the user's that speaks mobileview.Source's calls as JSON lines on its
// stdin and stdout, as language servers do, so it can be written in any
// language and needs no rebuild. docs/plugins.md says what it's sent and
// what it says back.
package plugin

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os/exec"
	"sync"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
	"github.com/ahmd-sh/wallflower/internal/userenv"
)

const (
	// restart is how soon a program that stopped is started again.
	restart = 5 * time.Second
	// queued is how many messages can wait for the program to read them;
	// past that, it isn't reading, and more are dropped.
	queued = 64
)

// A Source is a mobile view read by a program run as Command (its path,
// then its arguments), started when first asked, and again if it stops.
// It runs in Env, and is found on its PATH: nil is the user's environment
// (userenv), not the ssh connection's the gateway has.
type Source struct {
	Command []string
	Env     []string

	mu      sync.Mutex
	run     *running
	started time.Time
	closed  bool
	panes   map[string]*sync.Mutex // held while each pane is read
	reads   int                    // reads asked for, numbering each
}

// running is the program while it runs.
type running struct {
	cmd     *exec.Cmd
	in      io.WriteCloser
	out     chan []byte // lines on their way to it
	mu      sync.Mutex
	found   map[string]mobileview.Found // what it said last
	readers map[string]reader           // by pane, while it's read
	done    chan struct{}
}

// A reader takes what the program says of the read it numbered n.
type reader struct {
	n    int
	take func(message)
}

// message is a line the program writes: which panes it reads, or what's
// in a pane for the read numbered N, or that there's no more of it.
type message struct {
	Found  map[string]found `json:"found"`
	Pane   string           `json:"pane"`
	N      int              `json:"n"`
	Update *update          `json:"update"`
	End    *string          `json:"end"`
}

type found struct {
	Activity string `json:"activity"`
	Since    int64  `json:"since"`
}

type update struct {
	Reset   bool      `json:"reset"`
	More    bool      `json:"more"`
	Entries []entry   `json:"entries"`
	Context *int      `json:"context"`
	Title   string    `json:"title"`
	Queued  *[]string `json:"queued"`
}

// entry is a mobileview.Entry as the wire spells it.
type entry struct {
	ID        string `json:"id"`
	From      string `json:"from"`
	Kind      string `json:"kind"`
	Text      string `json:"text"`
	Name      string `json:"name"`
	Status    string `json:"status"`
	Output    string `json:"output"`
	OutputCut int    `json:"output_cut"`
	Diff      string `json:"diff"`
	DiffCut   int    `json:"diff_cut"`
	Mime      string `json:"mime"`
	Data      string `json:"data"`
}

// pane is a mobileview.Pane as the program is told of it.
type pane struct {
	ID      string `json:"id"`
	TTY     string `json:"tty"`
	Command string `json:"command"`
	Title   string `json:"title,omitempty"`
	Path    string `json:"path,omitempty"`
}

func paneOf(p mobileview.Pane) pane {
	return pane{ID: p.ID, TTY: p.TTY, Command: p.Command, Title: p.Title, Path: p.Path}
}

// Find asks the program which panes it reads, and says what it said last,
// without waiting: it's asked every moment or so, on the gateway's one
// loop. While the program isn't running, it reads none.
func (s *Source) Find(panes []mobileview.Pane) map[string]mobileview.Found {
	r := s.running()
	if r == nil {
		return nil
	}
	ps := make([]pane, len(panes))
	for i, p := range panes {
		ps[i] = paneOf(p)
	}
	r.send(map[string]any{"find": ps})
	r.mu.Lock()
	defer r.mu.Unlock()
	out := map[string]mobileview.Found{}
	for _, p := range panes {
		if f, ok := r.found[p.ID]; ok {
			out[p.ID] = f
		}
	}
	return out
}

// Read has the program send what's in the pane until ctx is done, or it
// says there's no more.
func (s *Source) Read(ctx context.Context, p mobileview.Pane, send func(mobileview.Update)) error {
	// One read of a pane at a time: the last one's stop goes before this one's read.
	reading := s.pane(p.ID)
	reading.Lock()
	defer reading.Unlock()
	r := s.running()
	if r == nil {
		return errors.New("the plugin isn't running")
	}
	ended := make(chan string, 1)
	s.mu.Lock()
	s.reads++
	n := s.reads
	s.mu.Unlock()
	r.mu.Lock()
	r.readers[p.ID] = reader{n, func(m message) {
		switch {
		case m.End != nil:
			select {
			case ended <- *m.End:
			default:
			}
		case m.Update != nil:
			send(updateOf(*m.Update))
		}
	}}
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.readers, p.ID)
		r.mu.Unlock()
		r.send(map[string]any{"stop": p.ID})
	}()
	r.send(map[string]any{"read": paneOf(p), "n": n})
	select {
	case <-ctx.Done():
		return ctx.Err()
	case why := <-ended:
		return errors.New(why)
	case <-r.done:
		return errors.New("the plugin stopped")
	}
}

func (s *Source) pane(id string) *sync.Mutex {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.panes == nil {
		s.panes = map[string]*sync.Mutex{}
	}
	if s.panes[id] == nil {
		s.panes[id] = &sync.Mutex{}
	}
	return s.panes[id]
}

func updateOf(u update) mobileview.Update {
	out := mobileview.Update{Reset: u.Reset, More: u.More, Title: u.Title}
	if u.Context != nil {
		out.Context, out.Measured = *u.Context, true
	}
	if u.Queued != nil {
		out.Queued, out.Requeued = *u.Queued, true
	}
	for _, e := range u.Entries {
		out.Entries = append(out.Entries, mobileview.Entry(e))
	}
	return out
}

// running is the program, started if it isn't running and wasn't just
// now; nil if it can't be.
func (s *Source) running() *running {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.run != nil {
		select {
		case <-s.run.done:
			s.run = nil
		default:
			return s.run
		}
	}
	if s.closed || len(s.Command) == 0 || time.Since(s.started) < restart {
		return nil
	}
	s.started = time.Now()
	env := s.Env
	if env == nil {
		env = userenv.Environ()
	}
	name := s.Command[0]
	if p, err := userenv.LookPath(name, env); err == nil {
		name = p
	}
	cmd := exec.Command(name, s.Command[1:]...)
	cmd.Env = env
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil
	}
	if cmd.Start() != nil {
		return nil
	}
	r := &running{cmd: cmd, in: in, out: make(chan []byte, queued), readers: map[string]reader{}, done: make(chan struct{})}
	go r.listen(out, cmd)
	go r.write()
	s.run = r
	return r
}

// Close stops the program, if it runs, for good.
func (s *Source) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	if s.run != nil {
		s.run.in.Close()
		s.run.cmd.Process.Kill()
	}
	return nil
}

// listen hands each line the program writes to whoever waits for it, until
// it stops.
func (r *running) listen(out io.Reader, cmd *exec.Cmd) {
	defer close(r.done)
	defer cmd.Wait()
	lines := bufio.NewScanner(out)
	lines.Buffer(make([]byte, 64<<10), 16<<20)
	for lines.Scan() {
		var m message
		if json.Unmarshal(lines.Bytes(), &m) != nil {
			continue
		}
		if m.Found != nil {
			f := make(map[string]mobileview.Found, len(m.Found))
			for id, x := range m.Found {
				f[id] = mobileview.Found{Activity: mobileview.Activity(x.Activity), Since: x.Since}
			}
			r.mu.Lock()
			r.found = f
			r.mu.Unlock()
			continue
		}
		r.mu.Lock()
		read, ok := r.readers[m.Pane]
		r.mu.Unlock()
		if ok && read.n == m.N { // not what's left of an earlier read
			read.take(m)
		}
	}
}

// send queues a message for the program, dropped if it isn't reading what
// it's sent: the gateway doesn't wait on it.
func (r *running) send(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	select {
	case r.out <- append(b, '\n'):
	default:
	}
}

// write hands the program what's queued for it, in order, until it stops.
func (r *running) write() {
	for {
		select {
		case b := <-r.out:
			if _, err := r.in.Write(b); err != nil {
				return
			}
		case <-r.done:
			return
		}
	}
}
