package gateway

import (
	"container/list"
	"context"
	"encoding/base64"
	"errors"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/readview"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

const (
	activityEvery = 500 * time.Millisecond // how often programs are asked what they're doing
	picturesKept  = 32 << 20               // bytes of a read view's pictures kept for the client to ask for
)

// A reader is the source that reads a pane, and what it says the pane's
// program is doing.
type reader struct {
	src   readview.Source
	found readview.Found
}

// A reading is a pane's read view the client has on, its reader feeding the
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
	u    readview.Update
	end  error // set on the last, if it stopped by itself
}

// readers maps the panes of a snapshot that some source reads to it.
func (g *gateway) readers(s *tmux.Session) map[string]reader {
	var panes []readview.Pane
	for _, w := range s.Windows {
		for _, p := range w.Panes {
			panes = append(panes, readview.Pane{ID: p.ID, TTY: p.TTY, Command: g.program(p)})
		}
	}
	found := map[string]reader{}
	for _, src := range g.sources {
		for id, f := range src.Find(panes) {
			if _, ok := found[id]; !ok {
				found[id] = reader{src, f}
			}
		}
	}
	return found
}

// watchActivity has state resent when what a program is doing changed,
// which no tmux notification says.
func (g *gateway) watchActivity() {
	if g.snapshot == nil || len(g.sources) == 0 {
		return
	}
	now := g.readers(g.snapshot)
	if len(now) != len(g.found) {
		g.dirty = true
		return
	}
	for id, r := range now {
		if g.found[id] != r.found {
			g.dirty = true
			return
		}
	}
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
			if _, ok := s.Find([]readview.Pane{p})[id]; ok {
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
	g.reading[id] = reading{gen, cancel, newAlbum(picturesKept)}
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
func entriesOf(pane string, u readview.Update, pictures *album) protocol.Entries {
	e := protocol.Entries{Pane: pane, Reset: u.Reset, More: u.More}
	if u.Measured {
		n := u.Context
		e.Context = &n
	}
	for _, x := range u.Entries {
		w := protocol.Entry{
			ID: x.ID, From: x.From, Kind: x.Kind, Text: x.Text, Name: x.Name, Status: x.Status,
			Output: x.Output, OutputCut: x.OutputCut, Diff: x.Diff, DiffCut: x.DiffCut,
		}
		if x.Kind == readview.Picture {
			data, err := base64.StdEncoding.DecodeString(x.Data)
			if err != nil {
				w.Kind, w.Text = readview.Note, "[an image that can't be shown]"
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
