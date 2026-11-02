package gateway

import (
	"bytes"
	"regexp"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
	"github.com/ahmd-sh/wallflower/internal/proc"
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

const stateDelay = 15 * time.Millisecond // tmux announces a change in several notifications

// sendState sends the session's state if it changed, and brings open panes
// and mobile views in line with it.
func (g *gateway) sendState() {
	g.dirty = false
	s, err := g.tmux.Snapshot()
	if err != nil {
		g.send(protocol.Error{Message: err.Error()})
		return
	}
	g.snapshot = s
	readers := g.readers(s)
	g.found = map[string]mobileview.Found{}
	for id, r := range readers {
		g.found[id] = r.found
	}
	st := stateOf(s, g.program, readers)
	if b, _ := protocol.AppendJSON(nil, st); !bytes.Equal(b, g.lastState) {
		g.out.push(b)
		g.lastState = b
	}

	sizes := map[string][2]int{}
	for _, w := range s.Windows {
		for _, p := range w.Panes {
			sizes[p.ID] = [2]int{p.Width, p.Height}
		}
	}
	for id := range g.reading {
		if _, ok := sizes[id]; !ok {
			g.unread(id)
		}
	}
	for id := range g.scanners {
		if _, ok := sizes[id]; !ok {
			delete(g.scanners, id)
		}
	}
	g.resized(sizes)
}

func stateOf(s *tmux.Session, program func(tmux.Pane) string, readers map[string]reader) protocol.State {
	st := protocol.State{Session: s.ID, Name: s.Name, Windows: []protocol.Window{}}
	for _, w := range s.Windows {
		pw := protocol.Window{ID: w.ID, Index: w.Index, Name: w.Name, Active: w.Active, Panes: []protocol.Pane{}}
		for _, p := range w.Panes {
			pp := protocol.Pane{
				ID: p.ID, Index: p.Index, Active: p.Active, Cols: p.Width, Rows: p.Height, Alt: p.AltScreen,
				Title: p.Title, Path: p.Path, Command: program(p),
			}
			if r, ok := readers[p.ID]; ok {
				pp.Reader, pp.Activity, pp.Since = r.src.Name(), string(r.found.Activity), r.found.Since
			}
			pw.Panes = append(pw.Panes, pp)
		}
		st.Windows = append(st.Windows, pw)
	}
	return st
}

// versioned matches a command that is a version number: some tools install
// as .../name/versions/1.2.3 and run that file, so tmux, which names the
// executable, reports the version (Claude Code shows up as 2.1.300).
var versioned = regexp.MustCompile(`^v?[0-9]+(\.[0-9]+)+$`)

// program is the pane's foreground program, by the name it was run as when
// tmux only knows its version.
func (g *gateway) program(p tmux.Pane) string {
	if !versioned.MatchString(p.Command) || p.TTY == "" {
		return p.Command
	}
	key := p.TTY + " " + p.Command
	name, ok := g.programs[key]
	if !ok {
		if name = proc.Foreground(p.TTY); name == "" {
			name = p.Command
		}
		g.programs[key] = name
	}
	return name
}
