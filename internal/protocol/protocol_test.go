package protocol

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"reflect"
	"strings"
	"testing"
)

func TestRoundTrip(t *testing.T) {
	var buf []byte
	var err error
	buf, err = AppendJSON(buf, ClientHello{V: Version, Cols: 56, Rows: 40})
	if err != nil {
		t.Fatal(err)
	}
	buf = AppendData(buf, "%12", []byte("ls\r\x1b[A ✓"))
	buf, _ = AppendJSON(buf, Keys{Pane: "%3", Keys: []string{"C-c", "Up"}})
	buf, _ = AppendJSON(buf, Bye{})
	buf = AppendData(buf, "%1", nil)
	buf = AppendUpload(buf, "u1", []byte{0, 0xff, 'j'})

	r := NewReader(bytes.NewReader(buf))
	want := []any{
		&ClientHello{V: 1, Cols: 56, Rows: 40},
		Frame{Type: FrameData, ID: "%12", Data: []byte("ls\r\x1b[A ✓")},
		&Keys{Pane: "%3", Keys: []string{"C-c", "Up"}},
		nil, // Bye: server message, checked below
		Frame{Type: FrameData, ID: "%1", Data: []byte{}},
		Frame{Type: FrameUpload, ID: "u1", Data: []byte{0, 0xff, 'j'}},
	}
	for i, w := range want {
		f, err := r.Next()
		if err != nil {
			t.Fatalf("frame %d: %v", i, err)
		}
		switch w := w.(type) {
		case Frame:
			if !reflect.DeepEqual(f, w) {
				t.Errorf("frame %d = %+v, want %+v", i, f, w)
			}
		case nil:
			if string(f.Data) != `{"t":"bye","reason":""}` {
				t.Errorf("bye encoded as %s", f.Data)
			}
			m, err := DecodeServer(f.Data)
			if _, ok := m.(*Bye); err != nil || !ok {
				t.Errorf("DecodeServer(bye) = %T, %v", m, err)
			}
		default:
			m, err := DecodeClient(f.Data)
			if err != nil || !reflect.DeepEqual(m, w) {
				t.Errorf("frame %d decoded to %#v, %v; want %#v", i, m, err, w)
			}
		}
	}
	if _, err := r.Next(); err != io.EOF {
		t.Errorf("after last frame: %v, want EOF", err)
	}
}

func TestNoHTMLEscaping(t *testing.T) {
	buf, err := AppendJSON(nil, Result{ID: 1, Lines: []string{"<a> & b"}})
	if err != nil {
		t.Fatal(err)
	}
	if got := string(buf[5:]); got != `{"t":"result","id":1,"lines":["<a> & b"]}` {
		t.Errorf("encoded %s", got)
	}
}

func TestReaderRejectsBadFrames(t *testing.T) {
	frame := func(n uint32, rest ...byte) []byte {
		return append(binary.BigEndian.AppendUint32(nil, n), rest...)
	}
	cases := map[string][]byte{
		"zero length":     frame(0),
		"too long":        frame(MaxFrame + 1),
		"unknown type":    frame(2, 9, 0),
		"truncated pane":  frame(3, FrameData, 5, 'x'),
		"truncated frame": frame(10, FrameJSON, '{'),
	}
	for name, b := range cases {
		if _, err := NewReader(bytes.NewReader(b)).Next(); err == nil || err == io.EOF {
			t.Errorf("%s: err = %v", name, err)
		}
	}
}

func TestWriterRefusesWhatNoReaderTakes(t *testing.T) {
	big := Entries{Pane: "%1", Entries: []Entry{{ID: "a", Role: "user", Kind: "text", Text: strings.Repeat("x", MaxFrame)}}}
	if b, err := AppendJSON([]byte("keep"), big); !errors.Is(err, ErrTooBig) || string(b) != "keep" {
		t.Fatalf("err %v, kept %q", err, b[:min(len(b), 8)])
	}
}

func TestDecodeUnknown(t *testing.T) {
	if _, err := DecodeClient([]byte(`{"t":"state"}`)); err == nil {
		t.Error("client decoder accepted a server message")
	}
	if _, err := DecodeClient([]byte(`not json`)); err == nil {
		t.Error("accepted invalid JSON")
	}
}
