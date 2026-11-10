package protocol

import (
	"encoding/json"
	"fmt"
	"reflect"
)

// Message is a JSON message; Type is its "t" field.
type Message interface{ Type() string }

// Client to gateway.

// ClientHello says where the client was (Session, in the multiplexer Mux
// names, tmux or rex; empty is the gateway's default), to attach there
// again if it's there. With no Session, or one that's gone, the client is
// attached to nothing.
type ClientHello struct {
	V       int    `json:"v"`
	Cols    int    `json:"cols"`
	Rows    int    `json:"rows"`
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

// Sessions asks for the sessions in every multiplexer the gateway has,
// which come in a Sessions of its own.
type Sessions struct{}

// Switch attaches the client to a session, in any multiplexer: Attached
// says which, and state follows. What it had open of one it was attached
// to is closed, and its mobile views are off.
type Switch struct {
	Mux     string `json:"mux"`
	Session string `json:"session"`
}

// NewSession starts a session, named Name or a name of the multiplexer's
// own, and switches to it; for one that isn't running, if it Can, it starts
// it too.
type NewSession struct {
	Mux  string `json:"mux"`
	Name string `json:"name,omitempty"`
}

type RenameSession struct {
	Mux     string `json:"mux"`
	Session string `json:"session"`
	Name    string `json:"name"`
}

// KillSession ends a session, and the programs in it; not the client's own.
type KillSession struct {
	Mux     string `json:"mux"`
	Session string `json:"session"`
}

// Detach leaves the session for none, as the user chose: Detached follows.
type Detach struct{}

func (ClientHello) Type() string   { return "hello" }
func (Resize) Type() string        { return "resize" }
func (Open) Type() string          { return "open" }
func (Close) Type() string         { return "close" }
func (Keys) Type() string          { return "keys" }
func (Submit) Type() string        { return "submit" }
func (Select) Type() string        { return "select" }
func (Focus) Type() string         { return "focus" }
func (Split) Type() string         { return "split" }
func (New) Type() string           { return "new" }
func (Rename) Type() string        { return "rename" }
func (Kill) Type() string          { return "kill" }
func (Read) Type() string          { return "read" }
func (Picture) Type() string       { return "picture" }
func (Upload) Type() string        { return "upload" }
func (Ping) Type() string          { return "ping" }
func (Away) Type() string          { return "away" }
func (Sessions) Type() string      { return "sessions" }
func (Switch) Type() string        { return "switch" }
func (NewSession) Type() string    { return "new-session" }
func (RenameSession) Type() string { return "rename-session" }
func (KillSession) Type() string   { return "kill-session" }
func (Detach) Type() string        { return "detach" }

// Gateway to client.

// ServerHello comes first, before the client is attached to anything.
type ServerHello struct {
	V int `json:"v"`
	// Uploads is the directory uploads are saved in, so a client can tell
	// their paths from others in what it sends.
	Uploads string `json:"uploads,omitempty"`
	// Machine is the name of the machine the gateway runs on, as its owner
	// calls it (a Mac's Computer Name), for a client to say where it is.
	Machine string `json:"machine,omitempty"`
	// Programs are the phone halves of plugins the user gave the gateway,
	// as data, for programs the client has no half of its own for.
	Programs []Program `json:"programs,omitempty"`
}

// Program is a plugin's phone half as data: the key bar its panes get
// (Keys), the commands the composer offers, and the keys that start a new
// line (Newline) and empty one (Clear).
type Program struct {
	ID       string    `json:"id"`
	Keys     *KeyBar   `json:"keys,omitempty"`
	Commands []Command `json:"commands,omitempty"`
	Newline  []string  `json:"newline,omitempty"`
	Clear    []string  `json:"clear,omitempty"`
}

// KeyBar is a key bar by key names, and what the send button presses to
// stop the program.
type KeyBar struct {
	Name string   `json:"name"`
	Keys []string `json:"keys"`
	Stop []string `json:"stop,omitempty"`
}

// Command is a command a program takes, as the composer offers it.
type Command struct {
	Text  string `json:"text"`
	About string `json:"about,omitempty"`
}

// Attached says the client is attached to a session in Mux; its state
// follows.
type Attached struct {
	Mux Mux `json:"mux"`
}

// Detached says the client is attached to nothing: it never was this
// connection, it asked (Detach), the session or its multiplexer went
// (Reason says), or one it asked for couldn't be had (likewise). The
// sessions follow, and come again whenever they change, until it's
// attached.
type Detached struct {
	Reason string `json:"reason,omitempty"`
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
// it says and whenever that changes; 0 when it no longer knows. Title is
// what the conversation is called, with a Reset and whenever it changes.
type Entries struct {
	Pane    string  `json:"pane"`
	Reset   bool    `json:"reset,omitempty"`
	More    bool    `json:"more,omitempty"`
	Entries []Entry `json:"entries,omitempty"`
	Error   string  `json:"error,omitempty"`
	Context *int    `json:"context,omitempty"`
	Title   string  `json:"title,omitempty"`
	// Queued is what the user sent that waits for the program to take it,
	// oldest first, when that changes: empty once nothing waits.
	Queued *[]string `json:"queued,omitempty"`
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

// SessionList is the sessions in each multiplexer the gateway has, sent
// when asked, after a session is started, renamed or ended, and whenever
// they change while the client is attached to nothing (Detached).
type SessionList struct {
	Muxes []MuxSessions `json:"muxes"`
}

// MuxSessions is one multiplexer's sessions, in its own order, or why they
// can't be listed; whether it's running, and what can be done with its
// sessions (CanNew...).
type MuxSessions struct {
	Name     string        `json:"name"`
	Running  bool          `json:"running"`
	Sessions []SessionInfo `json:"sessions"`
	Can      []string      `json:"can,omitempty"`
	Error    string        `json:"error,omitempty"`
}

// What can be done with a multiplexer's sessions: start one (and the
// multiplexer with it, if it isn't running), rename one, end one.
const (
	CanNew    = "new"
	CanRename = "rename"
	CanKill   = "kill"
)

type SessionInfo struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Windows int    `json:"windows"`
}

func (ServerHello) Type() string  { return "hello" }
func (Attached) Type() string     { return "attached" }
func (Detached) Type() string     { return "detached" }
func (SessionList) Type() string  { return "sessions" }
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
	Read{}, Picture{}, Upload{}, Ping{}, Away{}, Sessions{}, Switch{}, NewSession{}, RenameSession{}, KillSession{}, Detach{},
)

var serverMessages = registry(
	ServerHello{}, Attached{}, Detached{}, State{}, Reset{}, Error{}, Pong{}, Bye{}, Entries{}, Uploaded{}, Notification{}, SessionList{},
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
