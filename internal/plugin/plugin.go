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
)

const (
	// answer is how long Find waits for the program to say which panes it
	// reads; past that, it's what it said last.
	answer = 300 * time.Millisecond
	// restart is how soon a program that stopped is started again.
	restart = 5 * time.Second
)

// A Source is a mobile view read by a program run as Command (its path,
// then its arguments), started when first asked, and again if it stops.
type Source struct {
	Command []string

	mu      sync.Mutex
	run     *running
	started time.Time
	found   map[string]mobileview.Found // what the program said last
	closed  bool
}

// running is the program while it runs.
type running struct {
	cmd     *exec.Cmd
	in      io.WriteCloser
	write   sync.Mutex
	found   chan map[string]mobileview.Found
	mu      sync.Mutex
	readers map[string]func(message) // by pane, while it's read
	done    chan struct{}
}

// message is a line the program writes: which panes it reads, or what's
// in one, or that there's no more of it.
type message struct {
	Found  map[string]found `json:"found"`
	Pane   string           `json:"pane"`
	Update *update          `json:"update"`
	End    *string          `json:"end"`
}

type found struct {
	Activity string `json:"activity"`
	Since    int64  `json:"since"`
}

type update struct {
	Reset   bool    `json:"reset"`
	More    bool    `json:"more"`
	Entries []entry `json:"entries"`
	Context *int    `json:"context"`
	Title   string  `json:"title"`
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
}

func paneOf(p mobileview.Pane) pane { return pane{ID: p.ID, TTY: p.TTY, Command: p.Command} }

// Find asks the program which panes it reads; if it's slow to say, or not
// running, it's what it said last.
func (s *Source) Find(panes []mobileview.Pane) map[string]mobileview.Found {
	r := s.running()
	s.mu.Lock()
	last := s.found
	s.mu.Unlock()
	if r == nil {
		return last
	}
	ps := make([]pane, len(panes))
	for i, p := range panes {
		ps[i] = paneOf(p)
	}
	if r.send(map[string]any{"find": ps}) != nil {
		return last
	}
	select {
	case f := <-r.found:
		s.mu.Lock()
		s.found = f
		s.mu.Unlock()
		return f
	case <-time.After(answer):
		return last
	case <-r.done:
		return last
	}
}

// Read has the program send what's in the pane until ctx is done, or it
// says there's no more.
func (s *Source) Read(ctx context.Context, p mobileview.Pane, send func(mobileview.Update)) error {
	r := s.running()
	if r == nil {
		return errors.New("the plugin isn't running")
	}
	ended := make(chan string, 1)
	r.mu.Lock()
	r.readers[p.ID] = func(m message) {
		switch {
		case m.End != nil:
			select {
			case ended <- *m.End:
			default:
			}
		case m.Update != nil:
			send(updateOf(*m.Update))
		}
	}
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.readers, p.ID)
		r.mu.Unlock()
		r.send(map[string]any{"stop": p.ID})
	}()
	if err := r.send(map[string]any{"read": paneOf(p)}); err != nil {
		return err
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case why := <-ended:
		return errors.New(why)
	case <-r.done:
		return errors.New("the plugin stopped")
	}
}

func updateOf(u update) mobileview.Update {
	out := mobileview.Update{Reset: u.Reset, More: u.More, Title: u.Title}
	if u.Context != nil {
		out.Context, out.Measured = *u.Context, true
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
	cmd := exec.Command(s.Command[0], s.Command[1:]...)
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
	r := &running{cmd: cmd, in: in, found: make(chan map[string]mobileview.Found, 1), readers: map[string]func(message){}, done: make(chan struct{})}
	go r.listen(out, cmd)
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
			select {
			case <-r.found: // an answer no one waited for
			default:
			}
			r.found <- f
			continue
		}
		r.mu.Lock()
		read := r.readers[m.Pane]
		r.mu.Unlock()
		if read != nil {
			read(m)
		}
	}
}

func (r *running) send(v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	r.write.Lock()
	defer r.write.Unlock()
	_, err = r.in.Write(append(b, '\n'))
	return err
}
