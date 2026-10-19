package tmux

import (
	"fmt"
	"strings"
)

var captureFormat = strings.Join([]string{
	"#{pane_width}", "#{pane_height}", "#{cursor_x}", "#{cursor_y}", "#{cursor_flag}",
	"#{alternate_on}", "#{history_size}", "#{scroll_region_upper}", "#{scroll_region_lower}",
	"#{mouse_standard_flag}", "#{mouse_button_flag}", "#{mouse_all_flag}", "#{mouse_sgr_flag}", "#{mouse_utf8_flag}",
}, "\t")

// The mouse modes, in captureFormat's order.
var mouseModes = []int{1000, 1002, 1003, 1006, 1005}

// A Capture is a pane's screen as tmux holds it, read in one atomic command
// line so it lines up with the pane's output: notifications with Seq up to
// its Seq are already in it.
type Capture struct {
	Cols, Rows   int
	X, Y         int // the cursor
	CursorHidden bool
	Alt          bool
	Top, Bottom  int      // the scroll region
	Mouse        []int    // mouse modes the app turned on (DEC private modes)
	History      []string // the normal screen's scrollback, oldest first
	Normal       []string // the normal screen; covered by the alternate screen if Alt
	AltScreen    []string
	// The shell's marks on History's and Normal's lines, line by line; nil
	// if this tmux doesn't say.
	HistoryMarks, NormalMarks []Mark
	Seq                       uint64
}

// A Mark is what tmux keeps of the marks a shell puts on its prompts and
// output (OSC 133): which lines a prompt starts on, and a command's output.
type Mark uint8

const (
	PromptLine Mark = 1 << iota
	OutputLine
)

// Capture reads a pane's screen, with its normal screen's last history
// lines of scrollback, and its lines keeping their escapes, and the shell's
// marks on them if this tmux keeps them (capture-pane -F, 3.8). Whether it
// does is found out on the first capture that works either way.
func (c *Client) Capture(pane string, history int) (*Capture, error) {
	if err := check(paneID, pane); err != nil {
		return nil, err
	}
	marks := c.lineMarks.Load()
	r, err := c.Command(captureLine(pane, history, marks != marksUnsupported))
	if err != nil && marks == marksUnknown {
		if r, err = c.Command(captureLine(pane, history, false)); err == nil {
			c.lineMarks.Store(marksUnsupported)
		}
	} else if err == nil && marks == marksUnknown {
		c.lineMarks.Store(marksSupported)
	}
	if err != nil {
		return nil, err
	}
	return parseCapture(r, history, c.lineMarks.Load() == marksSupported)
}

const (
	marksUnknown int32 = iota
	marksSupported
	marksUnsupported
)

// captureLine reads the pane's metadata, its normal-screen history, the
// normal screen saved behind an alternate screen (empty if none), and the
// visible screen; with marks, each line starts with tmux's flags for it.
func captureLine(pane string, history int, marks bool) string {
	flags := "-p -e"
	if marks {
		flags += " -F"
	}
	return fmt.Sprintf("display -p -t %[1]s %[2]s ; capture-pane %[4]s -t %[1]s -S -%[3]d -E -1 ; "+
		"capture-pane %[4]s -q -a -t %[1]s ; capture-pane %[4]s -t %[1]s",
		pane, Quote(captureFormat), max(history, 1), flags)
}

func parseCapture(r Reply, history int, marks bool) (*Capture, error) {
	if len(r.Blocks) != 4 || len(r.Blocks[0]) != 1 {
		return nil, fmt.Errorf("tmux: unexpected capture reply (%d blocks)", len(r.Blocks))
	}
	f := strings.Split(r.Blocks[0][0], "\t")
	if len(f) != 9+len(mouseModes) {
		return nil, fmt.Errorf("tmux: unexpected pane metadata %q", r.Blocks[0][0])
	}
	c := &Capture{
		Cols: atoi(f[0]), Rows: atoi(f[1]), X: atoi(f[2]), Y: atoi(f[3]),
		CursorHidden: f[4] == "0", Alt: f[5] == "1",
		Top: atoi(f[7]), Bottom: atoi(f[8]), Seq: r.Seq,
	}
	for i, mode := range mouseModes {
		if f[9+i] == "1" {
			c.Mouse = append(c.Mouse, mode)
		}
	}

	// With no history tmux clamps the range onto the screen, so trust
	// history_size over the line count.
	hist, histMarks := lines(r.Blocks[1], marks)
	n := min(history, atoi(f[6]), len(hist))
	c.History = hist[len(hist)-n:]

	normal, normalMarks := lines(r.Blocks[3], marks)
	if c.Alt {
		c.AltScreen = fit(normal, c.Rows)
		normal, normalMarks = lines(r.Blocks[2], marks)
	}
	c.Normal = fit(normal, c.Rows)
	if marks {
		c.HistoryMarks = histMarks[len(histMarks)-n:]
		c.NormalMarks = fit(normalMarks, c.Rows)
	}
	return c, nil
}

// lines takes the flags tmux starts each line with off it, if marks, and
// says which lines a prompt or output starts on.
func lines(block []string, marks bool) ([]string, []Mark) {
	if !marks {
		return block, nil
	}
	text := make([]string, len(block))
	m := make([]Mark, len(block))
	for i, line := range block {
		flags, rest, _ := strings.Cut(line, " ")
		text[i] = rest
		if strings.ContainsRune(flags, 'P') {
			m[i] |= PromptLine
		}
		if strings.ContainsRune(flags, 'O') {
			m[i] |= OutputLine
		}
	}
	return text, m
}

// fit pads or trims lines to exactly n.
func fit[T any](lines []T, n int) []T {
	if len(lines) >= n {
		return lines[:n]
	}
	return append(lines[:len(lines):len(lines)], make([]T, n-len(lines))...)
}
