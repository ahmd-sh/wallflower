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
		ClientHello{V: Version, Cols: 56, Rows: 20, Away: true},
		ClientHello{V: Version, Cols: 56, Rows: 20, Mux: "rex", Session: "work"},
		Resize{Cols: 70, Rows: 25},
		Open{Pane: "%1"},
		Open{Pane: "%1", History: 500},
		Close{Pane: "%1"},
		Keys{Pane: "%1", Keys: []string{"C-c", "Up", "M-Enter"}},
		Keys{Pane: "%1", Keys: []string{"prefix", "o"}, Bindings: true},
		Submit{Pane: "%1", Text: "fix the tests ✓", Enter: true, Clear: []string{"A", "C-u", "C-u", "C-u"}},
		Submit{Pane: "%1", Text: "two\nlines"},
		Select{Window: "@2"},
		Select{Pane: "%3"},
		Focus{Pane: "%2"},
		Split{Pane: "%2"},
		New{},
		Rename{Window: "@2", Name: "logs ✓"},
		Kill{Pane: "%2"},
		Kill{Window: "@2"},
		Read{Pane: "%2", On: true},
		Read{Pane: "%2"},
		Picture{Pane: "%2", ID: "u2.0"},
		Upload{ID: "u1", Name: "PXL 1 ✓.jpg", Size: 3},
		Ping{ID: 3},
		Away{On: true},
		Away{},
		Sessions{},
		Switch{Mux: "rex", Session: "session:1"},
		NewSession{Mux: "tmux", Name: "work ✓"},
		NewSession{Mux: "tmux"},
		RenameSession{Mux: "tmux", Session: "$2", Name: "play"},
		KillSession{Mux: "tmux", Session: "$2"},
	} {
		b = mustJSON(t, b, m)
	}
	b = AppendData(b, "%1", []byte("\x1b[<64;10;5M"))
	return AppendUpload(b, "u1", []byte{0xff, 0xd8, 0xff})
}

func goldenServer(t *testing.T) []byte {
	var b []byte
	context := 17967
	b = mustJSON(t, b, ServerHello{V: Version, Mux: Mux{Name: "tmux", Version: "3.8", Can: []string{CanBindings}}, Uploads: "/Users/me/Library/Caches/wallflower/uploads"})
	b = mustJSON(t, b, State{Session: "$1", Name: "o", Windows: []Window{{
		ID: "@1", Index: 1, Name: "base", Active: true,
		Panes: []Pane{
			{ID: "%2", Index: 0, Active: false, Cols: 28, Rows: 20, Alt: true, Title: "✳ Fix tests", Path: "/Users/ahmedshaikh/dev/wallflower",
				Command: "claude", Program: "claude", View: true, Activity: ActivityWaiting, Since: 1792209270006},
			{ID: "%3", Index: 1, Active: true, Cols: 28, Rows: 20, Command: "zsh", Program: "zsh"},
			{ID: "%4", Index: 2, Active: false, Cols: 28, Rows: 20, Command: "htop"},
		},
	}}})
	b = mustJSON(t, b, Reset{Pane: "%2", Cols: 28, Rows: 20})
	b = AppendData(b, "%2", []byte("\x1b[0m\x1b[H\x1b[2J\x1b[42mgreen\x1b[49m ✓\r\n"))
	b = mustJSON(t, b, Error{Message: "tmux: bad id \"x\""})
	b = mustJSON(t, b, Pong{ID: 4})
	b = mustJSON(t, b, Entries{Pane: "%2", Reset: true, More: true, Context: &context, Entries: []Entry{
		{ID: "u1", From: FromUser, Kind: KindSaid, Text: "fix the tests ✓"},
		{ID: "a0", From: FromProgram, Kind: KindThought, Text: "the tests"},
		{ID: "a1", From: FromProgram, Kind: KindStep, Text: "go test ./...", Name: "Bash", Status: StatusDone, Output: "ok", OutputCut: 1200},
		{ID: "a2", From: FromProgram, Kind: KindStep, Text: "rm -rf /", Name: "Bash", Status: StatusFailed, Output: "denied"},
		{ID: "a4", From: FromProgram, Kind: KindStep, Text: "main.go", Name: "Edit", Status: StatusDone, Output: "updated", Diff: "@@ -3,2 +3,2 @@\n x\n-old\n+new ✓", DiffCut: 12},
		{ID: "a5", From: FromProgram, Kind: KindStep, Text: "ls", Name: "Bash", Status: StatusRunning},
		{ID: "u2.0", From: FromUser, Kind: KindPicture, Mime: "image/png", Size: 3},
		{ID: "a3", From: FromProgram, Kind: KindSaid, Text: "**Done.** All `3` pass."},
	}})
	b = mustJSON(t, b, Entries{Pane: "%2", Entries: []Entry{{ID: "n1", From: FromUser, Kind: KindNote, Text: "[Request interrupted by user]"}}})
	b = mustJSON(t, b, Entries{Pane: "%2", Context: new(int)})
	b = mustJSON(t, b, Entries{Pane: "%2", Reset: true, Error: "the conversation ended"})
	b = mustJSON(t, b, Notification{Pane: "%2", Title: "Claude Code", Body: "Claude needs your permission to use Bash ✓"})
	b = mustJSON(t, b, Notification{Pane: "%3", Body: "build done"})
	b = mustJSON(t, b, Notification{Pane: "%4", Bell: true})
	b = AppendPicture(b, "u2.0", []byte{0x89, 'P', 'N'})
	b = AppendPicture(b, "gone", nil)
	b = mustJSON(t, b, Uploaded{ID: "u1", Path: "/Users/me/Library/Caches/wallflower/uploads/PXL-1.jpg"})
	b = mustJSON(t, b, Uploaded{ID: "u2", Error: "too big"})
	b = mustJSON(t, b, SessionList{Muxes: []MuxSessions{
		{Name: "rex", Sessions: []SessionInfo{}, Error: "rex: no server at /x/server.sock"},
		{Name: "tmux", Sessions: []SessionInfo{{ID: "$1", Name: "o", Windows: 3}, {ID: "$2", Name: "work ✓", Windows: 1}}},
	}})
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
