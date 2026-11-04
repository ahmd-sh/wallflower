package gateway

import (
	"context"
	"slices"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

func (c *client) sessions(what string, match func([]protocol.SessionInfo) bool) []protocol.SessionInfo {
	c.t.Helper()
	_, m := c.expect(what, func(_ protocol.Frame, m protocol.Message) bool {
		l, ok := m.(*protocol.SessionList)
		return ok && len(l.Muxes) == 1 && l.Muxes[0].Error == "" && match(l.Muxes[0].Sessions)
	})
	return m.(*protocol.SessionList).Muxes[0].Sessions
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
		c.expect("hello again", func(_ protocol.Frame, m protocol.Message) bool {
			h, ok := m.(*protocol.ServerHello)
			return ok && h.Mux.Name == b.name
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

// A session the phone asks for that's gone (ended while it was away) is
// the default one instead.
func TestHelloFallsBackToTheDefaultSession(t *testing.T) {
	each(t, func(t *testing.T, b backend) {
		c := b.dial(t, context.Background(), Options{}, protocol.ClientHello{V: protocol.Version, Cols: 60, Rows: 20, Session: "gone"})
		c.state("the default session", func(s protocol.State) bool { return s.Name == "t" })
	})
}
