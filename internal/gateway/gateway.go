// Package gateway serves one client over the wallflower protocol, backed by
// a client of the multiplexer the session is in (see package mux). It keeps
// no state that the multiplexer doesn't already hold; everything it sends
// can be rebuilt from it.
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
	Session  string             // the session in that one when it doesn't ask, or what it asks for is gone
	Programs []program.Program  // the programs it knows, by the panes that run them; first match wins
	Uploads  string             // where files the client sends are saved; UploadDir() if empty
}

type gateway struct {
	ctx   context.Context
	opts  Options
	mux   mux.Client
	muxed string // the name of the multiplexer it's attached to
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
}

// Serve runs one client session over in and out until the client
// disconnects or the multiplexer detaches it.
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
	defer g.uploads.close()
	defer g.out.close(2 * time.Second)

	r := protocol.NewReader(in)
	hello, err := readHello(r)
	if err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	g.cols, g.rows, g.away = hello.Cols, hello.Rows, hello.Away
	name, c, err := g.attach(hello.Mux, hello.Session)
	if err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	defer func() { g.mux.Close() }()
	g.attached(name, c)

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
// empty) to the session, or the one the multiplexer would pick if that's
// gone, at the client's size.
func (g *gateway) attach(name, session string) (string, mux.Client, error) {
	name, m, err := g.opts.pick(name)
	if err != nil {
		return "", nil, err
	}
	fallback := ""
	if name == g.opts.Mux {
		fallback = g.opts.Session
	}
	if session == "" {
		session = fallback
	}
	at := func(session string) (mux.Client, error) {
		return m.Attach(mux.Attach{Session: session, Cols: g.cols, Rows: g.rows, Away: g.away})
	}
	c, err := at(session)
	if err != nil && session != fallback {
		c, err = at(fallback)
	}
	return name, c, err
}

// attached makes c the client's, saying hello and sending its state.
func (g *gateway) attached(name string, c mux.Client) {
	g.mux, g.muxed, g.about = c, name, c.About()
	g.send(protocol.ServerHello{
		V:       protocol.Version,
		Mux:     protocol.Mux{Name: g.about.Name, Version: g.about.Version, Can: g.about.Can},
		Uploads: g.uploads.dir,
	})
	g.sendState()
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
	for {
		select {
		case e, ok := <-g.mux.Events():
			if !ok {
				err := g.mux.Err()
				reason := g.about.Name + " exited"
				if err != nil {
					reason += ": " + err.Error()
				}
				g.send(protocol.Bye{Reason: reason})
				return err
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
