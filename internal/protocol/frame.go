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

const Version = 5

const (
	FrameJSON    byte = 1
	FrameData    byte = 2 // a pane's bytes: output from the gateway, typed by the client
	FrameUpload  byte = 3 // the next of an upload's bytes
	FramePicture byte = 4 // a picture entry's bytes, asked for with Picture
)

// MaxFrame bounds a frame's length field, so a corrupt stream can't make a
// reader allocate without limit.
const MaxFrame = 16 << 20

// ErrTooBig is a message too big for a frame, which no reader would take.
var ErrTooBig = errors.New("protocol: message too big for a frame")

type Frame struct {
	Type byte
	ID   string // FrameData: the pane; FrameUpload: the upload; FramePicture: the entry
	Data []byte // FrameJSON: the JSON object; otherwise the bytes
}

type Reader struct {
	r *bufio.Reader
}

func NewReader(r io.Reader) *Reader { return &Reader{r: bufio.NewReaderSize(r, 64<<10)} }

// Next reads the next frame, skipping any of a type newer than this reader.
func (r *Reader) Next() (Frame, error) {
	for {
		f, known, err := r.next()
		if err != nil || known {
			return f, err
		}
	}
}

func (r *Reader) next() (f Frame, known bool, err error) {
	var hdr [5]byte
	if _, err := io.ReadFull(r.r, hdr[:]); err != nil {
		return Frame{}, false, err
	}
	n := binary.BigEndian.Uint32(hdr[:4])
	if n < 1 || n > MaxFrame {
		return Frame{}, false, fmt.Errorf("protocol: bad frame length %d", n)
	}
	buf := make([]byte, n-1)
	if _, err := io.ReadFull(r.r, buf); err != nil {
		return Frame{}, false, unexpected(err)
	}

	f = Frame{Type: hdr[4]}
	switch f.Type {
	case FrameJSON:
		f.Data = buf
	case FrameData, FrameUpload, FramePicture:
		if len(buf) < 1 || int(buf[0]) > len(buf)-1 {
			return Frame{}, false, errors.New("protocol: truncated id")
		}
		f.ID = string(buf[1 : 1+buf[0]])
		f.Data = buf[1+buf[0]:]
	default:
		return Frame{}, false, nil
	}
	return f, true, nil
}

func unexpected(err error) error {
	if err == io.EOF {
		return io.ErrUnexpectedEOF
	}
	return err
}

// AppendData appends a pane data frame.
func AppendData(dst []byte, pane string, data []byte) []byte {
	return appendBytes(dst, FrameData, pane, data)
}

// AppendUpload appends an upload frame: the next of an upload's bytes.
func AppendUpload(dst []byte, upload string, data []byte) []byte {
	return appendBytes(dst, FrameUpload, upload, data)
}

// AppendPicture appends a picture frame: a picture entry's bytes, none if
// they're gone.
func AppendPicture(dst []byte, entry string, data []byte) []byte {
	return appendBytes(dst, FramePicture, entry, data)
}

func appendBytes(dst []byte, typ byte, id string, data []byte) []byte {
	if len(id) > 255 {
		panic("protocol: id longer than 255 bytes")
	}
	dst = binary.BigEndian.AppendUint32(dst, uint32(2+len(id)+len(data)))
	dst = append(dst, typ, byte(len(id)))
	dst = append(dst, id...)
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
	if n > MaxFrame {
		return dst, ErrTooBig
	}
	dst = binary.BigEndian.AppendUint32(dst, uint32(n))
	dst = append(dst, FrameJSON)
	dst = append(dst, head...)
	return append(dst, body[1:]...), nil
}
