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
	running map[int]string
}

func (f *fake) Name() string { return "fake" }

func (f *fake) Running() map[int]string {
	f.mu.Lock()
	defer f.mu.Unlock()
	r := map[int]string{}
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
		f.running[pid] = path
	}
}

func (f *fake) Parser() Parser { return fakeParser{} }

type fakeParser struct{}

func (fakeParser) Line(b []byte) []protocol.Entry {
	var e struct{ ID, Text string }
	if json.Unmarshal(b, &e) != nil {
		return nil
	}
	return []protocol.Entry{{ID: e.ID, Role: "assistant", Kind: "text", Text: e.Text}}
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
	src := &fake{running: map[int]string{7: one}}
	got, done := follow(t, src, 7)

	if e := next(t, got); !e.Reset || e.More || texts(e) != "a=hi b=there" {
		t.Fatalf("first: %+v", e)
	}
	appendTo(t, one, `xt":"!"}`+"\n"+line("b", "there, again"))
	if e := next(t, got); e.Reset || texts(e) != "c=! b=there, again" {
		t.Fatalf("appended: %+v", e)
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

func TestFollowWaitsForTheFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "new.jsonl")
	got, _ := follow(t, &fake{running: map[int]string{7: path}}, 7)
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
	got, _ := follow(t, &fake{running: map[int]string{7: path}}, 7)
	e := next(t, got)
	if !e.Reset || !e.More || len(e.Entries) != Recent || e.Entries[0].ID != "50" || e.Entries[Recent-1].ID != fmt.Sprint(Recent+49) {
		t.Fatalf("got more=%v n=%d first=%s", e.More, len(e.Entries), e.Entries[0].ID)
	}
}
