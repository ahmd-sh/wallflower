package gateway

import (
	"errors"
	"fmt"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const maxSize = 1000 // columns or rows, at most

// handle does what a frame from the client asks.
func (g *gateway) handle(f protocol.Frame) error {
	switch f.Type {
	case protocol.FrameData:
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
	switch m := m.(type) {
	case *protocol.Resize:
		return g.resize(m.Cols, m.Rows)
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
			return g.tmux.SelectPane(m.Pane)
		case m.Window != "":
			return g.selectWindow(m.Window)
		}
		return errors.New("select needs a window or pane")
	case *protocol.Focus:
		return g.focus(m.Pane)
	case *protocol.Split:
		pane, err := g.tmux.Split(m.Pane)
		if err != nil {
			return err
		}
		return g.focus(pane)
	case *protocol.New:
		// The client's session is current here, so the window is current only for it.
		return g.tmux.NewWindow()
	case *protocol.Rename:
		return g.tmux.RenameWindow(m.Window, m.Name)
	case *protocol.Kill:
		switch {
		case m.Pane != "":
			return g.tmux.KillPane(m.Pane)
		case m.Window != "":
			return g.tmux.KillWindow(m.Window)
		}
		return errors.New("kill needs a window or pane")
	case *protocol.Quit:
		return g.quit(m.Pane)
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
	case *protocol.ClientHello:
		return errors.New("already said hello")
	}
	return fmt.Errorf("unhandled message %q", m.Type())
}

func validSize(cols, rows int) bool {
	return cols > 0 && rows > 0 && cols <= maxSize && rows <= maxSize
}
