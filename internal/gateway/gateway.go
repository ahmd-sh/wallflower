// Package gateway serves one client over the wallflower protocol, backed by
// a client of the multiplexer the session it's attached to is in (see
// package mux), or none while it's attached to nothing. It keeps no state
// that the multiplexer doesn't already hold; everything it sends can be
// rebuilt from it.
//
// One loop owns all of it: the client's messages (dispatch.go) drive the
// multiplexer (input.go), and its events become the client's pane streams
// (panes.go) and session state (state.go), with mobile views
// (mobileview.go) and uploads alongside.
package gateway

import (
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"time"

	"github.com/ahmd-sh/wallflower/internal/desktop"
	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/program"
	"github.com/ahmd-sh/wallflower/internal/protocol"
)

type Options struct {
	Muxes    map[string]mux.Mux // the multiplexers a client can ask for, by name
	Mux      string             // the one when it doesn't ask
	Session  string             // the session in that one to attach a client to that names none; none if empty
	Programs []program.Program  // the programs it knows, by the panes that run them; first match wins
	Uploads  string             // where files the client sends are saved; UploadDir() if empty
	Machine  string             // the machine's name, said in hello; MachineName() if empty
}

type gateway struct {
	ctx   context.Context
	opts  Options
	mux   mux.Client // nil while attached to nothing
	muxed string     // the name of the multiplexer it's attached to
	about mux.About
	out   *outbox

	cols, rows int // the client's size
	away       bool

	snapshot  *mux.Session // the last
	lastState []byte
	dirty     bool
	names     map[string]string // tty and versioned command -> the name it was run as
	found     map[string]match  // panes whose programs it knows, as last sent

	panes    map[string]*pane            // panes the client has open
	scanners map[string]*desktop.Scanner // every pane's output, for notifications
	stale    bool                        // some are, so repaintStale is due

	held   []func() error   // work waiting for resume, in order: the client's frames, and the rest of what one asked
	resume <-chan time.Time // while a pane settles; see hold

	programs []program.Program
	reading  map[string]reading // panes whose mobile views the client has on
	updates  chan readUpdate
	gen      int // readers started

	uploads uploads

	listed []byte // the sessions as last sent
}

// Serve serves one client over in and out until it disconnects: attached
// to the session its hello names, if it's there, or to none, and to others
// or none as it asks or the multiplexer lets go of it.
func Serve(ctx context.Context, in io.Reader, out io.Writer, opts Options) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	g := &gateway{
		ctx: ctx, opts: opts, out: newOutbox(out), names: map[string]string{},
		panes: map[string]*pane{}, scanners: map[string]*desktop.Scanner{},
		programs: opts.Programs, reading: map[string]reading{}, updates: make(chan readUpdate, 16),
		uploads: uploads{dir: opts.Uploads, open: map[string]*upload{}},
	}
	if g.uploads.dir == "" {
		g.uploads.dir = UploadDir()
	}
	if g.opts.Machine == "" {
		g.opts.Machine = MachineName()
	}
	defer g.uploads.close()
	defer g.out.close(2 * time.Second)

	r := protocol.NewReader(in)
	hello, err := readHello(r)
	if err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	g.cols, g.rows, g.away = hello.Cols, hello.Rows, hello.Away
	g.send(protocol.ServerHello{
		V:        protocol.Version,
		Uploads:  g.uploads.dir,
		Machine:  g.opts.Machine,
		Programs: clients(g.programs),
	})
	defer func() {
		if g.mux != nil {
			g.mux.Close()
		}
	}()
	where, session := hello.Mux, hello.Session
	if session == "" {
		where, session = opts.Mux, opts.Session
	}
	if session == "" {
		g.detach("")
	} else if name, c, err := g.attach(where, session); err != nil {
		g.detach(g.unhad(where, err))
	} else {
		g.attached(name, c)
	}

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

// attach attaches a client of the multiplexer named (the default if it's
// empty) to the session, at the client's size.
func (g *gateway) attach(name, session string) (string, mux.Client, error) {
	name, m, err := g.opts.pick(name)
	if err != nil {
		return "", nil, err
	}
	if session == "" {
		return "", nil, errors.New("attaching needs a session")
	}
	c, err := m.Attach(mux.Attach{Session: session, Cols: g.cols, Rows: g.rows, Away: g.away})
	return name, c, err
}

// attached makes c the client's, in place of the one it had, saying so and
// sending its state.
func (g *gateway) attached(name string, c mux.Client) {
	g.leave()
	g.mux, g.muxed, g.about = c, name, c.About()
	g.send(protocol.Attached{Mux: protocol.Mux{Name: g.about.Name, Version: g.about.Version, Can: g.about.Can}})
	g.sendState()
}

// detach leaves the client attached to nothing, saying so, and why if it
// didn't ask, then sending the sessions it can attach to.
func (g *gateway) detach(reason string) {
	g.leave()
	g.send(protocol.Detached{Reason: reason})
	g.listed = nil
	g.sessions(false)
}

// unhad says why a session the client asked for in the multiplexer named
// couldn't be had: the multiplexer isn't running, or the session's gone;
// otherwise, what went wrong.
func (g *gateway) unhad(name string, err error) string {
	name, m, perr := g.opts.pick(name)
	if perr != nil {
		return perr.Error()
	}
	l, lerr := m.Sessions()
	switch {
	case lerr != nil:
		return err.Error()
	case !l.Running:
		return name + " isn't running"
	}
	return "that " + name + " session is gone"
}

// letGo says why the multiplexer let go of the client's session: it
// exited, or it's still there (someone detached the client).
func (g *gateway) letGo() string {
	if l, err := g.opts.Muxes[g.muxed].Sessions(); err != nil || !l.Running {
		return g.about.Name + " exited"
	}
	reason := g.about.Name + " let go of the session"
	if err := g.mux.Err(); err != nil {
		reason += ": " + err.Error()
	}
	return reason
}

// leave closes the multiplexer's client, if there is one, and what the
// client had open of its session.
func (g *gateway) leave() {
	if g.mux == nil {
		return
	}
	g.mux.Close()
	for id := range g.reading {
		g.unread(id)
	}
	g.mux, g.muxed, g.about = nil, "", mux.About{}
	g.snapshot, g.lastState, g.found, g.dirty, g.stale = nil, nil, nil, false, false
	g.panes, g.scanners = map[string]*pane{}, map[string]*desktop.Scanner{}
}

// pick is the multiplexer named, or the default for none, and its name.
func (o Options) pick(name string) (string, mux.Mux, error) {
	if name == "" {
		name = o.Mux
	}
	if m, ok := o.Muxes[name]; ok {
		return name, m, nil
	}
	names := make([]string, 0, len(o.Muxes))
	for n := range o.Muxes {
		names = append(names, n)
	}
	sort.Strings(names)
	return "", nil, fmt.Errorf("no multiplexer %q here (%s)", name, strings.Join(names, ", "))
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

func (g *gateway) loop(ctx context.Context, frames <-chan protocol.Frame, readErr <-chan error) error {
	var stateDue, repaintDue <-chan time.Time
	activity := time.NewTicker(activityEvery)
	defer activity.Stop()
	listing := time.NewTicker(listEvery)
	defer listing.Stop()
	for {
		var events <-chan mux.Event
		if g.mux != nil {
			events = g.mux.Events()
		}
		select {
		case e, ok := <-events:
			if !ok {
				g.detach(g.letGo())
				stateDue, repaintDue = nil, nil
				continue
			}
			if e.Changed {
				g.dirty = true
			} else {
				g.scan(e)
				g.output(e)
			}
		case f := <-frames:
			g.take(f)
		case r := <-g.updates:
			g.readUpdate(r)
		case <-g.resume:
			g.resume = nil
			held := g.held
			g.held = nil
			for _, work := range held {
				g.do(work) // held again behind any that holds once more
			}
		case err := <-readErr:
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		case <-stateDue:
			stateDue = nil
			g.sendState()
		case <-repaintDue:
			repaintDue = nil
			g.repaintStale()
		case <-activity.C:
			g.watchActivity()
		case <-listing.C:
			if g.mux == nil && !g.away {
				g.sessions(false)
			}
		case <-ctx.Done():
			return ctx.Err()
		}
		if g.dirty && stateDue == nil {
			stateDue = time.After(stateDelay)
		}
		if g.stale && repaintDue == nil {
			repaintDue = time.After(repaintEvery)
		}
	}
}

func (g *gateway) send(m protocol.Message) {
	b, err := protocol.AppendJSON(nil, m)
	if errors.Is(err, protocol.ErrTooBig) {
		b, err = protocol.AppendJSON(nil, protocol.Error{Message: "a " + m.Type() + " message too big to send"})
	}
	if err != nil {
		panic(err) // our own message types always encode
	}
	g.out.push(b)
}
