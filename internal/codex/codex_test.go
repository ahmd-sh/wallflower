package codex

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

const thread = "01a1caf7-b98e-75d0-8b25-d2ac549855a8"

func entries(t *testing.T) ([]mobileview.Entry, *parser) {
	t.Helper()
	b, err := os.ReadFile("testdata/rollout.jsonl")
	if err != nil {
		t.Fatal(err)
	}
	p := newParser(func(id string) string {
		if id == thread {
			return "Ask a question"
		}
		return ""
	}).(*parser)
	var out []mobileview.Entry
	for _, line := range bytes.Split(b, []byte("\n")) {
		out = append(out, p.Line(line)...)
	}
	return out, p
}

func TestParse(t *testing.T) {
	got, p := entries(t)
	want := []mobileview.Entry{
		{ID: "u2", From: mobileview.User, Kind: mobileview.Said, Text: "Ask me a question, then run: sleep 60"},
		{ID: "a1", From: mobileview.Program, Kind: mobileview.Said, Text: "I’ll ask first, then run it."},
		{ID: "r2", From: mobileview.Program, Kind: mobileview.Thought, Text: "**Planning** the run"},
		{ID: "call_q", From: mobileview.Program, Kind: mobileview.Said, Text: "**Favorite season**\n- Spring\n- Summer\n\n**Pace**\n\nHow fast should I go?\n- Slow — careful\n- Fast"},
		{ID: "c1", From: mobileview.Program, Kind: mobileview.Step, Name: "Read", Text: "README.md", Status: mobileview.Done, Output: "# proj"},
		{ID: "c2", From: mobileview.Program, Kind: mobileview.Step, Name: "Search", Text: "TODO in .", Status: mobileview.Failed},
		{ID: "c3", From: mobileview.Program, Kind: mobileview.Step, Name: "Ran", Text: "sleep 60 && echo \x1b[32mdone\x1b[0m", Status: mobileview.Done, Output: "done"},
		{ID: "u3", From: mobileview.User, Kind: mobileview.Said, Text: "Favorite season → Summer"},
		{ID: "f1", From: mobileview.Program, Kind: mobileview.Step, Name: "Add", Text: "a.go", Status: mobileview.Done, Diff: "@@ -0,0 +1 @@\n+package a"},
		{ID: "f1:1", From: mobileview.Program, Kind: mobileview.Step, Name: "Edit", Text: "b.go", Status: mobileview.Done, Diff: "@@ -1,2 +1,2 @@\n package b\n-var x = 1\n+var x = 2"},
		{ID: "w1", From: mobileview.Program, Kind: mobileview.Step, Name: "Web search", Text: "codex rollout format", Status: mobileview.Done},
		{ID: "a2", From: mobileview.Program, Kind: mobileview.Said, Text: "Done: **Summer** it is."},
	}
	if len(got) != len(want) {
		for _, e := range got {
			t.Logf("%+v", e)
		}
		t.Fatalf("%d entries, want %d", len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("entry %d:\n got %+v\nwant %+v", i, got[i], want[i])
		}
	}
	if p.Context() != 13964 {
		t.Errorf("context %d, want 13964", p.Context())
	}
	if p.Title() != "Ask a question" {
		t.Errorf("title %q", p.Title())
	}
}

func TestInterrupted(t *testing.T) {
	p := newParser(func(string) string { return "" })
	got := p.Line([]byte(`{"timestamp":"2026-11-11T14:25:31.826Z","type":"event_msg","payload":{"type":"turn_aborted","turn_id":"t1","reason":"interrupted"}}`))
	if len(got) != 1 || got[0].Kind != mobileview.Note || got[0].Text != "Interrupted" {
		t.Fatalf("got %+v", got)
	}
}

func TestNamesIn(t *testing.T) {
	for title, want := range map[string][]string{
		"Read plugin documentation | wallflower":                     {"Read plugin documentation"},
		"⠋ Read plugin documentation | wallflower":                   {"Read plugin documentation"},
		"[ ! ] Action Required | Approve touch command | wallflower": {"Approve touch command"},
		"[ . ] Action Required | wallflower":                         nil,
		"wallflower":                                                 nil,
		"a | b | wallflower":                                         {"b", "a | b"},
		"":                                                           nil,
	} {
		if got := namesIn(title); !slices.Equal(got, want) {
			t.Errorf("namesIn(%q) = %q, want %q", title, got, want)
		}
	}
}

// home lays out a ~/.codex with the fixture as a thread named name, and
// another thread in another directory with the same name.
func home(t *testing.T, name string) (string, string) {
	t.Helper()
	dir := t.TempDir()
	day := filepath.Join(dir, "sessions", "2026", "11", "11")
	os.MkdirAll(day, 0o755)
	b, _ := os.ReadFile("testdata/rollout.jsonl")
	path := filepath.Join(day, "rollout-2026-11-11T10-47-00-"+thread+".jsonl")
	os.WriteFile(path, b, 0o644)
	other := "01a1ca00-0000-7000-8000-000000000000"
	os.WriteFile(filepath.Join(day, "rollout-2026-11-11T10-50-00-"+other+".jsonl"),
		[]byte(strings.Replace(strings.Replace(string(b), thread, other, -1), "/Users/me/dev/proj", "/Users/me/dev/elsewhere", -1)), 0o644)
	os.WriteFile(filepath.Join(dir, "session_index.jsonl"), []byte(
		`{"id":"`+thread+`","thread_name":"`+name+`","updated_at":"2026-11-11T14:16:54.711201Z"}`+"\n"+
			`{"id":"`+other+`","thread_name":"`+name+`","updated_at":"2026-11-11T14:20:00Z"}`+"\n"), 0o644)
	return dir, path
}

func TestFind(t *testing.T) {
	dir, path := home(t, "Ask a question")
	s := New(dir)
	panes := []mobileview.Pane{
		{ID: "%1", Command: "codex", Title: "Ask a question | proj", Path: "/Users/me/dev/proj"},
		{ID: "%2", Command: "zsh", Title: "Ask a question | proj"},
	}
	got := s.Find(panes)
	if len(got) != 1 || got["%1"].Activity != mobileview.Idle {
		t.Fatalf("found %+v", got)
	}
	if got := s.rollout(panes[0]); got != path {
		t.Errorf("rollout %s, want the one in the pane's directory", got)
	}

	// A new turn starts.
	f, _ := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	f.WriteString(`{"timestamp":"2026-11-11T14:30:00.000Z","type":"event_msg","payload":{"type":"task_started","turn_id":"t2"}}` + "\n")
	f.Close()
	got = s.Find(panes)
	if got["%1"].Activity != mobileview.Working || got["%1"].Since != time.Date(2026, 11, 11, 14, 30, 0, 0, time.UTC).UnixMilli() {
		t.Errorf("after task_started: %+v", got["%1"])
	}

	// It asks something: the title says so, as the rollout doesn't.
	panes[0].Title = "[ ! ] Action Required | Ask a question | proj"
	got = s.Find(panes)
	asked := got["%1"]
	if asked.Activity != mobileview.Waiting || asked.Since == 0 {
		t.Errorf("asking: %+v", asked)
	}
	panes[0].Title = "[ . ] Action Required | Ask a question | proj"
	if again := s.Find(panes)["%1"]; again != asked {
		t.Errorf("still asking, its mark blinking: %+v, was %+v", again, asked)
	}
	panes[0].Title = "⠋ Ask a question | proj"
	if got := s.Find(panes)["%1"]; got.Activity != mobileview.Working {
		t.Errorf("answered: %+v", got)
	}
}

func TestRead(t *testing.T) {
	mobileview.Poll = 20 * time.Millisecond
	dir, path := home(t, "Ask a question")
	s := New(dir)
	pane := mobileview.Pane{ID: "%1", Command: "codex", Title: "proj", Path: "/Users/me/dev/proj"}
	s.Find([]mobileview.Pane{pane})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	updates := make(chan mobileview.Update, 16)
	done := make(chan error, 1)
	go func() { done <- s.Read(ctx, pane, func(u mobileview.Update) { updates <- u }) }()

	// Before codex names it, with no terminal to ask when codex started: nothing yet.
	u := <-updates
	if !u.Reset || len(u.Entries) != 0 {
		t.Fatalf("first update %+v", u)
	}
	// Named, the title says which thread.
	pane.Title = "Ask a question | proj"
	s.Find([]mobileview.Pane{pane})
	select {
	case u = <-updates:
	case <-time.After(2 * time.Second):
		t.Fatal("no update once named")
	}
	if !u.Reset || len(u.Entries) != 12 || u.Title != "Ask a question" || u.Context != 13964 {
		t.Fatalf("named update: reset %v, %d entries, title %q, context %d", u.Reset, len(u.Entries), u.Title, u.Context)
	}
	// A new item comes as it's written.
	f, _ := os.OpenFile(path, os.O_APPEND|os.O_WRONLY, 0)
	f.WriteString(`{"timestamp":"2026-11-11T14:30:00.000Z","type":"event_msg","payload":{"type":"item_completed","item":{"type":"AgentMessage","id":"a3","content":[{"type":"Text","text":"more"}],"phase":"final_answer"}}}` + "\n")
	f.Close()
	select {
	case u = <-updates:
		if len(u.Entries) != 1 || u.Entries[0].ID != "a3" {
			t.Errorf("appended update %+v", u)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no update for an appended item")
	}
	// Codex leaves the pane: the reading ends.
	s.Find(nil)
	select {
	case err := <-done:
		if err == nil {
			t.Error("read of a pane codex left didn't end with why")
		}
	case <-time.After(2 * time.Second):
		t.Error("read of a pane codex left didn't end")
	}
}
