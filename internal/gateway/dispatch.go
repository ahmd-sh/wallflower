package gateway

import (
	"errors"
	"fmt"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const maxSize = 1000 // columns or rows, at most

// errNowhere is a client attached to nothing asking for what only one
// attached to a session can have.
var errNowhere = errors.New("not attached to a session: switch to one first")

// handle does what a frame from the client asks.
func (g *gateway) handle(f protocol.Frame) error {
	switch f.Type {
	case protocol.FrameData:
		if g.mux == nil {
			return errNowhere
		}
		return g.typeBytes(f.ID, f.Data)
	case protocol.FrameUpload:
		path, err := g.uploads.write(f.ID, f.Data)
		if err != nil || path != "" {
			g.sendUploaded(f.ID, path, err)
		}
		return nil
	}

	m, err := protocol.DecodeClient(f.Data)
	if err != nil {
		return err
	}
	if g.mux == nil && inSession(m) {
		return errNowhere
	}
	switch m := m.(type) {
	case *protocol.Resize:
		if !validSize(m.Cols, m.Rows) {
			return fmt.Errorf("bad size %dx%d", m.Cols, m.Rows)
		}
		g.cols, g.rows = m.Cols, m.Rows
		if g.mux == nil {
			return nil
		}
		return g.mux.Resize(m.Cols, m.Rows)
	case *protocol.Open:
		return g.open(m.Pane, m.History)
	case *protocol.Close:
		delete(g.panes, m.Pane)
		return nil
	case *protocol.Keys:
		return g.keys(m.Pane, m.Keys, m.Bindings)
	case *protocol.Submit:
		return g.submit(m)
	case *protocol.Upload:
		path, err := g.uploads.begin(m)
		if err != nil || path != "" {
			g.sendUploaded(m.ID, path, err)
		}
		return nil
	case *protocol.Select:
		switch {
		case m.Pane != "":
			return g.mux.SelectPane(m.Pane)
		case m.Window != "":
			return g.mux.SelectWindow(m.Window)
		}
		return errors.New("select needs a window or pane")
	case *protocol.Focus:
		return g.mux.Focus(m.Pane)
	case *protocol.Split:
		pane, err := g.mux.Split(m.Pane)
		if err != nil {
			return err
		}
		return g.mux.Focus(pane)
	case *protocol.New:
		return g.mux.NewWindow()
	case *protocol.Rename:
		return g.mux.RenameWindow(m.Window, m.Name)
	case *protocol.Kill:
		switch {
		case m.Pane != "":
			return g.mux.KillPane(m.Pane)
		case m.Window != "":
			return g.mux.KillWindow(m.Window)
		}
		return errors.New("kill needs a window or pane")
	case *protocol.Read:
		if m.On {
			g.read(m.Pane)
		} else {
			g.unread(m.Pane)
		}
		return nil
	case *protocol.Picture:
		g.picture(m.Pane, m.ID)
		return nil
	case *protocol.Ping:
		g.send(protocol.Pong{ID: m.ID})
		return nil
	case *protocol.Away:
		g.away = m.On
		if g.mux == nil {
			g.sessions(false) // back, it sees them as they are
			return nil
		}
		return g.mux.Away(m.On)
	case *protocol.Sessions:
		g.sessions(true)
		return nil
	case *protocol.Detach:
		g.detach("")
		return nil
	case *protocol.Switch:
		return g.switchTo(m.Mux, m.Session)
	case *protocol.NewSession:
		return g.newSession(m.Mux, m.Name)
	case *protocol.RenameSession:
		return g.renameSession(m.Mux, m.Session, m.Name)
	case *protocol.KillSession:
		return g.killSession(m.Mux, m.Session)
	case *protocol.ClientHello:
		return errors.New("already said hello")
	}
	return fmt.Errorf("unhandled message %q", m.Type())
}

// inSession is whether m is about the session the client's attached to.
func inSession(m protocol.Message) bool {
	switch m.(type) {
	case *protocol.Open, *protocol.Close, *protocol.Keys, *protocol.Submit, *protocol.Select, *protocol.Focus,
		*protocol.Split, *protocol.New, *protocol.Rename, *protocol.Kill, *protocol.Read, *protocol.Picture:
		return true
	}
	return false
}

func validSize(cols, rows int) bool {
	return cols > 0 && rows > 0 && cols <= maxSize && rows <= maxSize
}
