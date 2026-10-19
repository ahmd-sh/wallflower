package gateway

import (
	"bytes"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/tmux"
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
	paint(&b, []string{"\x1b[42mgreen", "more", "\x1b[49mdone"}, nil)
	want := "\x1b[42mgreen\x1b[49m\r\n\x1b[42mmore\x1b[49m\r\n\x1b[42m\x1b[49mdone"
	if b.String() != want {
		t.Errorf("paint = %q\nwant    %q", b.String(), want)
	}
}

func TestPaintPutsMarksBack(t *testing.T) {
	var b bytes.Buffer
	P, O := tmux.PromptLine, tmux.OutputLine
	// A command over two lines, its output, a line left unrun, then the prompt waiting.
	paint(&b, []string{"$ for x", "> done", "out", "more", "$ abandoned", "$ "}, []tmux.Mark{P, P, O, 0, P, P})
	want := "\x1b]133;A\x07$ for x\r\n\x1b]133;A\x07> done\r\n\x1b]133;C\x07out\r\nmore\r\n\x1b]133;A\x07$ abandoned\r\n\x1b]133;A\x07$ "
	if b.String() != want {
		t.Errorf("paint = %q\nwant    %q", b.String(), want)
	}
}

func TestRepaintEscapes(t *testing.T) {
	c := &tmux.Capture{Cols: 10, Rows: 3, X: 4, Y: 1, Bottom: 2, History: []string{"h2", "h3"}, Normal: []string{"a", "b", ""}}
	want := "\x1b[0m\x1b[H\x1b[2Jh2\r\nh3\r\na\r\nb\r\n\x1b[0m\x1b[2;5H"
	if got := string(repaint(c)); got != want {
		t.Errorf("repaint = %q\nwant      %q", got, want)
	}

	// Marks run on from the history into the screen.
	c.HistoryMarks, c.NormalMarks = []tmux.Mark{tmux.PromptLine, tmux.OutputLine}, []tmux.Mark{0, tmux.PromptLine, 0}
	want = "\x1b[0m\x1b[H\x1b[2J\x1b]133;A\x07h2\r\n\x1b]133;C\x07h3\r\na\r\n\x1b]133;A\x07b\r\n\x1b[0m\x1b[2;5H"
	if got := string(repaint(c)); got != want {
		t.Errorf("marked repaint = %q\nwant             %q", got, want)
	}

	// A full-screen app with the mouse on, as Claude Code runs: any-event tracking, SGR encoding.
	c = &tmux.Capture{
		Cols: 10, Rows: 3, CursorHidden: true, Alt: true, Top: 1, Bottom: 2, Mouse: []int{1003, 1006},
		Normal: []string{"n1", "n2", "n3"}, AltScreen: []string{"x", "y", "z"},
	}
	want = "\x1b[0m\x1b[H\x1b[2Jn1\r\nn2\r\nn3\x1b[0m\x1b[?1049h\x1b[H\x1b[2Jx\r\ny\r\nz\x1b[0m\x1b[2;3r\x1b[1;1H\x1b[?25l\x1b[?1003h\x1b[?1006h"
	if got := string(repaint(c)); got != want {
		t.Errorf("alt repaint = %q\nwant          %q", got, want)
	}
}
