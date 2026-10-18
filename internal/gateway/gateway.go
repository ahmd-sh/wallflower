// Package gateway serves one client over the wallflower protocol, backed by a
// tmux control-mode client. It keeps no state that tmux doesn't already
// hold; everything it sends can be rebuilt from tmux.
package gateway

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
	"github.com/ahmd-sh/wallflower/internal/transcript"
)

type Options struct {
	Socket      string              // tmux socket, see tmux.Options
	Session     string              // default session when the client doesn't ask for one
	Transcripts []transcript.Source // CLIs whose conversations panes can show
	Uploads     string              // where files the client sends are saved; UploadDir() if empty
}

const (
	highWater    = 1 << 20   // stop forwarding pane output above this backlog
	lowWater     = 256 << 10 // repaint stale panes once the backlog is below this
	stateDelay   = 15 * time.Millisecond
	uploadSettle = 150 * time.Millisecond
	rewindLimit  = time.Minute
	maxSize      = 1000
)

// Any change to these re-sends state.
var paneSubscription = "meta:%*:" + strings.Join([]string{
	"#{pane_current_command}", "#{pane_title}", "#{pane_current_path}",
	"#{@agent_state}", "#{alternate_on}",
}, "\t")

type gateway struct {
	tmux       *tmux.Client
	out        *outbox
	cols, rows int
	session    *tmux.Session     // last snapshot
	panes      map[string]*pane  // panes the client has open
	zoomed     map[string]bool   // windows the client zoomed -> zoom state before
	group      string            // the session the client asked for; its own session is grouped with it
	programs   map[string]string // tty and versioned command -> the name it was run as
	client     string            // this control client's name, for keys sent through bindings
	ctx        context.Context
	sources    []transcript.Source
	ttys       map[int]string                // pid -> its tty, to find the pane a transcript's process runs in
	following  map[string]context.CancelFunc // pane -> the follower sending its conversation
	rewinding  sync.Map                      // panes being rewound
	uploads    uploads
	lastState  []byte
	dirty      bool
	exitReason string
}

type pane struct {
	openAt     uint64 // output with Seq <= openAt is already in the repaint
	history    int
	cols, rows int
	stale      bool // output dropped under backpressure; repaint when drained
}

// Serve runs one client session over in and out until the client
// disconnects or tmux exits.
func Serve(ctx context.Context, in io.Reader, out io.Writer, opts Options) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	g := &gateway{
		out: newOutbox(out), panes: map[string]*pane{}, zoomed: map[string]bool{}, programs: map[string]string{},
		ctx: ctx, sources: opts.Transcripts, ttys: map[int]string{}, following: map[string]context.CancelFunc{},
		uploads: uploads{dir: opts.Uploads, open: map[string]*upload{}},
	}
	if g.uploads.dir == "" {
		g.uploads.dir = UploadDir()
	}
	defer g.uploads.close()
	defer g.out.close(2 * time.Second)

	r := protocol.NewReader(in)
	hello, err := readHello(r)
	if err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	g.cols, g.rows = hello.Cols, hello.Rows
	session := hello.Session
	if session == "" {
		session = opts.Session
	}
	if g.group, err = sessionName(opts.Socket, session); err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}

	// The client gets its own session in the target's group: the same
	// windows, but its own current window, so switching windows on the phone
	// never moves the desktop. Not tied to ctx: cleanup still needs tmux
	// after a signal.
	g.tmux, err = tmux.Start(context.Background(), tmux.Options{
		Socket:  opts.Socket,
		Session: g.group,
		Group:   fmt.Sprintf("%s-wallflower-%d", g.group, os.Getpid()),
		Flags:   []string{"new-layouts", "no-detach-on-destroy"},
	})
	if err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	defer g.cleanup()

	version, err := g.setup()
	if err != nil {
		err = g.tmuxErr(err)
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	g.send(protocol.ServerHello{V: protocol.Version, Tmux: version, Uploads: g.uploads.dir})
	g.sendState()

	frames := make(chan protocol.Frame)
	readErr := make(chan error, 1)
	go func() {
		for {
			f, err := r.Next()
			if err != nil {
				readErr <- err
				return
			}
			select {
			case frames <- f:
			case <-ctx.Done():
				return
			}
		}
	}()
	return g.loop(ctx, frames, readErr)
}

// sessionName resolves the session to group with: the named one, or the one
// tmux would pick.
func sessionName(socket, session string) (string, error) {
	args := append(tmux.SocketArgs(socket), "-u", "display-message", "-p")
	if session != "" {
		args = append(args, "-t", session+":")
	}
	out, err := exec.Command(tmux.Binary(), append(args, "#{session_name}")...).CombinedOutput()
	name := strings.TrimSpace(string(out))
	if err != nil || name == "" {
		return "", fmt.Errorf("no tmux session %q: %s", session, name)
	}
	return name, nil
}

func readHello(r *protocol.Reader) (*protocol.ClientHello, error) {
	f, err := r.Next()
	if err != nil {
		return nil, err
	}
	if f.Type != protocol.FrameJSON {
		return nil, errors.New("first message must be hello")
	}
	m, err := protocol.DecodeClient(f.Data)
	if err != nil {
		return nil, err
	}
	hello, ok := m.(*protocol.ClientHello)
	if !ok {
		return nil, errors.New("first message must be hello")
	}
	if hello.V != protocol.Version {
		return nil, fmt.Errorf("protocol version %d not supported (gateway speaks %d)", hello.V, protocol.Version)
	}
	if !validSize(hello.Cols, hello.Rows) {
		return nil, fmt.Errorf("bad size %dx%d", hello.Cols, hello.Rows)
	}
	return hello, nil
}

func (g *gateway) setup() (string, error) {
	r, err := g.tmux.Command(fmt.Sprintf("refresh-client -C %dx%d ; refresh-client -B %s ; display -p '#{version}\t#{client_name}'",
		g.cols, g.rows, tmux.Quote(paneSubscription)))
	if err != nil {
		return "", err
	}
	lines := r.Lines()
	if len(lines) != 1 {
		return "", fmt.Errorf("unexpected setup reply %q", lines)
	}
	version, client, _ := strings.Cut(lines[0], "\t")
	g.client = client
	return version, nil
}

func (g *gateway) loop(ctx context.Context, frames <-chan protocol.Frame, readErr <-chan error) error {
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
	var stateDue <-chan time.Time
	for {
		select {
		case n, ok := <-g.tmux.Events():
			if !ok {
				reason := "tmux exited"
				if g.exitReason != "" {
					reason += ": " + g.exitReason
				}
				g.send(protocol.Bye{Reason: reason})
				return g.tmux.Err()
			}
			g.notify(n)
		case f := <-frames:
			if err := g.handle(f); err != nil {
				g.send(protocol.Error{Message: err.Error()})
			}
		case err := <-readErr:
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		case <-stateDue:
			stateDue = nil
			g.sendState()
		case <-tick.C:
			g.repaintStale()
		case <-ctx.Done():
			return ctx.Err()
		}
		if g.dirty && stateDue == nil {
			stateDue = time.After(stateDelay)
		}
	}
}

func (g *gateway) notify(n tmux.Notification) {
	switch n.Type {
	case "output", "extended-output":
		p := g.panes[n.Pane]
		if p == nil || p.stale || n.Seq <= p.openAt {
			return
		}
		if g.out.buffered() > highWater {
			p.stale = true
			return
		}
		g.out.push(protocol.AppendData(nil, n.Pane, n.Data))
	case "layout-change", "window-add", "window-close", "window-renamed", "window-pane-changed",
		"session-window-changed", "session-changed", "session-renamed", "subscription-changed":
		g.dirty = true
	case "exit":
		g.exitReason = n.Value
	}
}

func (g *gateway) handle(f protocol.Frame) error {
	if f.Type == protocol.FrameData {
		if err := checkID(paneID, f.ID); err != nil {
			return err
		}
		g.claimSize(f.ID)
		return g.sendText(f.ID, f.Data)
	}
	if f.Type == protocol.FrameUpload {
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
		if !validSize(m.Cols, m.Rows) {
			return fmt.Errorf("bad size %dx%d", m.Cols, m.Rows)
		}
		g.cols, g.rows = m.Cols, m.Rows
		if err := g.run(fmt.Sprintf("refresh-client -C %dx%d", m.Cols, m.Rows)); err != nil {
			return err
		}
		// The size only applies to windows this client was last to use;
		// showing the current window again makes it so.
		for _, w := range g.windows() {
			if w.Active {
				return g.selectWindow(w.ID)
			}
		}
		return nil

	case *protocol.Open:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		return g.open(m.Pane, m.History)

	case *protocol.Close:
		delete(g.panes, m.Pane)
		return nil

	case *protocol.Keys:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		if len(m.Keys) == 0 {
			return nil
		}
		g.claimSize(m.Pane)
		if m.Bindings {
			return g.sendBound(m.Keys)
		}
		cmd := "send-keys -t " + m.Pane + " --"
		for _, k := range m.Keys {
			cmd += " " + tmux.Quote(k)
		}
		return g.run(cmd)

	case *protocol.Rewind:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		g.rewind(m)
		return nil

	case *protocol.Upload:
		path, err := g.uploads.begin(m)
		if err != nil || path != "" {
			g.sendUploaded(m.ID, path, err)
		}
		return nil

	case *protocol.Paste:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		if m.Text == "" {
			return nil
		}
		g.claimSize(m.Pane)
		buf := fmt.Sprintf("wallflower-%d", os.Getpid())
		err := g.run(fmt.Sprintf("set-buffer -b %[1]s -- %[2]s ; paste-buffer -p -d -b %[1]s -t %[3]s", buf, tmux.QuoteText(m.Text), m.Pane))
		// An upload pasted on its own may be taken in as a file (Claude Code attaches an image), which
		// takes the app a moment; keys right behind it, like Enter, would be lost meanwhile.
		if err == nil && filepath.Dir(m.Text) == g.uploads.dir {
			time.Sleep(uploadSettle)
		}
		return err

	case *protocol.Select:
		switch {
		case m.Pane != "":
			if err := checkID(paneID, m.Pane); err != nil {
				return err
			}
			return g.run("select-pane -t " + m.Pane)
		case m.Window != "":
			if err := checkID(windowID, m.Window); err != nil {
				return err
			}
			return g.selectWindow(m.Window)
		}
		return errors.New("select needs a window or pane")

	case *protocol.Zoom:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		return g.zoom(m.Pane, "resize-pane -Z -t "+m.Pane)

	case *protocol.Focus:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		return g.focus(m.Pane)

	case *protocol.Transcript:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		g.unfollow(m.Pane)
		if m.On {
			g.follow(m.Pane)
		}
		return nil

	case *protocol.Split:
		if err := checkID(paneID, m.Pane); err != nil {
			return err
		}
		out, err := g.tmux.Run(fmt.Sprintf("split-window -t %s -c %s -P -F '#{pane_id}'", m.Pane, tmux.Quote("#{pane_current_path}")))
		if err != nil {
			return err
		}
		if len(out) != 1 || checkID(paneID, out[0]) != nil {
			return fmt.Errorf("unexpected split reply %q", out)
		}
		return g.focus(out[0])

	case *protocol.New:
		// The client's session is current here, so the window is current only for it.
		return g.run("new-window -c " + tmux.Quote("#{pane_current_path}"))

	case *protocol.Rename:
		if err := checkID(windowID, m.Window); err != nil {
			return err
		}
		return g.run("rename-window -t " + m.Window + " -- " + tmux.QuoteText(m.Name))

	case *protocol.Kill:
		switch {
		case m.Pane != "":
			if err := checkID(paneID, m.Pane); err != nil {
				return err
			}
			return g.run("kill-pane -t " + m.Pane)
		case m.Window != "":
			if err := checkID(windowID, m.Window); err != nil {
				return err
			}
			return g.run("kill-window -t " + m.Window)
		}
		return errors.New("kill needs a window or pane")

	case *protocol.ScrollbackRequest:
		g.send(g.scrollback(m))
		return nil

	case *protocol.TmuxCommand:
		lines, err := g.tmux.Run(m.Command)
		res := protocol.Result{ID: m.ID, Lines: lines}
		if err != nil {
			res.Error = err.Error()
		}
		g.send(res)
		return nil

	case *protocol.Ping:
		g.send(protocol.Pong{ID: m.ID})
		return nil

	case *protocol.ClientHello:
		return errors.New("already said hello")
	}
	return fmt.Errorf("unhandled message %q", m.Type())
}

// focus makes a pane its window's active one, zoomed if the window has
// others; select-pane -Z keeps a zoomed window zoomed on the new pane.
func (g *gateway) focus(pane string) error {
	g.claimSize(pane)
	return g.zoom(pane, fmt.Sprintf("select-pane -Z -t %[1]s ; if -F -t %[1]s '#{&&:#{!=:#{window_panes},1},#{!=:#{window_zoomed_flag},1}}' 'resize-pane -Z -t %[1]s'", pane))
}

// zoom runs a command that may change pane's window's zoom, remembering how
// the window was first so cleanup can put it back.
func (g *gateway) zoom(pane, cmd string) error {
	r, err := g.tmux.Command(fmt.Sprintf("display -p -t %s '#{window_id} #{window_zoomed_flag}' ; %s", pane, cmd))
	if err != nil {
		return err
	}
	if lines := r.Lines(); len(lines) > 0 {
		id, was, _ := strings.Cut(lines[0], " ")
		if _, seen := g.zoomed[id]; !seen {
			g.zoomed[id] = was == "1"
		}
	}
	return nil
}

// sendBound sends keys through this client's key tables, as if typed on a
// keyboard attached to tmux, so the prefix and bindings work.
func (g *gateway) sendBound(keys []string) error {
	cmd := "send-keys -K -c " + tmux.Quote(g.client) + " --"
	for _, k := range keys {
		if k == "prefix" {
			out, err := g.tmux.Run("show -gv prefix")
			if err != nil || len(out) != 1 {
				return fmt.Errorf("no prefix key: %v", err)
			}
			k = out[0]
		}
		cmd += " " + tmux.Quote(k)
	}
	return g.run(cmd)
}

// open (re)paints a pane and starts streaming its output.
func (g *gateway) open(id string, history int) error {
	if history <= 0 {
		history = protocol.DefaultHistory
	}
	r, err := g.tmux.Command(captureCommand(id, history))
	if err != nil {
		return err
	}
	c, err := parseCapture(r, history)
	if err != nil {
		return err
	}
	g.panes[id] = &pane{openAt: r.Seq, history: history, cols: c.cols, rows: c.rows}
	g.send(protocol.Reset{Pane: id, Cols: c.cols, Rows: c.rows})
	g.out.push(protocol.AppendData(nil, id, c.repaint()))
	return nil
}

func (g *gateway) repaintStale() {
	if g.out.buffered() > lowWater {
		return
	}
	for id, p := range g.panes {
		if p.stale {
			if err := g.open(id, p.history); err != nil {
				delete(g.panes, id)
			}
		}
	}
}

func (g *gateway) sendState() {
	g.dirty = false
	s, err := g.tmux.Snapshot()
	if err != nil {
		g.send(protocol.Error{Message: err.Error()})
		return
	}
	g.session = s
	convs := g.conversations()
	st := stateOf(s, g.program, func(p tmux.Pane) string { return convs[p.TTY].name })
	st.Name = g.group // the client's own session is an implementation detail
	if b, _ := protocol.AppendJSON(nil, st); !bytes.Equal(b, g.lastState) {
		g.out.push(b)
		g.lastState = b
	}

	// Panes that closed are forgotten; panes that changed size are repainted,
	// since the client's terminal would reflow differently from tmux's.
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
	for id, p := range g.panes {
		size, ok := sizes[id]
		switch {
		case !ok:
			delete(g.panes, id)
		case size != [2]int{p.cols, p.rows}:
			if err := g.open(id, p.history); err != nil {
				delete(g.panes, id)
			}
		}
	}
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

// A conversation is a process recording a transcript, in some pane.
type conversation struct {
	name string // its source's
	src  transcript.Source
	pid  int
}

// conversations finds the transcripts being written, by the tty of the
// process writing each.
func (g *gateway) conversations() map[string]conversation {
	found := map[string]conversation{}
	for _, src := range g.sources {
		for pid := range src.Running() {
			if tty := g.tty(pid); tty != "" {
				found[tty] = conversation{src.Name(), src, pid}
			}
		}
	}
	return found
}

func (g *gateway) tty(pid int) string {
	tty, ok := g.ttys[pid]
	if !ok {
		out, _ := exec.Command("ps", "-o", "tty=", "-p", strconv.Itoa(pid)).Output()
		if t := strings.TrimSpace(string(out)); t != "" && !strings.HasPrefix(t, "?") {
			tty = "/dev/" + t
		}
		g.ttys[pid] = tty
	}
	return tty
}

// conversationIn is the conversation being recorded in pane, if there is one.
func (g *gateway) conversationIn(pane string) conversation {
	for _, w := range g.windows() {
		for _, p := range w.Panes {
			if p.ID == pane {
				return g.conversations()[p.TTY]
			}
		}
	}
	return conversation{}
}

// follow sends the conversation in pane as it's written, until unfollow.
func (g *gateway) follow(pane string) {
	c := g.conversationIn(pane)
	if c.src == nil {
		g.send(protocol.Entries{Pane: pane, Reset: true, Error: "no conversation in this pane"})
		return
	}
	ctx, cancel := context.WithCancel(g.ctx)
	g.following[pane] = cancel
	go transcript.Follow(ctx, c.src, c.pid, func(e protocol.Entries) {
		if ctx.Err() == nil { // a follower being replaced says nothing more
			e.Pane = pane
			g.send(e)
		}
	})
}

// rewind takes the conversation in m.Pane back, in the background: it's a
// back and forth with the program's screen that takes a moment.
func (g *gateway) rewind(m *protocol.Rewind) {
	reply := protocol.Rewound{Pane: m.Pane, Entry: m.Entry}
	c := g.conversationIn(m.Pane)
	r, ok := c.src.(transcript.Rewinder)
	if !ok {
		reply.Error = "nothing to rewind in this pane"
		g.send(reply)
		return
	}
	if _, busy := g.rewinding.LoadOrStore(m.Pane, true); busy {
		reply.Error = "already rewinding"
		g.send(reply)
		return
	}
	g.claimSize(m.Pane)
	go func() {
		defer g.rewinding.Delete(m.Pane)
		ctx, cancel := context.WithTimeout(g.ctx, rewindLimit)
		defer cancel()
		if err := r.Rewind(ctx, paneTerminal{g.tmux, m.Pane}, c.pid, m.Entry, m.Code); err != nil {
			reply.Error = err.Error()
		}
		g.send(reply)
	}()
}

// paneTerminal is a pane, for a program's transcript.Rewinder to drive.
type paneTerminal struct {
	tmux *tmux.Client
	pane string
}

func (p paneTerminal) Screen() ([]string, error) { return p.tmux.Run("capture-pane -p -t " + p.pane) }

func (p paneTerminal) Keys(keys ...string) error {
	cmd := "send-keys -t " + p.pane + " --"
	for _, k := range keys {
		cmd += " " + tmux.Quote(k)
	}
	_, err := p.tmux.Run(cmd)
	return err
}

func (p paneTerminal) Type(text string) error {
	_, err := p.tmux.Run("send-keys -t " + p.pane + " -l -- " + tmux.QuoteText(text))
	return err
}

func (g *gateway) unfollow(pane string) {
	if cancel, ok := g.following[pane]; ok {
		cancel()
		delete(g.following, pane)
	}
}

func (g *gateway) windows() []tmux.Window {
	if g.session == nil {
		return nil
	}
	return g.session.Windows
}

// selectWindow makes a window current in this client's own session. A bare
// window id would pick whichever session holding it was active last, which
// can be the desktop's.
func (g *gateway) selectWindow(id string) error {
	if g.session == nil {
		return errors.New("no session yet")
	}
	return g.run("select-window -t " + tmux.Quote(g.session.ID+":"+id))
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

// sendText types data into the pane byte for byte; send-keys -H takes hex,
// so no quoting is involved.
func (g *gateway) sendText(pane string, data []byte) error {
	const perCommand, perLine = 512, 16
	var cmds []string
	for len(data) > 0 {
		n := min(len(data), perCommand)
		var b strings.Builder
		b.WriteString("send-keys -t " + pane + " -H")
		for _, c := range data[:n] {
			fmt.Fprintf(&b, " %02x", c)
		}
		cmds = append(cmds, b.String())
		data = data[n:]
		if len(cmds) == perLine || len(data) == 0 {
			if err := g.run(strings.Join(cmds, " ; ")); err != nil {
				return err
			}
			cmds = cmds[:0]
		}
	}
	return nil
}

func (g *gateway) scrollback(m *protocol.ScrollbackRequest) protocol.Message {
	if err := checkID(paneID, m.Pane); err != nil {
		return protocol.Result{ID: m.ID, Error: err.Error()}
	}
	r, err := g.tmux.Command(fmt.Sprintf("display -p -t %[1]s '#{history_size}' ; capture-pane -p -e -t %[1]s -S %[2]d -E %[3]d",
		m.Pane, m.Start, m.End))
	if err == nil && (len(r.Blocks) != 2 || len(r.Blocks[0]) != 1) {
		err = errors.New("unexpected capture reply")
	}
	if err != nil {
		return protocol.Result{ID: m.ID, Error: err.Error()}
	}
	return protocol.ScrollbackReply{ID: m.ID, Pane: m.Pane, HistorySize: atoi(r.Blocks[0][0]), Lines: r.Blocks[1]}
}

// cleanup puts back the zoom state of windows the client zoomed, then
// detaches.
func (g *gateway) cleanup() {
	var cmds []string
	for w, was := range g.zoomed {
		cmds = append(cmds, fmt.Sprintf("if -F -t %[1]s '#{!=:#{window_zoomed_flag},%[2]d}' 'resize-pane -Z -t %[1]s'", w, b2i(was)))
	}
	if len(cmds) > 0 {
		g.tmux.Run(strings.Join(cmds, " ; "))
	}
	g.tmux.Close()
}

func (g *gateway) sendUploaded(id, path string, err error) {
	if err != nil {
		g.send(protocol.Uploaded{ID: id, Error: err.Error()})
		return
	}
	g.send(protocol.Uploaded{ID: id, Path: path})
}

func (g *gateway) send(m protocol.Message) {
	b, err := protocol.AppendJSON(nil, m)
	if err != nil {
		panic(err) // our own message types always encode
	}
	g.out.push(b)
}

func (g *gateway) run(cmd string) error {
	_, err := g.tmux.Run(cmd)
	return err
}

// tmuxErr prefers tmux's own exit message (no server, no such session) over
// the generic closed error.
func (g *gateway) tmuxErr(err error) error {
	if errors.Is(err, tmux.ErrClosed) {
		if exitErr := g.tmux.Err(); exitErr != nil {
			return exitErr
		}
		if g.exitReason != "" {
			return errors.New(g.exitReason)
		}
	}
	return err
}

var (
	paneID   = regexp.MustCompile(`^%[0-9]+$`)
	windowID = regexp.MustCompile(`^@[0-9]+$`)
)

// checkID guards ids that are spliced into tmux commands unquoted.
func checkID(re *regexp.Regexp, id string) error {
	if !re.MatchString(id) {
		return fmt.Errorf("bad id %q", id)
	}
	return nil
}

func validSize(cols, rows int) bool {
	return cols > 0 && rows > 0 && cols <= maxSize && rows <= maxSize
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}
