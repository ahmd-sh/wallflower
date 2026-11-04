package tmux

import (
	"bytes"
	"fmt"
	"regexp"
	"strings"
)

// repaint returns bytes that rebuild a captured pane in a fresh terminal of
// the same size: history scrolls into the scrollback, then the screen, then
// the alternate screen on top if one is active, then cursor state, and the
// mouse modes, which a full-screen app needs to get its scroll wheel.
func repaint(c *Capture) []byte {
	var b bytes.Buffer
	b.WriteString("\x1b[0m\x1b[H\x1b[2J")
	paint(&b, append(c.History[:len(c.History):len(c.History)], c.Normal...))
	if c.Alt {
		b.WriteString("\x1b[0m\x1b[?1049h\x1b[H\x1b[2J")
		paint(&b, c.AltScreen)
	}
	b.WriteString("\x1b[0m")
	if c.Top != 0 || c.Bottom != c.Rows-1 {
		fmt.Fprintf(&b, "\x1b[%d;%dr", c.Top+1, c.Bottom+1)
	}
	fmt.Fprintf(&b, "\x1b[%d;%dH", c.Y+1, c.X+1)
	if c.CursorHidden {
		b.WriteString("\x1b[?25l")
	}
	for _, mode := range c.Mouse {
		fmt.Fprintf(&b, "\x1b[?%dh", mode)
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
