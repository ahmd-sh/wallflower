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
}

// Reset tells the client to recreate the pane's terminal at this size; the
// pane data frames that follow repaint it.
type Reset struct {
	Pane string `json:"pane"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
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

var clientMessages = registry(
	ClientHello{}, Resize{}, Open{}, Close{}, Keys{}, Select{}, Zoom{},
	ScrollbackRequest{}, TmuxCommand{}, Ping{}, Paste{},
)

var serverMessages = registry(
	ServerHello{}, State{}, Reset{}, ScrollbackReply{}, Result{}, Error{}, Pong{}, Bye{},
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
