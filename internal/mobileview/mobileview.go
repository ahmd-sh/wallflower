// Package mobileview is what a pane's mobile view is made of, whatever program
// it's for: entries a client renders natively in place of the terminal,
// which keeps running underneath, and what the program is doing. A Source
// knows one kind of program: which panes run it, and how to read them (see
// internal/claude). The gateway puts what sources read on the wire.
package mobileview

import "context"

// Activity is what a program is doing, when it says.
type Activity string

const (
	Working Activity = "working" // on a turn, its tools included
	Waiting Activity = "waiting" // for the user to answer something mid-turn
	Idle    Activity = "idle"    // done until the next message
)

// Who an entry is from, what kind it is, and how a step went.
const (
	User    = "user"
	Program = "program"

	Said    = "said" // markdown
	Step    = "step" // a tool run, a command
	Thought = "thought"
	Picture = "picture"
	Note    = "note" // an event rather than something said

	Running = "running"
	Done    = "done"
	Failed  = "failed"
)

// An Entry is one part of a mobile view: something said, or a step taken.
type Entry struct {
	ID        string
	From      string // User or Program
	Kind      string // Said, Step, Thought, Picture or Note
	Text      string // what was said; for a step, what it was asked
	Name      string // step
	Status    string // step: Running, Done or Failed
	Output    string // step, trimmed
	OutputCut int    // characters trimmed from Output
	Diff      string // step: the change it made to a file, as unified diff hunks
	DiffCut   int    // lines trimmed from Diff
	Mime      string // picture
	Data      string // picture, base64
}

// An Update changes a pane's mobile view. With Reset, its Entries replace any
// before (More says there were earlier ones); otherwise they're new, or
// replace those with their IDs. Context, how many tokens the conversation
// takes of the program's context (0 for unknown), comes when it changes, if
// Measured. What the program is doing isn't here: Find says.
type Update struct {
	Reset    bool
	More     bool
	Entries  []Entry
	Context  int
	Measured bool
}

// A Pane is what a Source is told of a pane.
type Pane struct {
	ID      string
	TTY     string
	Command string
}

// Found is a pane a Source reads, and what its program is doing, if it
// says, since when (Unix milliseconds).
type Found struct {
	Activity Activity
	Since    int64
}

// A Source reads the panes running one kind of program.
type Source interface {
	// Name is how clients know it.
	Name() string
	// Find says which of these panes it reads, by ID. It's asked whenever
	// the session changes, and every moment or so for what programs are
	// doing, so it should be quick.
	Find(panes []Pane) map[string]Found
	// Read sends what's in a pane, as it changes, until ctx is done. It
	// returns why there's nothing (more) to read.
	Read(ctx context.Context, pane Pane, send func(Update)) error
}
