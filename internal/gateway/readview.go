package gateway

import (
	"context"
	"errors"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/readview"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

// A reading is a pane's read view the client has on, its reader feeding the
// loop updates until it's cancelled.
type reading struct {
	gen    int
	cancel context.CancelFunc
}

// A readUpdate is what a reader sends the loop: gen tells a replaced
// reader's last words from its successor's.
type readUpdate struct {
	pane string
	gen  int
	u    readview.Update
	end  error // set on the last, if it stopped by itself
}

// readable maps the panes of a snapshot that some source reads to it.
func (g *gateway) readable(s *tmux.Session) map[string]readview.Source {
	var panes []readview.Pane
	for _, w := range s.Windows {
		for _, p := range w.Panes {
			panes = append(panes, readview.Pane{ID: p.ID, TTY: p.TTY, Command: g.program(p)})
		}
	}
	found := map[string]readview.Source{}
	for _, src := range g.sources {
		for id := range src.Find(panes) {
			if _, ok := found[id]; !ok {
				found[id] = src
			}
		}
	}
	return found
}

// read sends a pane's read view as it changes, until unread.
func (g *gateway) read(id string) {
	g.unread(id)
	var p readview.Pane
	for _, w := range g.windows() {
		for _, wp := range w.Panes {
			if wp.ID == id {
				p = readview.Pane{ID: id, TTY: wp.TTY, Command: g.program(wp)}
			}
		}
	}
	var src readview.Source
	if p.ID != "" {
		for _, s := range g.sources {
			if s.Find([]readview.Pane{p})[id] {
				src = s
				break
			}
		}
	}
	if src == nil {
		g.send(protocol.Entries{Pane: id, Reset: true, Error: "nothing to read in this pane"})
		return
	}
	g.gen++
	gen := g.gen
	ctx, cancel := context.WithCancel(g.ctx)
	g.reading[id] = reading{gen, cancel}
	update := func(u readUpdate) {
		select {
		case g.updates <- u:
		case <-ctx.Done():
		}
	}
	go func() {
		err := src.Read(ctx, p, func(u readview.Update) { update(readUpdate{pane: id, gen: gen, u: u}) })
		if ctx.Err() == nil {
			if err == nil {
				err = errors.New("nothing more to read")
			}
			update(readUpdate{pane: id, gen: gen, end: err})
		}
	}()
}

func (g *gateway) unread(id string) {
	if r, ok := g.reading[id]; ok {
		r.cancel()
		delete(g.reading, id)
	}
}

// readUpdate puts a reader's update on the wire, unless the client turned
// that read view off (or on again) since.
func (g *gateway) readUpdate(r readUpdate) {
	if cur, ok := g.reading[r.pane]; !ok || cur.gen != r.gen {
		return
	}
	if r.end != nil {
		g.unread(r.pane)
		g.send(protocol.Entries{Pane: r.pane, Reset: true, Error: r.end.Error()})
		return
	}
	g.send(entriesOf(r.pane, r.u))
}

// entriesOf is an update as the wire has it.
func entriesOf(pane string, u readview.Update) protocol.Entries {
	e := protocol.Entries{Pane: pane, Reset: u.Reset, More: u.More, State: string(u.Activity), Since: u.Since}
	if u.Measured {
		n := u.Context
		e.Context = &n
	}
	if len(u.Entries) > 0 {
		e.Entries = make([]protocol.Entry, len(u.Entries))
		for i, x := range u.Entries {
			e.Entries[i] = protocol.Entry(x)
		}
	}
	return e
}
