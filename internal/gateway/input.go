package gateway

import (
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const (
	enterSettle  = 50 * time.Millisecond
	uploadSettle = 150 * time.Millisecond
)

// take handles a frame from the client, or keeps it behind work still held.
func (g *gateway) take(f protocol.Frame) {
	g.do(func() error { return g.handle(f) })
}

// do does work now, or after what's held.
func (g *gateway) do(work func() error) {
	if g.resume != nil {
		g.held = append(g.held, work)
		return
	}
	if err := work(); err != nil {
		g.send(protocol.Error{Message: err.Error()})
	}
}

// hold gives a program a moment with what it was just sent: the work after
// it waits ([first], then the rest) until d has passed. Output and state go
// on meanwhile, and input keeps its order, so a pong still means everything
// sent before its ping reached tmux.
func (g *gateway) hold(d time.Duration, first ...func() error) {
	g.held = append(first, g.held...)
	g.resume = time.After(d)
}

// typeBytes types data into the pane as it is, as a mouse wheel's.
func (g *gateway) typeBytes(pane string, data []byte) error {
	g.claimSize(pane)
	return g.tmux.SendBytes(pane, data)
}

// submit gives a pane's program composed text in place of what its input
// line holds: the keys that empty the line, then the text as the program
// takes it best (see typeOut), then Enter if asked.
func (g *gateway) submit(m *protocol.Submit) error {
	g.claimSize(m.Pane)
	if len(m.Clear) > 0 {
		if err := g.tmux.SendKeys(m.Pane, m.Clear...); err != nil {
			return err
		}
	}
	return g.typeOut(m.Pane, g.uploads.split(m.Text), m.Enter)
}

// typeOut types runs of text in order. An upload's path is pasted on its
// own, as a file dropped on a terminal is, so the program can take it in as
// one (Claude Code attaches an image); that takes it a moment, so what
// follows waits. Text with newlines is pasted, bracketed if the program
// asked, so its lines don't run one by one; the rest is typed. Enter
// follows typed text a moment later: a program that reads them at once,
// like Claude Code, takes it all as a paste, Enter and all, and nothing is
// submitted.
func (g *gateway) typeOut(pane string, runs []run, enter bool) error {
	typed := false
	for i, r := range runs {
		var err error
		switch {
		case r.upload:
			if err := g.tmux.Paste(pane, g.buffer(), r.text); err != nil {
				return err
			}
			rest := runs[i+1:]
			g.hold(uploadSettle, func() error { return g.typeOut(pane, rest, enter) })
			return nil
		case strings.Contains(r.text, "\n"):
			err, typed = g.tmux.Paste(pane, g.buffer(), r.text), false
		default:
			err, typed = g.tmux.SendBytes(pane, []byte(r.text)), true
		}
		if err != nil {
			return err
		}
	}
	switch {
	case !enter:
		return nil
	case typed:
		g.hold(enterSettle, func() error { return g.tmux.SendKeys(pane, "Enter") })
		return nil
	}
	return g.tmux.SendKeys(pane, "Enter")
}

// buffer names the paste buffer this gateway pastes through.
func (g *gateway) buffer() string { return fmt.Sprintf("wallflower-%d", os.Getpid()) }

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
