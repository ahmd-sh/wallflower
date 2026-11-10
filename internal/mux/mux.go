// Package mux is what wallflower needs of the terminal multiplexer whose
// session it serves: the session's windows and panes, each pane's output as
// it comes and its screen on demand, and the input and changes the client
// asks for. Each multiplexer is an adapter (tmux.Server), and the gateway
// knows only this. Another is rex.Server.
//
// Keys go by wallflower's names, which are tmux's whatever the multiplexer:
// Enter, Escape, Tab, BTab (shift-tab), BSpace, Space, Up, Down, Left,
// Right, Home, End, PageUp, PageDown, IC (insert), DC (delete) and F1 to
// F12, each with any of C- (ctrl), M- (alt) and S- (shift) before it, as in
// C-c, M-Enter and S-Up. Anything else is typed as it is. An adapter
// translates them.
package mux

// A Mux is a multiplexer a client can attach to, and whose sessions it can
// list, start and end without being attached to them.
type Mux interface {
	Attach(a Attach) (Client, error)

	// Sessions lists its sessions, and what can be done with them.
	Sessions() (Listing, error)
	// NewSession starts a session in the user's home directory, with one
	// shell, named name or a name of its own if that's empty, and says its
	// id; it starts the multiplexer too if it isn't running, if it Can.
	NewSession(name string) (string, error)
	RenameSession(session, name string) error
	// KillSession ends a session, and the programs in it.
	KillSession(session string) error
}

// A Listing is a multiplexer's sessions, in its own order (none while it
// isn't running), and what can be done with them (CanNew...).
type Listing struct {
	Running  bool
	Sessions []Listed
	Can      []string
}

// What can be done with a multiplexer's sessions, as Listing.Can and the
// wire name it: start one, starting the multiplexer if it isn't running;
// rename one; end one.
const (
	CanNew    = "new"
	CanRename = "rename"
	CanKill   = "kill"
)

// A Listed session is one as its multiplexer lists them.
type Listed struct {
	ID      string
	Name    string
	Windows int
}

// Attach says which session to attach to (empty: the one the multiplexer
// would pick), the client's size, and whether its user is away (see
// Client.Away). Unless they are, the client shows the session's current
// window once attached, so it's sized for it.
type Attach struct {
	Session    string
	Cols, Rows int
	Away       bool
}

// About is the multiplexer a client is attached to, its version, and what
// it Can do beyond what every one does.
type About struct {
	Name    string
	Version string
	Can     []string
}

// What a multiplexer can do beyond the rest, as About.Can and the wire name
// it.
const (
	// Bindings: Client.Bound presses keys through the multiplexer's own key
	// bindings, "prefix" standing for its prefix key.
	Bindings = "bindings"
)

// A Client is attached to a session as the desktop's own clients are, so
// they share it: a window switched to on one is switched to on the others.
// Its methods are called from one goroutine. Ids are the multiplexer's,
// opaque to the rest of wallflower; each method checks those it's given.
type Client interface {
	About() About

	// Events delivers what happens in the session, in order, until the
	// client is detached; then it's closed.
	Events() <-chan Event
	// Err says why the client was detached, once Events is closed: nil if
	// by Close.
	Err() error
	// Close detaches, putting back what the client changed only for its
	// own sake (tmux's zooms), and giving up what it took (Rex's terminals).
	Close() error

	// Snapshot is the session as it is now.
	Snapshot() (*Session, error)
	// Screen is a pane's screen as it is now, with up to history lines of
	// its scrollback.
	Screen(pane string, history int) (*Screen, error)

	// Resize sets the client's size. The windows it shows and types into
	// are sized for it, however the multiplexer sizes windows for several
	// clients.
	Resize(cols, rows int) error
	// Away says whether the client's user has left it for now: while they
	// have, it sizes no windows, as if it weren't attached.
	Away(on bool) error

	// Type types bytes into a pane as they are.
	Type(pane string, data []byte) error
	// Keys presses keys in a pane, encoded for its modes.
	Keys(pane string, keys ...string) error
	// Bound presses keys as if typed on a keyboard attached to the
	// multiplexer, so its key bindings apply, if it Can Bindings.
	Bound(pane string, keys ...string) error
	// Paste pastes text into a pane as a terminal does: bracketed if its
	// program asked, newlines as Enter.
	Paste(pane, text string) error

	// SelectWindow makes a window current for the client, its active pane
	// with the whole of the client's size, as Focus leaves one.
	SelectWindow(window string) error
	// SelectPane makes a pane its window's active one.
	SelectPane(pane string) error
	// Focus makes a pane its window's active one, with the whole of the
	// client's size: tmux zooms it if the window has others.
	Focus(pane string) error
	// Split opens a pane beside one, in its directory, and says its id.
	Split(pane string) (string, error)
	// NewWindow opens a window, current for the client.
	NewWindow() error
	RenameWindow(window, name string) error
	// KillPane closes a pane; closing a window's last pane closes it.
	KillPane(pane string) error
	KillWindow(window string) error
}

// An Event is a pane's output, or word that the session changed.
type Event struct {
	// Output: what Pane's programs wrote, and where it is in the client's
	// stream of it (see Screen).
	Pane string
	Data []byte
	Seq  uint64

	// Changed: the session did, in a way its Snapshot shows (windows,
	// panes, names, sizes, programs) and no output says.
	Changed bool
}

type Session struct {
	ID      string
	Name    string
	Windows []Window
}

type Window struct {
	ID     string
	Index  int
	Name   string
	Active bool
	Panes  []Pane
}

type Pane struct {
	ID         string
	Index      int
	Active     bool
	Cols, Rows int
	Alt        bool // a full-screen program owns it
	Title      string
	Path       string
	Command    string // its foreground program: its executable's name, or the name it was run as
	TTY        string // the terminal its programs run on, if the multiplexer knows
}

// A Screen is a pane's, read at once. Repaint rebuilds it in a fresh
// terminal of Cols x Rows: its scrollback, its screen, an alternate screen
// over it if one is up, the cursor, and the modes its program set. It holds
// the pane's output up to Seq: events with a Seq up to its own are in it.
type Screen struct {
	Cols, Rows int
	Repaint    []byte
	Seq        uint64
}
