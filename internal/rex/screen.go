package rex

import (
	"bytes"
	"strings"
)

// The most of a terminal's scrollback one reply carries, and so the most a
// repaint reads of it.
const maxRead = 960 << 10

// What format includes besides the screen: the modes and keyboard state
// ahead of it, and the scroll region, cursor and style after it.
var formatArgs = map[string]any{
	"format": "vt", "max_bytes": maxRead,
	"extra_modes": true, "extra_keyboard": true, "extra_kitty_keyboard": true, "extra_charsets": true,
	"extra_scrolling_region": true, "extra_cursor": true, "extra_style": true,
}

type formatted struct {
	Content string `json:"content"`
	ID      int    `json:"id"`
	Total   int    `json:"total"`
	EOF     bool   `json:"eof"`
}

// read is a terminal's screen and scrollback as VT, or its newest maxRead
// bytes of it, whole rows, and what goes ahead of them.
func read(c *control, session, block string) (head string, rows []string, err error) {
	var f formatted
	if err := c.callBlock(session, block, "format", formatArgs, &f); err != nil {
		return "", nil, err
	}
	head, body := split(f.Content)
	if f.ID == 0 || f.EOF {
		return head, strings.Split(body, "\r\n"), nil
	}
	// The rest is too much: read its end, from the row after where it starts.
	var tail struct {
		Data string `json:"data"`
		EOF  bool   `json:"eof"`
	}
	offset := max(len(f.Content), f.Total-maxRead)
	if err := c.callBlock(session, block, "blob_read", map[string]any{"id": f.ID, "offset": offset, "max_bytes": maxRead}, &tail); err != nil {
		return "", nil, err
	}
	body = tail.Data
	if offset > len(f.Content) {
		if i := strings.Index(body, "\r\n"); i >= 0 {
			body = body[i+2:]
		}
	} else {
		body = f.Content[len(head):] + body
	}
	return head, strings.Split(body, "\r\n"), nil
}

// split takes the modes and keyboard state format puts ahead of the screen
// (private CSI sequences, charset designations) off its content.
func split(content string) (head, body string) {
	i := 0
	for i < len(content) && content[i] == 0x1b {
		n := sequence(content[i:])
		if n == 0 {
			break
		}
		i += n
	}
	return content[:i], content[i:]
}

// sequence is the length of the escape sequence s starts with, if it's one
// that goes ahead of the screen; 0 otherwise.
func sequence(s string) int {
	if len(s) >= 3 && (s[1] == '(' || s[1] == ')' || s[1] == '*' || s[1] == '+') {
		return 3
	}
	if len(s) < 4 || s[1] != '[' || !strings.ContainsRune("?>=<", rune(s[2])) {
		return 0
	}
	for j := 3; j < len(s); j++ {
		switch c := s[j]; {
		case c >= 0x40 && c <= 0x7e:
			return j + 1
		case c < 0x20:
			return 0
		}
	}
	return 0
}

// repaint is a screen of rows rows, with its history lines of scrollback at
// most, as bytes that rebuild it in a fresh terminal of its size.
func repaint(head string, lines []string, rows, history int) []byte {
	if drop := len(lines) - rows - history; drop > 0 {
		lines = lines[drop:]
	}
	var b bytes.Buffer
	b.WriteString("\x1b[0m\x1b[H\x1b[2J\x1b[3J")
	b.WriteString(head)
	b.WriteString(strings.Join(lines, "\r\n"))
	return b.Bytes()
}
