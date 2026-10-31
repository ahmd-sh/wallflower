package gateway

import (
	"time"

	"github.com/ahmd-sh/wallflower/internal/desktop"
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

const (
	highWater    = 1 << 20   // stop forwarding pane output above this backlog
	lowWater     = 256 << 10 // repaint stale panes while the backlog is below this
	repaintEvery = 100 * time.Millisecond
	maxHistory   = 10000 // lines a repaint holds at most, so it stays well inside a frame
)

// A pane the client has open: repainted, then its output streamed.
type pane struct {
	openAt     uint64 // output with Seq <= openAt is already in the repaint
	history    int
	cols, rows int
	stale      bool // output dropped under backpressure; repaint when drained
}

// open (re)paints a pane and starts streaming its output.
func (g *gateway) open(id string, history int) error {
	if history <= 0 {
		history = protocol.DefaultHistory
	}
	history = min(history, maxHistory)
	c, err := g.tmux.Capture(id, history)
	if err != nil {
		return err
	}
	g.panes[id] = &pane{openAt: c.Seq, history: history, cols: c.Cols, rows: c.Rows}
	g.send(protocol.Reset{Pane: id, Cols: c.Cols, Rows: c.Rows})
	g.out.push(protocol.AppendData(nil, id, repaint(c)))
	return nil
}

// scan passes on the notifications in a pane's output, open or not.
func (g *gateway) scan(n tmux.Notification) {
	s := g.scanners[n.Pane]
	if s == nil {
		s = &desktop.Scanner{}
		g.scanners[n.Pane] = s
	}
	for _, d := range s.Scan(n.Data) {
		g.send(protocol.Notification{Pane: n.Pane, Title: d.Title, Body: d.Body})
	}
}

// output forwards a pane's output, unless the client is too far behind to
// take it; the pane is repainted once it catches up.
func (g *gateway) output(n tmux.Notification) {
	p := g.panes[n.Pane]
	if p == nil || p.stale || n.Seq <= p.openAt {
		return
	}
	if g.out.buffered() > highWater {
		p.stale, g.stale = true, true
		return
	}
	g.out.push(protocol.AppendData(nil, n.Pane, n.Data))
}

// repaintStale repaints stale panes while the backlog stays low, so a
// client that fell behind isn't sent every repaint at once; those left wait
// for the next go.
func (g *gateway) repaintStale() {
	g.stale = false
	for id, p := range g.panes {
		if !p.stale {
			continue
		}
		if g.out.buffered() > lowWater {
			g.stale = true
			return
		}
		if err := g.open(id, p.history); err != nil {
			delete(g.panes, id)
		}
	}
}

// resized repaints open panes whose size changed, since the client's
// terminal would reflow differently from tmux's, and forgets those that
// closed.
func (g *gateway) resized(sizes map[string][2]int) {
	for id, p := range g.panes {
		size, ok := sizes[id]
		switch {
		case !ok:
			delete(g.panes, id)
		case size != [2]int{p.cols, p.rows}:
			if err := g.open(id, p.history); err != nil {
				delete(g.panes, id)
			}
		}
	}
}
