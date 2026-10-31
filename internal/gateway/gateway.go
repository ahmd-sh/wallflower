// Package gateway serves one client over the wallflower protocol, backed by a
// tmux control-mode client. It keeps no state that tmux doesn't already
// hold; everything it sends can be rebuilt from tmux.
//
// One loop owns all of it: the client's messages (dispatch.go) drive tmux
// (input.go, windows.go), and tmux's notifications become the client's pane
// streams (panes.go) and session state (state.go), with read views
// (readview.go) and uploads alongside.
package gateway

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/ahmd-sh/wallflower/internal/desktop"
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/readview"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

type Options struct {
	Socket  string            // tmux socket, see tmux.Options
	Session string            // default session when the client doesn't ask for one
	Readers []readview.Source // programs whose panes have read views
	Uploads string            // where files the client sends are saved; UploadDir() if empty
}

type gateway struct {
	ctx        context.Context
	tmux       *tmux.Client
	out        *outbox
	client     string // this control client's name, for keys sent through bindings
	target     string // the session the client asked for; its own session is grouped with it
	cols, rows int
	exitReason string

	snapshot  *tmux.Session // the last
	lastState []byte
	dirty     bool
	programs  map[string]string         // tty and versioned command -> the name it was run as
	found     map[string]readview.Found // panes read, and what their programs are doing, as last sent

	panes    map[string]*pane            // panes the client has open
	scanners map[string]*desktop.Scanner // every pane's output, for notifications
	stale    bool                        // some are, so repaintStale is due
	zoomed   map[string]bool             // windows the client zoomed -> zoomed before

	held   []func() error   // work waiting for resume, in order: the client's frames, and the rest of what one asked
	resume <-chan time.Time // while a pane settles; see hold

	sources []readview.Source
	reading map[string]reading // panes whose read views the client has on
	updates chan readUpdate
	gen     int // readers started

	uploads uploads
}

// Serve runs one client session over in and out until the client
// disconnects or tmux exits.
func Serve(ctx context.Context, in io.Reader, out io.Writer, opts Options) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	g := &gateway{
		ctx: ctx, out: newOutbox(out), programs: map[string]string{},
		panes: map[string]*pane{}, scanners: map[string]*desktop.Scanner{}, zoomed: map[string]bool{},
		sources: opts.Readers, reading: map[string]reading{}, updates: make(chan readUpdate, 16),
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
	g.cols, g.rows = hello.Cols, hello.Rows
	session := hello.Session
	if session == "" {
		session = opts.Session
	}
	if g.target, err = tmux.ResolveSession(opts.Socket, session); err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}

	// The client attaches to the session itself, as the desktop does, so
	// both are one session: a window switched to on one is switched to on
	// the other. Not tied to ctx: cleanup still needs tmux after a signal.
	flags := []string{"new-layouts", "no-detach-on-destroy"}
	if hello.Away {
		flags = append(flags, "ignore-size")
	}
	g.tmux, err = tmux.Start(context.Background(), tmux.Options{Socket: opts.Socket, Session: g.target, Flags: flags})
	if err != nil {
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	defer g.cleanup()

	version, client, err := g.tmux.Configure(g.cols, g.rows)
	if err != nil {
		err = g.tmuxErr(err)
		g.send(protocol.Bye{Reason: err.Error()})
		return err
	}
	g.client = client
	g.send(protocol.ServerHello{V: protocol.Version, Tmux: version, Uploads: g.uploads.dir})
	g.sendState()

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
		case n, ok := <-g.tmux.Events():
			if !ok {
				reason := "tmux exited"
				if g.exitReason != "" {
					reason += ": " + g.exitReason
				}
				g.send(protocol.Bye{Reason: reason})
				return g.tmux.Err()
			}
			g.notify(n)
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

func (g *gateway) notify(n tmux.Notification) {
	switch n.Type {
	case "output":
		g.scan(n)
		g.output(n)
	case "layout-change", "window-add", "window-close", "window-renamed", "window-pane-changed",
		"session-window-changed", "session-changed", "session-renamed", "subscription-changed":
		g.dirty = true
	case "exit":
		g.exitReason = n.Value
	}
}

// cleanup puts back the zoom state of windows the client zoomed, then
// detaches.
func (g *gateway) cleanup() {
	g.tmux.SetZoom(g.zoomed)
	g.tmux.Close()
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

// tmuxErr prefers tmux's own exit message (no server, no such session) over
// the generic closed error.
func (g *gateway) tmuxErr(err error) error {
	if errors.Is(err, tmux.ErrClosed) {
		if exitErr := g.tmux.Err(); exitErr != nil {
			return exitErr
		}
		if g.exitReason != "" {
			return errors.New(g.exitReason)
		}
	}
	return err
}
