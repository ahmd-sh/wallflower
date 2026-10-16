package gateway

import (
	"bytes"
	"fmt"
	"regexp"
	"strings"

	"github.com/ahmd-sh/tether/internal/tmux"
)

var metaFormat = strings.Join([]string{
	"#{pane_width}", "#{pane_height}", "#{cursor_x}", "#{cursor_y}", "#{cursor_flag}",
	"#{alternate_on}", "#{history_size}", "#{scroll_region_upper}", "#{scroll_region_lower}",
}, "\t")

// capture is a pane's screen as tmux holds it, read in one atomic command
// line so it lines up with the pane's output stream.
type capture struct {
	cols, rows   int
	cx, cy       int
	cursorHidden bool
	alt          bool
	top, bottom  int      // scroll region
	history      []string // normal screen scrollback, oldest first
	normal       []string // normal screen; covered by the alternate screen if alt
	altScreen    []string
}

// captureCommand reads the pane's metadata, its normal-screen history, the
// normal screen saved behind an alternate screen (empty if none), and the
// visible screen.
func captureCommand(pane string, history int) string {
	return fmt.Sprintf("display -p -t %[1]s %[2]s ; capture-pane -p -e -t %[1]s -S -%[3]d -E -1 ; "+
		"capture-pane -p -e -q -a -t %[1]s ; capture-pane -p -e -t %[1]s",
		pane, tmux.Quote(metaFormat), max(history, 1))
}

func parseCapture(r tmux.Reply, history int) (*capture, error) {
	if len(r.Blocks) != 4 || len(r.Blocks[0]) != 1 {
		return nil, fmt.Errorf("unexpected capture reply (%d blocks)", len(r.Blocks))
	}
	f := strings.Split(r.Blocks[0][0], "\t")
	if len(f) != 9 {
		return nil, fmt.Errorf("unexpected pane metadata %q", r.Blocks[0][0])
	}
	c := &capture{
		cols: atoi(f[0]), rows: atoi(f[1]), cx: atoi(f[2]), cy: atoi(f[3]),
		cursorHidden: f[4] == "0", alt: f[5] == "1",
		top: atoi(f[7]), bottom: atoi(f[8]),
	}

	// With no history tmux clamps the range onto the screen, so trust
	// history_size over the line count.
	hist := r.Blocks[1]
	n := min(history, atoi(f[6]), len(hist))
	c.history = hist[len(hist)-n:]

	if c.alt {
		c.normal = fit(r.Blocks[2], c.rows)
		c.altScreen = fit(r.Blocks[3], c.rows)
	} else {
		c.normal = fit(r.Blocks[3], c.rows)
	}
	return c, nil
}

// repaint returns bytes that rebuild the pane in a fresh terminal of the
// same size: history scrolls into the scrollback, then the screen, then the
// alternate screen on top if one is active, then cursor state.
func (c *capture) repaint() []byte {
	var b bytes.Buffer
	b.WriteString("\x1b[0m\x1b[H\x1b[2J")
	paint(&b, append(c.history[:len(c.history):len(c.history)], c.normal...))
	if c.alt {
		b.WriteString("\x1b[0m\x1b[?1049h\x1b[H\x1b[2J")
		paint(&b, c.altScreen)
	}
	b.WriteString("\x1b[0m")
	if c.top != 0 || c.bottom != c.rows-1 {
		fmt.Fprintf(&b, "\x1b[%d;%dr", c.top+1, c.bottom+1)
	}
	fmt.Fprintf(&b, "\x1b[%d;%dH", c.cy+1, c.cx+1)
	if c.cursorHidden {
		b.WriteString("\x1b[?25l")
	}
	return b.Bytes()
}

// paint writes lines separated by CRLF. capture-pane carries attributes
// across lines, so a background colour can be active at a line break, and a
// newline that scrolls would fill the new line with it; drop it for the
// break and restore it after.
func paint(b *bytes.Buffer, lines []string) {
	bg := ""
	for i, line := range lines {
		if i > 0 {
			if bg != "" {
				b.WriteString("\x1b[49m\r\n" + bg)
			} else {
				b.WriteString("\r\n")
			}
		}
		b.WriteString(line)
		bg = background(bg, line)
	}
}

var sgr = regexp.MustCompile(`\x1b\[([0-9;:]*)m`)

// background returns the SGR sequence for the background colour in effect
// after line, given the one in effect before it ("" for the default).
func background(bg, line string) string {
	for _, m := range sgr.FindAllStringSubmatch(line, -1) {
		params := strings.Split(m[1], ";")
		for i := 0; i < len(params); i++ {
			p := params[i]
			switch {
			case p == "" || p == "0" || p == "49":
				bg = ""
			case len(p) == 2 && p[0] == '4' && p[1] <= '7',
				len(p) == 3 && p[:2] == "10" && p[2] <= '7':
				bg = "\x1b[" + p + "m"
			case strings.HasPrefix(p, "48:"):
				bg = "\x1b[" + p + "m"
			case p == "38" || p == "48" || p == "58":
				n := 0
				if rest := params[i+1:]; len(rest) >= 2 && rest[0] == "5" {
					n = 2
				} else if len(rest) >= 4 && rest[0] == "2" {
					n = 4
				}
				if p == "48" && n > 0 {
					bg = "\x1b[" + strings.Join(params[i:i+1+n], ";") + "m"
				}
				i += n
			}
		}
	}
	return bg
}

// fit pads or trims lines to exactly n.
func fit(lines []string, n int) []string {
	if len(lines) >= n {
		return lines[:n]
	}
	return append(lines[:len(lines):len(lines)], make([]string, n-len(lines))...)
}
