package gateway

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const (
	enterSettle  = 50 * time.Millisecond
	uploadSettle = 150 * time.Millisecond
)

// take handles a frame from the client, or keeps it behind those still held.
func (g *gateway) take(f protocol.Frame) {
	if g.resume != nil {
		g.held = append(g.held, f)
		return
	}
	if err := g.handle(f); err != nil {
		g.send(protocol.Error{Message: err.Error()})
	}
}

// hold gives a program a moment with what it was just sent: the client's
// frames after it, then wait (first, then the rest) until d has passed. Output
// and state go on meanwhile, and input keeps its order, so a pong still means
// everything sent before its ping reached tmux.
func (g *gateway) hold(d time.Duration, first ...protocol.Frame) {
	g.held = append(first, g.held...)
	g.resume = time.After(d)
}

// typeText types data into the pane byte for byte. An Enter that ends more
// than itself goes a moment after the rest: a program that reads it all at
// once, like Claude Code, takes it as a paste with the Enter in it, and
// nothing is submitted.
func (g *gateway) typeText(pane string, data []byte) error {
	g.claimSize(pane)
	if n := len(data) - 1; n > 0 && data[n] == '\r' {
		g.hold(enterSettle, protocol.Frame{Type: protocol.FrameData, ID: pane, Data: data[n:]})
		data = data[:n]
	}
	return g.tmux.SendBytes(pane, data)
}

// keys sends tmux key names, or, through bindings, keys as if typed on a
// keyboard attached to tmux.
func (g *gateway) keys(pane string, keys []string, bindings bool) error {
	if len(keys) == 0 {
		return nil
	}
	g.claimSize(pane)
	if bindings {
		return g.tmux.SendBound(g.client, keys...)
	}
	return g.tmux.SendKeys(pane, keys...)
}

func (g *gateway) paste(pane, text string) error {
	if text == "" {
		return nil
	}
	g.claimSize(pane)
	err := g.tmux.Paste(pane, fmt.Sprintf("wallflower-%d", os.Getpid()), text)
	// An upload pasted on its own may be taken in as a file (Claude Code attaches an image), which
	// takes the app a moment; keys right behind it, like Enter, would be lost meanwhile.
	if err == nil && filepath.Dir(text) == g.uploads.dir {
		g.hold(uploadSettle)
	}
	return err
}
