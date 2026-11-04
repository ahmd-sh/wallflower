package gateway

import (
	"errors"
	"sort"

	"github.com/ahmd-sh/wallflower/internal/desktop"
	"github.com/ahmd-sh/wallflower/internal/protocol"
)

// sessions sends the sessions in every multiplexer the gateway has.
func (g *gateway) sessions() {
	names := make([]string, 0, len(g.opts.Muxes))
	for name := range g.opts.Muxes {
		names = append(names, name)
	}
	sort.Strings(names)
	list := protocol.SessionList{Muxes: []protocol.MuxSessions{}}
	for _, name := range names {
		ms := protocol.MuxSessions{Name: name, Sessions: []protocol.SessionInfo{}}
		listed, err := g.opts.Muxes[name].Sessions()
		if err != nil {
			ms.Error = err.Error()
		}
		for _, l := range listed {
			ms.Sessions = append(ms.Sessions, protocol.SessionInfo{ID: l.ID, Name: l.Name, Windows: l.Windows})
		}
		list.Muxes = append(list.Muxes, ms)
	}
	g.send(list)
}

// switchTo attaches the client to another session, as if it had connected
// again: what it had open of the last is closed.
func (g *gateway) switchTo(name, session string) error {
	if session == "" {
		return errors.New("switch needs a session")
	}
	name, c, err := g.attach(name, session)
	if err != nil {
		return err
	}
	g.mux.Close()
	for id := range g.reading {
		g.unread(id)
	}
	g.snapshot, g.lastState, g.found, g.dirty, g.stale = nil, nil, nil, false, false
	g.panes, g.scanners = map[string]*pane{}, map[string]*desktop.Scanner{}
	g.attached(name, c)
	return nil
}

func (g *gateway) newSession(name, label string) error {
	name, m, err := g.opts.pick(name)
	if err != nil {
		return err
	}
	id, err := m.NewSession(label)
	if err != nil {
		return err
	}
	err = g.switchTo(name, id)
	g.sessions()
	return err
}

func (g *gateway) renameSession(name, session, label string) error {
	_, m, err := g.opts.pick(name)
	if err != nil {
		return err
	}
	err = m.RenameSession(session, label)
	g.sessions()
	return err
}

// killSession ends a session, unless it's the client's: it's to switch away first.
func (g *gateway) killSession(name, session string) error {
	name, m, err := g.opts.pick(name)
	if err != nil {
		return err
	}
	if name == g.muxed && g.snapshot != nil && session == g.snapshot.ID {
		return errors.New("that's the session you're in: switch to another first")
	}
	err = m.KillSession(session)
	g.sessions()
	return err
}
