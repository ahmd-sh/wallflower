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
	Seq          uint64
}

// Capture reads a pane's screen, with its normal screen's last history
// lines of scrollback, and its lines keeping their escapes.
func (c *Client) Capture(pane string, history int) (*Capture, error) {
	if err := check(paneID, pane); err != nil {
		return nil, err
	}
	// The pane's metadata, its normal-screen history, the normal screen saved
	// behind an alternate screen (empty if none), and the visible screen.
	r, err := c.Command(fmt.Sprintf("display -p -t %[1]s %[2]s ; capture-pane -p -e -t %[1]s -S -%[3]d -E -1 ; "+
		"capture-pane -p -e -q -a -t %[1]s ; capture-pane -p -e -t %[1]s",
		pane, Quote(captureFormat), max(history, 1)))
	if err != nil {
		return nil, err
	}
	return parseCapture(r, history)
}

func parseCapture(r Reply, history int) (*Capture, error) {
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
	hist := r.Blocks[1]
	n := min(history, atoi(f[6]), len(hist))
	c.History = hist[len(hist)-n:]

	if c.Alt {
		c.Normal = fit(r.Blocks[2], c.Rows)
		c.AltScreen = fit(r.Blocks[3], c.Rows)
	} else {
		c.Normal = fit(r.Blocks[3], c.Rows)
	}
	return c, nil
}

// fit pads or trims lines to exactly n.
func fit(lines []string, n int) []string {
	if len(lines) >= n {
		return lines[:n]
	}
	return append(lines[:len(lines):len(lines)], make([]string, n-len(lines))...)
}
