package mobileview

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func init() { Poll = 50 * time.Millisecond }

// fake is a program's processes, as the test sets them, recording lines that
// are {"id","text"} objects; a line with an id seen before changes that entry.
type fake struct {
	mu      sync.Mutex
	running map[int]Recording
}

func (f *fake) recording(pid int) func() (Recording, bool) {
	return func() (Recording, bool) {
		f.mu.Lock()
		defer f.mu.Unlock()
		r, ok := f.running[pid]
		return r, ok
	}
}

func (f *fake) set(pid int, path string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if path == "" {
		delete(f.running, pid)
	} else {
		f.running[pid] = Recording{Path: path}
	}
}

func newParser() Parser { return &fakeParser{} }

// fakeParser reads {"id","text"} lines as entries, {"ctx"} lines as how full
// the context is, and {"back"} lines as the conversation going back to before
// that entry.
type fakeParser struct {
	ctx      int
	read     []string
	left     map[string]bool
	branches int
}

func (p *fakeParser) Line(b []byte) []Entry {
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
	return []Entry{{ID: e.ID, From: Program, Kind: Said, Text: e.Text}}
}

func (p *fakeParser) Context() int { return p.ctx }

func (p *fakeParser) Branches() int { return p.branches }

func (p *fakeParser) Kept(entries []Entry) []Entry {
	var out []Entry
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

// follow runs Tail on pid's recordings, returning what it sends.
func follow(t *testing.T, src *fake, pid int) (<-chan Update, <-chan error) {
	got := make(chan Update, 100)
	done := make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { done <- Tail(ctx, src.recording(pid), newParser, func(u Update) { got <- u }) }()
	return got, done
}

// next returns the next update with something in it, skipping the empty
// reset that comes before a file is read.
func next(t *testing.T, got <-chan Update) Update {
	t.Helper()
	for {
		select {
		case e := <-got:
			if e.Reset && len(e.Entries) == 0 && !e.More {
				continue
			}
			return e
		case <-time.After(5 * time.Second):
			t.Fatal("nothing sent")
		}
	}
}

func texts(e Update) string {
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
	src := &fake{running: map[int]Recording{7: {Path: one}}}
	got, done := follow(t, src, 7)

	if e := next(t, got); !e.Reset || e.More || texts(e) != "a=hi b=there" {
		t.Fatalf("first: %+v", e)
	}
	appendTo(t, one, `xt":"!"}`+"\n"+line("b", "there, again"))
	if e := next(t, got); e.Reset || texts(e) != "c=! b=there, again" {
		t.Fatalf("appended: %+v", e)
	}

	// How full its context is comes as it changes.
	appendTo(t, one, `{"ctx":5000}`+"\n")
	if e := next(t, got); e.Reset || len(e.Entries) > 0 || !e.Measured || e.Context != 5000 {
		t.Fatalf("context: %+v", e)
	}

	// A cleared conversation is a new file, which starts the client over.
	src.set(7, two)
	appendTo(t, two, line("x", "fresh"))
	if e := next(t, got); !e.Reset || texts(e) != "x=fresh" {
		t.Fatalf("new transcript: %+v", e)
	}

	src.set(7, "")
	if err := <-done; !errors.Is(err, ErrEnded) {
		t.Fatalf("ended: %v", err)
	}
}

func TestFollowGoesBack(t *testing.T) {
	path := filepath.Join(t.TempDir(), "one.jsonl")
	appendTo(t, path, line("a", "one")+line("b", "two")+line("c", "three")+`{"back":"c"}`+"\n"+line("d", "three, again"))
	got, _ := follow(t, &fake{running: map[int]Recording{7: {Path: path}}}, 7)
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
	got, _ := follow(t, &fake{running: map[int]Recording{7: {Path: path}}}, 7)
	time.Sleep(2 * Poll)
	appendTo(t, path, line("a", "first"))
	if e := next(t, got); !e.Reset || texts(e) != "a=first" {
		t.Fatalf("got %+v", e)
	}
}

func TestEntriesFitAMessage(t *testing.T) {
	path := filepath.Join(t.TempDir(), "big.jsonl")
	mib := strings.Repeat("x", 1<<20) // an entry about the size of a pasted screenshot
	var b strings.Builder
	for i := range 10 {
		b.WriteString(line(fmt.Sprint(i), mib))
	}
	appendTo(t, path, b.String())
	got, _ := follow(t, &fake{running: map[int]Recording{7: {Path: path}}}, 7)

	// The first message holds as many of the newest as fit, and says there are more.
	if e := next(t, got); !e.Reset || !e.More || ids(e) != "7 8 9" {
		t.Fatalf("first: more=%v ids=%s", e.More, ids(e))
	}

	// Many written at once come in as many messages as they need, in order.
	b.Reset()
	for i := 10; i < 17; i++ {
		b.WriteString(line(fmt.Sprint(i), mib))
	}
	appendTo(t, path, b.String())
	var all []string
	for n := 0; n < 7; {
		e := next(t, got)
		if e.Reset || len(e.Entries) > 3 {
			t.Fatalf("batch: reset=%v n=%d", e.Reset, len(e.Entries))
		}
		n += len(e.Entries)
		all = append(all, ids(e))
	}
	if strings.Join(all, " ") != "10 11 12 13 14 15 16" || len(all) < 3 {
		t.Fatalf("got %q", all)
	}
}

func ids(e Update) string {
	var s []string
	for _, x := range e.Entries {
		s = append(s, x.ID)
	}
	return strings.Join(s, " ")
}

func TestRecentOfALongTranscript(t *testing.T) {
	path := filepath.Join(t.TempDir(), "long.jsonl")
	var b strings.Builder
	pad := strings.Repeat("x", 30<<10) // ~30KB lines: more than the first read holds
	for i := range Recent + 50 {
		fmt.Fprintf(&b, `{"id":"%d","text":"%d","pad":"%s"}`+"\n", i, i, pad)
	}
	appendTo(t, path, b.String())
	got, _ := follow(t, &fake{running: map[int]Recording{7: {Path: path}}}, 7)
	e := next(t, got)
	if !e.Reset || !e.More || len(e.Entries) != Recent || e.Entries[0].ID != "50" || e.Entries[Recent-1].ID != fmt.Sprint(Recent+49) {
		t.Fatalf("got more=%v n=%d first=%s", e.More, len(e.Entries), e.Entries[0].ID)
	}
}
