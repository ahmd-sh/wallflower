package rex

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/ahmd-sh/wallflower/internal/ws"
)

// What a block's stream says, besides what its program wrote.
type streamer interface {
	output(block string, data []byte)
	resized(block string, cols, rows int)
	alt(block string, on bool)
	ended(block string, err error)
}

// A stream is a terminal block's output, from its own data connection.
type stream struct {
	block string
	ws    *ws.Conn
}

// openStream starts a block's stream: Ghostty's snapshot of the terminal
// (skipped: the repaint is VT, see Screen), then everything its program
// writes, with Rex's messages (sizes) where they're safe.
func openStream(c *control, session, block string, to streamer) (*stream, error) {
	var t struct {
		Ticket string `json:"ticket"`
	}
	if err := c.call("data.connect", map[string]string{
		"session_id": session, "block_id": block, "data_ref_id": "terminal", "subprotocol": "rex.data.terminal.v1",
	}, &t); err != nil {
		return nil, err
	}
	ticket, err := base64.RawURLEncoding.DecodeString(t.Ticket)
	if err != nil || len(ticket) != 16 {
		return nil, fmt.Errorf("rex: bad ticket %q", t.Ticket)
	}
	conn, err := connect(c.endpoint)
	if err != nil {
		return nil, err
	}
	w, err := ws.Client(conn, "localhost", "/api/data", "rex.data.terminal.v1")
	if err != nil {
		conn.Close()
		return nil, err
	}
	if err := w.Write(ws.Binary, append([]byte{0x01}, ticket...)); err != nil {
		conn.Close()
		return nil, err
	}
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, welcome, err := w.Read()
	conn.SetReadDeadline(time.Time{})
	switch {
	case err != nil:
		conn.Close()
		return nil, fmt.Errorf("rex: data connection: %w", err)
	case len(welcome) == 2 && welcome[0] == 0x82:
		conn.Close()
		return nil, fmt.Errorf("rex: data connection refused (reason %#02x)", welcome[1])
	case len(welcome) != 32 || welcome[0] != 0x81:
		conn.Close()
		return nil, errors.New("rex: unexpected data welcome")
	}
	nonce := binary.LittleEndian.Uint64(welcome[24:32])
	s := &stream{block: block, ws: w}
	go s.run(newFilter(nonce), to)
	return s, nil
}

func (s *stream) run(f *filter, to streamer) {
	for {
		_, data, err := s.ws.Read()
		if err != nil {
			var ce *ws.CloseError
			if errors.As(err, &ce) && (ce.Code == 1000 || ce.Code == 4000) {
				err = nil // it ended, or its block closed
			}
			to.ended(s.block, err)
			return
		}
		f.feed(data,
			func(out []byte) { to.output(s.block, out) },
			func(m message) {
				if m.Type == "resize" && m.Cols > 0 && m.Rows > 0 {
					to.resized(s.block, m.Cols, m.Rows)
				}
			},
			func(on bool) { to.alt(s.block, on) })
	}
}

func (s *stream) close() { s.ws.Close() }

// A message is one of Rex's own in a stream: APC rex1;<nonce>;<json> ST.
type message struct {
	Type string `json:"type"`
	Cols int    `json:"cols"`
	Rows int    `json:"rows"`
}

const (
	maxMessage = 64 << 10
	finishTag  = 6 // the snapshot's last record
)

// A filter takes a stream apart as it comes: the snapshot it starts with,
// the program's output, Rex's messages in it, and the program switching
// screens.
type filter struct {
	// The snapshot: its envelope, then records (tag, length, CRC, payload).
	snapshot bool
	records  bool   // past the envelope
	head     []byte // of the envelope or a record, until it's whole
	skip     uint32 // of a record's payload
	finished bool   // the record skipped is the last

	prefix []byte // a Rex message's start, with the stream's nonce
	held   []byte // what may be one starting
	inMsg  bool
	msg    []byte
	esc    bool // in a message, after ESC

	modes modes
}

func newFilter(nonce uint64) *filter {
	return &filter{snapshot: true, prefix: []byte("\x1b_rex1;" + strconv.FormatUint(nonce, 10) + ";")}
}

func (f *filter) feed(b []byte, out func([]byte), msg func(message), alt func(bool)) {
	if f.snapshot {
		if b = f.skipSnapshot(b); f.snapshot {
			return
		}
	}
	var o []byte // the program's output in b
	for i := 0; i < len(b); i++ {
		c := b[i]
		switch {
		case f.inMsg:
			switch {
			case f.esc && c == '\\':
				f.inMsg, f.esc = false, false
				var m message
				if json.Unmarshal(f.msg, &m) == nil {
					msg(m)
				}
				f.msg = f.msg[:0]
			case f.esc:
				f.esc = false
				f.msg = append(f.msg, 0x1b, c)
			case c == 0x1b:
				f.esc = true
			case len(f.msg) < maxMessage:
				f.msg = append(f.msg, c)
			}
		case len(f.held) > 0:
			if c == f.prefix[len(f.held)] {
				if f.held = append(f.held, c); len(f.held) == len(f.prefix) {
					f.held, f.inMsg = f.held[:0], true
				}
				continue
			}
			// Not Rex's: what was held is output, and c starts afresh.
			o = append(o, f.held...)
			f.held = f.held[:0]
			i--
		case c == 0x1b:
			f.held = append(f.held, c)
		default:
			n := bytes.IndexByte(b[i:], 0x1b)
			if n < 0 {
				n = len(b) - i
			}
			o = append(o, b[i:i+n]...)
			i += n - 1
		}
	}
	if len(o) > 0 {
		f.modes.scan(o, alt)
		out(o)
	}
}

// skipSnapshot passes over the snapshot, and says what's after it. A stream
// that doesn't start with one has none.
func (f *filter) skipSnapshot(b []byte) []byte {
	for len(b) > 0 {
		if f.skip > 0 {
			n := min(uint32(len(b)), f.skip)
			b, f.skip = b[n:], f.skip-n
			if f.skip == 0 && f.finished {
				f.snapshot = false
			}
			if !f.snapshot {
				return b
			}
			continue
		}
		n := min(10-len(f.head), len(b)) // the envelope, and each record's header, are 10 bytes
		f.head = append(f.head, b[:n]...)
		b = b[n:]
		if len(f.head) < 10 {
			continue
		}
		head := f.head
		f.head = f.head[:0]
		if !f.records {
			if !bytes.HasPrefix(head, []byte("GHOSTSNP")) {
				f.snapshot = false
				return append(append([]byte(nil), head...), b...)
			}
			f.records = true
			continue
		}
		f.skip = binary.LittleEndian.Uint32(head[2:6])
		f.finished = binary.LittleEndian.Uint16(head[0:2]) == finishTag
		if f.skip == 0 && f.finished {
			f.snapshot = false
			return b
		}
	}
	return b
}

// modes follows a program switching to the alternate screen and back
// (DECSET and DECRST 1049, 1047 and 47) in what it writes.
type modes struct {
	state   int // 0 ground, 1 after ESC, 2 in a CSI
	private bool
	params  []byte
}

func (m *modes) scan(b []byte, alt func(bool)) {
	for _, c := range b {
		switch m.state {
		case 0:
			if c == 0x1b {
				m.state = 1
			}
		case 1:
			m.state = 0
			if c == '[' {
				m.state, m.private, m.params = 2, false, m.params[:0]
			}
		case 2:
			switch {
			case c == '?' && len(m.params) == 0:
				m.private = true
			case c >= '0' && c <= '9' || c == ';':
				if len(m.params) < 64 {
					m.params = append(m.params, c)
				}
			case c >= 0x40 && c <= 0x7e:
				if m.private && (c == 'h' || c == 'l') {
					for _, p := range bytes.Split(m.params, []byte(";")) {
						if s := string(p); s == "1049" || s == "1047" || s == "47" {
							alt(c == 'h')
						}
					}
				}
				m.state = 0
			case c == 0x1b:
				m.state = 1
			case c < 0x20:
				m.state = 0
			}
		}
	}
}
