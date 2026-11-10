package gateway

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/proc"
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/rex"
	"github.com/ahmd-sh/wallflower/internal/rextest"
	"github.com/ahmd-sh/wallflower/internal/tmux"
	"github.com/ahmd-sh/wallflower/internal/tmuxtest"
)

// A backend is a multiplexer the gateway's contract runs against.
type backend struct {
	name string
	can  []string
	// start starts one of its own, holding a session "t" whose terminal
	// runs /bin/sh, 120x40 if it's sized by itself.
	start func(t *testing.T) (mux.Mux, desk)
	// behindAlt: a repaint under a full-screen program has the screen behind it.
	behindAlt bool
}

var (
	tmuxBackend = backend{name: "tmux", can: []string{protocol.CanBindings}, start: startTmux, behindAlt: true}
	rexBackend  = backend{name: "rex", start: startRex}
	backends    = []backend{tmuxBackend, rexBackend}
)

// each runs a test against every multiplexer.
func each(t *testing.T, test func(t *testing.T, b backend)) {
	for _, b := range backends {
		t.Run(b.name, func(t *testing.T) { test(t, b) })
	}
}

// A desk is someone at the Mac, acting on the session with the
// multiplexer's own tools.
type desk interface {
	// run types a line into a pane, and Enter.
	run(pane, line string)
	// screen is what's on a pane's screen.
	screen(pane string) string
	size(pane string) (cols, rows int)
	// tty is the terminal a pane's programs run on.
	tty(pane string) string
	// window opens a window behind the current one, running command, or a
	// shell if it's empty.
	window(name, command string) (window, pane string)
	// split opens a pane beside one, leaving the focus where it is.
	split(pane string) string
	// current is the session's current window.
	current() string
	selectWindow(window string)
	// take shows a pane on a desktop client of 120x40, as if its user had
	// just looked at it.
	take(pane string)
	sessions() []string
	// held is what clients hold of the session for their own sake (tmux's
	// zooms, Rex's terminals), if anything.
	held() string
	// exit stops the multiplexer.
	exit()
}

type tmuxDesk struct {
	t      *testing.T
	socket string
	tmux   func(args ...string) string
	client *tmux.Client
}

func startTmux(t *testing.T) (mux.Mux, desk) {
	socket, run := tmuxtest.Server(t, 120, 40)
	return tmux.Server{Socket: socket, Config: "/dev/null"}, &tmuxDesk{t: t, socket: socket, tmux: run}
}

func (d *tmuxDesk) run(pane, line string) { d.tmux("send-keys", "-t", pane, line, "Enter") }
func (d *tmuxDesk) screen(pane string) string {
	return d.tmux("capture-pane", "-p", "-t", pane)
}

func (d *tmuxDesk) size(pane string) (cols, rows int) {
	fmt.Sscan(d.tmux("display", "-p", "-t", pane, "#{pane_width} #{pane_height}"), &cols, &rows)
	return
}

func (d *tmuxDesk) tty(pane string) string { return d.tmux("display", "-p", "-t", pane, "#{pane_tty}") }

func (d *tmuxDesk) window(name, command string) (string, string) {
	args := []string{"new-window", "-d", "-t", "t:", "-P", "-F", "#{window_id} #{pane_id}", "-n", name}
	if command != "" {
		args = append(args, command)
	}
	w, p, _ := strings.Cut(d.tmux(args...), " ")
	return w, p
}

func (d *tmuxDesk) split(pane string) string {
	return d.tmux("split-window", "-d", "-t", pane, "-P", "-F", "#{pane_id}")
}

func (d *tmuxDesk) current() string            { return d.tmux("display", "-p", "-t", "t:", "#{window_id}") }
func (d *tmuxDesk) selectWindow(window string) { d.tmux("select-window", "-t", window) }

// take selects the pane's window on a client of the desktop's own, which
// makes it the latest, and so the one the window is sized for.
func (d *tmuxDesk) take(pane string) {
	if d.client == nil {
		c, err := tmux.Start(context.Background(), tmux.Options{Socket: d.socket, Session: "t"})
		if err != nil {
			d.t.Fatal(err)
		}
		d.t.Cleanup(func() { c.Close() })
		go func() {
			for range c.Events() {
			}
		}()
		d.client = c
	}
	for _, line := range []string{"refresh-client -C 120x40", "select-window -t " + pane} {
		if _, err := d.client.Run(line); err != nil {
			d.t.Fatal(err)
		}
	}
}

func (d *tmuxDesk) sessions() []string {
	return strings.Fields(d.tmux("list-sessions", "-F", "#{session_name}"))
}

func (d *tmuxDesk) held() string {
	if z := d.tmux("list-windows", "-t", "t", "-F", "#{?window_zoomed_flag,#{window_id},}"); strings.TrimSpace(z) != "" {
		return "zoomed: " + strings.Join(strings.Fields(z), " ")
	}
	return ""
}

func (d *tmuxDesk) exit() { d.tmux("kill-server") }

type rexDesk struct {
	t      *testing.T
	server *rextest.Server
	client mux.Client
}

func startRex(t *testing.T) (mux.Mux, desk) {
	s := rextest.Start(t)
	return rex.Server{Endpoint: s.Socket}, &rexDesk{t: t, server: s}
}

func (d *rexDesk) rex(args ...string) string { return d.server.Run(args...) }

// json runs a command, and decodes what it prints.
func (d *rexDesk) json(v any, args ...string) {
	d.t.Helper()
	out := d.rex(args...)
	if err := json.Unmarshal([]byte(out), v); err != nil {
		d.t.Fatalf("rex %v: %v: %s", args, err, out)
	}
}

func (d *rexDesk) run(pane, line string) {
	d.rex("send", "-s", d.server.Session, "-b", pane, line)
	d.rex("send-key", "-s", d.server.Session, "-b", pane, "enter")
}

func (d *rexDesk) screen(pane string) string {
	return d.rex("capture", "-s", d.server.Session, "-b", pane, "--trim")
}

func (d *rexDesk) block(pane, method string, v any) {
	d.t.Helper()
	d.json(v, "block", "call", "-s", d.server.Session, "-b", pane, method)
}

func (d *rexDesk) size(pane string) (int, int) {
	var s struct {
		Cols int `json:"columns"`
		Rows int `json:"rows"`
	}
	d.block(pane, "size", &s)
	return s.Cols, s.Rows
}

func (d *rexDesk) tty(pane string) string {
	var p struct {
		Child struct{ Pid int } `json:"child"`
	}
	d.block(pane, "process", &p)
	return proc.TTY(p.Child.Pid)
}

func (d *rexDesk) window(name, command string) (string, string) {
	args := []string{"window", "new", "-s", d.server.Session, name, "--focus=false", "--json"}
	if command != "" {
		args = append(args, "--shell=none", "--", "/bin/sh", "-c", command)
	}
	var w struct {
		Window string   `json:"window_id"`
		Blocks []string `json:"block_ids"`
	}
	d.json(&w, args...)
	return w.Window, w.Blocks[0]
}

func (d *rexDesk) split(pane string) string {
	var s struct {
		Blocks []string `json:"block_ids"`
	}
	d.json(&s, "split", "-s", d.server.Session, "-b", pane, "--focus=false", "--json")
	return s.Blocks[0]
}

type rexView struct {
	Active  string `json:"active_window_id"`
	Windows []struct {
		Layers []struct {
			Blocks []struct {
				ID    string  `json:"block_id"`
				Owner *string `json:"owner"`
			} `json:"blocks"`
		} `json:"layers"`
	} `json:"windows"`
}

func (d *rexDesk) view() rexView {
	var v rexView
	d.json(&v, "api", "call", "-s", d.server.Session, "session.view")
	return v
}

func (d *rexDesk) current() string            { return d.view().Active }
func (d *rexDesk) selectWindow(window string) { d.rex("focus", "-s", d.server.Session, window) }

// take shows the pane on a client of the desktop's own, which owns it, and
// so sizes it.
func (d *rexDesk) take(pane string) {
	if d.client == nil {
		c, err := rex.Server{Endpoint: d.server.Socket}.Attach(mux.Attach{Session: "t", Cols: 120, Rows: 40})
		if err != nil {
			d.t.Fatal(err)
		}
		d.t.Cleanup(func() { c.Close() })
		go func() {
			for range c.Events() {
			}
		}()
		if _, err := c.Snapshot(); err != nil {
			d.t.Fatal(err)
		}
		d.client = c
	}
	if err := d.client.Focus(pane); err != nil {
		d.t.Fatal(err)
	}
}

func (d *rexDesk) sessions() []string {
	var ls struct {
		Sessions []struct {
			Label string `json:"label"`
		} `json:"sessions"`
	}
	d.json(&ls, "ls", "--json")
	var names []string
	for _, s := range ls.Sessions {
		names = append(names, s.Label)
	}
	return names
}

func (d *rexDesk) held() string {
	var owned []string
	for _, w := range d.view().Windows {
		for _, l := range w.Layers {
			for _, b := range l.Blocks {
				if b.Owner != nil {
					owned = append(owned, b.ID)
				}
			}
		}
	}
	if len(owned) > 0 {
		return "owned: " + strings.Join(owned, " ")
	}
	return ""
}

func (d *rexDesk) exit() { d.server.Kill() }
