package gateway

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/transcript"
)

// lines is a transcript source whose processes are set by the test, and
// whose lines are {"id","text"} entries.
type lines struct {
	mu      sync.Mutex
	running map[int]transcript.Session
}

func (l *lines) Name() string { return "lines" }

func (l *lines) Running() map[int]transcript.Session {
	l.mu.Lock()
	defer l.mu.Unlock()
	r := map[int]transcript.Session{}
	for k, v := range l.running {
		r[k] = v
	}
	return r
}

func (l *lines) set(pid int, path string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.running[pid] = transcript.Session{Path: path}
}

func (l *lines) Parser() transcript.Parser { return linesParser{} }

type linesParser struct{}

func (linesParser) Line(b []byte) []protocol.Entry {
	var e struct{ ID, Text string }
	if json.Unmarshal(b, &e) != nil {
		return nil
	}
	return []protocol.Entry{{ID: e.ID, Role: "assistant", Kind: "text", Text: e.Text}}
}

func (c *client) entries(what string, match func(protocol.Entries) bool) protocol.Entries {
	c.t.Helper()
	_, m := c.expect(what, func(_ protocol.Frame, m protocol.Message) bool {
		e, ok := m.(*protocol.Entries)
		return ok && match(*e)
	})
	return *m.(*protocol.Entries)
}

func TestTranscript(t *testing.T) {
	src := &lines{running: map[int]transcript.Session{}}
	c := connectOpts(t, context.Background(), 56, 20, Options{Transcripts: []transcript.Source{src}})
	p := activePane(c.state("state", func(protocol.State) bool { return true }))
	if p.Transcript != "" {
		t.Fatalf("transcript before there is one: %q", p.Transcript)
	}

	// The pane's shell stands in for a CLI writing a transcript.
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
	pid, _ := strconv.Atoi(c.tmux("display", "-p", "-t", p.ID, "#{pane_pid}"))
	src.set(pid, path)
	c.tmux("rename-window", "-t", "t", "chat") // state is re-sent on changes
	c.state("pane with a transcript", func(s protocol.State) bool { return activePane(s).Transcript == "lines" })

	c.send(protocol.Transcript{Pane: p.ID, On: true})
	c.entries("recent entries", func(e protocol.Entries) bool {
		return e.Pane == p.ID && e.Reset && len(e.Entries) == 1 && e.Entries[0].Text == "hello"
	})
	write(`{"id":"b","text":"world"}` + "\n")
	c.entries("a new entry", func(e protocol.Entries) bool {
		return e.Pane == p.ID && !e.Reset && len(e.Entries) == 1 && e.Entries[0].ID == "b"
	})

	// Stopped, nothing more comes.
	c.send(protocol.Transcript{Pane: p.ID, On: false})
	write(`{"id":"c","text":"unseen"}` + "\n")
	time.Sleep(time.Second)
	c.send(protocol.Ping{ID: 9})
	c.expect("pong, with no entries before it", func(_ protocol.Frame, m protocol.Message) bool {
		if e, ok := m.(*protocol.Entries); ok {
			t.Fatalf("entries after stopping: %+v", e)
		}
		pong, ok := m.(*protocol.Pong)
		return ok && pong.ID == 9
	})

	// A pane without one says so.
	other := c.tmux("split-window", "-P", "-F", "#{pane_id}", "-t", p.ID)
	c.send(protocol.Transcript{Pane: other, On: true})
	c.entries("no conversation", func(e protocol.Entries) bool { return e.Pane == other && e.Error != "" })
}
