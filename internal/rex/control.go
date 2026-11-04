// Package rex is Rex, superlogical's multiplexer, as wallflower's
// multiplexer (see package mux), through its server API: one control
// connection for methods and events, and a data connection streaming each
// terminal's output (see the server's own /llms.txt).
package rex

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ahmd-sh/wallflower/internal/ws"
)

const apiRevision = 10

// Error is a method the server refused.
type Error struct {
	Method  string
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string { return "rex: " + e.Method + ": " + e.Message + " (" + e.Code + ")" }

// An event is one the server sent: its type, and its fields. A session's
// event comes with the session it's from; a block's, with the block.
type event struct {
	Type    string
	Session string
	Block   string // block_event: the block, and its creator's Name for it
	Name    string
	Payload json.RawMessage
}

// control is a control connection (rex.control.v1). call may be called from
// any goroutine.
type control struct {
	endpoint string
	ws       *ws.Conn

	mu      sync.Mutex
	pending map[string]chan response
	next    int
	err     error

	events chan event // unbuffered; see pump
	queue  []event
	cond   *sync.Cond
	done   chan struct{}
}

type response struct {
	payload json.RawMessage
	err     error
}

// dial opens a control connection to the server at endpoint: a Unix
// socket's path, or a unix:// URL.
func dial(endpoint string) (*control, error) {
	conn, err := connect(endpoint)
	if err != nil {
		return nil, err
	}
	w, err := ws.Client(conn, "localhost", "/api/control", "rex.control.v1")
	if err != nil {
		conn.Close()
		return nil, err
	}
	hello, _ := json.Marshal(map[string]any{
		"type": "hello", "api_revision": apiRevision, "api_min_revision": apiRevision,
		"client": map[string]string{"kind": "app", "name": "wallflower"},
	})
	if err := w.Write(ws.Text, hello); err != nil {
		conn.Close()
		return nil, err
	}
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, data, err := w.Read()
	conn.SetReadDeadline(time.Time{})
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("rex: %w", err)
	}
	var welcome struct {
		Type, Protocol string
	}
	if json.Unmarshal(data, &welcome) != nil || welcome.Type != "welcome" || welcome.Protocol != "rex.control.v1" {
		conn.Close()
		return nil, fmt.Errorf("rex: unexpected welcome %.200s", data)
	}
	c := &control{endpoint: endpoint, ws: w, pending: map[string]chan response{}, events: make(chan event), done: make(chan struct{})}
	c.cond = sync.NewCond(&c.mu)
	go c.read()
	go c.pump()
	return c, nil
}

// connect opens the stream a WebSocket to endpoint goes over.
func connect(endpoint string) (net.Conn, error) {
	path := endpoint
	if u, err := url.Parse(endpoint); err == nil && u.Scheme == "unix" {
		path = u.Path
	}
	if !strings.HasPrefix(path, "/") {
		return nil, fmt.Errorf("rex: endpoint %q isn't a Unix socket", endpoint)
	}
	conn, err := net.Dial("unix", path)
	if err != nil {
		return nil, fmt.Errorf("rex: no server at %s: %w", path, err)
	}
	return conn, nil
}

// call calls a method and decodes its result into result, if it's not nil.
func (c *control) call(method string, payload, result any) error {
	c.mu.Lock()
	if c.err != nil {
		c.mu.Unlock()
		return c.err
	}
	c.next++
	id := strconv.Itoa(c.next)
	reply := make(chan response, 1)
	c.pending[id] = reply
	c.mu.Unlock()

	msg, err := json.Marshal(map[string]any{"type": "request", "id": id, "method": method, "payload": payload})
	if err != nil {
		return err
	}
	if err := c.ws.Write(ws.Text, msg); err != nil {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
		return err
	}
	r := <-reply
	if r.err != nil {
		var e *Error
		if errors.As(r.err, &e) {
			e.Method = method
		}
		return r.err
	}
	if result == nil {
		return nil
	}
	return json.Unmarshal(r.payload, result)
}

// callBlock calls one of a block's own methods, as its creator names it
// (terminal), with its arguments.
func (c *control) callBlock(session, block, method string, args, result any) error {
	payload := map[string]any{"session_id": session, "block_id": block}
	if args != nil {
		payload["args"] = args
	}
	return c.call("com.superlogical.terminal."+method, payload, result)
}

func (c *control) read() {
	var err error
	for {
		var data []byte
		if _, data, err = c.ws.Read(); err != nil {
			break
		}
		var m struct {
			Type    string          `json:"type"`
			ID      string          `json:"id"`
			Payload json.RawMessage `json:"payload"`
			Error   *Error          `json:"error"`
		}
		if json.Unmarshal(data, &m) != nil {
			continue
		}
		switch m.Type {
		case "response":
			c.mu.Lock()
			reply := c.pending[m.ID]
			delete(c.pending, m.ID)
			c.mu.Unlock()
			if reply != nil {
				r := response{payload: m.Payload}
				if m.Error != nil {
					r.err = m.Error
				}
				reply <- r
			}
		case "event":
			if e, ok := parseEvent(m.Payload); ok {
				c.mu.Lock()
				c.queue = append(c.queue, e)
				c.mu.Unlock()
				c.cond.Signal()
			}
		}
	}
	c.mu.Lock()
	c.err = fmt.Errorf("rex: connection lost: %w", err)
	var ce *ws.CloseError
	if errors.As(err, &ce) && ce.Code == 1000 {
		c.err = errors.New("rex: connection closed")
	}
	for id, reply := range c.pending {
		reply <- response{err: c.err}
		delete(c.pending, id)
	}
	c.mu.Unlock()
	c.cond.Signal()
}

// pump hands events on in order without ever holding up read: a caller
// handling one may make calls of its own.
func (c *control) pump() {
	defer close(c.events)
	for {
		c.mu.Lock()
		for len(c.queue) == 0 && c.err == nil {
			c.cond.Wait()
		}
		if len(c.queue) == 0 {
			c.mu.Unlock()
			return
		}
		e := c.queue[0]
		c.queue = c.queue[1:]
		c.mu.Unlock()
		select {
		case c.events <- e:
		case <-c.done:
		}
	}
}

func parseEvent(data json.RawMessage) (event, bool) {
	var outer struct {
		Type    string          `json:"type"`
		Payload json.RawMessage `json:"payload"`
	}
	if json.Unmarshal(data, &outer) != nil {
		return event{}, false
	}
	e := event{Type: outer.Type, Payload: outer.Payload}
	if outer.Type != "session_event" {
		return e, true
	}
	var se struct {
		Session string `json:"session_id"`
		Event   struct {
			Type    string          `json:"type"`
			Payload json.RawMessage `json:"payload"`
		} `json:"event"`
	}
	if json.Unmarshal(outer.Payload, &se) != nil {
		return event{}, false
	}
	e = event{Type: se.Event.Type, Session: se.Session, Payload: se.Event.Payload}
	if e.Type == "block_event" {
		var be struct {
			Block   string          `json:"block_id"`
			Name    string          `json:"name"`
			Payload json.RawMessage `json:"payload"`
		}
		if json.Unmarshal(e.Payload, &be) != nil {
			return event{}, false
		}
		e.Block, e.Name, e.Payload = be.Block, be.Name, be.Payload
	}
	return e, true
}

// close ends the connection, and the client with it: blocks it owned pass on.
func (c *control) close() {
	select {
	case <-c.done:
	default:
		close(c.done)
	}
	c.ws.Close()
}
