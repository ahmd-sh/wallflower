package gateway

import (
	"bytes"
	"regexp"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/proc"
	"github.com/ahmd-sh/wallflower/internal/program"
	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const stateDelay = 15 * time.Millisecond // a multiplexer may announce one change in several events

// sendState sends the session's state if it changed, and brings open panes
// and mobile views in line with it.
func (g *gateway) sendState() {
	g.dirty = false
	if g.mux == nil {
		return
	}
	s, err := g.mux.Snapshot()
	if err != nil {
		g.send(protocol.Error{Message: err.Error()})
		return
	}
	g.snapshot = s
	g.found = g.matches(s)
	st := stateOf(s, g.program, g.found)
	if b, _ := protocol.AppendJSON(nil, st); !bytes.Equal(b, g.lastState) {
		g.out.push(b)
		g.lastState = b
	}

	sizes := map[string][2]int{}
	for _, w := range s.Windows {
		for _, p := range w.Panes {
			sizes[p.ID] = [2]int{p.Cols, p.Rows}
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

func stateOf(s *mux.Session, program func(mux.Pane) string, matches map[string]match) protocol.State {
	st := protocol.State{Session: s.ID, Name: s.Name, Windows: []protocol.Window{}}
	for _, w := range s.Windows {
		pw := protocol.Window{ID: w.ID, Index: w.Index, Name: w.Name, Active: w.Active, Panes: []protocol.Pane{}}
		for _, p := range w.Panes {
			pp := protocol.Pane{
				ID: p.ID, Index: p.Index, Active: p.Active, Cols: p.Cols, Rows: p.Rows, Alt: p.Alt,
				Title: p.Title, Path: p.Path, Command: program(p),
			}
			if m, ok := matches[p.ID]; ok {
				pp.Program = m.program.ID
				if m.view {
					pp.View, pp.Activity, pp.Since = true, string(m.found.Activity), m.found.Since
				}
			}
			pw.Panes = append(pw.Panes, pp)
			if p.Active && w.Name == p.Command && pp.Command != p.Command {
				pw.Name = pp.Command // named after the version the program runs as
			}
		}
		st.Windows = append(st.Windows, pw)
	}
	return st
}

// versioned matches a command that is a version number: some tools install
// as .../name/versions/1.2.3 and run that file, so a multiplexer that names
// the executable, as tmux does, reports the version (Claude Code shows up as
// 2.1.300).
var versioned = regexp.MustCompile(`^v?[0-9]+(\.[0-9]+)+$`)

// program is the pane's foreground program, by the name it was run as when
// the multiplexer only knows its version.
func (g *gateway) program(p mux.Pane) string {
	if !versioned.MatchString(p.Command) || p.TTY == "" {
		return p.Command
	}
	key := p.TTY + " " + p.Command
	name, ok := g.names[key]
	if !ok {
		if name = proc.Foreground(p.TTY); name == "" {
			name = p.Command
		}
		g.names[key] = name
	}
	return name
}

func (g *gateway) windows() []mux.Window {
	if g.snapshot == nil {
		return nil
	}
	return g.snapshot.Windows
}

// clients is the phone halves of programs that bring theirs as data.
func clients(programs []program.Program) []protocol.Program {
	var out []protocol.Program
	for _, p := range programs {
		c := p.Client
		if c == nil {
			continue
		}
		pp := protocol.Program{ID: p.ID, Newline: c.Newline, Clear: c.Clear}
		if c.Keys != nil {
			pp.Keys = &protocol.KeyBar{Name: c.Keys.Name, Keys: c.Keys.Keys, Stop: c.Keys.Stop}
		}
		for _, cmd := range c.Commands {
			pp.Commands = append(pp.Commands, protocol.Command{Text: cmd.Text, About: cmd.About})
		}
		out = append(out, pp)
	}
	return out
}
