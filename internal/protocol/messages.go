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
	V       int    `json:"v"`
	Cols    int    `json:"cols"`
	Rows    int    `json:"rows"`
	Session string `json:"session,omitempty"`
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

// Keys sends tmux key names (Enter, Escape, Up, C-c, M-Enter, BTab, ...),
// encoded for the pane's current modes. Text goes in data frames.
type Keys struct {
	Pane string   `json:"pane"`
	Keys []string `json:"keys"`
	// Bindings sends the keys as if typed on a keyboard attached to tmux, so
	// key bindings apply; "prefix" stands for the prefix key.
	Bindings bool `json:"bindings,omitempty"`
}

// Paste pastes text the way a terminal does: bracketed if the pane's app
// asked for bracketed paste, so its newlines don't run anything.
type Paste struct {
	Pane string `json:"pane"`
	Text string `json:"text"`
}

type Select struct {
	Window string `json:"window,omitempty"`
	Pane   string `json:"pane,omitempty"`
}

type Zoom struct {
	Pane string `json:"pane"`
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

// Transcript starts or stops a pane's conversation arriving as Entries; a
// pane has one when Pane.Transcript names its source.
type Transcript struct {
	Pane string `json:"pane"`
	On   bool   `json:"on"`
}

type ScrollbackRequest struct {
	ID    int    `json:"id"`
	Pane  string `json:"pane"`
	Start int    `json:"start"` // tmux line numbers: 0 is the top visible line, negative is history
	End   int    `json:"end"`
}

type TmuxCommand struct {
	ID      int    `json:"id"`
	Command string `json:"command"`
}

type Ping struct {
	ID int `json:"id"`
}

func (ClientHello) Type() string       { return "hello" }
func (Resize) Type() string            { return "resize" }
func (Open) Type() string              { return "open" }
func (Close) Type() string             { return "close" }
func (Keys) Type() string              { return "keys" }
func (Select) Type() string            { return "select" }
func (Zoom) Type() string              { return "zoom" }
func (ScrollbackRequest) Type() string { return "scrollback" }
func (TmuxCommand) Type() string       { return "tmux" }
func (Ping) Type() string              { return "ping" }
func (Paste) Type() string             { return "paste" }
func (Focus) Type() string             { return "focus" }
func (Split) Type() string             { return "split" }
func (New) Type() string               { return "new" }
func (Rename) Type() string            { return "rename" }
func (Kill) Type() string              { return "kill" }
func (Transcript) Type() string        { return "transcript" }

// Gateway to client.

type ServerHello struct {
	V    int    `json:"v"`
	Tmux string `json:"tmux"`
}

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
	Zoomed bool   `json:"zoomed"`
	Cols   int    `json:"cols"`
	Rows   int    `json:"rows"`
	Panes  []Pane `json:"panes"`
}

type Pane struct {
	ID       string `json:"id"`
	Index    int    `json:"index"`
	Active   bool   `json:"active"`
	X        int    `json:"x"`
	Y        int    `json:"y"`
	Cols     int    `json:"cols"`
	Rows     int    `json:"rows"`
	Floating bool   `json:"floating,omitempty"`
	Z        int    `json:"z,omitempty"`   // floating stacking order, 0 is the front
	Alt      bool   `json:"alt,omitempty"` // a full-screen app owns the pane
	Agent    string `json:"agent,omitempty"`
	Title    string `json:"title,omitempty"`
	Path     string `json:"path,omitempty"`
	Command  string `json:"command"`
	// Transcript names the source of the conversation the pane's program
	// records (claude), if it has one; see Transcript.
	Transcript string `json:"transcript,omitempty"`
}

// Reset tells the client to recreate the pane's terminal at this size; the
// pane data frames that follow repaint it.
type Reset struct {
	Pane string `json:"pane"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

// Entries carries a pane's conversation. With Reset, its latest entries
// replace any before (More says earlier ones weren't sent); otherwise these
// entries are new, or replace the ones with their IDs. Error says why there
// is no conversation (any more). State says what the CLI is doing, when it
// says and whenever that changes (with no entries), and Since when it began.
type Entries struct {
	Pane    string  `json:"pane"`
	Reset   bool    `json:"reset,omitempty"`
	More    bool    `json:"more,omitempty"`
	Entries []Entry `json:"entries,omitempty"`
	Error   string  `json:"error,omitempty"`
	State   string  `json:"state,omitempty"` // StateWorking, StateWaiting or StateIdle
	Since   int64   `json:"since,omitempty"` // Unix milliseconds
}

// What a CLI is doing: working on a turn (its tools included), waiting for
// the user to answer it mid-turn, or done until the next message.
const (
	StateWorking = "working"
	StateWaiting = "waiting"
	StateIdle    = "idle"
)

// Entry is one part of a conversation.
type Entry struct {
	ID     string `json:"id"`
	Role   string `json:"role"`             // user or assistant
	Kind   string `json:"kind"`             // text (markdown), thinking, tool, image or note
	Text   string `json:"text,omitempty"`   // what was said; for a tool, what it was asked
	Name   string `json:"name,omitempty"`   // tool
	Output string `json:"output,omitempty"` // tool, trimmed
	Done   bool   `json:"done,omitempty"`   // tool finished
	Error  bool   `json:"error,omitempty"`  // tool failed
	Mime   string `json:"mime,omitempty"`   // image
	Data   string `json:"data,omitempty"`   // image, base64
}

type ScrollbackReply struct {
	ID          int      `json:"id"`
	Pane        string   `json:"pane"`
	HistorySize int      `json:"history_size"`
	Lines       []string `json:"lines"`
}

type Result struct {
	ID    int      `json:"id"`
	Lines []string `json:"lines,omitempty"`
	Error string   `json:"error,omitempty"`
}

type Error struct {
	Message string `json:"message"`
}

type Pong struct {
	ID int `json:"id"`
}

type Bye struct {
	Reason string `json:"reason"`
}

func (ServerHello) Type() string     { return "hello" }
func (State) Type() string           { return "state" }
func (Reset) Type() string           { return "reset" }
func (ScrollbackReply) Type() string { return "scrollback" }
func (Result) Type() string          { return "result" }
func (Error) Type() string           { return "error" }
func (Pong) Type() string            { return "pong" }
func (Bye) Type() string             { return "bye" }
func (Entries) Type() string         { return "entries" }

var clientMessages = registry(
	ClientHello{}, Resize{}, Open{}, Close{}, Keys{}, Select{}, Zoom{},
	ScrollbackRequest{}, TmuxCommand{}, Ping{}, Paste{}, Focus{}, Split{}, New{}, Rename{}, Kill{},
	Transcript{},
)

var serverMessages = registry(
	ServerHello{}, State{}, Reset{}, ScrollbackReply{}, Result{}, Error{}, Pong{}, Bye{}, Entries{},
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
