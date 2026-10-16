// Package protocol is the wire format between the gateway and its clients.
// See docs/protocol.md.
package protocol

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

const Version = 1

const (
	FrameJSON byte = 1
	FrameData byte = 2
)

// MaxFrame bounds a frame's length field, so a corrupt stream can't make a
// reader allocate without limit.
const MaxFrame = 16 << 20

type Frame struct {
	Type byte
	Pane string // FrameData only
	Data []byte // FrameJSON: the JSON object; FrameData: the pane bytes
}

type Reader struct {
	r *bufio.Reader
}

func NewReader(r io.Reader) *Reader { return &Reader{r: bufio.NewReaderSize(r, 64<<10)} }

func (r *Reader) Next() (Frame, error) {
	var hdr [5]byte
	if _, err := io.ReadFull(r.r, hdr[:]); err != nil {
		return Frame{}, err
	}
	n := binary.BigEndian.Uint32(hdr[:4])
	if n < 1 || n > MaxFrame {
		return Frame{}, fmt.Errorf("protocol: bad frame length %d", n)
	}
	buf := make([]byte, n-1)
	if _, err := io.ReadFull(r.r, buf); err != nil {
		return Frame{}, unexpected(err)
	}

	f := Frame{Type: hdr[4]}
	switch f.Type {
	case FrameJSON:
		f.Data = buf
	case FrameData:
		if len(buf) < 1 || int(buf[0]) > len(buf)-1 {
			return Frame{}, errors.New("protocol: truncated pane id")
		}
		f.Pane = string(buf[1 : 1+buf[0]])
		f.Data = buf[1+buf[0]:]
	default:
		return Frame{}, fmt.Errorf("protocol: unknown frame type %d", f.Type)
	}
	return f, nil
}

func unexpected(err error) error {
	if err == io.EOF {
		return io.ErrUnexpectedEOF
	}
	return err
}

// AppendData appends a pane data frame.
func AppendData(dst []byte, pane string, data []byte) []byte {
	if len(pane) > 255 {
		panic("protocol: pane id longer than 255 bytes")
	}
	dst = binary.BigEndian.AppendUint32(dst, uint32(2+len(pane)+len(data)))
	dst = append(dst, FrameData, byte(len(pane)))
	dst = append(dst, pane...)
	return append(dst, data...)
}

// AppendJSON appends a JSON frame holding m with its "t" field.
func AppendJSON(dst []byte, m Message) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(m); err != nil {
		return dst, err
	}
	body := bytes.TrimSuffix(buf.Bytes(), []byte("\n"))
	if len(body) < 2 || body[0] != '{' {
		return dst, fmt.Errorf("protocol: %T does not encode to an object", m)
	}

	head := `{"t":"` + m.Type() + `"`
	if len(body) > 2 {
		head += ","
	}
	n := 1 + len(head) + len(body) - 1
	dst = binary.BigEndian.AppendUint32(dst, uint32(n))
	dst = append(dst, FrameJSON)
	dst = append(dst, head...)
	return append(dst, body[1:]...), nil
}
