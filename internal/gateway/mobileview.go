package gateway

import (
	"container/list"
	"context"
	"encoding/base64"
	"errors"
	"maps"
	"slices"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/program"
	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const (
	activityEvery = 500 * time.Millisecond // how often programs are asked what they're doing
	picturesKept  = 32 << 20               // bytes of a mobile view's pictures kept for the client to ask for
)

// A match is the program a pane runs, as one of the programs the gateway
// knows says, and, if it has a mobile view of the pane, what it's doing.
type match struct {
	program *program.Program
	view    bool
	found   mobileview.Found
}

// A reading is a pane's mobile view the client has on, its reader feeding the
// loop updates until it's cancelled, and the pictures it read, which the
// client asks for when it shows them.
type reading struct {
	gen      int
	cancel   context.CancelFunc
	pictures *album
}

// A readUpdate is what a reader sends the loop: gen tells a replaced
// reader's last words from its successor's.
type readUpdate struct {
	pane string
	gen  int
	u    mobileview.Update
	end  error // set on the last, if it stopped by itself
}

// matches maps the panes of a snapshot to the programs they run: those a
// program has a mobile view of first, then by their command.
func (g *gateway) matches(s *mux.Session) map[string]match {
	var panes []mobileview.Pane
	for _, w := range s.Windows {
		for _, p := range w.Panes {
			panes = append(panes, mobileview.Pane{ID: p.ID, TTY: p.TTY, Command: g.program(p), Title: p.Title, Path: p.Path})
		}
	}
	found := map[string]match{}
	for i := range g.programs {
		if v := g.programs[i].View; v != nil {
			for id, f := range v.Find(panes) {
				if _, ok := found[id]; !ok {
					found[id] = match{&g.programs[i], true, f}
				}
			}
		}
	}
	for _, p := range panes {
		if _, ok := found[p.ID]; ok {
			continue
		}
		for i := range g.programs {
			if g.programs[i].Runs(p.Command) {
				found[p.ID] = match{program: &g.programs[i]}
				break
			}
		}
	}
	return found
}

// watchActivity has state resent when what a program is doing changed,
// which no event from the multiplexer says.
func (g *gateway) watchActivity() {
	if g.snapshot == nil || !slices.ContainsFunc(g.programs, func(p program.Program) bool { return p.View != nil }) {
		return
	}
	if !maps.Equal(g.matches(g.snapshot), g.found) {
		g.dirty = true
	}
}

// read sends a pane's mobile view as it changes, until unread.
func (g *gateway) read(id string) {
	g.unread(id)
	var p mobileview.Pane
	for _, w := range g.windows() {
		for _, wp := range w.Panes {
			if wp.ID == id {
				p = mobileview.Pane{ID: id, TTY: wp.TTY, Command: g.program(wp), Title: wp.Title, Path: wp.Path}
			}
		}
	}
	// What the client was last told has a view: a Source asked of one pane
	// alone (a plugin's) could forget its others.
	var src mobileview.Source
	if m, ok := g.found[id]; ok && m.view && p.ID != "" {
		src = m.program.View
	}
	if src == nil {
		g.send(protocol.Entries{Pane: id, Reset: true, Error: "nothing to read in this pane"})
		return
	}
	g.gen++
	gen := g.gen
	ctx, cancel := context.WithCancel(g.ctx)
	g.reading[id] = reading{gen, cancel, newAlbum(picturesKept)}
	update := func(u readUpdate) {
		select {
		case g.updates <- u:
		case <-ctx.Done():
		}
	}
	go func() {
		err := src.Read(ctx, p, func(u mobileview.Update) { update(readUpdate{pane: id, gen: gen, u: u}) })
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
// that mobile view off (or on again) since.
func (g *gateway) readUpdate(r readUpdate) {
	cur, ok := g.reading[r.pane]
	if !ok || cur.gen != r.gen {
		return
	}
	if r.end != nil {
		g.unread(r.pane)
		g.send(protocol.Entries{Pane: r.pane, Reset: true, Error: r.end.Error()})
		return
	}
	g.send(entriesOf(r.pane, r.u, cur.pictures))
}

// picture sends the client a picture entry's bytes, none if they're gone.
func (g *gateway) picture(pane, id string) {
	var data []byte
	if r, ok := g.reading[pane]; ok {
		data = r.pictures.get(id)
	}
	g.out.push(protocol.AppendPicture(nil, id, data))
}

// entriesOf is an update as the wire has it; pictures' bytes stay in
// [pictures], for the client to ask for.
func entriesOf(pane string, u mobileview.Update, pictures *album) protocol.Entries {
	e := protocol.Entries{Pane: pane, Reset: u.Reset, More: u.More, Title: u.Title}
	if u.Measured {
		n := u.Context
		e.Context = &n
	}
	if u.Requeued {
		q := append([]string{}, u.Queued...)
		e.Queued = &q
	}
	for _, x := range u.Entries {
		w := protocol.Entry{
			ID: x.ID, From: x.From, Kind: x.Kind, Text: x.Text, Name: x.Name, Status: x.Status,
			Output: x.Output, OutputCut: x.OutputCut, Diff: x.Diff, DiffCut: x.DiffCut,
		}
		if x.Kind == mobileview.Picture {
			data, err := base64.StdEncoding.DecodeString(x.Data)
			if err != nil {
				w.Kind, w.Text = mobileview.Note, "[an image that can't be shown]"
			} else {
				pictures.put(x.ID, data)
				w.Mime, w.Size = x.Mime, len(data)
			}
		}
		e.Entries = append(e.Entries, w)
	}
	return e
}

// An album keeps pictures by entry, up to a number of bytes, the least
// recently put or got going first.
type album struct {
	left  int
	order *list.List // of *photo, the most recent first
	byID  map[string]*list.Element
}

type photo struct {
	id   string
	data []byte
}

func newAlbum(bytes int) *album {
	return &album{left: bytes, order: list.New(), byID: map[string]*list.Element{}}
}

func (a *album) put(id string, data []byte) {
	if el, ok := a.byID[id]; ok {
		a.left += len(el.Value.(*photo).data)
		a.order.Remove(el)
	}
	a.byID[id] = a.order.PushFront(&photo{id, data})
	a.left -= len(data)
	for a.left < 0 && a.order.Len() > 1 {
		old := a.order.Remove(a.order.Back()).(*photo)
		delete(a.byID, old.id)
		a.left += len(old.data)
	}
}

func (a *album) get(id string) []byte {
	el, ok := a.byID[id]
	if !ok {
		return nil
	}
	a.order.MoveToFront(el)
	return el.Value.(*photo).data
}
