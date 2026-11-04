package rex

import (
	"encoding/json"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/proc"
)

const (
	terminal = "com.superlogical.terminal"
	shell    = "com.superlogical.terminal.shell"
)

// Server is a Rex server as wallflower's multiplexer, at Endpoint (see
// dial); empty is the one Rex.app runs, or $REX_SERVER.
type Server struct {
	Endpoint string
}

func defaultEndpoint() string {
	if e := os.Getenv("REX_SERVER"); e != "" {
		return e
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "Application Support", "rex", "server.sock")
}

// Attach attaches to the session named (by label or id), or the first.
func (s Server) Attach(a mux.Attach) (mux.Client, error) {
	endpoint := s.Endpoint
	if endpoint == "" {
		endpoint = defaultEndpoint()
	}
	c, err := dial(endpoint)
	if err != nil {
		return nil, err
	}
	t, err := attach(c, a)
	if err != nil {
		c.close()
		return nil, err
	}
	go t.forward()
	return t, nil
}

func attach(c *control, a mux.Attach) (*attached, error) {
	var version struct {
		Rex string `json:"rex_version"`
	}
	if err := c.call("server.version", nil, &version); err != nil {
		return nil, err
	}
	var list struct {
		Sessions []struct {
			ID    string `json:"session_id"`
			Label string `json:"label"`
		} `json:"sessions"`
	}
	if err := c.call("session.list", nil, &list); err != nil {
		return nil, err
	}
	session := ""
	for _, s := range list.Sessions {
		if a.Session == "" || a.Session == s.ID || a.Session == s.Label {
			session = s.ID
			break
		}
	}
	if session == "" {
		return nil, errors.New("no rex session " + strings.TrimSpace(`"`+a.Session+`"`))
	}
	var att struct {
		Client string `json:"client_id"`
	}
	if err := c.call("session.attach", map[string]string{"session_id": session}, &att); err != nil {
		return nil, err
	}
	t := &attached{
		c: c, session: session, client: att.Client, cols: a.Cols, rows: a.Rows, away: a.Away,
		about:  mux.About{Name: "rex", Version: version.Rex},
		blocks: map[string]*block{}, owned: map[string][2]int{},
		events: make(chan mux.Event),
	}
	t.cond = sync.NewCond(&t.qmu)
	go t.pump()
	return t, nil
}

// attached is a control connection attached to a session, as a
// mux.Client, with a stream for each of its terminals. A terminal takes
// its size from the client that owns it, so the client owns and sizes the
// terminals it shows and types into, unless its user is away.
type attached struct {
	c       *control
	session string
	client  string
	about   mux.About

	cols, rows int
	away       bool
	owned      map[string][2]int // terminals the client owns, at the size it gave them
	last       view

	mu     sync.Mutex
	blocks map[string]*block // the session's terminals, by id

	qmu    sync.Mutex
	cond   *sync.Cond
	queue  []mux.Event
	seq    uint64
	err    error
	over   bool // no more events
	events chan mux.Event
}

// A block is what the client knows of one terminal.
type block struct {
	stream     *stream
	cols, rows int
	alt        bool
	title      string
	path       string
	command    string
	tty        string
}

type view struct {
	Label   string `json:"label"`
	Active  string `json:"active_window_id"`
	Windows []struct {
		ID      string `json:"window_id"`
		Label   string `json:"label"`
		Focused string `json:"focused_block_id"`
		Layers  []struct {
			Blocks []struct {
				ID      string `json:"block_id"`
				Creator string `json:"creator_name"`
			} `json:"blocks"`
		} `json:"layers"`
	} `json:"windows"`
}

func (t *attached) About() mux.About         { return t.about }
func (t *attached) Events() <-chan mux.Event { return t.events }

func (t *attached) Err() error {
	t.qmu.Lock()
	defer t.qmu.Unlock()
	return t.err
}

// Close ends the client: the terminals it owned pass on, and their streams
// end.
func (t *attached) Close() error {
	t.end(nil)
	t.mu.Lock()
	for _, b := range t.blocks {
		if b.stream != nil {
			b.stream.close()
		}
	}
	t.mu.Unlock()
	t.c.close()
	return nil
}

// push queues an event; output gets its place in the stream.
func (t *attached) push(e mux.Event) {
	t.qmu.Lock()
	if !t.over {
		if !e.Changed {
			t.seq++
			e.Seq = t.seq
		}
		t.queue = append(t.queue, e)
	}
	t.qmu.Unlock()
	t.cond.Signal()
}

// end stops events, err saying why (nil: Close).
func (t *attached) end(err error) {
	t.qmu.Lock()
	if !t.over {
		t.over, t.err = true, err
	}
	t.qmu.Unlock()
	t.cond.Signal()
}

// pump hands events on in order, so neither the control connection nor a
// stream ever waits on the gateway.
func (t *attached) pump() {
	defer close(t.events)
	for {
		t.qmu.Lock()
		for len(t.queue) == 0 && !t.over {
			t.cond.Wait()
		}
		if len(t.queue) == 0 {
			t.qmu.Unlock()
			return
		}
		e := t.queue[0]
		t.queue = t.queue[1:]
		t.qmu.Unlock()
		t.events <- e
	}
}

// forward follows the session's events, for what Snapshot shows.
func (t *attached) forward() {
	for e := range t.c.events {
		switch e.Type {
		case "session_view_changed", "window_created", "window_closed", "window_label_changed",
			"active_window_changed", "block_created", "block_closed", "session_label_changed":
			t.push(mux.Event{Changed: true})
		case "block_owner_changed":
			var o struct {
				Block string `json:"block_id"`
				Owner string `json:"owner"`
			}
			if json.Unmarshal(e.Payload, &o) == nil && o.Owner != t.client {
				t.mu.Lock()
				delete(t.owned, o.Block)
				t.mu.Unlock()
			}
		case "block_event":
			if t.blockEvent(e) {
				t.push(mux.Event{Changed: true})
			}
		case "session_destroyed":
			var d struct {
				Session string `json:"session_id"`
			}
			if json.Unmarshal(e.Payload, &d) == nil && d.Session == t.session {
				t.end(errors.New("session ended"))
				return
			}
		}
	}
	t.c.mu.Lock()
	err := t.c.err
	t.c.mu.Unlock()
	t.end(err)
}

// blockEvent keeps what a terminal says of itself, and whether that changed.
func (t *attached) blockEvent(e event) bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	b := t.blocks[e.Block]
	if b == nil {
		return false
	}
	var p struct {
		Title      string   `json:"title"`
		Pwd        string   `json:"pwd"`
		Foreground *process `json:"foreground"`
	}
	json.Unmarshal(e.Payload, &p)
	switch e.Name {
	case "title_changed":
		b.title = p.Title
	case "pwd_changed":
		if u, err := url.Parse(p.Pwd); err == nil && u.Path != "" {
			b.path = u.Path
		}
	case "process_changed":
		if p.Foreground != nil {
			b.command, b.path = p.Foreground.command(), p.Foreground.Cwd
		}
	default:
		return false
	}
	return true
}

type process struct {
	Pid   int    `json:"pid"`
	Name  string `json:"name"`
	Argv0 string `json:"argv0"`
	Cwd   string `json:"cwd"`
}

// command is the name a process was run as, as a shell shows it.
func (p *process) command() string {
	if name := strings.TrimPrefix(filepath.Base(p.Argv0), "-"); p.Argv0 != "" && name != "" {
		return name
	}
	return p.Name
}

// The streams' word.
func (t *attached) output(blk string, data []byte) { t.push(mux.Event{Pane: blk, Data: data}) }

func (t *attached) resized(blk string, cols, rows int) {
	t.mu.Lock()
	b := t.blocks[blk]
	changed := b != nil && (b.cols != cols || b.rows != rows)
	if changed {
		b.cols, b.rows = cols, rows
	}
	t.mu.Unlock()
	if changed {
		t.push(mux.Event{Changed: true})
	}
}

func (t *attached) alt(blk string, on bool) {
	t.mu.Lock()
	b := t.blocks[blk]
	changed := b != nil && b.alt != on
	if changed {
		b.alt = on
	}
	t.mu.Unlock()
	if changed {
		t.push(mux.Event{Changed: true})
	}
}

func (t *attached) ended(blk string, err error) {
	t.mu.Lock()
	if b := t.blocks[blk]; b != nil {
		b.stream = nil
	}
	t.mu.Unlock()
}

// Snapshot reads the session's view, and starts following terminals new
// to it.
func (t *attached) Snapshot() (*mux.Session, error) {
	var v view
	if err := t.c.call("session.view", map[string]string{"session_id": t.session}, &v); err != nil {
		return nil, err
	}
	t.last = v
	s := &mux.Session{ID: t.session, Name: v.Label}
	seen := map[string]bool{}
	for i, w := range v.Windows {
		mw := mux.Window{ID: w.ID, Index: i + 1, Name: w.Label, Active: w.ID == v.Active}
		for _, l := range w.Layers {
			for _, lb := range l.Blocks {
				if lb.Creator != terminal {
					continue
				}
				seen[lb.ID] = true
				b := t.follow(lb.ID)
				t.mu.Lock()
				mw.Panes = append(mw.Panes, mux.Pane{
					ID: lb.ID, Index: len(mw.Panes), Active: lb.ID == w.Focused, Cols: b.cols, Rows: b.rows, Alt: b.alt,
					Title: b.title, Path: b.path, Command: b.command, TTY: b.tty,
				})
				t.mu.Unlock()
			}
		}
		s.Windows = append(s.Windows, mw)
	}
	t.mu.Lock()
	for id, b := range t.blocks {
		if !seen[id] {
			if b.stream != nil {
				b.stream.close()
			}
			delete(t.blocks, id)
			delete(t.owned, id)
		}
	}
	t.mu.Unlock()
	return s, nil
}

// follow is a terminal as the client knows it, read first and streamed
// from then on if it's new.
func (t *attached) follow(id string) *block {
	t.mu.Lock()
	b := t.blocks[id]
	t.mu.Unlock()
	if b != nil {
		return b
	}
	b = &block{}
	var size struct {
		Cols int `json:"columns"`
		Rows int `json:"rows"`
	}
	t.c.callBlock(t.session, id, "size", nil, &size)
	b.cols, b.rows = size.Cols, size.Rows
	var title struct {
		Title string `json:"title"`
	}
	t.c.callBlock(t.session, id, "title", nil, &title)
	b.title = title.Title
	var ps struct {
		Child      *process `json:"child"`
		Foreground *process `json:"foreground"`
	}
	t.c.callBlock(t.session, id, "process", nil, &ps)
	if ps.Foreground != nil {
		b.command, b.path = ps.Foreground.command(), ps.Foreground.Cwd
	}
	if ps.Child != nil {
		b.tty = proc.TTY(ps.Child.Pid)
	}
	t.mu.Lock()
	t.blocks[id] = b
	t.mu.Unlock()
	// The stream first, so a switch of screens after the mode is read is in it.
	s, err := openStream(t.c, t.session, id, t)
	var modes struct {
		Screen string `json:"screen"`
	}
	t.c.callBlock(t.session, id, "modes", nil, &modes)
	t.mu.Lock()
	b.stream = s
	if err == nil {
		b.alt = modes.Screen == "alternate"
	}
	t.mu.Unlock()
	return b
}

func (t *attached) Screen(pane string, history int) (*mux.Screen, error) {
	t.qmu.Lock()
	seq := t.seq
	t.qmu.Unlock()
	head, lines, err := read(t.c, t.session, pane)
	if err != nil {
		return nil, err
	}
	t.mu.Lock()
	b := t.blocks[pane]
	t.mu.Unlock()
	if b == nil {
		return nil, errors.New("rex: no terminal " + pane)
	}
	return &mux.Screen{Cols: b.cols, Rows: b.rows, Repaint: repaint(head, lines, b.rows, history), Seq: seq}, nil
}

// Resize sizes the terminals the client owns.
func (t *attached) Resize(cols, rows int) error {
	t.cols, t.rows = cols, rows
	t.mu.Lock()
	owned := make([]string, 0, len(t.owned))
	for id := range t.owned {
		owned = append(owned, id)
	}
	t.mu.Unlock()
	for _, id := range owned {
		if err := t.claim(id); err != nil {
			return err
		}
	}
	return nil
}

// Away gives up the terminals the client owns, to whoever's next in line
// for them (the desktop).
func (t *attached) Away(on bool) error {
	t.away = on
	if !on {
		return nil
	}
	t.mu.Lock()
	owned := make([]string, 0, len(t.owned))
	for id := range t.owned {
		owned = append(owned, id)
	}
	t.owned = map[string][2]int{}
	t.mu.Unlock()
	if len(owned) == 0 {
		return nil
	}
	return t.c.call("session.disown_block", map[string]any{"session_id": t.session, "block_ids": owned}, nil)
}

// claim owns a terminal and sizes it for the client, unless it has.
func (t *attached) claim(pane string) error {
	if t.away {
		return nil
	}
	size := [2]int{t.cols, t.rows}
	t.mu.Lock()
	had, ok := t.owned[pane]
	t.mu.Unlock()
	if ok && had == size {
		return nil
	}
	if !ok {
		if err := t.c.call("session.own_block", map[string]any{"session_id": t.session, "block_ids": []string{pane}, "mode": "take"}, nil); err != nil {
			return err
		}
	}
	if err := t.c.callBlock(t.session, pane, "resize", map[string]int{"columns": t.cols, "rows": t.rows}, nil); err != nil {
		return err
	}
	t.mu.Lock()
	t.owned[pane] = size
	t.mu.Unlock()
	return nil
}

func (t *attached) Type(pane string, data []byte) error {
	if err := t.claim(pane); err != nil {
		return err
	}
	return t.write(pane, string(data))
}

func (t *attached) write(pane, text string) error {
	return t.c.callBlock(t.session, pane, "write", map[string]string{"data": strings.ToValidUTF8(text, "�")}, nil)
}

// Keys presses each named key, and releases it; text is typed.
func (t *attached) Keys(pane string, keys ...string) error {
	if err := t.claim(pane); err != nil {
		return err
	}
	for _, name := range keys {
		k, ok := keyOf(name)
		if !ok {
			if err := t.write(pane, name); err != nil {
				return err
			}
			continue
		}
		for _, action := range []string{"press", "release"} {
			k.Action = action
			if err := t.event(pane, "key", k); err != nil {
				return err
			}
		}
	}
	return nil
}

func (t *attached) event(pane, typ string, payload any) error {
	return t.c.call("block.event", map[string]any{
		"session_id": t.session, "block_id": pane, "event": map[string]any{"type": typ, "payload": payload},
	}, nil)
}

func (t *attached) Bound(pane string, keys ...string) error {
	return errors.New("rex: keys through its bindings aren't supported yet")
}

// Paste sends newlines as Enter, as tmux does; Rex would paste them as they are.
func (t *attached) Paste(pane, text string) error {
	if err := t.claim(pane); err != nil {
		return err
	}
	text = strings.ReplaceAll(strings.ReplaceAll(text, "\r\n", "\r"), "\n", "\r")
	return t.event(pane, "paste", map[string]string{"text": text})
}

func (t *attached) SelectWindow(window string) error {
	if err := t.c.call("session.focus_window", map[string]string{"session_id": t.session, "window_id": window}, nil); err != nil {
		return err
	}
	for _, w := range t.last.Windows {
		if w.ID == window && w.Focused != "" {
			return t.claim(w.Focused)
		}
	}
	return nil
}

func (t *attached) SelectPane(pane string) error {
	return t.c.call("session.focus_block", map[string]string{"session_id": t.session, "block_id": pane}, nil)
}

// Focus gives the pane the client's whole size; Rex sizes it on its own,
// so there's nothing to zoom.
func (t *attached) Focus(pane string) error {
	if err := t.SelectPane(pane); err != nil {
		return err
	}
	return t.claim(pane)
}

func (t *attached) Split(pane string) (string, error) {
	var r struct {
		Blocks []string `json:"block_ids"`
	}
	if err := t.c.call("session.new_split", map[string]any{
		"session_id": t.session, "anchor_id": pane, "direction": "horizontal", "side": "after",
		"layout": t.newBlock(pane), "focus": true,
	}, &r); err != nil {
		return "", err
	}
	if len(r.Blocks) == 0 {
		return "", errors.New("rex: split made no terminal")
	}
	return r.Blocks[0], nil
}

func (t *attached) NewWindow() error {
	focused := ""
	for _, w := range t.last.Windows {
		if w.ID == t.last.Active {
			focused = w.Focused
		}
	}
	return t.c.call("session.new_window", map[string]any{"session_id": t.session, "layout": t.newBlock(focused), "focus": true}, nil)
}

// newBlock is a layout of one shell, in the directory of the terminal
// beside it.
func (t *attached) newBlock(beside string) map[string]any {
	options := map[string]any{}
	t.mu.Lock()
	if b := t.blocks[beside]; b != nil && b.path != "" {
		options["cwd"] = b.path
	}
	t.mu.Unlock()
	return map[string]any{"block": map[string]any{"flavor": shell, "options": options}}
}

func (t *attached) RenameWindow(window, name string) error {
	return t.c.call("session.set_window_label", map[string]string{"session_id": t.session, "window_id": window, "label": name}, nil)
}

func (t *attached) KillPane(pane string) error {
	return t.c.call("block.close", map[string]string{"session_id": t.session, "block_id": pane}, nil)
}

func (t *attached) KillWindow(window string) error {
	return t.c.call("session.close_window", map[string]string{"session_id": t.session, "window_id": window}, nil)
}
