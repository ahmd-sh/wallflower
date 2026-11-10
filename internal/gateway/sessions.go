package gateway

import (
	"bytes"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/protocol"
)

// listEvery is how often the sessions are listed again while the client
// is attached to nothing, and there to see them: what's listed is sent
// when it changes. A client not heard from in listHeard isn't there (its
// heartbeat comes more often), or is gone without a word; listWait is how
// long a multiplexer has to list them.
const (
	listEvery = 2 * time.Second
	listHeard = 45 * time.Second
	listWait  = 2 * time.Second
)

// A listing is what a multiplexer said of its sessions.
type listing struct {
	l   mux.Listing
	err error
}

// listing is what the multiplexer named lists, within listWait: one that
// takes longer isn't answering, and isn't asked again until it has.
func (g *gateway) listing(name string, m mux.Mux) (mux.Listing, error) {
	if was, ok := g.asking[name]; ok {
		select {
		case <-was:
			delete(g.asking, name)
		default:
			return mux.Listing{}, fmt.Errorf("%s isn't answering", name)
		}
	}
	answer := make(chan listing, 1)
	go func() {
		l, err := m.Sessions()
		answer <- listing{l, err}
	}()
	select {
	case a := <-answer:
		return a.l, a.err
	case <-time.After(listWait):
		g.asking[name] = answer
		return mux.Listing{}, fmt.Errorf("%s isn't answering", name)
	}
}

// sessions sends the sessions in every multiplexer the gateway has, unless
// they're as last sent and it's not asked to.
func (g *gateway) sessions(asked bool) {
	names := make([]string, 0, len(g.opts.Muxes))
	for name := range g.opts.Muxes {
		names = append(names, name)
	}
	sort.Strings(names)
	list := protocol.SessionList{Muxes: []protocol.MuxSessions{}}
	for _, name := range names {
		l, err := g.listing(name, g.opts.Muxes[name])
		ms := protocol.MuxSessions{Name: name, Running: l.Running, Sessions: []protocol.SessionInfo{}, Can: l.Can}
		if err != nil {
			ms.Error = err.Error()
		}
		for _, s := range l.Sessions {
			ms.Sessions = append(ms.Sessions, protocol.SessionInfo{ID: s.ID, Name: s.Name, Windows: s.Windows})
		}
		list.Muxes = append(list.Muxes, ms)
	}
	b, err := protocol.AppendJSON(nil, list)
	if err != nil || !asked && bytes.Equal(b, g.listed) {
		return
	}
	g.listed = b
	g.out.push(b)
}

// switchTo attaches the client to a session, in place of the one it's in.
// If it can't be, the client stays where it was: attached to nothing, it's
// told so again, with why.
func (g *gateway) switchTo(name, session string) error {
	asked := name
	name, c, err := g.attach(name, session)
	if err != nil {
		g.stay(errors.New(g.unhad(asked, err)))
		return err
	}
	g.attached(name, c)
	return nil
}

// stay tells a client attached to nothing that it still is, and why: what
// it asked for failed.
func (g *gateway) stay(err error) {
	if g.mux == nil {
		g.send(protocol.Detached{Reason: err.Error()})
		g.sessions(true)
	}
}

func (g *gateway) newSession(name, label string) error {
	name, m, err := g.opts.pick(name)
	if err != nil {
		g.stay(err)
		return err
	}
	id, err := m.NewSession(label)
	if err != nil {
		g.stay(err)
		return err
	}
	err = g.switchTo(name, id)
	g.sessions(true)
	return err
}

func (g *gateway) renameSession(name, session, label string) error {
	_, m, err := g.opts.pick(name)
	if err != nil {
		return err
	}
	err = m.RenameSession(session, label)
	g.sessions(true)
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
	g.sessions(true)
	return err
}
