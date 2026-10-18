package gateway

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/readview"
)

func init() { readview.Poll = 50 * time.Millisecond }

// lines is a source reading the panes on terminals the test names, each
// from a file of {"id","text"} lines.
type lines struct {
	mu    sync.Mutex
	files map[string]string // tty -> its recording
}

func (l *lines) Name() string { return "lines" }

func (l *lines) Find(panes []readview.Pane) map[string]bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	found := map[string]bool{}
	for _, p := range panes {
		if _, ok := l.files[p.TTY]; ok {
			found[p.ID] = true
		}
	}
	return found
}

func (l *lines) Read(ctx context.Context, p readview.Pane, send func(readview.Update)) error {
	recording := func() (readview.Recording, bool) {
		l.mu.Lock()
		defer l.mu.Unlock()
		path, ok := l.files[p.TTY]
		return readview.Recording{Path: path}, ok
	}
	return readview.Tail(ctx, recording, func() readview.Parser { return linesParser{} }, send)
}

func (l *lines) set(tty, path string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.files[tty] = path
}

type linesParser struct{}

func (linesParser) Line(b []byte) []readview.Entry {
	var e struct{ ID, Text string }
	if json.Unmarshal(b, &e) != nil {
		return nil
	}
	return []readview.Entry{{ID: e.ID, Role: readview.Assistant, Kind: readview.Text, Text: e.Text}}
}

func (c *client) entries(what string, match func(protocol.Entries) bool) protocol.Entries {
	c.t.Helper()
	_, m := c.expect(what, func(_ protocol.Frame, m protocol.Message) bool {
		e, ok := m.(*protocol.Entries)
		return ok && match(*e)
	})
	return *m.(*protocol.Entries)
}

// pong waits for the pong to a ping, failing on any entries before it.
func (c *client) pong(id int) {
	c.t.Helper()
	c.send(protocol.Ping{ID: id})
	c.expect("pong, with no entries before it", func(_ protocol.Frame, m protocol.Message) bool {
		if e, ok := m.(*protocol.Entries); ok {
			c.t.Fatalf("entries after stopping: %+v", e)
		}
		pong, ok := m.(*protocol.Pong)
		return ok && pong.ID == id
	})
}

func TestReadView(t *testing.T) {
	src := &lines{files: map[string]string{}}
	c := connectOpts(t, context.Background(), 56, 20, Options{Readers: []readview.Source{src}})
	p := activePane(c.state("state", func(protocol.State) bool { return true }))
	if p.Transcript != "" {
		t.Fatalf("a reader before there is one: %q", p.Transcript)
	}

	// The pane's shell stands in for a program writing what's said.
	path := filepath.Join(t.TempDir(), "conversation.jsonl")
	write := func(s string) {
		f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
		if err != nil {
			t.Fatal(err)
		}
		f.WriteString(s)
		f.Close()
	}
	write(`{"id":"a","text":"hello"}` + "\n")
	src.set(c.tmux("display", "-p", "-t", p.ID, "#{pane_tty}"), path)
	c.tmux("rename-window", "-t", "t", "chat") // state is re-sent on changes
	c.state("pane with a reader", func(s protocol.State) bool { return activePane(s).Transcript == "lines" })

	c.send(protocol.Transcript{Pane: p.ID, On: true})
	c.entries("recent entries", func(e protocol.Entries) bool {
		return e.Pane == p.ID && e.Reset && len(e.Entries) == 1 && e.Entries[0].Text == "hello"
	})
	write(`{"id":"b","text":"world"}` + "\n")
	c.entries("a new entry", func(e protocol.Entries) bool {
		return e.Pane == p.ID && !e.Reset && len(e.Entries) == 1 && e.Entries[0].ID == "b"
	})

	// Turned off, nothing more comes, however long it's written to.
	c.send(protocol.Transcript{Pane: p.ID, On: false})
	c.pong(9)
	write(`{"id":"c","text":"unseen"}` + "\n")
	time.Sleep(4 * readview.Poll)
	c.pong(10)

	// On again, it starts over.
	c.send(protocol.Transcript{Pane: p.ID, On: true})
	c.entries("all of it again", func(e protocol.Entries) bool { return e.Pane == p.ID && e.Reset && len(e.Entries) == 3 })

	// A pane without one says so.
	other := c.tmux("split-window", "-P", "-F", "#{pane_id}", "-t", p.ID)
	c.send(protocol.Transcript{Pane: other, On: true})
	c.entries("nothing to read", func(e protocol.Entries) bool { return e.Pane == other && e.Error != "" })
}
