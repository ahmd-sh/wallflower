package gateway

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
	"github.com/ahmd-sh/wallflower/internal/tmuxtest"
)

func TestMain(m *testing.M) {
	if os.Getenv("WALLFLOWER_TEST_SLEEP") != "" {
		time.Sleep(time.Minute)
		os.Exit(0)
	}
	os.Exit(m.Run())
}

type client struct {
	t      *testing.T
	w      io.WriteCloser
	frames chan protocol.Frame
	done   chan error
	socket string
	tmux   func(args ...string) string
}

// connect starts a gateway for a cols x rows phone against a fresh 120x40
// tmux session and says hello.
func connect(t *testing.T, cols, rows int) *client {
	t.Helper()
	return connectCtx(t, context.Background(), cols, rows)
}

func connectCtx(t *testing.T, ctx context.Context, cols, rows int) *client {
	t.Helper()
	return connectOpts(t, ctx, cols, rows, Options{})
}

// connectOpts is connect with more options; the socket and session are filled in.
func connectOpts(t *testing.T, ctx context.Context, cols, rows int, opts Options) *client {
	t.Helper()
	socket, tmuxRun := tmuxtest.Server(t, 120, 40)
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	c := &client{t: t, w: inW, frames: make(chan protocol.Frame, 1024), done: make(chan error, 1), socket: socket, tmux: tmuxRun}
	go func() {
		opts.Socket, opts.Session = socket, "t"
		c.done <- Serve(ctx, inR, outW, opts)
		outW.Close()
	}()
	go func() {
		r := protocol.NewReader(outR)
		for {
			f, err := r.Next()
			if err != nil {
				close(c.frames)
				return
			}
			c.frames <- f
		}
	}()
	t.Cleanup(func() { c.disconnect() })
	c.send(protocol.ClientHello{V: protocol.Version, Cols: cols, Rows: rows})
	return c
}

func (c *client) send(m protocol.Message) {
	b, err := protocol.AppendJSON(nil, m)
	if err != nil {
		c.t.Fatal(err)
	}
	c.w.Write(b)
}

func (c *client) sendText(pane, s string) {
	c.w.Write(protocol.AppendData(nil, pane, []byte(s)))
}

// disconnect closes the client's side and waits for Serve to return.
func (c *client) disconnect() error {
	c.w.Close()
	select {
	case err := <-c.done:
		c.done <- err
		return err
	case <-time.After(5 * time.Second):
		c.t.Fatal("gateway did not exit after disconnect")
		return nil
	}
}

// expect reads frames until match returns true. m is the decoded message for
// JSON frames and nil for data frames.
func (c *client) expect(what string, match func(f protocol.Frame, m protocol.Message) bool) (protocol.Frame, protocol.Message) {
	c.t.Helper()
	timeout := time.After(5 * time.Second)
	for {
		select {
		case f, ok := <-c.frames:
			if !ok {
				c.t.Fatalf("connection closed waiting for %s", what)
			}
			var m protocol.Message
			if f.Type == protocol.FrameJSON {
				var err error
				if m, err = protocol.DecodeServer(f.Data); err != nil {
					c.t.Fatalf("bad server message %s: %v", f.Data, err)
				}
			}
			if match(f, m) {
				return f, m
			}
		case <-timeout:
			c.t.Fatalf("timed out waiting for %s", what)
		}
	}
}

func (c *client) state(what string, match func(protocol.State) bool) protocol.State {
	c.t.Helper()
	_, m := c.expect(what, func(_ protocol.Frame, m protocol.Message) bool {
		s, ok := m.(*protocol.State)
		return ok && match(*s)
	})
	return *m.(*protocol.State)
}

// output waits for the pane's output, from now on, to hold contains, however it's split into frames.
func (c *client) output(pane, contains string) {
	c.t.Helper()
	var out []byte
	c.expect("output "+contains, func(f protocol.Frame, _ protocol.Message) bool {
		if f.Type == protocol.FrameData && f.ID == pane {
			out = append(out, f.Data...)
		}
		return bytes.Contains(out, []byte(contains))
	})
}

// until polls until ok, for what happens in a pane outside the protocol.
func (c *client) until(what string, ok func() bool) {
	c.t.Helper()
	for deadline := time.Now().Add(5 * time.Second); !ok(); time.Sleep(10 * time.Millisecond) {
		if time.Now().After(deadline) {
			c.t.Fatalf("timed out waiting for %s", what)
		}
	}
}

// zoomed is whether tmux has the session's current window zoomed.
func (c *client) zoomed() bool {
	return c.tmux("display", "-p", "-t", "t", "#{window_zoomed_flag}") == "1"
}

func activePane(s protocol.State) protocol.Pane {
	for _, w := range s.Windows {
		if w.Active {
			for _, p := range w.Panes {
				if p.Active {
					return p
				}
			}
		}
	}
	return protocol.Pane{}
}

func activeWindow(s protocol.State) protocol.Window {
	for _, w := range s.Windows {
		if w.Active {
			return w
		}
	}
	return protocol.Window{}
}

func TestHelloStateOpenType(t *testing.T) {
	c := connect(t, 56, 20)

	_, m := c.expect("hello", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.ServerHello); return ok })
	if h := m.(*protocol.ServerHello); h.V != protocol.Version || !strings.HasPrefix(h.Tmux, "3.") {
		t.Errorf("hello = %+v", h)
	}
	// The phone's size applies to the window it's looking at.
	s := c.state("phone-sized state", func(s protocol.State) bool { return activePane(s).Cols == 56 })
	p := activePane(s)
	if s.Name != "t" || p.ID == "" || p.Cols != 56 || p.Rows != 20 {
		t.Fatalf("state = %+v", s)
	}

	c.send(protocol.Open{Pane: p.ID})
	_, m = c.expect("reset", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Reset); return ok })
	if r := m.(*protocol.Reset); r.Pane != p.ID || r.Cols != 56 || r.Rows != 20 {
		t.Errorf("reset = %+v", r)
	}
	c.output(p.ID, "$") // capture-pane trims the prompt's trailing space

	c.sendText(p.ID, "echo hi-$((1+1))\r")
	c.output(p.ID, "hi-2\r\n")

	c.send(protocol.Keys{Pane: p.ID, Keys: []string{"echo ", "-n", " ", `"key's"`, "Enter"}})
	c.output(p.ID, "-n key's\r\n")
}

func TestRepaint(t *testing.T) {
	c := connect(t, 60, 10)
	s := c.state("state", func(s protocol.State) bool { return activePane(s).Cols == 60 })
	p := activePane(s).ID

	c.tmux("send-keys", "-t", "t", "for i in $(seq 1 30); do echo line-$i; done", "Enter")
	c.until("the lines", func() bool { return strings.Contains(c.tmux("capture-pane", "-p", "-t", p), "line-30") })
	c.send(protocol.Open{Pane: p, History: 100})
	f, _ := c.expect("repaint", func(f protocol.Frame, _ protocol.Message) bool { return f.Type == protocol.FrameData })
	for _, want := range []string{"line-1\r\n", "line-30\r\n"} {
		if !bytes.Contains(f.Data, []byte(want)) {
			t.Errorf("repaint missing %q", want)
		}
	}

	// A full-screen app: the repaint switches to the alternate screen and
	// keeps the shell's screen behind it.
	c.tmux("send-keys", "-t", "t", `printf '\033[?1049hFULLSCREEN'; sleep 30`, "Enter")
	c.state("alt screen", func(s protocol.State) bool { return activePane(s).Alt })
	c.send(protocol.Open{Pane: p})
	// The app's own output can come after its state says so; the repaint starts by clearing.
	f, _ = c.expect("alt repaint", func(f protocol.Frame, _ protocol.Message) bool {
		return f.Type == protocol.FrameData && bytes.Contains(f.Data, []byte("\x1b[H\x1b[2J")) && bytes.Contains(f.Data, []byte("\x1b[?1049h"))
	})
	alt := bytes.Index(f.Data, []byte("\x1b[?1049h"))
	if !bytes.Contains(f.Data[:alt], []byte("line-30")) || !bytes.Contains(f.Data[alt:], []byte("FULLSCREEN")) {
		t.Errorf("alt repaint out of order: %q", f.Data)
	}
}

func TestResizeAndReclaim(t *testing.T) {
	c := connect(t, 50, 20)
	s := c.state("phone-sized", func(s protocol.State) bool { return activePane(s).Cols == 50 })
	p := activePane(s).ID
	c.send(protocol.Open{Pane: p})

	c.send(protocol.Resize{Cols: 70, Rows: 25})
	c.state("resized", func(s protocol.State) bool { p := activePane(s); return p.Cols == 70 && p.Rows == 25 })
	c.expect("repaint at new size", func(_ protocol.Frame, m protocol.Message) bool {
		r, ok := m.(*protocol.Reset)
		return ok && r.Cols == 70 && r.Rows == 25
	})

	// Another client (the desktop) takes the window; typing on the phone takes it back.
	desk, err := tmux.Start(context.Background(), tmux.Options{Socket: c.socket, Session: "t"})
	if err != nil {
		t.Fatal(err)
	}
	defer desk.Close()
	go func() {
		for range desk.Events() {
		}
	}()
	if _, err := desk.Run("refresh-client -C 120x40"); err != nil {
		t.Fatal(err)
	}
	c.state("desktop-sized", func(s protocol.State) bool { return activePane(s).Cols == 120 })
	c.sendText(p, "x")
	c.state("reclaimed", func(s protocol.State) bool { return activePane(s).Cols == 70 })

	// So does the phone resizing (its keyboard opening) after the desktop took over.
	if _, err := desk.Run("select-window -t " + tmux.Quote("t:"+s.Windows[0].ID)); err != nil {
		t.Fatal(err)
	}
	c.state("desktop-sized again", func(s protocol.State) bool { return activePane(s).Cols == 120 })
	c.send(protocol.Resize{Cols: 70, Rows: 15})
	c.state("phone keyboard size", func(s protocol.State) bool { p := activePane(s); return p.Cols == 70 && p.Rows == 15 })
	c.send(protocol.Resize{Cols: 70, Rows: 25})
	c.state("phone size", func(s protocol.State) bool { return activePane(s).Rows == 25 })

	// Typing into a window that isn't current for the phone (it shows what it
	// likes) makes it current, at the phone's size.
	c.tmux("new-window", "-d", "-t", "t:")
	s = c.state("second window", func(s protocol.State) bool { return len(s.Windows) == 2 })
	other := s.Windows[1]
	if other.Active {
		t.Fatal("new window is current")
	}
	if _, err := desk.Run("select-window -t " + other.ID); err != nil {
		t.Fatal(err)
	}
	c.sendText(other.Panes[0].ID, "x")
	c.state("typed-into window is the phone's", func(s protocol.State) bool {
		w := activeWindow(s)
		return w.ID == other.ID && w.Panes[0].Cols == 70 && w.Panes[0].Rows == 25
	})
}

func TestZoomRestoredOnDisconnect(t *testing.T) {
	c := connect(t, 60, 20)
	c.tmux("split-window", "-t", "t")
	s := c.state("two panes", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 2 })
	c.send(protocol.Focus{Pane: activeWindow(s).Panes[0].ID})
	c.state("zoomed", func(protocol.State) bool { return c.zoomed() })

	if err := c.disconnect(); err != nil {
		t.Fatal(err)
	}
	if z := c.tmux("display", "-p", "-t", "t", "#{window_zoomed_flag}"); z != "0" {
		t.Errorf("window still zoomed after disconnect")
	}
}

// A signal (ssh hangup) cancels the context; zooms are still undone.
func TestFocus(t *testing.T) {
	c := connect(t, 60, 20)
	c.tmux("split-window", "-t", "t")
	s := c.state("two panes", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 2 })
	first := activeWindow(s).Panes[0].ID

	// Focusing another pane selects it and zooms the window; focusing the
	// next keeps the zoom.
	c.send(protocol.Focus{Pane: first})
	c.state("first zoomed", func(s protocol.State) bool { return c.zoomed() && activePane(s).ID == first })
	second := activeWindow(s).Panes[1].ID
	c.send(protocol.Focus{Pane: second})
	c.state("second zoomed", func(s protocol.State) bool { return c.zoomed() && activePane(s).ID == second })

	if err := c.disconnect(); err != nil {
		t.Fatal(err)
	}
	if z := c.tmux("display", "-p", "-t", "t", "#{window_zoomed_flag}"); z != "0" {
		t.Errorf("window still zoomed after disconnect")
	}
}

func TestPaneAndWindowActions(t *testing.T) {
	c := connect(t, 60, 20)
	s := c.state("one window", func(s protocol.State) bool { return len(s.Windows) == 1 })
	first := activePane(s).ID

	c.send(protocol.Split{Pane: first})
	s = c.state("split, focused and zoomed", func(s protocol.State) bool {
		w := activeWindow(s)
		return len(w.Panes) == 2 && activePane(s).ID != first && c.zoomed()
	})
	c.send(protocol.Kill{Pane: activePane(s).ID})
	c.state("back to one pane", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 1 })

	// A new window is current, for the desktop too: they're one session.
	c.send(protocol.New{})
	s = c.state("two windows", func(s protocol.State) bool { return len(s.Windows) == 2 && activeWindow(s).Index == 1 })
	if w := c.tmux("display", "-p", "-t", "t:", "#{window_index}"); w != "1" {
		t.Errorf("desktop on window %s, want the new one", w)
	}
	added := activeWindow(s).ID
	c.send(protocol.Rename{Window: added, Name: "logs $HOME ✓"})
	c.state("renamed", func(s protocol.State) bool { return activeWindow(s).Name == "logs $HOME ✓" })
	c.send(protocol.Kill{Window: added})
	c.state("one window again", func(s protocol.State) bool { return len(s.Windows) == 1 })
}

func TestBoundKeys(t *testing.T) {
	c := connect(t, 60, 20)
	c.tmux("split-window", "-t", "t")
	c.tmux("bind", "-n", "C-k", "select-pane", "-U")
	s := c.state("two panes", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 2 })
	bottom := activePane(s).ID

	// A root binding, then the prefix and a prefix binding (C-b o: next pane).
	c.send(protocol.Keys{Pane: bottom, Keys: []string{"C-k"}, Bindings: true})
	c.state("top pane", func(s protocol.State) bool { return activePane(s).ID != bottom })
	c.send(protocol.Keys{Pane: bottom, Keys: []string{"prefix", "o"}, Bindings: true})
	c.state("bottom again", func(s protocol.State) bool { return activePane(s).ID == bottom })
}

func TestZoomRestoredOnSignal(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	c := connectCtx(t, ctx, 60, 20)
	c.tmux("split-window", "-t", "t")
	s := c.state("two panes", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 2 })
	c.send(protocol.Focus{Pane: activeWindow(s).Panes[0].ID})
	c.state("zoomed", func(protocol.State) bool { return c.zoomed() })

	cancel()
	select {
	case <-c.done:
	case <-time.After(5 * time.Second):
		t.Fatal("gateway did not exit on cancel")
	}
	c.done <- nil
	if z := c.tmux("display", "-p", "-t", "t", "#{window_zoomed_flag}"); z != "0" {
		t.Errorf("window still zoomed after signal")
	}
}

func TestStateFollowsTmux(t *testing.T) {
	c := connect(t, 60, 20)
	c.state("initial", func(s protocol.State) bool { return len(s.Windows) == 1 })

	// A window opened on the desktop shows up.
	c.tmux("new-window", "-d", "-t", "t:", "-n", "second")
	c.tmux("new-pane", "-t", "t:second", "-x", "20", "-y", "6")
	s := c.state("floating pane", func(s protocol.State) bool {
		return len(s.Windows) == 2 && len(s.Windows[1].Panes) == 2
	})
	if w := s.Windows[1]; w.Name != "second" || w.Active {
		t.Errorf("window = %+v", w)
	}
	if s.Name != "t" {
		t.Errorf("state names session %q, want t", s.Name)
	}
}

func TestPaste(t *testing.T) {
	c := connect(t, 60, 20)
	p := activePane(c.state("state", func(s protocol.State) bool { return true })).ID
	c.send(protocol.Open{Pane: p})
	c.output(p, "$")

	// An app that asked for bracketed paste gets the text bracketed, with
	// newlines as Enter the way a terminal sends them.
	c.sendText(p, `printf '\033[?2004h'; stty raw -echo; echo RE""ADY; cat -v`+"\r")
	c.output(p, "READY")
	c.send(protocol.Submit{Pane: p, Text: "one $HOME\n'two'"})
	c.output(p, "^[[200~one $HOME^M'two'^[[201~")

	// What empties the line goes first, then the text, then Enter.
	c.send(protocol.Submit{Pane: p, Text: "hi", Enter: true, Clear: []string{"C-u"}})
	c.output(p, "^Uhi^M")
}

// An upload's path is pasted on its own, so the program can take it in as a
// file, and what follows waits for that.
func TestSubmitPastesUploadsAlone(t *testing.T) {
	dir := t.TempDir()
	c := connectOpts(t, context.Background(), 60, 20, Options{Uploads: dir})
	p := activePane(c.state("state", func(s protocol.State) bool { return true })).ID
	c.send(protocol.Open{Pane: p})
	c.output(p, "$")
	c.sendText(p, `printf '\033[?2004h'; stty raw -echo; echo RE""ADY; cat -v`+"\r")
	c.output(p, "READY")
	c.send(protocol.Submit{Pane: p, Text: "see " + dir + "/PXL-1.jpg now", Enter: true})
	c.output(p, "see ^[[200~"+dir+"/PXL-1.jpg^[[201~ now^M")
}

func TestEnterOnItsOwn(t *testing.T) {
	c := connect(t, 60, 20)
	p := activePane(c.state("state", func(s protocol.State) bool { return true })).ID
	c.send(protocol.Open{Pane: p})
	c.output(p, "$")

	// Text sent with an Enter arrives in two reads, the Enter alone, so it
	// isn't taken as a paste.
	c.sendText(p, `perl -e 'system "stty raw -echo"; $|=1; print "RE", "ADY"; print "<", length($b), ">" while sysread STDIN, $b, 4096'`+"\r")
	c.output(p, "READY")
	sent := time.Now()
	c.send(protocol.Submit{Pane: p, Text: "say just: ok", Enter: true})
	// What follows waits for the Enter, a ping too: its pong still means all before it reached tmux.
	c.send(protocol.Ping{ID: 9})
	var out []byte
	var ponged time.Duration
	c.expect("two reads and the pong", func(f protocol.Frame, m protocol.Message) bool {
		if f.Type == protocol.FrameData && f.ID == p {
			out = append(out, f.Data...)
		}
		if pong, ok := m.(*protocol.Pong); ok && pong.ID == 9 {
			ponged = time.Since(sent)
		}
		return bytes.Contains(out, []byte("<12><1>")) && ponged > 0
	})
	if ponged < enterSettle {
		t.Errorf("pong after %v, before the Enter was typed", ponged)
	}
}

func TestVersionedProgram(t *testing.T) {
	c := connect(t, 60, 20)
	c.state("state", func(s protocol.State) bool { return true })

	// Run the way Claude Code runs: a file named after its version, started
	// through a symlink with the tool's name. The file is this test binary
	// (see TestMain); macOS won't run a copy of a system one.
	dir := t.TempDir()
	bin := filepath.Join(dir, "1.2.3")
	self, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(self)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(bin, data, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(bin, filepath.Join(dir, "tool")); err != nil {
		t.Fatal(err)
	}
	c.tmux("send-keys", "-t", "t", "WALLFLOWER_TEST_SLEEP=1 "+filepath.Join(dir, "tool"), "Enter")
	c.state("program named tool", func(s protocol.State) bool { return activePane(s).Command == "tool" })
}

func TestOneSession(t *testing.T) {
	c := connect(t, 60, 20)
	c.tmux("new-window", "-d", "-t", "t:", "-n", "second")
	s := c.state("two windows", func(s protocol.State) bool { return len(s.Windows) == 2 })
	if ls := c.tmux("list-sessions", "-F", "#{session_name}"); ls != "t" {
		t.Errorf("sessions while connected = %q, want only t", ls)
	}

	// A window switched to on the phone is the desktop's too, and back.
	c.send(protocol.Select{Window: s.Windows[1].ID})
	c.state("phone on second", func(s protocol.State) bool { return activeWindow(s).Name == "second" })
	if w := c.tmux("display", "-p", "-t", "t:", "#{window_name}"); w != "second" {
		t.Errorf("desktop on window %s, want second", w)
	}
	c.tmux("select-window", "-t", "t:0")
	c.state("desktop back on the first", func(s protocol.State) bool { return activeWindow(s).Name != "second" })

	if err := c.disconnect(); err != nil {
		t.Fatal(err)
	}
	if ls := c.tmux("list-sessions", "-F", "#{session_name}"); ls != "t" {
		t.Errorf("sessions after disconnect = %q, want only t", ls)
	}
}

func TestRequests(t *testing.T) {
	c := connect(t, 60, 20)
	c.state("state", func(s protocol.State) bool { return true })

	c.send(protocol.Ping{ID: 1})
	c.expect("pong", func(_ protocol.Frame, m protocol.Message) bool { r, ok := m.(*protocol.Pong); return ok && r.ID == 1 })

	// Ids are spliced into tmux commands, so anything but %N / @N is refused.
	c.send(protocol.Open{Pane: "%1 ; kill-server"})
	c.expect("bad id error", func(_ protocol.Frame, m protocol.Message) bool {
		e, ok := m.(*protocol.Error)
		return ok && strings.Contains(e.Message, "bad id")
	})
	c.send(protocol.Ping{ID: 5})
	c.expect("still alive", func(_ protocol.Frame, m protocol.Message) bool { r, ok := m.(*protocol.Pong); return ok && r.ID == 5 })
}

func TestBadHello(t *testing.T) {
	socket, _ := tmuxtest.Server(t, 80, 24)
	var in bytes.Buffer
	b, _ := protocol.AppendJSON(nil, protocol.ClientHello{V: 99, Cols: 80, Rows: 24})
	in.Write(b)
	var out bytes.Buffer
	if err := Serve(context.Background(), &in, &out, Options{Socket: socket}); err == nil {
		t.Fatal("accepted protocol version 99")
	}
	f, err := protocol.NewReader(&out).Next()
	if err != nil || !bytes.Contains(f.Data, []byte(`"t":"bye"`)) {
		t.Errorf("want bye, got %s, %v", f.Data, err)
	}
}

func TestTmuxExit(t *testing.T) {
	c := connect(t, 60, 20)
	c.state("state", func(s protocol.State) bool { return true })
	c.tmux("kill-server")
	c.expect("bye", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Bye); return ok })
}

func TestUpload(t *testing.T) {
	dir := t.TempDir()
	c := connectOpts(t, context.Background(), 60, 20, Options{Uploads: dir})
	_, m := c.expect("hello", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.ServerHello); return ok })
	if got := m.(*protocol.ServerHello).Uploads; got != dir {
		t.Errorf("hello says uploads go in %q, want %q", got, dir)
	}

	uploaded := func(id string) protocol.Uploaded {
		t.Helper()
		_, m := c.expect("uploaded "+id, func(_ protocol.Frame, m protocol.Message) bool {
			u, ok := m.(*protocol.Uploaded)
			return ok && u.ID == id
		})
		return *m.(*protocol.Uploaded)
	}

	// Its bytes arrive in pieces, and it's saved under a name a shell takes as is.
	c.send(protocol.Upload{ID: "a", Name: "PXL 1 (2).jpg", Size: 6})
	c.w.Write(protocol.AppendUpload(nil, "a", []byte("abc")))
	c.w.Write(protocol.AppendUpload(nil, "a", []byte("def")))
	u := uploaded("a")
	if want := filepath.Join(dir, "PXL-1-2-.jpg"); u.Path != want || u.Error != "" {
		t.Fatalf("uploaded %+v, want %s", u, want)
	}
	if data, _ := os.ReadFile(u.Path); string(data) != "abcdef" {
		t.Errorf("saved %q", data)
	}

	// The same name again gets a name of its own.
	c.send(protocol.Upload{ID: "b", Name: "PXL 1 (2).jpg", Size: 1})
	c.w.Write(protocol.AppendUpload(nil, "b", []byte("x")))
	if u := uploaded("b"); u.Path != filepath.Join(dir, "PXL-1-2--2.jpg") {
		t.Errorf("second upload saved as %q", u.Path)
	}

	// More than it said is an error, and nothing is left behind.
	c.send(protocol.Upload{ID: "c", Name: "short.txt", Size: 1})
	c.w.Write(protocol.AppendUpload(nil, "c", []byte("xy")))
	if u := uploaded("c"); u.Error == "" {
		t.Errorf("an upload longer than it said was saved: %+v", u)
	}
	if _, err := os.Stat(filepath.Join(dir, "short.txt")); err == nil {
		t.Error("a failed upload was left behind")
	}

	// One the client leaves partway is removed when it goes.
	c.send(protocol.Upload{ID: "d", Name: "half.bin", Size: 10})
	c.w.Write(protocol.AppendUpload(nil, "d", []byte("12345")))
	c.send(protocol.Ping{ID: 9})
	c.expect("pong", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Pong); return ok })
	c.disconnect()
	if _, err := os.Stat(filepath.Join(dir, "half.bin")); err == nil {
		t.Error("an unfinished upload outlived the client")
	}
}

func TestSafeName(t *testing.T) {
	for in, want := range map[string]string{
		"PXL_20261017_101010123.jpg":            "PXL_20261017_101010123.jpg",
		"Screenshot 2026-10-17 at 10.01.15.png": "Screenshot-2026-10-17-at-10.01.15.png",
		"../../etc/passwd":                      "passwd",
		".hidden":                               "hidden",
		"résumé ✓.pdf":                          "r-sum-.pdf",
		"":                                      "upload",
		"   ":                                   "upload",
	} {
		if got := safeName(in); got != want {
			t.Errorf("safeName(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestUploadsAreTheirOwnRuns(t *testing.T) {
	u := uploads{dir: "/Users/me/Library/Caches/wallflower/uploads"}
	got := u.split("what's in /Users/me/Library/Caches/wallflower/uploads/PXL_1.jpg and /Users/me/Library/Caches/wallflower/uploads/a-2.png. ok")
	want := []run{
		{text: "what's in "},
		{text: "/Users/me/Library/Caches/wallflower/uploads/PXL_1.jpg", upload: true},
		{text: " and "},
		{text: "/Users/me/Library/Caches/wallflower/uploads/a-2.png", upload: true},
		{text: ". ok"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %+v", got)
	}
	if got := u.split("no uploads"); len(got) != 1 || got[0].upload {
		t.Errorf("plain text: %+v", got)
	}
}

func TestNotificationsFromEveryPane(t *testing.T) {
	c := connect(t, 60, 20)
	c.state("first", func(s protocol.State) bool { return len(s.Windows) == 1 })
	// A window the phone hasn't opened, behind the current one.
	c.tmux("new-window", "-d", "-t", "t:", `printf '\033]777;notify;Tests;all pass\007\033Ptmux;\033\033]9;wrapped\007\033\134 and \007'; sleep 30`)
	var got []protocol.Notification
	c.expect("three notifications", func(_ protocol.Frame, m protocol.Message) bool {
		if n, ok := m.(*protocol.Notification); ok {
			got = append(got, *n)
		}
		return len(got) == 3
	})
	back := c.tmux("display", "-p", "-t", "t:1", "#{pane_id}")
	want := []protocol.Notification{{Pane: back, Title: "Tests", Body: "all pass"}, {Pane: back, Body: "wrapped"}, {Pane: back, Bell: true}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("got %+v, want %+v", got, want)
	}
}

func TestAwayLeavesSizing(t *testing.T) {
	c := connect(t, 50, 20)
	c.state("phone-sized", func(s protocol.State) bool { return activePane(s).Cols == 50 })
	desk, err := tmux.Start(context.Background(), tmux.Options{Socket: c.socket, Session: "t"})
	if err != nil {
		t.Fatal(err)
	}
	defer desk.Close()
	go func() {
		for range desk.Events() {
		}
	}()
	if _, err := desk.Run("refresh-client -C 120x40"); err != nil {
		t.Fatal(err)
	}
	s := c.state("desktop-sized", func(s protocol.State) bool { return activePane(s).Cols == 120 })
	flags := func() string { return c.tmux("list-clients", "-F", "#{client_flags}") }

	// Away, the phone's resizes and selects leave the desktop's size alone.
	c.send(protocol.Away{On: true})
	c.until("ignore-size", func() bool { return strings.Contains(flags(), "ignore-size") })
	c.send(protocol.Resize{Cols: 50, Rows: 15})
	c.send(protocol.Select{Window: s.Windows[0].ID})
	c.send(protocol.Ping{ID: 1})
	c.expect("pong", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Pong); return ok })
	if w := c.tmux("display", "-p", "-t", "t:", "#{window_width}"); w != "120" {
		t.Errorf("window %s wide while away, want the desktop's 120", w)
	}

	c.send(protocol.Away{})
	c.until("sizing again", func() bool { return !strings.Contains(flags(), "ignore-size") })
	c.send(protocol.Select{Window: s.Windows[0].ID})
	c.state("phone-sized again", func(s protocol.State) bool { p := activePane(s); return p.Cols == 50 && p.Rows == 15 })
}
