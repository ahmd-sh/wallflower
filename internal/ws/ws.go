// Package ws is a WebSocket client (RFC 6455), as much of one as wallflower
// needs: text and binary messages over a connection it's handed, no
// extensions.
package ws

import (
	"bufio"
	"crypto/rand"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
)

// Message types.
const (
	Text   = 1
	Binary = 2

	continuation = 0
	closing      = 8
	ping         = 9
	pong         = 10
)

// MaxMessage is the most a message may hold, joined from its frames.
const MaxMessage = 16 << 20

// CloseError is the other side closing the connection, with its status and
// reason.
type CloseError struct {
	Code   int
	Reason string
}

func (e *CloseError) Error() string {
	return fmt.Sprintf("websocket closed: %d %s", e.Code, e.Reason)
}

// A Conn is one WebSocket. Read from one goroutine; Write from any.
type Conn struct {
	c  net.Conn
	br *bufio.Reader

	wmu    sync.Mutex
	closed bool
}

// Client opens a WebSocket at path over c, asking for subprotocol, which the
// server must take.
func Client(c net.Conn, host, path, subprotocol string) (*Conn, error) {
	var nonce [16]byte
	rand.Read(nonce[:])
	key := base64.StdEncoding.EncodeToString(nonce[:])
	req, err := http.NewRequest("GET", "http://"+host+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Upgrade", "websocket")
	req.Header.Set("Connection", "Upgrade")
	req.Header.Set("Sec-WebSocket-Key", key)
	req.Header.Set("Sec-WebSocket-Version", "13")
	req.Header.Set("Sec-WebSocket-Protocol", subprotocol)
	if err := req.Write(c); err != nil {
		return nil, err
	}
	br := bufio.NewReaderSize(c, 64<<10)
	resp, err := http.ReadResponse(br, req)
	if err != nil {
		return nil, err
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusSwitchingProtocols {
		return nil, fmt.Errorf("websocket: %s", resp.Status)
	}
	sum := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	if resp.Header.Get("Sec-WebSocket-Accept") != base64.StdEncoding.EncodeToString(sum[:]) {
		return nil, errors.New("websocket: bad accept")
	}
	if got := resp.Header.Get("Sec-WebSocket-Protocol"); !strings.EqualFold(got, subprotocol) {
		return nil, fmt.Errorf("websocket: server chose subprotocol %q, not %q", got, subprotocol)
	}
	return &Conn{c: c, br: br}, nil
}

// Read returns the next message, answering pings on the way. The other side
// closing comes as a *CloseError.
func (w *Conn) Read() (typ int, data []byte, err error) {
	typ = -1
	for {
		fin, op, payload, err := w.frame()
		if err != nil {
			return 0, nil, err
		}
		switch op {
		case ping:
			if err := w.write(pong, payload); err != nil {
				return 0, nil, err
			}
			continue
		case pong:
			continue
		case closing:
			ce := &CloseError{Code: 1005}
			if len(payload) >= 2 {
				ce.Code, ce.Reason = int(binary.BigEndian.Uint16(payload)), string(payload[2:])
			}
			w.write(closing, payload[:min(len(payload), 2)])
			w.c.Close()
			return 0, nil, ce
		case continuation:
			if typ < 0 {
				return 0, nil, errors.New("websocket: continuation without a message")
			}
		case Text, Binary:
			if typ >= 0 {
				return 0, nil, errors.New("websocket: new message inside another")
			}
			typ = int(op)
		default:
			return 0, nil, fmt.Errorf("websocket: opcode %d", op)
		}
		if len(data)+len(payload) > MaxMessage {
			return 0, nil, errors.New("websocket: message too big")
		}
		data = append(data, payload...)
		if fin {
			return typ, data, nil
		}
	}
}

func (w *Conn) frame() (fin bool, op byte, payload []byte, err error) {
	var head [2]byte
	if _, err = io.ReadFull(w.br, head[:]); err != nil {
		return
	}
	fin, op = head[0]&0x80 != 0, head[0]&0x0f
	n := uint64(head[1] & 0x7f)
	switch n {
	case 126:
		var b [2]byte
		if _, err = io.ReadFull(w.br, b[:]); err != nil {
			return
		}
		n = uint64(binary.BigEndian.Uint16(b[:]))
	case 127:
		var b [8]byte
		if _, err = io.ReadFull(w.br, b[:]); err != nil {
			return
		}
		n = binary.BigEndian.Uint64(b[:])
	}
	if n > MaxMessage {
		return false, 0, nil, errors.New("websocket: frame too big")
	}
	var mask [4]byte
	masked := head[1]&0x80 != 0
	if masked {
		if _, err = io.ReadFull(w.br, mask[:]); err != nil {
			return
		}
	}
	payload = make([]byte, n)
	if _, err = io.ReadFull(w.br, payload); err != nil {
		return
	}
	if masked {
		for i := range payload {
			payload[i] ^= mask[i%4]
		}
	}
	return
}

// Write sends one message of type typ.
func (w *Conn) Write(typ int, data []byte) error { return w.write(byte(typ), data) }

func (w *Conn) write(op byte, data []byte) error {
	w.wmu.Lock()
	defer w.wmu.Unlock()
	if w.closed {
		return net.ErrClosed
	}
	if op == closing {
		w.closed = true
	}
	b := make([]byte, 0, 14+len(data))
	b = append(b, 0x80|op)
	switch n := len(data); {
	case n < 126:
		b = append(b, 0x80|byte(n))
	case n < 1<<16:
		b = append(b, 0x80|126)
		b = binary.BigEndian.AppendUint16(b, uint16(n))
	default:
		b = append(b, 0x80|127)
		b = binary.BigEndian.AppendUint64(b, uint64(n))
	}
	var mask [4]byte
	rand.Read(mask[:])
	b = append(b, mask[:]...)
	for i, c := range data {
		b = append(b, c^mask[i%4])
	}
	_, err := w.c.Write(b)
	return err
}

// Close says goodbye with status 1000 and closes the connection.
func (w *Conn) Close() error {
	w.write(closing, []byte{0x03, 0xe8})
	return w.c.Close()
}
