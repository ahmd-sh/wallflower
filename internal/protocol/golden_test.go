package protocol

import (
	"bytes"
	"flag"
	"os"
	"path/filepath"
	"testing"
)

var update = flag.Bool("update", false, "rewrite testdata golden frames")

// Golden frames pin the wire format across languages: the Android client's
// tests decode server.bin and must encode client.bin byte for byte.
// Regenerate with: go test ./internal/protocol -run Golden -update

func goldenClient(t *testing.T) []byte {
	var b []byte
	for _, m := range []Message{
		ClientHello{V: Version, Cols: 56, Rows: 20},
		ClientHello{V: Version, Cols: 56, Rows: 20, Session: "o"},
		Resize{Cols: 70, Rows: 25},
		Open{Pane: "%1"},
		Open{Pane: "%1", History: 500},
		Close{Pane: "%1"},
		Keys{Pane: "%1", Keys: []string{"C-c", "Up", "M-Enter"}},
		Select{Window: "@2"},
		Select{Pane: "%3"},
		Zoom{Pane: "%1"},
		ScrollbackRequest{ID: 1, Pane: "%1", Start: -50, End: -1},
		TmuxCommand{ID: 2, Command: `display -p '#{session_name}'`},
		Ping{ID: 3},
		Paste{Pane: "%1", Text: "two\nlines ✓"},
		Keys{Pane: "%1", Keys: []string{"prefix", "o"}, Bindings: true},
		Focus{Pane: "%2"},
		Split{Pane: "%2"},
		New{},
		Rename{Window: "@2", Name: "logs ✓"},
		Kill{Pane: "%2"},
		Kill{Window: "@2"},
		Transcript{Pane: "%2", On: true},
		Transcript{Pane: "%2"},
		Upload{ID: "u1", Name: "PXL 1 ✓.jpg", Size: 3},
	} {
		b = mustJSON(t, b, m)
	}
	b = AppendData(b, "%1", []byte("echo héllo ✓\r"))
	return AppendUpload(b, "u1", []byte{0xff, 0xd8, 0xff})
}

func goldenServer(t *testing.T) []byte {
	var b []byte
	b = mustJSON(t, b, ServerHello{V: Version, Tmux: "3.8", Uploads: "/Users/me/Library/Caches/wallflower/uploads"})
	b = mustJSON(t, b, State{Session: "$1", Name: "o", Windows: []Window{{
		ID: "@1", Index: 1, Name: "base", Active: true, Zoomed: false, Cols: 56, Rows: 20,
		Panes: []Pane{
			{ID: "%2", Index: 0, Active: false, X: 0, Y: 0, Cols: 28, Rows: 20, Alt: true, Agent: "waiting",
				Title: "✳ Fix tests", Path: "/Users/ahmedshaikh/dev/wallflower", Command: "claude", Transcript: "claude"},
			{ID: "%3", Index: 1, Active: true, X: 4, Y: 2, Cols: 18, Rows: 3, Floating: true, Z: 0, Command: "zsh"},
		},
	}}})
	b = mustJSON(t, b, Reset{Pane: "%2", Cols: 28, Rows: 20})
	b = AppendData(b, "%2", []byte("\x1b[0m\x1b[H\x1b[2J\x1b[42mgreen\x1b[49m ✓\r\n"))
	b = mustJSON(t, b, ScrollbackReply{ID: 1, Pane: "%2", HistorySize: 120, Lines: []string{"one", "\x1b[31mtwo\x1b[39m"}})
	b = mustJSON(t, b, Result{ID: 2, Lines: []string{"o"}})
	b = mustJSON(t, b, Result{ID: 3, Error: "tmux: no-such-command: unknown command"})
	b = mustJSON(t, b, Error{Message: "bad id \"x\""})
	b = mustJSON(t, b, Pong{ID: 4})
	b = mustJSON(t, b, Entries{Pane: "%2", Reset: true, More: true, Entries: []Entry{
		{ID: "u1", Role: "user", Kind: "text", Text: "fix the tests ✓"},
		{ID: "a1", Role: "assistant", Kind: "tool", Text: "go test ./...", Name: "Bash", Output: "ok", Done: true},
		{ID: "a2", Role: "assistant", Kind: "tool", Text: "rm -rf /", Name: "Bash", Output: "denied", Done: true, Error: true},
		{ID: "u2", Role: "user", Kind: "image", Mime: "image/png", Data: "iVBORw0KGgo="},
		{ID: "a3", Role: "assistant", Kind: "text", Text: "**Done.** All `3` pass."},
	}})
	b = mustJSON(t, b, Entries{Pane: "%2", Entries: []Entry{{ID: "n1", Role: "user", Kind: "note", Text: "[Request interrupted by user]"}}})
	b = mustJSON(t, b, Entries{Pane: "%2", State: StateWaiting, Since: 1792209270006})
	b = mustJSON(t, b, Entries{Pane: "%2", Reset: true, Error: "the conversation ended"})
	b = mustJSON(t, b, Uploaded{ID: "u1", Path: "/Users/me/Library/Caches/wallflower/uploads/PXL-1.jpg"})
	b = mustJSON(t, b, Uploaded{ID: "u2", Error: "too big"})
	return mustJSON(t, b, Bye{Reason: "tmux exited"})
}

func mustJSON(t *testing.T, b []byte, m Message) []byte {
	t.Helper()
	b, err := AppendJSON(b, m)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestGolden(t *testing.T) {
	for name, got := range map[string][]byte{"client.bin": goldenClient(t), "server.bin": goldenServer(t)} {
		path := filepath.Join("..", "..", "testdata", name)
		if *update {
			if err := os.WriteFile(path, got, 0o644); err != nil {
				t.Fatal(err)
			}
			continue
		}
		want, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("%v (run with -update to create)", err)
		}
		if !bytes.Equal(got, want) {
			t.Errorf("%s changed; the wire format is a contract with the Android client. Run with -update if intended.", name)
		}
	}
}
