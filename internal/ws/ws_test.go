package ws

import (
	"bufio"
	"bytes"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// serve runs a WebSocket server that hands each connection to talk, frames
// read and written raw.
func serve(t *testing.T, talk func(rw *bufio.ReadWriter)) string {
	t.Helper()
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sum := sha1.Sum([]byte(r.Header.Get("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
		c, rw, err := w.(http.Hijacker).Hijack()
		if err != nil {
			t.Error(err)
			return
		}
		defer c.Close()
		rw.WriteString("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
			"Sec-WebSocket-Accept: " + base64.StdEncoding.EncodeToString(sum[:]) + "\r\n" +
			"Sec-WebSocket-Protocol: " + r.Header.Get("Sec-WebSocket-Protocol") + "\r\n\r\n")
		rw.Flush()
		talk(rw)
	}))
	t.Cleanup(s.Close)
	return strings.TrimPrefix(s.URL, "http://")
}

func frame(fin bool, op byte, data []byte) []byte {
	b := []byte{op}
	if fin {
		b[0] |= 0x80
	}
	switch {
	case len(data) < 126:
		b = append(b, byte(len(data)))
	default:
		b = append(b, 126)
		b = binary.BigEndian.AppendUint16(b, uint16(len(data)))
	}
	return append(b, data...)
}

// readFrame reads a client's frame, which must be masked.
func readFrame(t *testing.T, r io.Reader) (byte, []byte) {
	var head [2]byte
	if _, err := io.ReadFull(r, head[:]); err != nil {
		t.Fatal(err)
	}
	if head[1]&0x80 == 0 {
		t.Fatal("client frame not masked")
	}
	n := int(head[1] & 0x7f)
	if n == 126 {
		var b [2]byte
		io.ReadFull(r, b[:])
		n = int(binary.BigEndian.Uint16(b[:]))
	}
	var mask [4]byte
	io.ReadFull(r, mask[:])
	data := make([]byte, n)
	io.ReadFull(r, data)
	for i := range data {
		data[i] ^= mask[i%4]
	}
	return head[0] & 0x0f, data
}

func dial(t *testing.T, addr string) *Conn {
	t.Helper()
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	w, err := Client(c, "localhost", "/api", "test.v1")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return w
}

func TestMessages(t *testing.T) {
	done := make(chan struct{})
	addr := serve(t, func(rw *bufio.ReadWriter) {
		defer close(done)
		if op, data := readFrame(t, rw); op != Text || string(data) != `{"type":"hello"}` {
			t.Errorf("server got %d %q", op, data)
		}
		big := bytes.Repeat([]byte("x"), 300)
		rw.Write(frame(true, Text, []byte("welcome")))
		// A message in two frames, a ping between them.
		rw.Write(frame(false, Binary, big[:100]))
		rw.Write(frame(true, ping, []byte("are you there")))
		rw.Write(frame(true, continuation, big[100:]))
		rw.Flush()
		if op, data := readFrame(t, rw); op != pong || string(data) != "are you there" {
			t.Errorf("pong = %d %q", op, data)
		}
		rw.Write(frame(true, closing, append([]byte{0x03, 0xe8}, "bye"...)))
		rw.Flush()
		readFrame(t, rw) // the client's close
	})
	w := dial(t, addr)
	if err := w.Write(Text, []byte(`{"type":"hello"}`)); err != nil {
		t.Fatal(err)
	}
	if typ, data, err := w.Read(); err != nil || typ != Text || string(data) != "welcome" {
		t.Fatalf("read %d %q %v", typ, data, err)
	}
	if typ, data, err := w.Read(); err != nil || typ != Binary || len(data) != 300 {
		t.Fatalf("read %d %d bytes %v", typ, len(data), err)
	}
	_, _, err := w.Read()
	var ce *CloseError
	if !errors.As(err, &ce) || ce.Code != 1000 || ce.Reason != "bye" {
		t.Fatalf("close = %v", err)
	}
	<-done
}

func TestRefusesAnotherSubprotocol(t *testing.T) {
	s := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sum := sha1.Sum([]byte(r.Header.Get("Sec-WebSocket-Key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
		w.Header().Set("Upgrade", "websocket")
		w.Header().Set("Connection", "Upgrade")
		w.Header().Set("Sec-WebSocket-Accept", base64.StdEncoding.EncodeToString(sum[:]))
		w.Header().Set("Sec-WebSocket-Protocol", "other.v1")
		w.WriteHeader(http.StatusSwitchingProtocols)
	}))
	defer s.Close()
	c, err := net.Dial("tcp", strings.TrimPrefix(s.URL, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := Client(c, "localhost", "/api", "test.v1"); err == nil {
		t.Fatal("took a connection speaking another subprotocol")
	}
}
