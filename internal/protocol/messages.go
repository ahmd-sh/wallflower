package protocol

import (
	"encoding/json"
	"fmt"
	"reflect"
)

// Message is a JSON message; Type is its "t" field.
type Message interface{ Type() string }

// Client to gateway.

type ClientHello struct {
	V    int `json:"v"`
	Cols int `json:"cols"`
	Rows int `json:"rows"`
	// Mux names the multiplexer the session is in (tmux, rex); empty is the
	// gateway's default.
	Mux     string `json:"mux,omitempty"`
	Session string `json:"session,omitempty"`
	Away    bool   `json:"away,omitempty"` // see Away
}

// Away says whether the user has left the client for now: while they have,
// it doesn't size the session's windows.
type Away struct {
	On bool `json:"on"`
}

type Resize struct {
	Cols int `json:"cols"`
	Rows int `json:"rows"`
}

type Open struct {
	Pane    string `json:"pane"`
	History int    `json:"history,omitempty"` // lines of scrollback to include; 0 means DefaultHistory
}

const DefaultHistory = 1000

type Close struct {
	Pane string `json:"pane"`
}

// Keys presses keys by wallflower's names (Enter, Escape, Up, C-c, M-Enter,
// BTab, ...; see package mux), encoded for the pane's current modes;
// anything else is typed as it is. Bytes go in data frames, composed text
// in Submit.
type Keys struct {
	Pane string   `json:"pane"`
	Keys []string `json:"keys"`
	// Bindings presses the keys as if typed on a keyboard attached to the
	// multiplexer, so its key bindings apply, if it can (CanBindings);
	// "prefix" stands for its prefix key.
	Bindings bool `json:"bindings,omitempty"`
}

// Submit sends composed text to a pane's program, in place of whatever its
// input line holds: Clear keys empty the line first, then the text goes as
// the program takes it best, and Enter if asked.
type Submit struct {
	Pane  string   `json:"pane"`
	Text  string   `json:"text"`
	Enter bool     `json:"enter,omitempty"`
	Clear []string `json:"clear,omitempty"`
}

type Select struct {
	Window string `json:"window,omitempty"`
	Pane   string `json:"pane,omitempty"`
}

// Focus makes a pane its window's active pane, zoomed if the window has
// others; the zoom is undone when the client disconnects.
type Focus struct {
	Pane string `json:"pane"`
}

// Split opens a new pane beside one, in the same directory, and focuses it.
type Split struct {
	Pane string `json:"pane"`
}

// New opens a new window, current in the client's session only.
type New struct{}

type Rename struct {
	Window string `json:"window"`
	Name   string `json:"name"`
}

// Kill closes a window or a pane; closing a window's last pane closes it.
type Kill struct {
	Window string `json:"window,omitempty"`
	Pane   string `json:"pane,omitempty"`
}

// Read starts or stops a pane's mobile view arriving as Entries, for a pane
// with one (Pane.View).
type Read struct {
	Pane string `json:"pane"`
	On   bool   `json:"on"`
}

// Picture asks for a picture entry's bytes, which come in a picture frame.
type Picture struct {
	Pane string `json:"pane"`
	ID   string `json:"id"`
}

// Upload starts sending a file: Size bytes follow in upload frames with its
// ID. The gateway saves it among its uploads, named after Name, and says
// where in Uploaded.
type Upload struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Size int64  `json:"size"`
}

type Ping struct {
	ID int `json:"id"`
}

func (ClientHello) Type() string { return "hello" }
func (Resize) Type() string      { return "resize" }
func (Open) Type() string        { return "open" }
func (Close) Type() string       { return "close" }
func (Keys) Type() string        { return "keys" }
func (Submit) Type() string      { return "submit" }
func (Select) Type() string      { return "select" }
func (Focus) Type() string       { return "focus" }
func (Split) Type() string       { return "split" }
func (New) Type() string         { return "new" }
func (Rename) Type() string      { return "rename" }
func (Kill) Type() string        { return "kill" }
func (Read) Type() string        { return "read" }
func (Picture) Type() string     { return "picture" }
func (Upload) Type() string      { return "upload" }
func (Ping) Type() string        { return "ping" }
func (Away) Type() string        { return "away" }

// Gateway to client.

type ServerHello struct {
	V   int `json:"v"`
	Mux Mux `json:"mux"`
	// Uploads is the directory uploads are saved in, so a client can tell
	// their paths from others in what it sends.
	Uploads string `json:"uploads,omitempty"`
}

// Mux is the multiplexer the session is in (tmux), its version, and what
// it can do beyond what every one does (CanBindings).
type Mux struct {
	Name    string   `json:"name"`
	Version string   `json:"version"`
	Can     []string `json:"can,omitempty"`
}

// CanBindings: Keys can press keys through the multiplexer's own key
// bindings.
const CanBindings = "bindings"

type State struct {
	Session string   `json:"session"`
	Name    string   `json:"name"`
	Windows []Window `json:"windows"`
}

type Window struct {
	ID     string `json:"id"`
	Index  int    `json:"index"`
	Name   string `json:"name"`
	Active bool   `json:"active"`
	Panes  []Pane `json:"panes"`
}

type Pane struct {
	ID      string `json:"id"`
	Index   int    `json:"index"`
	Active  bool   `json:"active"`
	Cols    int    `json:"cols"`
	Rows    int    `json:"rows"`
	Alt     bool   `json:"alt,omitempty"` // a full-screen app owns the pane
	Title   string `json:"title,omitempty"`
	Path    string `json:"path,omitempty"`
	Command string `json:"command"`
	// Program names the program the pane runs (claude, zsh, shell), if the
	// gateway knows it: a client knows it by the same name. View says it has
	// a mobile view of the pane; see Read.
	Program string `json:"program,omitempty"`
	View    bool   `json:"view,omitempty"`
	// Activity is what the program is doing, for one with a mobile view, if
	// it says (ActivityWorking...), and Since when it began, in Unix
	// milliseconds.
	Activity string `json:"activity,omitempty"`
	Since    int64  `json:"since,omitempty"`
}

// What a program is doing: working on a turn (its steps included), waiting
// for the user to answer it mid-turn, or done until the next message.
const (
	ActivityWorking = "working"
	ActivityWaiting = "waiting"
	ActivityIdle    = "idle"
)

// Reset tells the client to recreate the pane's terminal at this size; the
// pane data frames that follow repaint it.
type Reset struct {
	Pane string `json:"pane"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

// Entries carries a pane's mobile view. With Reset, its latest entries
// replace any before (More says earlier ones weren't sent); otherwise these
// entries are new, or replace the ones with their IDs. Error says why there
// is nothing to read (any more). Context is how many tokens the
// conversation takes of the program's context, as of its latest reply, when
// it says and whenever that changes; 0 when it no longer knows.
type Entries struct {
	Pane    string  `json:"pane"`
	Reset   bool    `json:"reset,omitempty"`
	More    bool    `json:"more,omitempty"`
	Entries []Entry `json:"entries,omitempty"`
	Error   string  `json:"error,omitempty"`
	Context *int    `json:"context,omitempty"`
}

// Entry is one part of a mobile view: something said, or a step taken.
type Entry struct {
	ID        string `json:"id"`
	From      string `json:"from"`             // FromUser or FromProgram
	Kind      string `json:"kind"`             // KindSaid (markdown), KindStep, KindThought, KindPicture or KindNote
	Text      string `json:"text,omitempty"`   // what was said; for a step, what it was asked
	Name      string `json:"name,omitempty"`   // step
	Status    string `json:"status,omitempty"` // step: StatusRunning, StatusDone or StatusFailed
	Output    string `json:"output,omitempty"` // step, trimmed
	OutputCut int    `json:"output_cut,omitempty"`
	Diff      string `json:"diff,omitempty"` // step: the change it made to a file, as unified diff hunks
	DiffCut   int    `json:"diff_cut,omitempty"`
	Mime      string `json:"mime,omitempty"` // picture
	Size      int    `json:"size,omitempty"` // picture: its bytes, which Picture asks for
}

const (
	FromUser    = "user"
	FromProgram = "program"

	KindSaid    = "said"
	KindStep    = "step"
	KindThought = "thought"
	KindPicture = "picture"
	KindNote    = "note"

	StatusRunning = "running"
	StatusDone    = "done"
	StatusFailed  = "failed"
)

type Error struct {
	Message string `json:"message"`
}

type Pong struct {
	ID int `json:"id"`
}

// Uploaded says where an upload was saved, once all of it arrived, or why it
// wasn't.
type Uploaded struct {
	ID    string `json:"id"`
	Path  string `json:"path,omitempty"`
	Error string `json:"error,omitempty"`
}

type Bye struct {
	Reason string `json:"reason"`
}

// Notification is a desktop notification a pane's program sent through its
// terminal (OSC 9, 777 or 99), or its bell (Bell, with no body), from any
// pane in the session.
type Notification struct {
	Pane  string `json:"pane"`
	Title string `json:"title,omitempty"`
	Body  string `json:"body"`
	Bell  bool   `json:"bell,omitempty"`
}

func (ServerHello) Type() string  { return "hello" }
func (State) Type() string        { return "state" }
func (Reset) Type() string        { return "reset" }
func (Error) Type() string        { return "error" }
func (Pong) Type() string         { return "pong" }
func (Bye) Type() string          { return "bye" }
func (Entries) Type() string      { return "entries" }
func (Uploaded) Type() string     { return "uploaded" }
func (Notification) Type() string { return "notification" }

var clientMessages = registry(
	ClientHello{}, Resize{}, Open{}, Close{}, Keys{}, Submit{}, Select{}, Focus{}, Split{}, New{}, Rename{}, Kill{},
	Read{}, Picture{}, Upload{}, Ping{}, Away{},
)

var serverMessages = registry(
	ServerHello{}, State{}, Reset{}, Error{}, Pong{}, Bye{}, Entries{}, Uploaded{}, Notification{},
)

// DecodeClient decodes a message sent by a client. It returns a pointer to
// the message struct, e.g. *Open.
func DecodeClient(data []byte) (Message, error) { return decode(data, clientMessages) }

// DecodeServer decodes a message sent by the gateway.
func DecodeServer(data []byte) (Message, error) { return decode(data, serverMessages) }

func registry(msgs ...Message) map[string]reflect.Type {
	r := map[string]reflect.Type{}
	for _, m := range msgs {
		r[m.Type()] = reflect.TypeOf(m)
	}
	return r
}

func decode(data []byte, reg map[string]reflect.Type) (Message, error) {
	var head struct {
		T string `json:"t"`
	}
	if err := json.Unmarshal(data, &head); err != nil {
		return nil, fmt.Errorf("protocol: %w", err)
	}
	typ, ok := reg[head.T]
	if !ok {
		return nil, fmt.Errorf("protocol: unknown message type %q", head.T)
	}
	m := reflect.New(typ).Interface().(Message)
	if err := json.Unmarshal(data, m); err != nil {
		return nil, fmt.Errorf("protocol: %s: %w", head.T, err)
	}
	return m, nil
}
