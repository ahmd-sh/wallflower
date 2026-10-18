package gateway

import (
	"errors"
	"fmt"

	"github.com/ahmd-sh/wallflower/internal/tmux"
)

// resize changes the client's size. It only applies to windows this client
// was last to use; showing the current window again makes it so.
func (g *gateway) resize(cols, rows int) error {
	if !validSize(cols, rows) {
		return fmt.Errorf("bad size %dx%d", cols, rows)
	}
	g.cols, g.rows = cols, rows
	if err := g.tmux.Resize(cols, rows); err != nil {
		return err
	}
	for _, w := range g.windows() {
		if w.Active {
			return g.selectWindow(w.ID)
		}
	}
	return nil
}

// selectWindow makes a window current in this client's own session.
func (g *gateway) selectWindow(id string) error {
	if g.snapshot == nil {
		return errors.New("no session yet")
	}
	return g.tmux.SelectWindow(g.snapshot.ID, id)
}

// claimSize makes the window holding pane current for this client, at its
// size, if it isn't: another client (the desktop) may have taken the window
// over, or the client is showing a window its session doesn't have current
// (it chooses what to show itself). tmux sizes a window for the client that
// last resized, selected or typed into it, and typing here doesn't count, but
// selecting the window again does.
func (g *gateway) claimSize(pane string) {
	for _, w := range g.windows() {
		for _, p := range w.Panes {
			if p.ID == pane {
				if !w.Active || w.Width != g.cols || w.Height != g.rows {
					g.selectWindow(w.ID)
				}
				return
			}
		}
	}
}

// focus makes a pane its window's active one, zoomed if the window has
// others.
func (g *gateway) focus(pane string) error {
	g.claimSize(pane)
	return g.noteZoom(g.tmux.Focus(pane))
}

func (g *gateway) zoom(pane string) error {
	return g.noteZoom(g.tmux.Zoom(pane))
}

// noteZoom remembers how a window the client zooms was at first, so
// cleanup can put it back.
func (g *gateway) noteZoom(window string, was bool, err error) error {
	if err != nil {
		return err
	}
	if _, seen := g.zoomed[window]; !seen {
		g.zoomed[window] = was
	}
	return nil
}

func (g *gateway) windows() []tmux.Window {
	if g.snapshot == nil {
		return nil
	}
	return g.snapshot.Windows
}
