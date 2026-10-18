// Package readview is what a pane's read view is made of, whatever program
// it's for: entries a client renders natively in place of the terminal,
// which keeps running underneath, and what the program is doing. A Source
// knows one kind of program: which panes run it, and how to read them (see
// internal/claude). The gateway puts what sources read on the wire.
package readview

import "context"

// Activity is what a program is doing, when it says.
type Activity string

const (
	Working Activity = "working" // on a turn, its tools included
	Waiting Activity = "waiting" // for the user to answer something mid-turn
	Idle    Activity = "idle"    // done until the next message
)

// Who said an entry, and what kind of entry it is.
const (
	User      = "user"
	Assistant = "assistant"

	Text     = "text" // markdown
	Thinking = "thinking"
	Tool     = "tool"
	Image    = "image"
	Note     = "note" // an event rather than something said
)

// An Entry is one part of a read view: something said, or a step taken.
type Entry struct {
	ID     string
	Role   string // User or Assistant
	Kind   string // Text, Thinking, Tool, Image or Note
	Text   string // what was said; for a tool, what it was asked
	Name   string // tool
	Output string // tool, trimmed
	Diff   string // tool: the change it made to a file, as unified diff hunks
	Done   bool   // tool finished
	Error  bool   // tool failed
	Mime   string // image
	Data   string // image, base64
}

// An Update changes a pane's read view. With Reset, its Entries replace any
// before (More says there were earlier ones); otherwise they're new, or
// replace those with their IDs. Activity comes when it changes, with Since,
// when it began; so does Context, how many tokens the conversation takes of
// the program's context (0 for unknown), if Measured.
type Update struct {
	Reset    bool
	More     bool
	Entries  []Entry
	Activity Activity
	Since    int64 // Unix milliseconds
	Context  int
	Measured bool
}

// A Pane is what a Source is told of a pane.
type Pane struct {
	ID      string
	TTY     string
	Command string
}

// A Source reads the panes running one kind of program.
type Source interface {
	// Name is how clients know it.
	Name() string
	// Find says which of these panes it reads, by ID. It's asked whenever
	// the session changes, so it should be quick.
	Find(panes []Pane) map[string]bool
	// Read sends what's in a pane, as it changes, until ctx is done. It
	// returns why there's nothing (more) to read.
	Read(ctx context.Context, pane Pane, send func(Update)) error
}
