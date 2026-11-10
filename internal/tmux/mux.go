package tmux

import (
	"context"
	"errors"
	"fmt"
	"os"

	"github.com/ahmd-sh/wallflower/internal/mux"
)

// Server is a tmux server as wallflower's multiplexer (see package mux):
// Socket names it (see Options).
type Server struct {
	Socket string
	Config string // the config a server started for a new session reads (tmux -f); empty is tmux's own
}

// Attach attaches a control client to the session itself, as the desktop's
// clients are, rather than to a session grouped with it. It isn't tied to a
// context: detaching after a signal still needs tmux.
func (s Server) Attach(a mux.Attach) (mux.Client, error) {
	session, err := ResolveSession(s.Socket, a.Session)
	if err != nil {
		return nil, err
	}
	flags := []string{"new-layouts", "no-detach-on-destroy"}
	if a.Away {
		flags = append(flags, "ignore-size")
	}
	c, err := Start(context.Background(), Options{Socket: s.Socket, Session: session, Flags: flags})
	if err != nil {
		return nil, err
	}
	version, name, id, err := c.Configure(a.Cols, a.Rows)
	if err != nil {
		go func() {
			for range c.Events() {
			}
		}()
		// tmux's own word (no server, no such session) over the closed client's.
		if exitErr := c.Close(); errors.Is(err, ErrClosed) && exitErr != nil {
			err = exitErr
		}
		return nil, err
	}
	t := &attached{
		c: c, name: name, session: id, cols: a.Cols, rows: a.Rows, zoomed: map[string]bool{},
		about:  mux.About{Name: "tmux", Version: version, Can: []string{mux.Bindings}},
		events: make(chan mux.Event), done: make(chan struct{}),
	}
	go t.forward()
	return t, nil
}

// attached is a control client as a mux.Client. tmux sizes a window for
// the client that last resized or selected it, or typed into it, but not
// through control mode, so the client selects a window again to type into
// it when it isn't the client's.
type attached struct {
	c          *Client
	about      mux.About
	name       string // the control client's, for keys sent through bindings
	session    string // the id of the session it was attached to
	cols, rows int
	last       *Session        // the latest snapshot
	zoomed     map[string]bool // windows the phone zoomed -> false, how they go back
	events     chan mux.Event
	done       chan struct{} // closed by Close
	exit       string        // why tmux detached the client, if it said
	ended      bool          // the session went, and tmux moved the client to another
}

func (t *attached) About() mux.About         { return t.about }
func (t *attached) Events() <-chan mux.Event { return t.events }

func (t *attached) Err() error {
	err := t.c.Err()
	select {
	case <-t.done:
		return nil
	default:
	}
	switch {
	case t.ended:
		return mux.ErrSessionEnded
	case t.exit != "":
		return errors.New(t.exit)
	case err != nil:
		return err
	}
	return errors.New("detached")
}

// Close puts the zoom of windows the phone zoomed back, then detaches.
func (t *attached) Close() error {
	t.c.SetZoom(t.zoomed)
	close(t.done)
	return t.c.Close()
}

// forward passes tmux's notifications on as events, those the session's
// snapshot shows as Changed, until tmux detaches the client. Once closed,
// it drains them, so tmux's queue never waits. When the session goes,
// tmux moves the client to another (no-detach-on-destroy, or the user's
// detach-on-destroy off), but the session the client was in has ended:
// so it detaches.
func (t *attached) forward() {
	defer close(t.events)
	for n := range t.c.Events() {
		var e mux.Event
		switch n.Type {
		case "output":
			e = mux.Event{Pane: n.Pane, Data: n.Data, Seq: n.Seq}
		case "session-changed":
			if n.Session != t.session && !t.ended && !t.c.Has(t.session) {
				t.ended = true
				go t.c.Close()
				continue
			}
			t.session = n.Session // moved at the Mac, with its session still there: it follows
			e = mux.Event{Changed: true}
		case "layout-change", "window-add", "window-close", "window-renamed", "window-pane-changed",
			"session-window-changed", "session-renamed", "subscription-changed":
			e = mux.Event{Changed: true}
		case "exit":
			t.exit = n.Value
			continue
		default:
			continue
		}
		select {
		case t.events <- e:
		case <-t.done:
		}
	}
}

func (t *attached) Snapshot() (*mux.Session, error) {
	s, err := t.c.Snapshot()
	if err != nil {
		return nil, err
	}
	t.last = s
	hs := &mux.Session{ID: s.ID, Name: s.Name}
	for _, w := range s.Windows {
		hw := mux.Window{ID: w.ID, Index: w.Index, Name: w.Name, Active: w.Active}
		for _, p := range w.Panes {
			hw.Panes = append(hw.Panes, mux.Pane{
				ID: p.ID, Index: p.Index, Active: p.Active, Cols: p.Width, Rows: p.Height, Alt: p.AltScreen,
				Title: p.Title, Path: p.Path, Command: p.Command, TTY: p.TTY,
			})
		}
		hs.Windows = append(hs.Windows, hw)
	}
	return hs, nil
}

func (t *attached) Screen(pane string, history int) (*mux.Screen, error) {
	c, err := t.c.Capture(pane, history)
	if err != nil {
		return nil, err
	}
	return &mux.Screen{Cols: c.Cols, Rows: c.Rows, Repaint: repaint(c), Seq: c.Seq}, nil
}

// Resize applies to windows the client is the latest to size: showing the
// current one again makes it so.
func (t *attached) Resize(cols, rows int) error {
	t.cols, t.rows = cols, rows
	if err := t.c.Resize(cols, rows); err != nil {
		return err
	}
	for _, w := range t.windows() {
		if w.Active {
			return t.SelectWindow(w.ID)
		}
	}
	return nil
}

// Away leaves the windows to the desktop, at its size and without the
// phone's zoom; showing one again takes it back.
func (t *attached) Away(on bool) error {
	if on && len(t.zoomed) > 0 {
		t.c.SetZoom(t.zoomed)
		t.zoomed = map[string]bool{}
	}
	return t.c.IgnoreSize(on)
}

func (t *attached) Type(pane string, data []byte) error {
	t.claim(pane)
	return t.c.SendBytes(pane, data)
}

func (t *attached) Keys(pane string, keys ...string) error {
	t.claim(pane)
	return t.c.SendKeys(pane, keys...)
}

// Bound sends keys through this client's key tables, which press them in
// its current pane.
func (t *attached) Bound(pane string, keys ...string) error {
	t.claim(pane)
	return t.c.SendBound(t.name, keys...)
}

// Paste goes through a paste buffer of this process's own.
func (t *attached) Paste(pane, text string) error {
	t.claim(pane)
	return t.c.Paste(pane, fmt.Sprintf("wallflower-%d", os.Getpid()), text)
}

// SelectWindow shows it as the phone shows a window, its active pane the
// whole of it, in the session: a bare window id would pick whichever
// session holding it was active last.
func (t *attached) SelectWindow(window string) error {
	if t.last == nil {
		return errors.New("no session yet")
	}
	zoomed, err := t.c.Show(t.last.ID, window)
	if err != nil {
		return err
	}
	t.hold(window, zoomed)
	return nil
}

func (t *attached) SelectPane(pane string) error { return t.c.SelectPane(pane) }

func (t *attached) Focus(pane string) error {
	t.claim(pane)
	window, zoomed, err := t.c.Focus(pane)
	if err != nil {
		return err
	}
	t.hold(window, zoomed)
	return nil
}

// hold remembers a window if the phone zoomed it, to unzoom when it goes; one
// the desktop zoomed stays the desktop's.
func (t *attached) hold(window string, zoomed bool) {
	if zoomed {
		t.zoomed[window] = false
	}
}

func (t *attached) Split(pane string) (string, error)      { return t.c.Split(pane) }
func (t *attached) NewWindow() error                       { return t.c.NewWindow() }
func (t *attached) RenameWindow(window, name string) error { return t.c.RenameWindow(window, name) }
func (t *attached) KillPane(pane string) error             { return t.c.KillPane(pane) }
func (t *attached) KillWindow(window string) error         { return t.c.KillWindow(window) }

// claim makes the window holding pane current for this client, at its
// size and its pane zoomed, if it isn't: another client (the desktop) may have taken the window
// over, or the client shows a window its session doesn't have current (it
// chooses what to show itself).
func (t *attached) claim(pane string) {
	for _, w := range t.windows() {
		for _, p := range w.Panes {
			if p.ID == pane {
				if !w.Active || w.Width != t.cols || w.Height != t.rows || len(w.Panes) > 1 && !w.Zoomed {
					t.SelectWindow(w.ID)
				}
				return
			}
		}
	}
}

func (t *attached) windows() []Window {
	if t.last == nil {
		return nil
	}
	return t.last.Windows
}
