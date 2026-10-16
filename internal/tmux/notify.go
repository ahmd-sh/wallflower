package tmux

import (
	"strconv"
	"strings"
)

// Notification is one control-mode notification line. Type is the name
// without the leading %, e.g. "output" or "layout-change"; only the fields
// that notification carries are set. Unknown notifications keep their
// arguments in Value.
type Notification struct {
	Type          string `json:"type"`
	Session       string `json:"session,omitempty"`
	Window        string `json:"window,omitempty"`
	Pane          string `json:"pane,omitempty"`
	Client        string `json:"client,omitempty"`
	Name          string `json:"name,omitempty"`
	Value         string `json:"value,omitempty"`
	Layout        string `json:"layout,omitempty"`
	VisibleLayout string `json:"visible_layout,omitempty"`
	WindowFlags   string `json:"window_flags,omitempty"`
	AgeMs         int    `json:"age_ms,omitempty"`
	Data          []byte `json:"-"`
}

func parseNotification(line string) (Notification, bool) {
	if !strings.HasPrefix(line, "%") {
		return Notification{}, false
	}
	kind, rest, _ := strings.Cut(line[1:], " ")
	n := Notification{Type: kind}

	switch kind {
	case "output":
		n.Pane, rest, _ = strings.Cut(rest, " ")
		n.Data = unescape(rest)
	case "extended-output":
		var age string
		n.Pane, rest, _ = strings.Cut(rest, " ")
		age, rest, _ = strings.Cut(rest, " ")
		n.AgeMs, _ = strconv.Atoi(age)
		n.Data = unescape(afterColon(rest))
	case "layout-change":
		f := strings.SplitN(rest, " ", 4)
		for len(f) < 4 {
			f = append(f, "")
		}
		n.Window, n.Layout, n.VisibleLayout, n.WindowFlags = f[0], f[1], f[2], f[3]
	case "window-add", "window-close", "unlinked-window-add", "unlinked-window-close":
		n.Window = rest
	case "window-renamed", "unlinked-window-renamed":
		n.Window, n.Name, _ = strings.Cut(rest, " ")
	case "window-pane-changed":
		n.Window, n.Pane, _ = strings.Cut(rest, " ")
	case "session-changed":
		n.Session, n.Name, _ = strings.Cut(rest, " ")
	case "session-renamed":
		// tmux 3.8 sends "$id name"; the man page documents only the name.
		if id, name, ok := strings.Cut(rest, " "); ok && strings.HasPrefix(id, "$") {
			n.Session, n.Name = id, name
		} else {
			n.Name = rest
		}
	case "session-window-changed":
		n.Session, n.Window, _ = strings.Cut(rest, " ")
	case "client-session-changed":
		f := strings.SplitN(rest, " ", 3)
		for len(f) < 3 {
			f = append(f, "")
		}
		n.Client, n.Session, n.Name = f[0], f[1], f[2]
	case "client-detached":
		n.Client = rest
	case "pane-mode-changed", "pause", "continue":
		n.Pane = rest
	case "subscription-changed":
		// name session window window-index pane ... : value
		// window and pane are "-" when the subscription is not per window/pane.
		f := strings.SplitN(rest, " ", 6)
		for len(f) < 6 {
			f = append(f, "")
		}
		n.Name, n.Session = f[0], f[1]
		if f[2] != "-" {
			n.Window = f[2]
		}
		if f[4] != "-" {
			n.Pane = f[4]
		}
		n.Value = afterColon(f[5])
	case "paste-buffer-changed", "paste-buffer-deleted":
		n.Name = rest
	default: // sessions-changed, message, config-error, exit, and anything newer
		n.Value = rest
	}
	return n, true
}

// afterColon returns the value after the "... : " separator that ends the
// reserved arguments of %extended-output and %subscription-changed.
func afterColon(s string) string {
	if v, ok := strings.CutPrefix(s, ": "); ok {
		return v
	}
	if s == ":" {
		return ""
	}
	if _, v, ok := strings.Cut(s, " : "); ok {
		return v
	}
	return strings.TrimSuffix(s, " :")
}

// unescape decodes %output data: tmux writes bytes below 0x20 and backslash
// as \ooo octal and everything else, including UTF-8, as-is.
func unescape(s string) []byte {
	b := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+3 < len(s) && isOctal(s[i+1]) && isOctal(s[i+2]) && isOctal(s[i+3]) {
			b = append(b, (s[i+1]-'0')<<6|(s[i+2]-'0')<<3|(s[i+3]-'0'))
			i += 3
			continue
		}
		b = append(b, s[i])
	}
	return b
}

func isOctal(c byte) bool { return c >= '0' && c <= '7' }
