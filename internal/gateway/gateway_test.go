package gateway

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mux"
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
	t          *testing.T
	w          io.WriteCloser
	frames     chan protocol.Frame
	done       chan error
	desk       desk
	cols, rows int
	last       *protocol.State // the latest state read
}

// tmux runs tmux against the client's server, for what only tmux does.
func (c *client) tmux(args ...string) string { return c.desk.(*tmuxDesk).tmux(args...) }

// connect starts a gateway for a cols x rows phone against a fresh session
// "t" of the backend's, and says hello.
func (b backend) connect(t *testing.T, cols, rows int) *client {
	t.Helper()
	return b.connectOpts(t, context.Background(), cols, rows, Options{})
}

// connectOpts is connect with more options; the multiplexer and session
// are filled in.
func (b backend) connectOpts(t *testing.T, ctx context.Context, cols, rows int, opts Options) *client {
	t.Helper()
	return b.dial(t, ctx, opts, protocol.ClientHello{V: protocol.Version, Cols: cols, Rows: rows})
}

// dial is connectOpts saying its own hello.
func (b backend) dial(t *testing.T, ctx context.Context, opts Options, hello protocol.ClientHello) *client {
	t.Helper()
	cols, rows := hello.Cols, hello.Rows
	m, d := b.start(t)
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	c := &client{t: t, w: inW, frames: make(chan protocol.Frame, 1024), done: make(chan error, 1), desk: d, cols: cols, rows: rows}
	opts.Muxes, opts.Mux, opts.Session = map[string]mux.Mux{b.name: m}, b.name, "t"
	go func() {
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
	c.send(hello)
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
				if s, ok := m.(*protocol.State); ok {
					c.last = s
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
	defer func() {
		if c.t.Failed() {
			c.t.Logf("output so far: %q", out)
		}
	}()
	c.expect("output "+contains, func(f protocol.Frame, _ protocol.Message) bool {
		if f.Type == protocol.FrameData && f.ID == pane {
			out = append(out, f.Data...)
		}
		return bytes.Contains(out, []byte(contains))
	})
}

// show focuses a pane and opens it, as the phone does the pane it shows,
// and waits for its prompt. It's the phone's size before it's open, so no
// repaint for a resize comes after.
func (c *client) show(pane string) {
	c.t.Helper()
	c.send(protocol.Focus{Pane: pane})
	sized := func(s protocol.State) bool {
		p := paneOf(s, pane)
		return p.Active && p.Cols == c.cols && p.Rows == c.rows
	}
	if c.last == nil || !sized(*c.last) {
		c.state("phone-sized "+pane, sized)
	}
	c.send(protocol.Open{Pane: pane})
	c.output(pane, "$")
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

func paneOf(s protocol.State, id string) protocol.Pane {
	for _, w := range s.Windows {
		for _, p := range w.Panes {
			if p.ID == id {
				return p
			}
		}
	}
	return protocol.Pane{}
}

func anyState(protocol.State) bool { return true }

func TestHelloStateOpenType(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 56, 20)
		_, m := c.expect("hello", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.ServerHello); return ok })
		if h := m.(*protocol.ServerHello); h.V != protocol.Version || h.Mux.Name != b.name || h.Mux.Version == "" || !slices.Equal(h.Mux.Can, b.can) {
			t.Errorf("hello = %+v", h)
		}
		s := c.state("phone-sized", func(s protocol.State) bool { p := activePane(s); return p.Cols == 56 && p.Rows == 20 })
		p := activePane(s)
		// macOS's /bin/sh is bash: tmux names the executable, Rex what it was run as.
		if s.Name != "t" || p.ID == "" || (p.Command != "sh" && p.Command != "bash") {
			t.Fatalf("state = %+v", s)
		}

		c.send(protocol.Open{Pane: p.ID})
		_, m = c.expect("reset", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Reset); return ok })
		if r := m.(*protocol.Reset); r.Pane != p.ID || r.Cols != 56 || r.Rows != 20 {
			t.Errorf("reset = %+v", r)
		}
		c.output(p.ID, "$")

		c.sendText(p.ID, "echo hi-$((1+1))\r")
		c.output(p.ID, "hi-2\r\n")

		c.send(protocol.Keys{Pane: p.ID, Keys: []string{"echo ", "-n", " ", `"key's"`, "Enter"}})
		c.output(p.ID, "-n key's\r\n")
	})
}

// The phone's size applies to the window it's on as soon as it's attached.
func TestSizedForThePhoneAtOnce(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 56, 20)
		c.state("phone-sized", func(s protocol.State) bool { p := activePane(s); return p.Cols == 56 && p.Rows == 20 })
	})
}

func TestRepaint(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 10)
		p := activePane(c.state("state", anyState)).ID

		c.desk.run(p, "for i in $(seq 1 30); do echo line-$i; done")
		c.until("the lines", func() bool { return strings.Contains(c.desk.screen(p), "line-30") })
		c.send(protocol.Open{Pane: p, History: 100})
		f, _ := c.expect("repaint", func(f protocol.Frame, _ protocol.Message) bool { return f.Type == protocol.FrameData })
		for _, want := range []string{"line-1\r\n", "line-30\r\n"} {
			if !bytes.Contains(f.Data, []byte(want)) {
				t.Errorf("repaint missing %q", want)
			}
		}

		// A full-screen program: the repaint switches to the alternate screen,
		// over the shell's screen if the multiplexer keeps it.
		c.desk.run(p, `printf '\033[?1049hFULLSCREEN'; sleep 30`)
		c.state("alt screen", func(s protocol.State) bool { return activePane(s).Alt })
		c.send(protocol.Open{Pane: p})
		// The program's own output can come after its state says so; the repaint starts by clearing.
		f, _ = c.expect("alt repaint", func(f protocol.Frame, _ protocol.Message) bool {
			return f.Type == protocol.FrameData && bytes.Contains(f.Data, []byte("\x1b[H\x1b[2J")) && bytes.Contains(f.Data, []byte("FULLSCREEN"))
		})
		if b.behindAlt {
			alt := bytes.Index(f.Data, []byte("\x1b[?1049h"))
			if alt < 0 || !bytes.Contains(f.Data[:alt], []byte("line-30")) || !bytes.Contains(f.Data[alt:], []byte("FULLSCREEN")) {
				t.Errorf("alt repaint out of order: %q", f.Data)
			}
		}
	})
}

func TestResizeAndReclaim(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 50, 20)
		p := activePane(c.state("phone-sized", func(s protocol.State) bool { return activePane(s).Cols == 50 })).ID
		c.send(protocol.Open{Pane: p})

		c.send(protocol.Resize{Cols: 70, Rows: 25})
		c.state("resized", func(s protocol.State) bool { p := paneOf(s, p); return p.Cols == 70 && p.Rows == 25 })
		c.expect("repaint at new size", func(_ protocol.Frame, m protocol.Message) bool {
			r, ok := m.(*protocol.Reset)
			return ok && r.Cols == 70 && r.Rows == 25
		})

		// The desktop takes the pane; typing on the phone takes it back.
		c.desk.take(p)
		c.state("desktop-sized", func(s protocol.State) bool { return paneOf(s, p).Cols == 120 })
		c.sendText(p, "x")
		c.state("reclaimed", func(s protocol.State) bool { return paneOf(s, p).Cols == 70 })

		// So does the phone resizing (its keyboard opening) after the desktop took it.
		c.desk.take(p)
		c.state("desktop-sized again", func(s protocol.State) bool { return paneOf(s, p).Cols == 120 })
		c.send(protocol.Resize{Cols: 70, Rows: 15})
		c.state("phone keyboard size", func(s protocol.State) bool { p := paneOf(s, p); return p.Cols == 70 && p.Rows == 15 })
		c.send(protocol.Resize{Cols: 70, Rows: 25})
		c.state("phone size", func(s protocol.State) bool { return paneOf(s, p).Rows == 25 })

		// A pane in a window behind the current one is sized for the phone
		// once it's typed into.
		_, other := c.desk.window("other", "")
		c.state("second window", func(s protocol.State) bool { return len(s.Windows) == 2 })
		c.sendText(other, "x")
		c.state("typed-into pane is the phone's", func(s protocol.State) bool { p := paneOf(s, other); return p.Cols == 70 && p.Rows == 25 })
	})
}

// Focusing a pane makes it its window's active one, with the whole of the
// phone's size. What the phone held for that, it gives back when it goes.
func TestFocus(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		first := activePane(c.state("state", anyState)).ID
		second := c.desk.split(first)
		c.state("two panes", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 2 })

		c.send(protocol.Focus{Pane: second})
		c.state("second focused", func(s protocol.State) bool { p := activePane(s); return p.ID == second && p.Cols == 60 && p.Rows == 20 })
		c.send(protocol.Focus{Pane: first})
		c.state("first focused", func(s protocol.State) bool { p := activePane(s); return p.ID == first && p.Cols == 60 && p.Rows == 20 })
		if c.desk.held() == "" {
			t.Error("the phone holds nothing for its focus")
		}

		if err := c.disconnect(); err != nil {
			t.Fatal(err)
		}
		c.until("nothing held after disconnect", func() bool { return c.desk.held() == "" })
	})
}

// A signal (ssh hangup) cancels the context; what the phone held is still
// given back.
func TestHeldGivenBackOnSignal(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		ctx, cancel := context.WithCancel(context.Background())
		c := b.connectOpts(t, ctx, 60, 20, Options{})
		first := activePane(c.state("state", anyState)).ID
		c.desk.split(first)
		c.state("two panes", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 2 })
		c.send(protocol.Focus{Pane: first})
		c.until("held", func() bool { return c.desk.held() != "" })

		cancel()
		select {
		case <-c.done:
		case <-time.After(5 * time.Second):
			t.Fatal("gateway did not exit on cancel")
		}
		c.done <- nil
		c.until("nothing held after the signal", func() bool { return c.desk.held() == "" })
	})
}

func TestPaneAndWindowActions(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		s := c.state("one window", func(s protocol.State) bool { return len(s.Windows) == 1 })
		first := activePane(s).ID

		c.send(protocol.Split{Pane: first})
		s = c.state("split and focused", func(s protocol.State) bool {
			p := activePane(s)
			return len(activeWindow(s).Panes) == 2 && p.ID != first && p.Cols == 60 && p.Rows == 20
		})
		c.send(protocol.Kill{Pane: activePane(s).ID})
		c.state("back to one pane", func(s protocol.State) bool { return len(activeWindow(s).Panes) == 1 })

		// A new window is current, for the desktop too: they're one session.
		c.send(protocol.New{})
		s = c.state("two windows", func(s protocol.State) bool { return len(s.Windows) == 2 && s.Windows[1].Active })
		added := s.Windows[1].ID
		if w := c.desk.current(); w != added {
			t.Errorf("desktop on window %s, want the new one %s", w, added)
		}
		c.send(protocol.Rename{Window: added, Name: "logs $HOME ✓"})
		c.state("renamed", func(s protocol.State) bool { return activeWindow(s).Name == "logs $HOME ✓" })
		c.send(protocol.Kill{Window: added})
		c.state("one window again", func(s protocol.State) bool { return len(s.Windows) == 1 })
	})
}

func TestBoundKeys(t *testing.T) {
	c := tmuxBackend.connect(t, 60, 20)
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

func TestStateFollowsTheDesktop(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		c.state("initial", func(s protocol.State) bool { return len(s.Windows) == 1 })

		// A window opened on the desktop shows up, and a pane split in it.
		w, p := c.desk.window("second", "")
		c.desk.split(p)
		s := c.state("the window and its split", func(s protocol.State) bool {
			return len(s.Windows) == 2 && len(s.Windows[1].Panes) == 2
		})
		if w2 := s.Windows[1]; w2.ID != w || w2.Name != "second" || w2.Active {
			t.Errorf("window = %+v", w2)
		}
		if s.Name != "t" {
			t.Errorf("state names session %q, want t", s.Name)
		}
	})
}

// tmux 3.8's floating panes are panes of their window too.
func TestFloatingPanes(t *testing.T) {
	c := tmuxBackend.connect(t, 60, 20)
	c.tmux("new-window", "-d", "-t", "t:", "-n", "second")
	c.tmux("new-pane", "-t", "t:second", "-x", "20", "-y", "6")
	c.state("floating pane", func(s protocol.State) bool { return len(s.Windows) == 2 && len(s.Windows[1].Panes) == 2 })
}

func TestPaste(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		p := activePane(c.state("state", anyState)).ID
		c.show(p)

		// A program that asked for bracketed paste gets the text bracketed,
		// with newlines as Enter the way a terminal sends them.
		c.sendText(p, `printf '\033[?2004h'; stty raw -echo; echo RE""ADY; cat -v`+"\r")
		c.output(p, "READY")
		c.send(protocol.Submit{Pane: p, Text: "one $HOME\n'two'"})
		c.output(p, "^[[200~one $HOME^M'two'^[[201~")

		// What empties the line goes first, then the text, then Enter.
		c.send(protocol.Submit{Pane: p, Text: "hi", Enter: true, Clear: []string{"C-u"}})
		c.output(p, "^Uhi^M")
	})
}

// An upload's path is pasted on its own, so the program can take it in as a
// file, and what follows waits for that.
func TestSubmitPastesUploadsAlone(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		dir := t.TempDir()
		c := b.connectOpts(t, context.Background(), 60, 20, Options{Uploads: dir})
		p := activePane(c.state("state", anyState)).ID
		c.show(p)
		c.sendText(p, `printf '\033[?2004h'; stty raw -echo; echo RE""ADY; cat -v`+"\r")
		c.output(p, "READY")
		c.send(protocol.Submit{Pane: p, Text: "see " + dir + "/PXL-1.jpg now", Enter: true})
		c.output(p, "see ^[[200~"+dir+"/PXL-1.jpg^[[201~ now^M")
	})
}

func TestEnterOnItsOwn(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		p := activePane(c.state("state", anyState)).ID
		c.show(p)

		// Text sent with an Enter arrives in two reads, the Enter alone, so it
		// isn't taken as a paste.
		c.sendText(p, `perl -e 'system "stty raw -echo"; $|=1; print "RE", "ADY"; print "<", length($b), ">" while sysread STDIN, $b, 4096'`+"\r")
		c.output(p, "READY")
		sent := time.Now()
		c.send(protocol.Submit{Pane: p, Text: "say just: ok", Enter: true})
		// What follows waits for the Enter, a ping too: its pong still means
		// all before it reached the multiplexer.
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
	})
}

func TestVersionedProgram(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		p := activePane(c.state("state", anyState)).ID

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
		c.desk.run(p, "WALLFLOWER_TEST_SLEEP=1 "+filepath.Join(dir, "tool"))
		c.state("program named tool", func(s protocol.State) bool { return paneOf(s, p).Command == "tool" })
	})
}

// The phone is attached to the session as the desktop is: it makes no
// session of its own, and the current window is the same for both.
func TestOneSession(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		second, _ := c.desk.window("second", "")
		s := c.state("two windows", func(s protocol.State) bool { return len(s.Windows) == 2 })
		if ls := c.desk.sessions(); !slices.Equal(ls, []string{"t"}) {
			t.Errorf("sessions while connected = %q, want only t", ls)
		}

		c.send(protocol.Select{Window: second})
		c.state("phone on second", func(s protocol.State) bool { return activeWindow(s).ID == second })
		if w := c.desk.current(); w != second {
			t.Errorf("desktop on window %s, want second (%s)", w, second)
		}
		c.desk.selectWindow(s.Windows[0].ID)
		c.state("desktop back on the first", func(s protocol.State) bool { return activeWindow(s).ID == s.Windows[0].ID })

		if err := c.disconnect(); err != nil {
			t.Fatal(err)
		}
		if ls := c.desk.sessions(); !slices.Equal(ls, []string{"t"}) {
			t.Errorf("sessions after disconnect = %q, want only t", ls)
		}
	})
}

func TestRequests(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		c.state("state", anyState)

		c.send(protocol.Ping{ID: 1})
		c.expect("pong", func(_ protocol.Frame, m protocol.Message) bool { r, ok := m.(*protocol.Pong); return ok && r.ID == 1 })

		// An id that isn't one is refused, whatever it says.
		c.send(protocol.Open{Pane: "%1 ; kill-server"})
		c.expect("bad id error", func(_ protocol.Frame, m protocol.Message) bool {
			e, ok := m.(*protocol.Error)
			return ok && e.Message != ""
		})
		c.send(protocol.Ping{ID: 5})
		c.expect("still alive", func(_ protocol.Frame, m protocol.Message) bool { r, ok := m.(*protocol.Pong); return ok && r.ID == 5 })
	})
}

func TestBadHello(t *testing.T) {
	socket, _ := tmuxtest.Server(t, 80, 24)
	var in bytes.Buffer
	b, _ := protocol.AppendJSON(nil, protocol.ClientHello{V: 99, Cols: 80, Rows: 24})
	in.Write(b)
	var out bytes.Buffer
	if err := Serve(context.Background(), &in, &out, Options{Muxes: map[string]mux.Mux{"tmux": tmux.Server{Socket: socket}}, Mux: "tmux"}); err == nil {
		t.Fatal("accepted protocol version 99")
	}
	f, err := protocol.NewReader(&out).Next()
	if err != nil || !bytes.Contains(f.Data, []byte(`"t":"bye"`)) {
		t.Errorf("want bye, got %s, %v", f.Data, err)
	}
}

// The client names the multiplexer, or gets the default; one the gateway
// doesn't have is refused.
func TestHelloPicksTheMux(t *testing.T) {
	socket, _ := tmuxtest.Server(t, 80, 24)
	opts := Options{Muxes: map[string]mux.Mux{"tmux": tmux.Server{Socket: socket}}, Mux: "tmux", Session: "t"}
	for _, ask := range []string{"tmux", "rex"} {
		var in, out bytes.Buffer
		b, _ := protocol.AppendJSON(nil, protocol.ClientHello{V: protocol.Version, Cols: 80, Rows: 24, Mux: ask})
		in.Write(b)
		err := Serve(context.Background(), &in, &out, opts)
		m, _ := protocol.DecodeServer(firstJSON(t, &out))
		switch h := m.(type) {
		case *protocol.ServerHello:
			if ask != "tmux" || h.Mux.Name != "tmux" {
				t.Errorf("asked for %s, got hello %+v", ask, h)
			}
		case *protocol.Bye:
			if ask != "rex" || err == nil || !strings.Contains(h.Reason, `no multiplexer "rex" here (tmux)`) {
				t.Errorf("asked for %s, got bye %q (%v)", ask, h.Reason, err)
			}
		default:
			t.Errorf("asked for %s, got %+v", ask, m)
		}
	}
}

func firstJSON(t *testing.T, r io.Reader) []byte {
	t.Helper()
	f, err := protocol.NewReader(r).Next()
	if err != nil || f.Type != protocol.FrameJSON {
		t.Fatalf("first frame %+v, %v", f, err)
	}
	return f.Data
}

func TestMuxExit(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		c.state("state", anyState)
		c.desk.exit()
		_, m := c.expect("bye", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Bye); return ok })
		if r := m.(*protocol.Bye).Reason; !strings.HasPrefix(r, b.name+" exited") {
			t.Errorf("bye %q", r)
		}
	})
}

func TestUpload(t *testing.T) {
	dir := t.TempDir()
	c := tmuxBackend.connectOpts(t, context.Background(), 60, 20, Options{Uploads: dir})
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

// Notifications come from every pane, opened by the phone or not. A Rex
// terminal's output reaches the gateway from when it first sees it, so the
// program waits for that.
func TestNotificationsFromEveryPane(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		c.state("first", func(s protocol.State) bool { return len(s.Windows) == 1 })
		ready := filepath.Join(t.TempDir(), "ready")
		_, back := c.desk.window("back", `while [ ! -e `+ready+` ]; do sleep 0.05; done; `+
			`printf '\033]777;notify;Tests;all pass\007\033Ptmux;\033\033]9;wrapped\007\033\134 and \007'; sleep 30`)
		c.state("the window behind", func(s protocol.State) bool { return len(s.Windows) == 2 })
		os.WriteFile(ready, nil, 0o644)
		var got []protocol.Notification
		c.expect("three notifications", func(_ protocol.Frame, m protocol.Message) bool {
			if n, ok := m.(*protocol.Notification); ok {
				got = append(got, *n)
			}
			return len(got) == 3
		})
		want := []protocol.Notification{{Pane: back, Title: "Tests", Body: "all pass"}, {Pane: back, Body: "wrapped"}, {Pane: back, Bell: true}}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("got %+v, want %+v", got, want)
		}
	})
}

// Away, the phone sizes nothing: its resizes and selects leave the
// desktop's size alone.
func TestAwayLeavesSizing(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 50, 20)
		s := c.state("phone-sized", func(s protocol.State) bool { return activePane(s).Cols == 50 })
		p := activePane(s).ID
		c.desk.take(p)
		c.state("desktop-sized", func(s protocol.State) bool { return paneOf(s, p).Cols == 120 })

		c.send(protocol.Away{On: true})
		c.send(protocol.Resize{Cols: 50, Rows: 15})
		c.send(protocol.Select{Window: s.Windows[0].ID})
		c.send(protocol.Ping{ID: 1})
		c.expect("pong", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Pong); return ok })
		time.Sleep(100 * time.Millisecond)
		if cols, _ := c.desk.size(p); cols != 120 {
			t.Errorf("pane %d wide while away, want the desktop's 120", cols)
		}

		c.send(protocol.Away{})
		c.send(protocol.Select{Window: s.Windows[0].ID})
		c.state("phone-sized again", func(s protocol.State) bool { p := paneOf(s, p); return p.Cols == 50 && p.Rows == 15 })
	})
}
