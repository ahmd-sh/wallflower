package rex

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/rextest"
)

// snapshot is a Ghostty snapshot of records with these tags, payloads of n bytes.
func snapshot(n int, tags ...uint16) []byte {
	b := []byte("GHOSTSNP\x01\x00")
	for _, tag := range tags {
		b = binary.LittleEndian.AppendUint16(b, tag)
		b = binary.LittleEndian.AppendUint32(b, uint32(n))
		b = append(b, 0, 0, 0, 0) // CRC, unchecked
		b = append(b, bytes.Repeat([]byte{0xee}, n)...)
	}
	return b
}

func TestFilter(t *testing.T) {
	f := newFilter(42)
	stream := append(snapshot(5, 1, 2, 3, 7, 5, 4, 6),
		"hi \x1b[31mred\x1b[0m\x1b_rex1;42;{\"type\":\"resize\",\"cols\":50,\"rows\":16}\x1b\\ \x1b_rex1;7;{}\x1b\\\x1b]9;note\x07 \x1b[?1049h"...)
	var out []byte
	var msgs []message
	var alts []bool
	// Every split of the stream reads the same.
	for _, size := range []int{1, 2, 3, 7, len(stream)} {
		f, out, msgs, alts = newFilter(42), nil, nil, nil
		for b := stream; len(b) > 0; {
			n := min(size, len(b))
			f.feed(append([]byte(nil), b[:n]...),
				func(o []byte) { out = append(out, o...) },
				func(m message) { msgs = append(msgs, m) },
				func(on bool) { alts = append(alts, on) })
			b = b[n:]
		}
		want := "hi \x1b[31mred\x1b[0m \x1b_rex1;7;{}\x1b\\\x1b]9;note\x07 \x1b[?1049h"
		if string(out) != want {
			t.Fatalf("in pieces of %d: output %q, want %q", size, out, want)
		}
		if !reflect.DeepEqual(msgs, []message{{Type: "resize", Cols: 50, Rows: 16}}) {
			t.Fatalf("in pieces of %d: messages %+v", size, msgs)
		}
		if !reflect.DeepEqual(alts, []bool{true}) {
			t.Fatalf("in pieces of %d: screens %v", size, alts)
		}
	}

	// A stream without a snapshot is all output.
	f = newFilter(1)
	out = nil
	f.feed([]byte("plain output"), func(o []byte) { out = append(out, o...) }, nil, func(bool) {})
	if string(out) != "plain output" {
		t.Errorf("no snapshot: %q", out)
	}
}

func TestKeyOf(t *testing.T) {
	for name, want := range map[string]key{
		"Enter":   {Key: "enter"},
		"C-c":     {Key: "key_c", Mods: "ctrl", UnshiftedCodepoint: 'c'},
		"M-b":     {Key: "key_b", Mods: "alt", Text: "b", UnshiftedCodepoint: 'b'},
		"M-Enter": {Key: "enter", Mods: "alt"},
		"S-Up":    {Key: "arrow_up", Mods: "shift"},
		"BTab":    {Key: "tab", Mods: "shift"},
		"C-M-x":   {Key: "key_x", Mods: "ctrl+alt", UnshiftedCodepoint: 'x'},
		"M-?":     {Key: "slash", Mods: "shift+alt", ConsumedMods: "shift", Text: "?", UnshiftedCodepoint: '/'},
		"F5":      {Key: "f5"},
		"Space":   {Key: "space", Text: " ", UnshiftedCodepoint: ' '},
	} {
		want.Action = "press"
		if got, ok := keyOf(name); !ok || got != want {
			t.Errorf("keyOf(%q) = %+v, %v; want %+v", name, got, ok, want)
		}
	}
	for _, text := range []string{"a", ":w", "echo", "-n", "é", "C-é"} {
		if k, ok := keyOf(text); ok {
			t.Errorf("keyOf(%q) = %+v, want text", text, k)
		}
	}
}

func TestRepaintKeepsHistoryAsked(t *testing.T) {
	head, body := split("\x1b[?1000h\x1b[?2004h\x1b(B\x1b[0mh1\r\nh2\r\nh3\r\ns1\r\ns2\x1b[1;3H")
	if head != "\x1b[?1000h\x1b[?2004h\x1b(B" {
		t.Fatalf("head %q", head)
	}
	got := string(repaint(head, strings.Split(body, "\r\n"), 2, 1))
	if want := "\x1b[0m\x1b[H\x1b[2J\x1b[3J" + head + "h3\r\ns1\r\ns2\x1b[1;3H"; got != want {
		t.Errorf("repaint %q, want %q", got, want)
	}
}

// client attaches to a fresh server's session at cols x rows.
func client(t *testing.T, cols, rows int) (*attached, func(args ...string) string, string) {
	t.Helper()
	s := rextest.Start(t)
	c, err := Server{Endpoint: s.Socket}.Attach(mux.Attach{Cols: cols, Rows: rows})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c.(*attached), s.Run, s.Block
}

// until reads events until ok says what came is enough.
func until(t *testing.T, c *attached, what string, ok func(mux.Event) bool) {
	t.Helper()
	timeout := time.After(10 * time.Second)
	for {
		select {
		case e, open := <-c.Events():
			if !open {
				t.Fatalf("events ended waiting for %s: %v", what, c.Err())
			}
			if ok(e) {
				return
			}
		case <-timeout:
			t.Fatalf("timed out waiting for %s", what)
		}
	}
}

// output waits for a pane's output, from now on, to hold s.
func output(t *testing.T, c *attached, pane, s string) {
	t.Helper()
	var got []byte
	until(t, c, "output "+s, func(e mux.Event) bool {
		if e.Pane == pane {
			got = append(got, e.Data...)
		}
		return bytes.Contains(got, []byte(s))
	})
}

func pane(t *testing.T, c *attached, id string) mux.Pane {
	t.Helper()
	s, err := c.Snapshot()
	if err != nil {
		t.Fatal(err)
	}
	for _, w := range s.Windows {
		for _, p := range w.Panes {
			if p.ID == id {
				return p
			}
		}
	}
	t.Fatalf("no pane %s in %+v", id, s)
	return mux.Pane{}
}

func TestSessionOutputAndScreen(t *testing.T) {
	c, _, block := client(t, 60, 20)
	if a := c.About(); a.Name != "rex" || a.Version == "" {
		t.Errorf("about %+v", a)
	}
	s, err := c.Snapshot()
	if err != nil {
		t.Fatal(err)
	}
	if s.Name != "t" || len(s.Windows) != 1 || len(s.Windows[0].Panes) != 1 || !s.Windows[0].Active {
		t.Fatalf("snapshot %+v", s)
	}
	p := s.Windows[0].Panes[0]
	if p.ID != block || !p.Active || p.Command != "sh" || p.TTY == "" || p.Cols == 0 {
		t.Errorf("pane %+v", p)
	}

	// Typing takes the terminal, at the client's size, and its output streams.
	if err := c.Type(block, []byte("for i in 1 2 3 4 5 6 7 8; do echo line-$i; done\r")); err != nil {
		t.Fatal(err)
	}
	output(t, c, block, "line-8")
	if p := pane(t, c, block); p.Cols != 60 || p.Rows != 20 {
		t.Errorf("typed-into pane %dx%d, want the client's 60x20", p.Cols, p.Rows)
	}
	screen, err := c.Screen(block, 3)
	if err != nil {
		t.Fatal(err)
	}
	if screen.Cols != 60 || screen.Rows != 20 || !bytes.Contains(screen.Repaint, []byte("line-8")) {
		t.Errorf("screen %dx%d %q", screen.Cols, screen.Rows, screen.Repaint)
	}

	// A program's notification is in its output, for the gateway to read.
	c.Type(block, []byte(`printf '\033]9;from rex\007'`+"\r"))
	output(t, c, block, "\x1b]9;from rex\x07")
}

func TestKeysAndPaste(t *testing.T) {
	c, _, block := client(t, 60, 20)
	c.Snapshot()
	c.Type(block, []byte(`printf '\033[?2004h'; stty raw -echo; echo RE""ADY; cat -v`+"\r"))
	output(t, c, block, "READY")
	if err := c.Keys(block, "C-a", "M-b", "x", "Enter", "BTab", "Up"); err != nil {
		t.Fatal(err)
	}
	output(t, c, block, "^A^[bx^M^[[Z^[[A")
	if err := c.Paste(block, "one $HOME\ntwo"); err != nil {
		t.Fatal(err)
	}
	output(t, c, block, "^[[200~one $HOME^Mtwo^[[201~")
}

func TestScreensAndSizing(t *testing.T) {
	c, run, block := client(t, 60, 20)
	c.Snapshot()
	c.Type(block, []byte(`printf '\033[?1049h'; sleep 30`+"\r"))
	until(t, c, "the alternate screen", func(e mux.Event) bool { return e.Changed && pane(t, c, block).Alt })

	if err := c.Resize(50, 15); err != nil {
		t.Fatal(err)
	}
	until(t, c, "resized", func(e mux.Event) bool { p := pane(t, c, block); return e.Changed && p.Cols == 50 && p.Rows == 15 })

	// Away, the client gives the terminal up.
	if err := c.Away(true); err != nil {
		t.Fatal(err)
	}
	var size struct {
		Owner *string `json:"owner"`
	}
	if err := json.Unmarshal([]byte(run("block", "call", "-s", c.session, "-b", block, "size")), &size); err != nil || size.Owner != nil {
		t.Errorf("owned by %v while away (%v)", size.Owner, err)
	}
}

func TestLayout(t *testing.T) {
	c, _, block := client(t, 60, 20)
	c.Snapshot()
	split, err := c.Split(block)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.NewWindow(); err != nil {
		t.Fatal(err)
	}
	s, _ := c.Snapshot()
	if len(s.Windows) != 2 || len(s.Windows[0].Panes) != 2 || s.Windows[0].Panes[1].ID != split || !s.Windows[1].Active {
		t.Fatalf("after split and new window: %+v", s)
	}
	second := s.Windows[1]
	if err := c.RenameWindow(second.ID, "logs ✓"); err != nil {
		t.Fatal(err)
	}
	if err := c.SelectWindow(s.Windows[0].ID); err != nil {
		t.Fatal(err)
	}
	if err := c.Focus(block); err != nil {
		t.Fatal(err)
	}
	s, _ = c.Snapshot()
	if !s.Windows[0].Active || !s.Windows[0].Panes[0].Active || s.Windows[1].Name != "logs ✓" {
		t.Fatalf("after rename, select and focus: %+v", s)
	}
	if err := c.KillPane(split); err != nil {
		t.Fatal(err)
	}
	if err := c.KillWindow(second.ID); err != nil {
		t.Fatal(err)
	}
	s, _ = c.Snapshot()
	if len(s.Windows) != 1 || len(s.Windows[0].Panes) != 1 {
		t.Fatalf("after kills: %+v", s)
	}
}
