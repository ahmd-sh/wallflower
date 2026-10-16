package gateway

import (
	"bytes"
	"testing"

	"github.com/ahmd-sh/tether/internal/tmux"
)

func TestBackground(t *testing.T) {
	cases := []struct{ before, line, want string }{
		{"", "plain", ""},
		{"", "\x1b[42mgreen", "\x1b[42m"},
		{"\x1b[42m", "still green", "\x1b[42m"},
		{"\x1b[42m", "\x1b[49mdefault", ""},
		{"\x1b[42m", "\x1b[0mreset", ""},
		{"\x1b[42m", "\x1b[mreset", ""},
		{"", "\x1b[1;104mbright", "\x1b[104m"},
		{"", "\x1b[48;5;236mindexed", "\x1b[48;5;236m"},
		{"", "\x1b[48;2;30;35;38mrgb", "\x1b[48;2;30;35;38m"},
		{"", "\x1b[48:2::30:35:38mcolon", "\x1b[48:2::30:35:38m"},
		{"", "\x1b[38;5;42mfg 42 is not bg", ""},
		{"", "\x1b[38;2;40;41;42;44mfg rgb then bg", "\x1b[44m"},
		{"", "\x1b[42ma\x1b[31mb", "\x1b[42m"},
	}
	for _, tc := range cases {
		if got := background(tc.before, tc.line); got != tc.want {
			t.Errorf("background(%q, %q) = %q, want %q", tc.before, tc.line, got, tc.want)
		}
	}
}

func TestPaintDropsBackgroundAtBreaks(t *testing.T) {
	var b bytes.Buffer
	paint(&b, []string{"\x1b[42mgreen", "more", "\x1b[49mdone"})
	want := "\x1b[42mgreen\x1b[49m\r\n\x1b[42mmore\x1b[49m\r\n\x1b[42m\x1b[49mdone"
	if b.String() != want {
		t.Errorf("paint = %q\nwant    %q", b.String(), want)
	}
}

func TestParseCapture(t *testing.T) {
	reply := tmux.Reply{Blocks: [][]string{
		{"10\t3\t4\t1\t1\t0\t2\t0\t2"},
		{"h1", "h2", "h3"}, // tmux returned more than history_size
		nil,
		{"a", "b"}, // fewer lines than rows
	}}
	c, err := parseCapture(reply, 1000)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.history) != 2 || c.history[0] != "h2" || len(c.normal) != 3 || c.alt {
		t.Fatalf("capture = %+v", c)
	}
	want := "\x1b[0m\x1b[H\x1b[2Jh2\r\nh3\r\na\r\nb\r\n\x1b[0m\x1b[2;5H"
	if got := string(c.repaint()); got != want {
		t.Errorf("repaint = %q\nwant      %q", got, want)
	}

	reply.Blocks[0][0] = "10\t3\t0\t0\t0\t1\t0\t1\t2"
	reply.Blocks[2] = []string{"n1", "n2", "n3"}
	reply.Blocks[3] = []string{"x", "y", "z"}
	c, err = parseCapture(reply, 1000)
	if err != nil {
		t.Fatal(err)
	}
	want = "\x1b[0m\x1b[H\x1b[2Jn1\r\nn2\r\nn3\x1b[0m\x1b[?1049h\x1b[H\x1b[2Jx\r\ny\r\nz\x1b[0m\x1b[2;3r\x1b[1;1H\x1b[?25l"
	if got := string(c.repaint()); got != want {
		t.Errorf("alt repaint = %q\nwant          %q", got, want)
	}
}
