package tmux

import (
	"reflect"
	"testing"
)

func TestParseCapture(t *testing.T) {
	reply := Reply{Seq: 7, Blocks: [][]string{
		{"10\t3\t4\t1\t1\t0\t2\t0\t2\t0\t0\t0\t0\t0"},
		{"h1", "h2", "h3"}, // tmux returned more than history_size
		nil,
		{"a", "b"}, // fewer lines than rows
	}}
	c, err := parseCapture(reply, 1000)
	if err != nil {
		t.Fatal(err)
	}
	want := &Capture{Cols: 10, Rows: 3, X: 4, Y: 1, Bottom: 2, History: []string{"h2", "h3"}, Normal: []string{"a", "b", ""}, Seq: 7}
	if !reflect.DeepEqual(c, want) {
		t.Fatalf("capture = %+v\nwant      %+v", c, want)
	}

	// A full-screen app with the mouse on, as Claude Code runs: any-event tracking, SGR encoding.
	reply.Blocks[0][0] = "10\t3\t0\t0\t0\t1\t0\t1\t2\t0\t0\t1\t1\t0"
	reply.Blocks[2] = []string{"n1", "n2", "n3"}
	reply.Blocks[3] = []string{"x", "y", "z"}
	c, err = parseCapture(reply, 1000)
	if err != nil {
		t.Fatal(err)
	}
	want = &Capture{
		Cols: 10, Rows: 3, CursorHidden: true, Alt: true, Top: 1, Bottom: 2, Mouse: []int{1003, 1006},
		History: []string{}, Normal: []string{"n1", "n2", "n3"}, AltScreen: []string{"x", "y", "z"}, Seq: 7,
	}
	if !reflect.DeepEqual(c, want) {
		t.Errorf("alt capture = %+v\nwant          %+v", c, want)
	}
}
