package gateway

import (
	"context"
	"encoding/base64"
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
// from a file of {"id","text"} lines, or {"id","png"} for a picture, with
// what the test says the program's doing.
type lines struct {
	mu       sync.Mutex
	files    map[string]string // tty -> its recording
	activity readview.Activity
}

func (l *lines) Name() string { return "lines" }

func (l *lines) Find(panes []readview.Pane) map[string]readview.Found {
	l.mu.Lock()
	defer l.mu.Unlock()
	found := map[string]readview.Found{}
	for _, p := range panes {
		if _, ok := l.files[p.TTY]; ok {
			found[p.ID] = readview.Found{Activity: l.activity, Since: 1000}
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

func (l *lines) doing(a readview.Activity) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.activity = a
}

type linesParser struct{}

func (linesParser) Line(b []byte) []readview.Entry {
	var e struct{ ID, Text, PNG string }
	if json.Unmarshal(b, &e) != nil {
		return nil
	}
	if e.PNG != "" {
		return []readview.Entry{{ID: e.ID, From: readview.User, Kind: readview.Picture, Mime: "image/png", Data: e.PNG}}
	}
	return []readview.Entry{{ID: e.ID, From: readview.Program, Kind: readview.Said, Text: e.Text}}
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
	if p.Reader != "" {
		t.Fatalf("a reader before there is one: %q", p.Reader)
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
	png := []byte{0x89, 'P', 'N', 'G'}
	write(`{"id":"a","text":"hello"}` + "\n" + `{"id":"p","png":"` + base64.StdEncoding.EncodeToString(png) + `"}` + "\n")
	src.set(c.tmux("display", "-p", "-t", p.ID, "#{pane_tty}"), path)
	c.tmux("rename-window", "-t", "t", "chat") // state is re-sent on changes
	c.state("pane with a reader", func(s protocol.State) bool { return activePane(s).Reader == "lines" })

	// What the program is doing is in its pane's state, as it changes.
	src.doing(readview.Working)
	c.state("working", func(s protocol.State) bool { p := activePane(s); return p.Activity == "working" && p.Since == 1000 })

	c.send(protocol.Read{Pane: p.ID, On: true})
	e := c.entries("recent entries", func(e protocol.Entries) bool { return e.Pane == p.ID && e.Reset })
	if len(e.Entries) != 2 || e.Entries[0].Text != "hello" || e.Entries[1].Kind != "picture" || e.Entries[1].Size != len(png) {
		t.Fatalf("entries = %+v", e.Entries)
	}
	write(`{"id":"b","text":"world"}` + "\n")
	c.entries("a new entry", func(e protocol.Entries) bool {
		return e.Pane == p.ID && !e.Reset && len(e.Entries) == 1 && e.Entries[0].ID == "b"
	})

	// A picture's bytes come when asked for.
	c.send(protocol.Picture{Pane: p.ID, ID: "p"})
	f, _ := c.expect("picture", func(f protocol.Frame, _ protocol.Message) bool { return f.Type == protocol.FramePicture })
	if f.ID != "p" || string(f.Data) != string(png) {
		t.Errorf("picture %q = %q", f.ID, f.Data)
	}

	// Turned off, nothing more comes, however long it's written to.
	c.send(protocol.Read{Pane: p.ID, On: false})
	c.pong(9)
	write(`{"id":"c","text":"unseen"}` + "\n")
	time.Sleep(4 * readview.Poll)
	c.pong(10)

	// On again, it starts over.
	c.send(protocol.Read{Pane: p.ID, On: true})
	c.entries("all of it again", func(e protocol.Entries) bool { return e.Pane == p.ID && e.Reset && len(e.Entries) == 4 })

	// A pane without one says so.
	other := c.tmux("split-window", "-P", "-F", "#{pane_id}", "-t", p.ID)
	c.send(protocol.Read{Pane: other, On: true})
	c.entries("nothing to read", func(e protocol.Entries) bool { return e.Pane == other && e.Error != "" })
}

func TestAlbumKeepsTheNewest(t *testing.T) {
	a := newAlbum(10)
	a.put("a", make([]byte, 4))
	a.put("b", make([]byte, 4))
	a.get("a") // seen: newer than b
	a.put("c", make([]byte, 4))
	if a.get("b") != nil || a.get("a") == nil || a.get("c") == nil {
		t.Errorf("kept a=%v b=%v c=%v", a.get("a") != nil, a.get("b") != nil, a.get("c") != nil)
	}
}
