package claude

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"testing"
	"time"
)

// screen is claude's prompt, rewind list and confirmation, drawn as 2.1.302
// draws them, enough to rewind through.
type screen struct {
	messages []string // in the rewind list, oldest first
	code     bool     // whether it offers to restore code
	mode     string   // "prompt", "list" or "confirm"
	prompt   string
	cursor   int    // in the list; len(messages) is (current)
	height   int    // rows the pane has, if fewer than drawn
	quote    string // what the confirmation quotes, if not the message
	restored int    // the message rewound to, or -1
	withCode bool
	keys     []string
}

func newScreen(messages ...string) *screen {
	return &screen{messages: messages, mode: "prompt", restored: -1}
}

const width = 56

func (s *screen) Screen() ([]string, error) {
	rows := s.draw()
	if s.height > 0 && len(rows) > s.height {
		rows = rows[len(rows)-s.height:] // the top scrolled off
	}
	return rows, nil
}

// draw is all of the screen, as tall as it needs.
func (s *screen) draw() []string {
	rule := strings.Repeat("─", width)
	switch s.mode {
	case "list":
		rows := []string{"⏺ two", "", rule, "  Rewind", "", "  Restore the code and/or conversation to the point", "  before…", ""}
		all := append(slices.Clone(s.messages), "(current)")
		// Three at a time, around the cursor.
		from := min(max(s.cursor-1, 0), max(len(all)-3, 0))
		to := min(from+3, len(all))
		if from > 0 {
			rows = append(rows, fmt.Sprintf("    ↑ %d more", from))
		}
		for i := from; i < to; i++ {
			m := all[i]
			mark := "    "
			if i == s.cursor {
				mark = "  ❯ "
			}
			if r := []rune(m); len(r) > width-8 {
				m = string(r[:width-9]) + "…"
			}
			rows = append(rows, mark+m)
			if i < len(s.messages) {
				rows = append(rows, "    No code changes")
			}
			rows = append(rows, "")
		}
		if to < len(all) {
			rows = append(rows, fmt.Sprintf("    ↓ %d more", len(all)-to), "")
		}
		return append(rows, "  Enter to continue · Esc to cancel", "")
	case "confirm":
		rows := []string{"", "  Rewind", "  Confirm you want to restore to the point before you sent", "  this message:"}
		quote := s.messages[s.cursor]
		if s.quote != "" {
			quote = s.quote
		}
		for r := []rune(quote); len(r) > 0; {
			n := min(len(r), width-6)
			rows = append(rows, "  │ "+string(r[:n]))
			r = r[n:]
		}
		rows = append(rows, "  │ (26s ago)", "", "  The conversation will be forked.")
		options := []string{"Restore conversation", "Summarize from here", "Never mind"}
		if s.code {
			rows = append(rows, "  The code will be restored -1 in rw.txt.")
			options = []string{"Restore code and conversation", "Restore conversation", "Restore code", "Summarize from here", "Summarize up to here"}
		}
		rows = append(rows, "")
		for i, o := range options {
			mark := "    "
			if i == 0 {
				mark = "  ❯ "
			}
			rows = append(rows, fmt.Sprintf("%s%d. %s", mark, i+1, o))
		}
		return append(rows, "", "  ⚠ Rewinding does not affect files edited manually", "  or via bash.", "")
	}
	rows := []string{"⏺ two", "", rule}
	for i, line := range strings.Split(s.prompt, "\n") {
		if i == 0 {
			line = "❯ " + line
		} else {
			line = "  " + line
		}
		rows = append(rows, line)
	}
	return append(rows, rule, "  -- INSERT -- ⏸ manual mode on")
}

func (s *screen) Type(text string) error {
	s.keys = append(s.keys, "type "+text)
	if s.mode == "prompt" {
		s.prompt += text
	}
	return nil
}

func (s *screen) Keys(keys ...string) error {
	for _, k := range keys {
		s.keys = append(s.keys, k)
		switch s.mode + " " + k {
		case "prompt C-u":
			if i := strings.LastIndexByte(s.prompt, '\n'); i < 0 {
				s.prompt = ""
			} else if i == len(s.prompt)-1 {
				s.prompt = s.prompt[:i]
			} else {
				s.prompt = s.prompt[:i+1]
			}
		case "prompt Enter":
			if s.prompt == "/rewind" {
				s.mode, s.prompt, s.cursor = "list", "", len(s.messages)
			}
		case "list Up":
			s.cursor = max(s.cursor-1, 0)
		case "list Enter":
			if s.cursor < len(s.messages) {
				s.mode = "confirm"
			}
		case "list Escape":
			s.mode = "prompt"
		case "confirm Escape":
			s.mode = "list"
		case "confirm 1", "confirm 2":
			n, _ := strconv.Atoi(k)
			s.withCode = s.code && n == 1
			if s.withCode || (s.code && n == 2) || (!s.code && n == 1) {
				s.mode, s.restored, s.prompt = "prompt", s.cursor, s.messages[s.cursor]
			}
		}
	}
	return nil
}

// running sets up a claude that's idle (or state) in a conversation of the
// user's messages, each answered, and returns its Source and pid.
func running(t *testing.T, state string, messages ...string) (*Source, int) {
	t.Helper()
	dir := t.TempDir()
	pid := os.Getpid()
	var lines []string
	parent := "null"
	for i, m := range messages {
		u, a := fmt.Sprintf("u%d", i+1), fmt.Sprintf("a%d", i+1)
		lines = append(lines,
			fmt.Sprintf(`{"type":"user","uuid":%q,"parentUuid":%s,"message":{"content":%q}}`, u, parent, m),
			fmt.Sprintf(`{"type":"assistant","uuid":%q,"parentUuid":%q,"message":{"content":[{"type":"text","text":"ok"}]}}`, a, u))
		parent = strconv.Quote(a)
	}
	for name, body := range map[string]string{
		"projects/-x/s1.jsonl":               strings.Join(lines, "\n") + "\n",
		fmt.Sprintf("sessions/%d.json", pid): fmt.Sprintf(`{"pid":%d,"sessionId":"s1","cwd":"/x","status":%q}`, pid, state),
	} {
		path := filepath.Join(dir, name)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return New(dir), pid
}

func TestRewind(t *testing.T) {
	long := "Use the Write tool to create /private/tmp/claude-502/scratchpad/rw.txt containing hi, then say just: done"
	messages := []string{"one", "two", long, "two"}
	src, pid := running(t, "idle", messages...)

	s := newScreen(messages...)
	s.prompt = "half typed\non the Mac"
	if err := src.Rewind(context.Background(), s, pid, "u2.0", false); err != nil {
		t.Fatal(err)
	}
	// The earlier two, past the later one that looks the same.
	if s.restored != 1 || s.withCode || s.mode != "prompt" || s.prompt != "" {
		t.Errorf("restored %d (code %v), %s %q; keys %q", s.restored, s.withCode, s.mode, s.prompt, s.keys)
	}

	s = newScreen(messages...)
	if err := src.Rewind(context.Background(), s, pid, "u3.0", false); err != nil || s.restored != 2 {
		t.Errorf("a long message: %v, restored %d", err, s.restored)
	}

	// With the keyboard up, the pane's too short for the list's and the confirmation's heads.
	s = newScreen(messages...)
	s.code, s.height = true, 15
	if err := src.Rewind(context.Background(), s, pid, "u3.0", true); err != nil || s.restored != 2 || !s.withCode {
		t.Errorf("a short pane: %v, restored %d (code %v); keys %q", err, s.restored, s.withCode, s.keys)
	}

	// Code goes back too only when asked, and when claude offers.
	s = newScreen(messages...)
	s.code = true
	if err := src.Rewind(context.Background(), s, pid, "u4.0", true); err != nil || s.restored != 3 || !s.withCode {
		t.Errorf("with code: %v, restored %d (code %v)", err, s.restored, s.withCode)
	}
	s = newScreen(messages...)
	s.code = true
	if err := src.Rewind(context.Background(), s, pid, "u4.0", false); err != nil || s.restored != 3 || s.withCode {
		t.Errorf("without code: %v, restored %d (code %v)", err, s.restored, s.withCode)
	}
	s = newScreen(messages...)
	if err := src.Rewind(context.Background(), s, pid, "u1.0", true); err != nil || s.restored != 0 {
		t.Errorf("code asked, none to restore: %v, restored %d", err, s.restored)
	}
}

func TestRewindBacksOut(t *testing.T) {
	defer func(d time.Duration) { rewindDraw = d }(rewindDraw)
	rewindDraw = 100 * time.Millisecond
	src, pid := running(t, "idle", "one", "two")

	// A list without the message (claude's lists only what's since the last compaction).
	s := newScreen("two")
	if err := src.Rewind(context.Background(), s, pid, "u1.0", false); err == nil || s.mode != "prompt" || s.restored != -1 {
		t.Errorf("not listed: %v, %s, restored %d", err, s.mode, s.restored)
	}
	// One that turns out to be another.
	s = newScreen("one", "two")
	s.quote, s.height = "two, but different", 12
	if err := src.Rewind(context.Background(), s, pid, "u2.0", false); err == nil || s.mode != "prompt" || s.restored != -1 {
		t.Errorf("another message: %v, %s, restored %d", err, s.mode, s.restored)
	}
	if err := src.Rewind(context.Background(), newScreen("one", "two"), pid, "a1.0", false); err == nil {
		t.Error("rewound to a reply")
	}

	busy, pid := running(t, "busy", "one", "two")
	s = newScreen("one", "two")
	if err := busy.Rewind(context.Background(), s, pid, "u1.0", false); err == nil || len(s.keys) > 0 {
		t.Errorf("busy: %v, keys %q", err, s.keys)
	}
}
