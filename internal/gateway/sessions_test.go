package gateway

import (
	"context"
	"errors"
	"io"
	"slices"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
	"github.com/ahmd-sh/wallflower/internal/tmuxtest"
)

func (c *client) sessions(what string, match func([]protocol.SessionInfo) bool) []protocol.SessionInfo {
	c.t.Helper()
	_, m := c.expect(what, func(_ protocol.Frame, m protocol.Message) bool {
		l, ok := m.(*protocol.SessionList)
		return ok && len(l.Muxes) == 1 && l.Muxes[0].Error == "" && match(l.Muxes[0].Sessions)
	})
	return m.(*protocol.SessionList).Muxes[0].Sessions
}

// listing waits for the multiplexer's listing to match.
func (c *client) listing(what string, match func(protocol.MuxSessions) bool) protocol.MuxSessions {
	c.t.Helper()
	_, m := c.expect(what, func(_ protocol.Frame, m protocol.Message) bool {
		l, ok := m.(*protocol.SessionList)
		return ok && len(l.Muxes) == 1 && match(l.Muxes[0])
	})
	return m.(*protocol.SessionList).Muxes[0]
}

func named(list []protocol.SessionInfo, name string) protocol.SessionInfo {
	for _, s := range list {
		if s.Name == name {
			return s
		}
	}
	return protocol.SessionInfo{}
}

func names(list []protocol.SessionInfo) []string {
	var n []string
	for _, s := range list {
		n = append(n, s.Name)
	}
	slices.Sort(n)
	return n
}

// The phone lists the multiplexer's sessions, starts one and is switched
// to it, switches back, renames and ends one; not the one it's in.
func TestSessions(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		first := c.state("state", anyState)

		c.send(protocol.Sessions{})
		list := c.sessions("the session", func(l []protocol.SessionInfo) bool { return len(l) == 1 })
		if list[0].Name != "t" || list[0].ID != first.Session || list[0].Windows != 1 {
			t.Errorf("sessions = %+v", list)
		}

		c.send(protocol.NewSession{Mux: b.name, Name: "other"})
		c.expect("attached again", func(_ protocol.Frame, m protocol.Message) bool {
			a, ok := m.(*protocol.Attached)
			return ok && a.Mux.Name == b.name
		})
		other := c.state("in the new session", func(s protocol.State) bool { return s.Name == "other" && len(s.Windows) == 1 })
		c.sessions("both", func(l []protocol.SessionInfo) bool { return slices.Equal(names(l), []string{"other", "t"}) })
		if ls := c.desk.sessions(); !slices.Contains(ls, "other") {
			t.Errorf("the desktop's sessions %q, without the new one", ls)
		}

		// Its panes are its own: the new session's pane opens and streams.
		// (Rex's runs the login shell, whatever its prompt.)
		p := activePane(other).ID
		c.send(protocol.Focus{Pane: p})
		c.send(protocol.Open{Pane: p})
		c.expect("its reset", func(_ protocol.Frame, m protocol.Message) bool {
			r, ok := m.(*protocol.Reset)
			return ok && r.Pane == p
		})
		c.sendText(p, "echo in-$((6*7))\r")
		c.output(p, "in-42")

		c.send(protocol.Switch{Mux: b.name, Session: first.Session})
		c.state("back in t", func(s protocol.State) bool { return s.Name == "t" && s.Session == first.Session })

		c.send(protocol.RenameSession{Mux: b.name, Session: other.Session, Name: "renamed ✓"})
		c.sessions("renamed", func(l []protocol.SessionInfo) bool { return named(l, "renamed ✓").ID == other.Session })

		c.send(protocol.KillSession{Mux: b.name, Session: first.Session})
		c.expect("refused", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Error); return ok })
		c.send(protocol.KillSession{Mux: b.name, Session: other.Session})
		c.sessions("one again", func(l []protocol.SessionInfo) bool { return slices.Equal(names(l), []string{"t"}) })
		c.until("the desktop's one session", func() bool { return slices.Equal(c.desk.sessions(), []string{"t"}) })
	})
}

// A session the phone asks for that's gone (ended while it was away)
// leaves it attached to nothing, with the sessions there are.
func TestHelloWhereItWasIsGone(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.dial(t, context.Background(), Options{}, protocol.ClientHello{V: protocol.Version, Cols: 60, Rows: 20, Session: "gone"})
		_, m := c.expect("detached", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Detached); return ok })
		if r := m.(*protocol.Detached).Reason; r != "that "+b.name+" session is gone" {
			t.Errorf("detached saying %q", r)
		}
		c.sessions("the session there is", func(l []protocol.SessionInfo) bool { return slices.Equal(names(l), []string{"t"}) })
		c.send(protocol.Open{Pane: "%0"})
		c.expect("refused", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Error); return ok })
	})
}

// Detached as the user chose, the phone lets go of the session as it does
// disconnecting (the desktop has its layout back), sees the sessions as
// they come and go without asking, can end the one it was in, and
// attaches again.
func TestDetach(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		s := c.state("state", anyState)
		c.desk.split(activePane(s).ID)
		s = c.state("split", func(s protocol.State) bool { return len(s.Windows[0].Panes) == 2 })
		c.send(protocol.Focus{Pane: s.Windows[0].Panes[0].ID})
		c.until("held", func() bool { return c.desk.held() != "" })

		c.send(protocol.Detach{})
		_, m := c.expect("detached", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Detached); return ok })
		if r := m.(*protocol.Detached).Reason; r != "" {
			t.Errorf("detached as asked, saying %q", r)
		}
		c.sessions("the session", func(l []protocol.SessionInfo) bool { return slices.Equal(names(l), []string{"t"}) })
		c.until("let go", func() bool { return c.desk.held() == "" })
		c.send(protocol.Keys{Pane: s.Windows[0].Panes[0].ID, Keys: []string{"Enter"}})
		c.expect("refused", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Error); return ok })

		if b.name == "tmux" {
			c.tmux("new-session", "-d", "-s", "born")
			c.sessions("one started at the computer, unasked", func(l []protocol.SessionInfo) bool { return slices.Equal(names(l), []string{"born", "t"}) })
			c.send(protocol.KillSession{Mux: b.name, Session: s.Session})
			c.sessions("the one it was in, ended", func(l []protocol.SessionInfo) bool { return slices.Equal(names(l), []string{"born"}) })
			c.send(protocol.Switch{Mux: b.name, Session: "born"})
			c.expect("attached", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Attached); return ok })
			c.state("in born", func(s protocol.State) bool { return s.Name == "born" })
			return
		}
		c.send(protocol.Switch{Mux: b.name, Session: s.Session})
		c.expect("attached", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Attached); return ok })
		c.state("back in t", func(s protocol.State) bool { return s.Name == "t" })
	})
}

// A switch that fails leaves a phone attached to nothing where it was, and
// tells it so.
func TestASwitchThatFailsStaysDetached(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.connect(t, 60, 20)
		c.state("state", anyState)
		c.send(protocol.Detach{})
		c.expect("detached", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Detached); return ok })
		c.send(protocol.Switch{Mux: b.name, Session: "gone"})
		c.expect("detached again", func(_ protocol.Frame, m protocol.Message) bool {
			d, ok := m.(*protocol.Detached)
			return ok && d.Reason != ""
		})
		c.expect("the error", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.Error); return ok })
	})
}

// stuck is a multiplexer that never says what sessions it has.
type stuck struct{ never chan struct{} }

func (s stuck) Attach(mux.Attach) (mux.Client, error) { return nil, errors.New("stuck") }
func (s stuck) Sessions() (mux.Listing, error) {
	<-s.never
	return mux.Listing{}, nil
}
func (s stuck) NewSession(string) (string, error)  { return "", errors.New("stuck") }
func (s stuck) RenameSession(string, string) error { return errors.New("stuck") }
func (s stuck) KillSession(string) error           { return errors.New("stuck") }

// A multiplexer that doesn't answer holds up neither the others' sessions
// nor the connection: it's said not to, and isn't asked again until it has.
func TestAMuxThatDoesntAnswer(t *testing.T) {
	socket, _ := tmuxtest.Server(t, 80, 24)
	never := make(chan struct{})
	defer close(never)
	opts := Options{Muxes: map[string]mux.Mux{"tmux": tmux.Server{Socket: socket}, "slow": stuck{never}}, Mux: "tmux"}
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	done := make(chan error, 1)
	go func() { done <- Serve(context.Background(), inR, outW, opts); outW.Close() }()
	b, _ := protocol.AppendJSON(nil, protocol.ClientHello{V: protocol.Version, Cols: 80, Rows: 24})
	inW.Write(b)
	r := protocol.NewReader(outR)
	next := func(what string, match func(protocol.Message) bool) protocol.Message {
		t.Helper()
		for deadline := time.Now().Add(10 * time.Second); time.Now().Before(deadline); {
			f, err := r.Next()
			if err != nil {
				t.Fatalf("waiting for %s: %v", what, err)
			}
			if m, err := protocol.DecodeServer(f.Data); err == nil && match(m) {
				return m
			}
		}
		t.Fatalf("no %s", what)
		return nil
	}
	l := next("sessions", func(m protocol.Message) bool { _, ok := m.(*protocol.SessionList); return ok }).(*protocol.SessionList)
	if len(l.Muxes) != 2 || l.Muxes[0].Error != "slow isn't answering" || len(l.Muxes[1].Sessions) != 1 {
		t.Errorf("sessions %+v", l)
	}
	start := time.Now()
	b, _ = protocol.AppendJSON(nil, protocol.Ping{ID: 9})
	inW.Write(b)
	next("pong", func(m protocol.Message) bool { p, ok := m.(*protocol.Pong); return ok && p.ID == 9 })
	if d := time.Since(start); d > time.Second {
		t.Errorf("pong took %v", d)
	}
	inW.Close()
	<-done
}
