package claude

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/transcript"
)

// Rewinding goes through claude's own /rewind, as at the keyboard: up its
// list of the user's messages to the one wanted, then its confirmation. The
// screen is read at every step, and anything not as expected backs out
// before anything's restored.

const rewindPoll = 20 * time.Millisecond

var rewindDraw = 3 * time.Second // how long claude has to draw what a key does

var (
	errNotDrawn = errors.New("Claude didn't respond")
	errNotFound = errors.New("Claude's rewind list doesn't have that message")
	optionRow   = regexp.MustCompile(`^\s*(?:[❯›↑↓]\s*)?(\d+)\.\s+(.+?)\s*$`)
	agoRow      = regexp.MustCompile(`^\(.*\bago\)$`)
)

func (s *Source) Rewind(ctx context.Context, t transcript.Terminal, pid int, id string, code bool) error {
	sess, ok := s.Running()[pid]
	if !ok {
		return errors.New("the conversation ended")
	}
	if sess.State == protocol.StateWorking || sess.State == protocol.StateWaiting {
		return errors.New("Claude is busy")
	}
	entries, err := transcript.Read(sess.Path, s.Parser())
	if err != nil {
		return err
	}
	var want string
	var later []string // the user's messages after it, newest last
	for _, e := range entries {
		switch {
		case e.Role != "user" || e.Kind != "text":
		case e.ID == id:
			want = flat(e.Text)
		case want != "":
			later = append(later, flat(e.Text))
		}
	}
	if want == "" {
		return errors.New("that message isn't in the conversation")
	}
	r := rewind{ctx: ctx, t: t}
	if err := r.list(); err != nil {
		return err
	}
	if err := r.find(want, later, len(entries)); err != nil {
		r.back()
		return err
	}
	if err := r.confirm(want, code); err != nil {
		r.back()
		return err
	}
	return nil
}

type rewind struct {
	ctx context.Context
	t   transcript.Terminal
}

// list opens the rewind list from an empty prompt.
func (r rewind) list() error {
	rows, err := r.t.Screen()
	if err != nil {
		return err
	}
	if _, ok := prompt(rows); !ok {
		return errors.New("Claude isn't at its prompt; answer what it's asking first")
	}
	if err := r.clear(rows); err != nil {
		return err
	}
	if err := r.t.Type("/rewind"); err != nil {
		return err
	}
	if err := r.t.Keys("Enter"); err != nil {
		return err
	}
	_, err = r.until(func(rows []string) bool { _, ok := listed(rows); return ok })
	return err
}

// find moves up the list to the message want. Messages after it that look
// the same as it in the list are passed over, counting them from the newest.
func (r rewind) find(want string, later []string, most int) error {
	rows, err := r.t.Screen()
	if err != nil {
		return err
	}
	seen := 0
	for range most + 1 {
		before, _ := listed(rows)
		if err := r.t.Keys("Up"); err != nil {
			return err
		}
		rows, err = r.until(func(rows []string) bool { l, ok := listed(rows); return ok && l.key() != before.key() })
		if errors.Is(err, errNotDrawn) {
			return errNotFound // at the top
		}
		if err != nil {
			return err
		}
		l, _ := listed(rows)
		row := l.rows[l.cursor]
		if row == "(current)" {
			return errNotFound // round to the bottom
		}
		if !shows(row, want) {
			continue
		}
		seen++
		alike := 0
		for _, m := range later {
			if shows(row, m) {
				alike++
			}
		}
		if seen > alike {
			return nil
		}
	}
	return errNotFound
}

// confirm picks the message on the list's cursor, checks it's want, and
// restores the conversation to before it, and the code if code and
// claude offers to, then empties the prompt claude puts it back in.
func (r rewind) confirm(want string, code bool) error {
	if err := r.t.Keys("Enter"); err != nil {
		return err
	}
	rows, err := r.until(func(rows []string) bool { _, _, ok := confirming(rows); return ok })
	if err != nil {
		return err
	}
	quote, options, _ := confirming(rows)
	if !quotes(quote, want) {
		return errors.New("Claude picked a different message")
	}
	n, ok := options["Restore code and conversation"]
	if !code || !ok {
		if n, ok = options["Restore conversation"]; !ok {
			return errors.New("Claude didn't offer to restore the conversation")
		}
	}
	if err := r.t.Keys(n); err != nil {
		return err
	}
	rows, err = r.until(func(rows []string) bool {
		_, open := listed(rows)
		_, _, asking := confirming(rows)
		_, ok := prompt(rows)
		return ok && !open && !asking
	})
	if err != nil {
		return err
	}
	return r.clear(rows)
}

// clear empties the prompt shown in rows a row at a time: ^U clears to the
// row's start, and again joins it to the row before.
func (r rewind) clear(rows []string) error {
	n, _ := prompt(rows)
	keys := make([]string, 2*n+1)
	for i := range keys {
		keys[i] = "C-u"
	}
	return r.t.Keys(keys...)
}

// back closes the list or the confirmation, whichever is open.
func (r rewind) back() {
	for range 3 {
		rows, err := r.t.Screen()
		if err != nil {
			return
		}
		_, open := listed(rows)
		_, _, asking := confirming(rows)
		if !open && !asking {
			return
		}
		r.t.Keys("Escape")
		r.until(func(now []string) bool { return strings.Join(now, "\n") != strings.Join(rows, "\n") })
	}
}

// until reads the screen until done says it's drawn.
func (r rewind) until(done func([]string) bool) ([]string, error) {
	deadline := time.Now().Add(rewindDraw)
	for {
		rows, err := r.t.Screen()
		if err != nil {
			return nil, err
		}
		if done(rows) {
			return rows, nil
		}
		if time.Now().After(deadline) {
			return rows, errNotDrawn
		}
		select {
		case <-r.ctx.Done():
			return nil, r.ctx.Err()
		case <-time.After(rewindPoll):
		}
	}
}

// prompt is how many rows claude's prompt takes, below the rule above it, if
// it's on screen.
func prompt(rows []string) (int, bool) {
	for i := len(rows) - 1; i > 0; i-- {
		if !strings.HasPrefix(rows[i], "❯") || !rule(rows[i-1]) {
			continue
		}
		n := 1
		for i+n < len(rows) && !rule(rows[i+n]) {
			n++
		}
		return n, true
	}
	return 0, false
}

// A rewindList is claude's list of the user's messages, its rows as shown
// (each cut short with … to fit), and which the cursor is on.
type rewindList struct {
	rows   []string
	cursor int
}

// key is what's drawn, to tell when it moves.
func (l rewindList) key() string { return fmt.Sprint(l.cursor, l.rows) }

// listed reads the rewind list off the screen, up from what it says to do
// at its foot, which stays in view when its head is scrolled off a short
// pane: the messages, each followed by what code it changed, then
// (current), all indented, as many as fit.
func listed(rows []string) (rewindList, bool) {
	foot := -1
	for i := len(rows) - 1; i >= 0 && foot < 0; i-- {
		if strings.HasPrefix(strings.TrimSpace(rows[i]), "Enter to continue") {
			foot = i
		}
	}
	if foot < 0 {
		return rewindList{}, false
	}
	var l rewindList
	cursor := -1
	for i := foot - 1; i >= 0; i-- {
		row := strings.TrimSpace(rows[i])
		if row == "" {
			continue
		}
		text, on := strings.CutPrefix(row, "❯")
		if !on && !strings.HasPrefix(rows[i], "    ") {
			break
		}
		if on {
			cursor = len(l.rows)
		}
		l.rows = append(l.rows, strings.TrimSpace(text))
	}
	if cursor < 0 {
		return rewindList{}, false
	}
	slices.Reverse(l.rows)
	l.cursor = len(l.rows) - 1 - cursor
	return l, true
}

// confirming reads claude's confirmation off the screen, up from its
// options: their numbers by label, and what it quotes of the message (its
// start can be scrolled off a short pane).
func confirming(rows []string) (quote string, options map[string]string, ok bool) {
	options = map[string]string{}
	first := -1
	for i, row := range rows {
		if m := optionRow.FindStringSubmatch(row); m != nil {
			options[m[2]] = m[1]
			if first < 0 {
				first = i
			}
		}
	}
	if _, ok := options["Restore conversation"]; !ok {
		return "", nil, false
	}
	var quoted []string
	for i := first - 1; i >= 0; i-- {
		text, ok := strings.CutPrefix(strings.TrimSpace(rows[i]), "│")
		if !ok {
			if len(quoted) > 0 {
				break
			}
			continue
		}
		if text = strings.TrimSpace(text); !agoRow.MatchString(text) {
			quoted = append(quoted, text)
		}
	}
	slices.Reverse(quoted)
	return strings.Join(quoted, " "), options, true
}

// quotes is whether quote, as much of a message as claude shows, with …
// where it cut some out, is from message. A pane too short to show any of
// it leaves the list to have said which.
func quotes(quote, message string) bool {
	message = squash(message)
	at := 0
	for _, part := range strings.Split(squash(quote), "…") {
		i := strings.Index(message[at:], part)
		if i < 0 {
			return false
		}
		at += i + len(part)
	}
	return true
}

// shows is whether row, a message as the rewind list shows it, is message.
func shows(row, message string) bool {
	row = flat(strings.TrimSuffix(row, "…"))
	return row != "" && strings.HasPrefix(message, row)
}

func rule(row string) bool {
	row = strings.TrimSpace(row)
	n := strings.Count(row, "─")
	return n >= 20 && n*5 >= len([]rune(row))*4
}

// flat is s on one line, its runs of space one space.
func flat(s string) string { return strings.Join(strings.Fields(s), " ") }

// squash is s without spaces, to compare text however it's wrapped.
func squash(s string) string { return strings.Join(strings.Fields(s), "") }
