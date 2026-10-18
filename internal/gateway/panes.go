package gateway

import (
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

const (
	highWater  = 1 << 20   // stop forwarding pane output above this backlog
	lowWater   = 256 << 10 // repaint stale panes once the backlog is below this
	maxHistory = 10000     // lines a repaint holds at most, so it stays well inside a frame
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

// output forwards a pane's output, unless the client is too far behind to
// take it; the pane is repainted once it catches up.
func (g *gateway) output(n tmux.Notification) {
	p := g.panes[n.Pane]
	if p == nil || p.stale || n.Seq <= p.openAt {
		return
	}
	if g.out.buffered() > highWater {
		p.stale = true
		return
	}
	g.out.push(protocol.AppendData(nil, n.Pane, n.Data))
}

func (g *gateway) repaintStale() {
	if g.out.buffered() > lowWater {
		return
	}
	for id, p := range g.panes {
		if p.stale {
			if err := g.open(id, p.history); err != nil {
				delete(g.panes, id)
			}
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
