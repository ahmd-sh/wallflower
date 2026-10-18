package transcript

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

// fake is a source whose lines are {"id","text"} objects; a line with an
// id seen before changes that entry.
type fake struct {
	mu      sync.Mutex
	running map[int]Session
}

func (f *fake) Name() string { return "fake" }

func (f *fake) Running() map[int]Session {
	f.mu.Lock()
	defer f.mu.Unlock()
	r := map[int]Session{}
	for k, v := range f.running {
		r[k] = v
	}
	return r
}

func (f *fake) set(pid int, path string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if path == "" {
		delete(f.running, pid)
	} else {
		f.running[pid] = Session{Path: path}
	}
}

func (f *fake) state(pid int, state string, since int64) {
	f.mu.Lock()
	defer f.mu.Unlock()
	s := f.running[pid]
	s.State, s.Since = state, since
	f.running[pid] = s
}

func (f *fake) Parser() Parser { return &fakeParser{} }

// fakeParser reads {"id","text"} lines as entries, {"ctx"} lines as how full
// the context is, and {"back"} lines as the conversation going back to before
// that entry.
type fakeParser struct {
	ctx      int
	read     []string
	left     map[string]bool
	branches int
}

func (p *fakeParser) Line(b []byte) []protocol.Entry {
	var e struct {
		ID, Text, Back string
		Ctx            int
	}
	if json.Unmarshal(b, &e) != nil {
		return nil
	}
	if e.Ctx > 0 {
		p.ctx = e.Ctx
	}
	if e.Back != "" {
		p.branches++
		if p.left == nil {
			p.left = map[string]bool{}
		}
		gone := false
		for _, id := range p.read {
			gone = gone || id == e.Back
			p.left[id] = p.left[id] || gone
		}
	}
	if e.ID == "" {
		return nil
	}
	p.read = append(p.read, e.ID)
	return []protocol.Entry{{ID: e.ID, Role: "assistant", Kind: "text", Text: e.Text}}
}

func (p *fakeParser) Context() int { return p.ctx }

func (p *fakeParser) Branches() int { return p.branches }

func (p *fakeParser) Kept(entries []protocol.Entry) []protocol.Entry {
	var out []protocol.Entry
	for _, e := range entries {
		if !p.left[e.ID] {
			out = append(out, e)
		}
	}
	return out
}

func line(id, text string) string { return fmt.Sprintf(`{"id":%q,"text":%q}`+"\n", id, text) }

func appendTo(t *testing.T, path, s string) {
	t.Helper()
	f, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	if _, err := f.WriteString(s); err != nil {
		t.Fatal(err)
	}
}

// follow runs Follow, returning what it sends.
func follow(t *testing.T, src Source, pid int) (<-chan protocol.Entries, <-chan error) {
	got := make(chan protocol.Entries, 100)
	done := make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { done <- Follow(ctx, src, pid, func(e protocol.Entries) { got <- e }) }()
	return got, done
}

// next returns the next message with entries, or an error, skipping the
// empty reset that comes before a file is read.
func next(t *testing.T, got <-chan protocol.Entries) protocol.Entries {
	t.Helper()
	for {
		select {
		case e := <-got:
			if e.Reset && len(e.Entries) == 0 && e.Error == "" && !e.More {
				continue
			}
			return e
		case <-time.After(5 * time.Second):
			t.Fatal("nothing sent")
		}
	}
}

func texts(e protocol.Entries) string {
	var s []string
	for _, x := range e.Entries {
		s = append(s, x.ID+"="+x.Text)
	}
	return strings.Join(s, " ")
}

func TestFollow(t *testing.T) {
	dir := t.TempDir()
	one, two := filepath.Join(dir, "one.jsonl"), filepath.Join(dir, "two.jsonl")
	appendTo(t, one, line("a", "hi")+line("b", "there")+`{"id":"c","te`) // a line still being written
	src := &fake{running: map[int]Session{7: {Path: one}}}
	got, done := follow(t, src, 7)

	if e := next(t, got); !e.Reset || e.More || texts(e) != "a=hi b=there" {
		t.Fatalf("first: %+v", e)
	}
	appendTo(t, one, `xt":"!"}`+"\n"+line("b", "there, again"))
	if e := next(t, got); e.Reset || texts(e) != "c=! b=there, again" {
		t.Fatalf("appended: %+v", e)
	}

	// What the CLI is doing comes as it changes.
	src.state(7, protocol.StateWorking, 1000)
	if e := next(t, got); e.Reset || len(e.Entries) > 0 || e.State != protocol.StateWorking || e.Since != 1000 {
		t.Fatalf("working: %+v", e)
	}
	src.state(7, protocol.StateIdle, 2000)
	if e := next(t, got); e.State != protocol.StateIdle || e.Since != 2000 {
		t.Fatalf("idle: %+v", e)
	}

	// So does how full its context is.
	appendTo(t, one, `{"ctx":5000}`+"\n")
	if e := next(t, got); e.Reset || len(e.Entries) > 0 || e.Context == nil || *e.Context != 5000 || e.State != protocol.StateIdle {
		t.Fatalf("context: %+v", e)
	}

	// A cleared conversation is a new file, which starts the client over.
	src.set(7, two)
	appendTo(t, two, line("x", "fresh"))
	if e := next(t, got); !e.Reset || texts(e) != "x=fresh" {
		t.Fatalf("new transcript: %+v", e)
	}

	src.set(7, "")
	if e := next(t, got); !e.Reset || e.Error == "" {
		t.Fatalf("ended: %+v", e)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestFollowGoesBack(t *testing.T) {
	path := filepath.Join(t.TempDir(), "one.jsonl")
	appendTo(t, path, line("a", "one")+line("b", "two")+line("c", "three")+`{"back":"c"}`+"\n"+line("d", "three, again"))
	got, _ := follow(t, &fake{running: map[int]Session{7: {Path: path}}}, 7)
	if e := next(t, got); !e.Reset || texts(e) != "a=one b=two d=three, again" {
		t.Fatalf("first: %+v", e)
	}

	// Going back as it's written starts the client over, without what was left behind.
	appendTo(t, path, line("e", "four")+`{"back":"b"}`+"\n"+line("f", "two, edited"))
	if e := next(t, got); !e.Reset || texts(e) != "a=one f=two, edited" {
		t.Fatalf("went back: %+v", e)
	}
	appendTo(t, path, line("g", "after"))
	if e := next(t, got); e.Reset || texts(e) != "g=after" {
		t.Fatalf("after: %+v", e)
	}
}

func TestFollowWaitsForTheFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.jsonl")
	got, _ := follow(t, &fake{running: map[int]Session{7: {Path: path}}}, 7)
	time.Sleep(2 * poll)
	appendTo(t, path, line("a", "first"))
	if e := next(t, got); !e.Reset || texts(e) != "a=first" {
		t.Fatalf("got %+v", e)
	}
}

func TestRecentOfALongTranscript(t *testing.T) {
	path := filepath.Join(t.TempDir(), "long.jsonl")
	var b strings.Builder
	pad := strings.Repeat("x", 30<<10) // ~30KB lines: more than the first read holds
	for i := range Recent + 50 {
		fmt.Fprintf(&b, `{"id":"%d","text":"%d","pad":"%s"}`+"\n", i, i, pad)
	}
	appendTo(t, path, b.String())
	got, _ := follow(t, &fake{running: map[int]Session{7: {Path: path}}}, 7)
	e := next(t, got)
	if !e.Reset || !e.More || len(e.Entries) != Recent || e.Entries[0].ID != "50" || e.Entries[Recent-1].ID != fmt.Sprint(Recent+49) {
		t.Fatalf("got more=%v n=%d first=%s", e.More, len(e.Entries), e.Entries[0].ID)
	}
}
