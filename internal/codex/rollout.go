package codex

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

const (
	maxOutput = 4000 // characters of a command's output kept
	maxDiff   = 8000 // characters of a change to a file kept, in whole lines
)

// A parser reads a rollout: a line a record, the thread's items as each
// completes (what was said, commands run, files changed) among the
// requests and responses they came of, which aren't read.
type parser struct {
	name    func(id string) string
	thread  string
	cwd     string
	context int
}

// newParser reads a rollout of the thread m, which its first line says too
// (a rollout read from near its end has no first line).
func newParser(name func(string) string, m meta) mobileview.Parser {
	return &parser{name: name, thread: m.id, cwd: m.cwd}
}

func (p *parser) Title() string {
	if p.thread == "" {
		return ""
	}
	return p.name(p.thread)
}

func (p *parser) Context() int { return p.context }

type record struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

type event struct {
	Type   string `json:"type"`
	TurnID string `json:"turn_id"`
	Item   *item  `json:"item"`
	Reason string `json:"reason"`
	Msg    string `json:"message"`
	Info   *struct {
		Last struct {
			Total int `json:"total_tokens"`
		} `json:"last_token_usage"`
	} `json:"info"`
	ID  string `json:"id"`
	CWD string `json:"cwd"`
}

type item struct {
	Type    string `json:"type"`
	ID      string `json:"id"`
	Content []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	} `json:"content"`
	Questions []question        `json:"questions"`
	Summary   []string          `json:"summary_text"`
	Command   []string          `json:"command"`
	Parsed    []parsed          `json:"parsed_cmd"`
	Status    string            `json:"status"`
	Output    string            `json:"aggregated_output"`
	Exit      *int              `json:"exit_code"`
	Changes   map[string]change `json:"changes"`
	Kind      string            `json:"kind"`
	Query     string            `json:"query"`
	Action    struct {
		Queries []string `json:"queries"`
	} `json:"action"`
	Server    string          `json:"server"`
	Tool      string          `json:"tool"`
	Arguments json.RawMessage `json:"arguments"`
}

// parsed is what codex makes of a command: a read, a search, a listing, or unknown.
type parsed struct {
	Type  string `json:"type"`
	Cmd   string `json:"cmd"`
	Name  string `json:"name"`
	Path  string `json:"path"`
	Query string `json:"query"`
}

type change struct {
	Type string `json:"type"`
	Diff string `json:"unified_diff"`
	Move string `json:"move_path"`
}

type question struct {
	Title    string            `json:"title"`
	Question string            `json:"question"`
	Options  []json.RawMessage `json:"options"`
}

func (p *parser) Line(b []byte) []mobileview.Entry {
	var r record
	if json.Unmarshal(b, &r) != nil {
		return nil
	}
	switch r.Type {
	case "session_meta":
		var m event
		if json.Unmarshal(r.Payload, &m) == nil {
			p.thread, p.cwd = m.ID, m.CWD
		}
	case "event_msg":
		var e event
		if json.Unmarshal(r.Payload, &e) != nil {
			return nil
		}
		switch e.Type {
		case "item_completed":
			if e.Item != nil {
				return p.item(*e.Item)
			}
		case "token_count":
			if e.Info != nil && e.Info.Last.Total > 0 {
				p.context = e.Info.Last.Total
			}
		case "turn_aborted":
			if e.Reason == "interrupted" {
				return []mobileview.Entry{{ID: "aborted:" + e.TurnID, From: mobileview.Program, Kind: mobileview.Note, Text: "Interrupted"}}
			}
		case "error":
			if e.Msg != "" {
				return []mobileview.Entry{{ID: "error:" + e.TurnID + ":" + e.Msg, From: mobileview.Program, Kind: mobileview.Note, Text: e.Msg}}
			}
		}
	}
	return nil
}

// tagged is text codex wraps in a tag of its own: the environment, instructions, what it adds of its own accord.
var tagged = regexp.MustCompile(`^\s*<([a-z_]+)>[\s\S]*</([a-z_]+)>\s*$`)

const replyTag = "send_user_message_question_reply"

func (p *parser) item(it item) []mobileview.Entry {
	switch it.Type {
	case "UserMessage":
		text := it.text()
		if m := tagged.FindStringSubmatch(text); m != nil {
			if m[1] == replyTag {
				return reply(it.ID, text)
			}
			return nil
		}
		if strings.TrimSpace(text) == "" {
			return nil
		}
		return []mobileview.Entry{{ID: it.ID, From: mobileview.User, Kind: mobileview.Said, Text: text}}
	case "AgentMessage":
		if len(it.Questions) > 0 {
			return []mobileview.Entry{{ID: it.ID, From: mobileview.Program, Kind: mobileview.Said, Text: asked(it.Questions)}}
		}
		if text := strings.TrimSpace(it.text()); text != "" {
			return []mobileview.Entry{{ID: it.ID, From: mobileview.Program, Kind: mobileview.Said, Text: text}}
		}
	case "Reasoning":
		if text := strings.TrimSpace(strings.Join(it.Summary, "\n\n")); text != "" {
			return []mobileview.Entry{{ID: it.ID, From: mobileview.Program, Kind: mobileview.Thought, Text: text}}
		}
	case "CommandExecution":
		name, text := ran(it)
		out, cut := clip(strings.TrimSpace(ansi.ReplaceAllString(it.Output, "")))
		return []mobileview.Entry{{ID: it.ID, From: mobileview.Program, Kind: mobileview.Step, Name: name, Text: text, Status: status(it), Output: out, OutputCut: cut}}
	case "FileChange":
		paths := make([]string, 0, len(it.Changes))
		for path := range it.Changes {
			paths = append(paths, path)
		}
		slices.Sort(paths)
		var out []mobileview.Entry
		for i, path := range paths {
			c := it.Changes[path]
			text := p.rel(path)
			if c.Move != "" {
				text += " → " + p.rel(c.Move)
			}
			d, cut := diff(c.Diff)
			id := it.ID
			if i > 0 {
				id = fmt.Sprintf("%s:%d", it.ID, i)
			}
			out = append(out, mobileview.Entry{ID: id, From: mobileview.Program, Kind: mobileview.Step, Name: changed[c.Type], Text: text, Status: status(it), Diff: d, DiffCut: cut})
		}
		return out
	case "Extension":
		if it.Kind == "web.search" {
			q := strings.Join(it.Action.Queries, "\n")
			if q == "" {
				q = it.Query
			}
			return []mobileview.Entry{{ID: it.ID, From: mobileview.Program, Kind: mobileview.Step, Name: "Web search", Text: q, Status: mobileview.Done}}
		}
	case "McpToolCall":
		args, _ := clip(strings.TrimSpace(string(it.Arguments)))
		if args == "{}" || args == "null" {
			args = ""
		}
		return []mobileview.Entry{{ID: it.ID, From: mobileview.Program, Kind: mobileview.Step, Name: it.Tool, Text: args, Status: status(it)}}
	case "ContextCompaction":
		return []mobileview.Entry{{ID: it.ID, From: mobileview.Program, Kind: mobileview.Note, Text: "Compacted the conversation"}}
	}
	return nil
}

var changed = map[string]string{"add": "Add", "update": "Edit", "delete": "Delete"}

func (it item) text() string {
	var parts []string
	for _, c := range it.Content {
		if strings.EqualFold(c.Type, "text") && c.Text != "" {
			parts = append(parts, c.Text)
		}
	}
	return strings.Join(parts, "\n\n")
}

// ran is how a command reads: what it read, searched or listed, as codex
// works it out, or the command, without the shell that ran it.
func ran(it item) (name, text string) {
	if len(it.Parsed) > 0 && !slices.ContainsFunc(it.Parsed, func(c parsed) bool { return c.Type != it.Parsed[0].Type }) {
		var what []string
		for _, c := range it.Parsed {
			switch c.Type {
			case "read":
				what = append(what, cmp(c.Path, c.Name, c.Cmd))
			case "search":
				w := c.Query
				if c.Path != "" && w != "" {
					w += " in " + c.Path
				}
				what = append(what, cmp(w, c.Cmd))
			case "list_files":
				what = append(what, cmp(c.Path, c.Cmd))
			}
		}
		if n, ok := verbs[it.Parsed[0].Type]; ok && len(what) == len(it.Parsed) {
			return n, strings.Join(what, ", ")
		}
	}
	cmd := strings.Join(it.Command, " ")
	if len(it.Command) == 3 && strings.HasPrefix(it.Command[1], "-") && strings.HasSuffix(it.Command[1], "c") {
		cmd = it.Command[2] // sh -lc "…"
	}
	return "Ran", cmd
}

var verbs = map[string]string{"read": "Read", "search": "Search", "list_files": "List"}

func cmp(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}

func status(it item) string {
	switch it.Status {
	case "in_progress":
		return mobileview.Running
	case "completed", "":
		if it.Exit == nil || *it.Exit == 0 {
			return mobileview.Done
		}
	}
	return mobileview.Failed
}

// rel is a path from the thread's directory, if it's under it.
func (p *parser) rel(path string) string {
	path = strings.TrimPrefix(path, "file://")
	if p.cwd != "" {
		if r, err := filepath.Rel(p.cwd, path); err == nil && !strings.HasPrefix(r, "..") {
			return r
		}
	}
	return path
}

// asked is what codex asks while it works, as it shows it: each question's
// title, what it asks if more, and the options to pick from. It's answered
// whenever the user gets to it, from codex's terminal.
func asked(qs []question) string {
	var b strings.Builder
	for i, q := range qs {
		if i > 0 {
			b.WriteString("\n\n")
		}
		title := cmp(q.Title, q.Question)
		b.WriteString("**" + strings.TrimSpace(title) + "**")
		if q.Question != "" && q.Question != title {
			b.WriteString("\n\n" + q.Question)
		}
		for _, raw := range q.Options {
			var s string
			if json.Unmarshal(raw, &s) != nil {
				var o struct {
					Label       string `json:"label"`
					Description string `json:"description"`
				}
				json.Unmarshal(raw, &o)
				s = o.Label
				if o.Description != "" {
					s += " — " + o.Description
				}
			}
			if s != "" {
				b.WriteString("\n- " + s)
			}
		}
	}
	return b.String()
}

// reply is the user's answers to codex's questions, as codex passes them on: question → answer, a line each.
func reply(id, text string) []mobileview.Entry {
	body := strings.TrimSpace(text)
	body = strings.TrimSuffix(strings.TrimPrefix(body, "<"+replyTag+">"), "</"+replyTag+">")
	var answers []struct {
		Answer   string `json:"answer"`
		Question string `json:"question"`
	}
	if json.Unmarshal([]byte(strings.TrimSpace(body)), &answers) != nil || len(answers) == 0 {
		return nil
	}
	var lines []string
	for _, a := range answers {
		if a.Question != "" {
			lines = append(lines, a.Question+" → "+a.Answer)
		} else {
			lines = append(lines, a.Answer)
		}
	}
	return []mobileview.Entry{{ID: id, From: mobileview.User, Kind: mobileview.Said, Text: strings.Join(lines, "\n\n")}}
}

var ansi = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)

// diff is a file's change, cut in whole lines, and how many lines were cut.
func diff(d string) (string, int) {
	lines := strings.Split(strings.TrimSuffix(d, "\n"), "\n")
	var b strings.Builder
	for i, l := range lines {
		if b.Len()+len(l) > maxDiff {
			return strings.TrimSuffix(b.String(), "\n"), len(lines) - i
		}
		b.WriteString(l + "\n")
	}
	return strings.TrimSuffix(b.String(), "\n"), 0
}

// clip trims s to maxOutput bytes, not mid-character, and says how many
// characters went.
func clip(s string) (string, int) {
	if len(s) <= maxOutput {
		return s, 0
	}
	cut := maxOutput
	for cut > 0 && s[cut]&0xC0 == 0x80 {
		cut--
	}
	return s[:cut], utf8.RuneCountInString(s[cut:])
}
