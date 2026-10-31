// Package desktop finds the desktop notifications programs send through
// their terminal, in a pane's output as tmux reads it: OSC 9 (iTerm2's),
// OSC 777 (rxvt's notify) and OSC 99 (kitty's), also wrapped in tmux's
// passthrough. tmux itself drops the plain ones, and passes the wrapped
// ones on only from a pane on screen; its control mode has them all.
package desktop

import (
	"encoding/base64"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Notification is one a program sent: its title, if it gave one, and what
// it says.
type Notification struct {
	Title string
	Body  string
}

const (
	maxOSC     = 8 << 10 // longer ones aren't notifications (clipboard, pictures)
	maxTitle   = 200
	maxBody    = 2000
	maxPending = 8 // kitty notifications sent in parts, by id
)

type state int

const (
	ground state = iota
	escape
	osc
	oscEscape
	dcs
	passthrough
	passthroughEscape
	skip // a string that isn't read, to its end
	skipEscape
)

// Scanner reads one pane's output for notifications. A sequence can be
// split over reads.
type Scanner struct {
	state   state
	buf     []byte
	keep    bool                     // buf may be a notification
	inner   *Scanner                 // what tmux's passthrough wraps
	pending map[string]*Notification // kitty's, until their last part
}

// Scan reads the next of the pane's output, returning the notifications it
// finishes.
func (s *Scanner) Scan(b []byte) []Notification {
	var found []Notification
	for _, c := range b {
		if n, ok := s.step(c); ok {
			found = append(found, n)
		}
	}
	return found
}

func (s *Scanner) step(c byte) (Notification, bool) {
	switch s.state {
	case ground:
		if c == 0x1b {
			s.state = escape
		}
	case escape:
		s.state = ground
		switch c {
		case ']':
			s.state, s.buf, s.keep = osc, s.buf[:0], true
		case 'P':
			s.state, s.buf = dcs, s.buf[:0]
		case 0x1b:
			s.state = escape
		}
	case osc:
		switch c {
		case 0x07:
			s.state = ground
			return s.osc()
		case 0x1b:
			s.state = oscEscape
		default:
			s.add(c)
		}
	case oscEscape:
		if c == '\\' {
			s.state = ground
			return s.osc()
		}
		s.state = escape // a new sequence cut it short
		return s.step(c)
	case dcs:
		if c == 0x1b {
			s.state = skipEscape
			break
		}
		s.buf = append(s.buf, c)
		if len(s.buf) == len("tmux;") {
			if string(s.buf) == "tmux;" {
				s.state, s.inner = passthrough, &Scanner{}
			} else {
				s.state = skip
			}
		}
	case passthrough:
		if c == 0x1b {
			s.state = passthroughEscape
			break
		}
		return s.inner.step(c)
	case passthroughEscape:
		// Inside, tmux's passthrough doubles each escape; a single one ends it.
		if c == 0x1b {
			s.state = passthrough
			return s.inner.step(c)
		}
		s.state, s.inner = ground, nil
		if c != '\\' {
			s.state = escape
			return s.step(c)
		}
	case skip:
		if c == 0x1b {
			s.state = skipEscape
		}
	case skipEscape:
		s.state = skip
		if c == '\\' {
			s.state = ground
		}
	}
	return Notification{}, false
}

// add keeps an OSC's next byte while it may be a notification: one whose
// number is one of theirs, short enough.
func (s *Scanner) add(c byte) {
	if !s.keep {
		return
	}
	if c == ';' && !strings.Contains(string(s.buf), ";") {
		switch string(s.buf) {
		case "9", "99", "777":
		default:
			s.keep = false
			return
		}
	}
	if len(s.buf) == maxOSC {
		s.keep = false
		return
	}
	s.buf = append(s.buf, c)
}

// conEmu matches what ConEmu and Windows Terminal send as OSC 9 that isn't a
// notification: a number first (9;4;... is progress, 9;9;... the directory).
var conEmu = regexp.MustCompile(`^[0-9]+(;|$)`)

func (s *Scanner) osc() (Notification, bool) {
	if !s.keep {
		return Notification{}, false
	}
	code, rest, _ := strings.Cut(string(s.buf), ";")
	var n Notification
	switch code {
	case "9":
		if conEmu.MatchString(rest) {
			return Notification{}, false
		}
		n.Body = rest
	case "777":
		kind, rest, _ := strings.Cut(rest, ";")
		if kind != "notify" {
			return Notification{}, false
		}
		n.Title, n.Body, _ = strings.Cut(rest, ";")
	case "99":
		var done bool
		if n, done = s.kitty(rest); !done {
			return Notification{}, false
		}
	default:
		return Notification{}, false
	}
	n.Title, n.Body = clean(n.Title, maxTitle), clean(n.Body, maxBody)
	if n.Body == "" {
		n.Title, n.Body = "", n.Title
	}
	return n, n.Body != ""
}

// kitty reads OSC 99's metadata (key=value, colon-separated) and payload.
// A notification can come in parts under one id, the last with d=1 (the
// default); p says whether a part is the title (the default) or the body.
func (s *Scanner) kitty(rest string) (Notification, bool) {
	meta, payload, _ := strings.Cut(rest, ";")
	id, done, part, encoded := "", true, "title", false
	for _, kv := range strings.Split(meta, ":") {
		k, v, _ := strings.Cut(kv, "=")
		switch k {
		case "i":
			id = v
		case "d":
			done = v != "0"
		case "p":
			part = v
		case "e":
			encoded = v == "1"
		}
	}
	if encoded {
		b, err := base64.StdEncoding.DecodeString(payload)
		if err != nil {
			return Notification{}, false
		}
		payload = string(b)
	}
	n := s.pending[id]
	if n == nil {
		n = &Notification{}
	}
	switch part {
	case "title":
		n.Title += payload
	case "body":
		n.Body += payload
	}
	if !done {
		if s.pending == nil {
			s.pending = map[string]*Notification{}
		}
		if _, ok := s.pending[id]; ok || len(s.pending) < maxPending {
			s.pending[id] = n
		}
		return Notification{}, false
	}
	delete(s.pending, id)
	return *n, true
}

// clean makes what a program sent fit to show: valid UTF-8 on one line or
// several, without control characters, at most max runes.
func clean(s string, max int) string {
	s = strings.ToValidUTF8(s, "")
	s = strings.Map(func(r rune) rune {
		switch {
		case r == '\n':
			return r
		case r == '\t':
			return ' '
		case unicode.IsControl(r):
			return -1
		}
		return r
	}, s)
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) > max {
		s = string([]rune(s)[:max-1]) + "…"
	}
	return s
}
