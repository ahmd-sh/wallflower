package plugin

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

// TestMain runs the test binary as a plugin when asked to: one that reads
// the panes running "fake".
func TestMain(m *testing.M) {
	if os.Getenv("WALLFLOWER_TEST_PLUGIN") == "1" {
		fake()
		return
	}
	os.Exit(m.Run())
}

func fake() {
	in := bufio.NewScanner(os.Stdin)
	say := func(v any) {
		b, _ := json.Marshal(v)
		fmt.Println(string(b))
	}
	for in.Scan() {
		var call struct {
			Find []pane `json:"find"`
			Read *pane  `json:"read"`
			Stop string `json:"stop"`
		}
		json.Unmarshal(in.Bytes(), &call)
		switch {
		case call.Find != nil:
			reads := map[string]found{}
			for _, p := range call.Find {
				if p.Command == "fake" {
					reads[p.ID] = found{Activity: "working", Since: 42}
				}
			}
			say(map[string]any{"found": reads})
		case call.Read != nil:
			id := call.Read.ID
			say(map[string]any{"pane": id, "update": map[string]any{"reset": true, "title": "a chat", "context": 7,
				"entries": []map[string]any{{"id": "1", "from": "user", "kind": "said", "text": "hi"}}}})
			say(map[string]any{"pane": id, "update": map[string]any{"entries": []map[string]any{{"id": "2", "from": "program", "kind": "step", "name": "ls", "status": "done", "output": "a"}}}})
			say(map[string]any{"pane": id, "end": "that's all"})
		}
	}
}

func source(t *testing.T) *Source {
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	t.Setenv("WALLFLOWER_TEST_PLUGIN", "1")
	return &Source{Command: []string{self}}
}

// said asks until the program has said, as the gateway asks every moment.
func said(t *testing.T, s *Source, panes []mobileview.Pane) map[string]mobileview.Found {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(20 * time.Millisecond) {
		if f := s.Find(panes); len(f) > 0 {
			return f
		}
	}
	t.Fatal("the plugin never said which panes it reads")
	return nil
}

func TestFindAndRead(t *testing.T) {
	s := source(t)
	defer s.Close()
	panes := []mobileview.Pane{{ID: "%1", Command: "fake"}, {ID: "%2", Command: "zsh"}}
	if got := said(t, s, panes); !reflect.DeepEqual(got, map[string]mobileview.Found{"%1": {Activity: mobileview.Working, Since: 42}}) {
		t.Fatalf("found %v", got)
	}
	if got := s.Find(panes[1:]); len(got) != 0 {
		t.Errorf("asked of %%2 alone, found %v", got)
	}
	var updates []mobileview.Update
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err := s.Read(ctx, panes[0], func(u mobileview.Update) { updates = append(updates, u) })
	if err == nil || err.Error() != "that's all" {
		t.Fatalf("read ended with %v", err)
	}
	want := []mobileview.Update{
		{Reset: true, Title: "a chat", Context: 7, Measured: true, Entries: []mobileview.Entry{{ID: "1", From: "user", Kind: "said", Text: "hi"}}},
		{Entries: []mobileview.Entry{{ID: "2", From: "program", Kind: "step", Name: "ls", Status: "done", Output: "a"}}},
	}
	if !reflect.DeepEqual(updates, want) {
		t.Errorf("updates\n%+v\nwant\n%+v", updates, want)
	}
}

// A plugin that doesn't read what it's sent doesn't hold up whoever asks it.
func TestAStuckPluginHoldsNothingUp(t *testing.T) {
	s := &Source{Command: []string{"sleep", "30"}}
	defer s.Close()
	panes := []mobileview.Pane{{ID: "%1", Command: "fake", TTY: strings.Repeat("x", 4096)}}
	start := time.Now()
	for range 1000 {
		s.Find(panes)
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Errorf("1000 finds took %v", d)
	}
}

func TestClosedItStops(t *testing.T) {
	s := source(t)
	s.Find(nil)
	r := s.running()
	s.Close()
	select {
	case <-r.done:
	case <-time.After(5 * time.Second):
		t.Fatal("the plugin still runs")
	}
	if s.running() != nil {
		t.Error("started again once closed")
	}
}

func TestAProgramThatWontRunFindsNothing(t *testing.T) {
	s := &Source{Command: []string{"/nonexistent/plugin"}}
	if got := s.Find([]mobileview.Pane{{ID: "%1", Command: "fake"}}); len(got) != 0 {
		t.Errorf("found %v", got)
	}
	if err := s.Read(context.Background(), mobileview.Pane{ID: "%1"}, func(mobileview.Update) {}); err == nil {
		t.Error("read with no plugin running")
	}
}
