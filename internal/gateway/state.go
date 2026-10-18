package gateway

import (
	"bytes"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

const stateDelay = 15 * time.Millisecond // tmux announces a change in several notifications

// sendState sends the session's state if it changed, and brings open panes
// and read views in line with it.
func (g *gateway) sendState() {
	g.dirty = false
	s, err := g.tmux.Snapshot()
	if err != nil {
		g.send(protocol.Error{Message: err.Error()})
		return
	}
	g.snapshot = s
	convs := g.conversations()
	st := stateOf(s, g.program, func(p tmux.Pane) string { return convs[p.TTY].name })
	st.Name = g.target // the client's own session is an implementation detail
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
	for id := range g.following {
		if _, ok := sizes[id]; !ok {
			g.unfollow(id)
		}
	}
	g.resized(sizes)
}

func stateOf(s *tmux.Session, program, transcript func(tmux.Pane) string) protocol.State {
	st := protocol.State{Session: s.ID, Name: s.Name, Windows: []protocol.Window{}}
	for _, w := range s.Windows {
		pw := protocol.Window{
			ID: w.ID, Index: w.Index, Name: w.Name, Active: w.Active, Zoomed: w.Zoomed,
			Cols: w.Width, Rows: w.Height, Panes: []protocol.Pane{},
		}
		for _, p := range w.Panes {
			pw.Panes = append(pw.Panes, protocol.Pane{
				ID: p.ID, Index: p.Index, Active: p.Active, X: p.Left, Y: p.Top,
				Cols: p.Width, Rows: p.Height, Floating: p.Floating, Z: p.Z, Alt: p.AltScreen,
				Agent: p.Agent, Title: p.Title, Path: p.Path, Command: program(p), Transcript: transcript(p),
			})
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
		if name = foreground(p.TTY); name == "" {
			name = p.Command
		}
		g.programs[key] = name
	}
	return name
}

// foreground is the name the tty's foreground process was run as (argv[0]).
func foreground(tty string) string {
	out, err := exec.Command("ps", "-o", "pid=,tpgid=,comm=", "-t", strings.TrimPrefix(tty, "/dev/")).Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		f := strings.Fields(line)
		if len(f) >= 3 && f[0] == f[1] {
			return strings.TrimPrefix(filepath.Base(strings.Join(f[2:], " ")), "-")
		}
	}
	return ""
}
