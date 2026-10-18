// Package claude reads Claude Code's panes for their read views: which
// conversation each running claude process is in, and what it's doing, from
// sessions/<pid>.json, and the conversation, from
// projects/<dir>/<session>.jsonl, both under ~/.claude. Neither format is
// documented, so whatever doesn't parse is skipped.
package claude

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/ahmd-sh/wallflower/internal/proc"
	"github.com/ahmd-sh/wallflower/internal/readview"
)

const (
	maxOutput = 4000    // characters of a tool's result kept
	maxDiff   = 8000    // characters of a change to a file kept, in whole lines
	maxImage  = 2 << 20 // base64 bytes of an image sent; bigger ones are named, not shown
	reglob    = 5 * time.Second
)

type Source struct {
	dir   string
	mu    sync.Mutex
	paths map[string]found // session id -> its transcript
	ttys  map[int]string   // pid -> the terminal it runs on, while it runs
}

type found struct {
	path string
	at   time.Time // when a missing file was last looked for
}

// New reads the Claude Code data in dir, usually ~/.claude.
func New(dir string) *Source {
	return &Source{dir: dir, paths: map[string]found{}, ttys: map[int]string{}}
}

func (s *Source) Name() string { return "claude" }

// Find picks the panes a claude runs in, by the terminals they share.
func (s *Source) Find(panes []readview.Pane) map[string]bool {
	ids := map[string]string{}
	for _, p := range panes {
		if p.TTY != "" {
			ids[p.TTY] = p.ID
		}
	}
	found := map[string]bool{}
	for pid := range s.running() {
		if id, ok := ids[s.tty(pid)]; ok {
			found[id] = true
		}
	}
	return found
}

var errNone = errors.New("no conversation in this pane")

// Read follows the conversation of the claude running in a pane.
func (s *Source) Read(ctx context.Context, pane readview.Pane, send func(readview.Update)) error {
	pid := 0
	for p := range s.running() {
		if pane.TTY != "" && s.tty(p) == pane.TTY {
			pid = p
		}
	}
	if pid == 0 {
		return errNone
	}
	recording := func() (readview.Recording, bool) {
		r, ok := s.running()[pid]
		return r, ok
	}
	return readview.Tail(ctx, recording, newParser, send)
}

func (s *Source) tty(pid int) string {
	s.mu.Lock()
	tty, ok := s.ttys[pid]
	s.mu.Unlock()
	if !ok {
		tty = proc.TTY(pid)
		s.mu.Lock()
		s.ttys[pid] = tty
		s.mu.Unlock()
	}
	return tty
}

var sessionID = regexp.MustCompile(`^[A-Za-z0-9-]+$`)

// activities are what claude says it's doing: busy on a turn, waiting on a
// prompt or question mid-turn, or idle. Shell is idle with background tasks
// still running, which can go on long after the turn.
var activities = map[string]readview.Activity{
	"busy":    readview.Working,
	"shell":   readview.Idle,
	"waiting": readview.Waiting,
	"idle":    readview.Idle,
}

// running maps the claude processes running now to their recordings.
func (s *Source) running() map[int]readview.Recording {
	files, _ := filepath.Glob(filepath.Join(s.dir, "sessions", "*.json"))
	running := map[int]readview.Recording{}
	for _, name := range files {
		b, err := os.ReadFile(name)
		if err != nil {
			continue
		}
		var sess struct {
			PID             int    `json:"pid"`
			SessionID       string `json:"sessionId"`
			CWD             string `json:"cwd"`
			Status          string `json:"status"`
			StatusUpdatedAt int64  `json:"statusUpdatedAt"`
		}
		if json.Unmarshal(b, &sess) != nil || sess.PID <= 0 || !sessionID.MatchString(sess.SessionID) {
			continue
		}
		if syscall.Kill(sess.PID, 0) != nil {
			continue // left behind by a claude that crashed
		}
		r := readview.Recording{Path: s.transcript(sess.SessionID, sess.CWD), Activity: activities[sess.Status]}
		if r.Activity != "" {
			r.Since = sess.StatusUpdatedAt
		}
		running[sess.PID] = r
	}
	s.mu.Lock()
	for pid := range s.ttys {
		if _, ok := running[pid]; !ok {
			delete(s.ttys, pid) // gone; its pid may come back as another process
		}
	}
	s.mu.Unlock()
	return running
}

// transcript finds a session's file, which lives in a directory named after
// the directory claude started in: projects/-Users-me-dev-x/<id>.jsonl.
// Until the file exists, that's where it's expected.
func (s *Source) transcript(id, cwd string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if f, ok := s.paths[id]; ok && (f.at.IsZero() || time.Since(f.at) < reglob) {
		return f.path
	}
	if m, _ := filepath.Glob(filepath.Join(s.dir, "projects", "*", id+".jsonl")); len(m) > 0 {
		s.paths[id] = found{path: m[0]}
		return m[0]
	}
	path := filepath.Join(s.dir, "projects", projectDir(cwd), id+".jsonl")
	s.paths[id] = found{path: path, at: time.Now()}
	return path
}

var notAlnum = regexp.MustCompile(`[^A-Za-z0-9]`)

func projectDir(cwd string) string { return notAlnum.ReplaceAllString(cwd, "-") }

func newParser() readview.Parser {
	return &parser{tools: map[string]readview.Entry{}, next: map[string][]string{}, left: map[string]bool{}}
}

type parser struct {
	tools    map[string]readview.Entry // calls waiting for their result, by tool_use id
	context  int                       // tokens in context as of the latest reply or compaction
	next     map[string][]string       // record -> the records going on from it
	left     map[string]bool           // records a rewind left behind
	branches int
}

// Context is what the latest reply's request held, cached or not, and the
// reply itself (the next request holds that too). A compaction leaves it
// unknown until the next reply: claude counts what's left without the system
// prompt and tools that the replies' counts include.
func (p *parser) Context() int { return p.context }

type record struct {
	Type             string          `json:"type"`
	UUID             string          `json:"uuid"`
	ParentUUID       string          `json:"parentUuid"`
	IsMeta           bool            `json:"isMeta"`
	IsSidechain      bool            `json:"isSidechain"`
	IsCompactSummary bool            `json:"isCompactSummary"`
	PromptSource     string          `json:"promptSource"`
	Subtype          string          `json:"subtype"`
	ToolUseResult    json.RawMessage `json:"toolUseResult"`
	Message          struct {
		Content json.RawMessage `json:"content"`
		Usage   *struct {
			InputTokens              int `json:"input_tokens"`
			CacheCreationInputTokens int `json:"cache_creation_input_tokens"`
			CacheReadInputTokens     int `json:"cache_read_input_tokens"`
			OutputTokens             int `json:"output_tokens"`
		} `json:"usage"`
	} `json:"message"`
}

type block struct {
	Type      string          `json:"type"`
	Text      string          `json:"text"`
	Thinking  string          `json:"thinking"`
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Input     json.RawMessage `json:"input"`
	ToolUseID string          `json:"tool_use_id"`
	IsError   bool            `json:"is_error"`
	Content   json.RawMessage `json:"content"`
	Source    *struct {
		Type      string `json:"type"`
		MediaType string `json:"media_type"`
		Data      string `json:"data"`
	} `json:"source"`
}

// content is what a message says: text, or blocks of it, decoded once.
type content struct {
	text   *string
	blocks []block
}

func decode(raw json.RawMessage) (c content) {
	raw = bytes.TrimLeft(raw, " \t\r\n")
	if len(raw) == 0 {
		return c
	}
	switch raw[0] {
	case '"':
		var s string
		if json.Unmarshal(raw, &s) == nil {
			c.text = &s
		}
	case '[':
		json.Unmarshal(raw, &c.blocks)
	}
	return c
}

func (p *parser) Line(b []byte) []readview.Entry {
	var r record
	if json.Unmarshal(b, &r) != nil || r.IsSidechain || r.UUID == "" {
		return nil
	}
	talk := !r.IsMeta && (r.Type == "user" || r.Type == "assistant")
	var c content
	if talk {
		c = decode(r.Message.Content)
	}
	if p.place(r, c); p.left[r.UUID] {
		return nil
	}
	if u := r.Message.Usage; r.Type == "assistant" && u != nil {
		if n := u.InputTokens + u.CacheCreationInputTokens + u.CacheReadInputTokens + u.OutputTokens; n > 0 {
			p.context = n
		}
	}
	if r.Subtype == "compact_boundary" {
		p.context = 0
	}
	if !talk {
		return nil
	}
	if c.text != nil {
		if e, ok := p.said(r, 0, *c.text); ok {
			return []readview.Entry{e}
		}
		return nil
	}
	var out []readview.Entry
	for i, bl := range c.blocks {
		id := fmt.Sprintf("%s.%d", r.UUID, i)
		switch bl.Type {
		case "text":
			if e, ok := p.said(r, i, bl.Text); ok {
				out = append(out, e)
			}
		case "thinking":
			if strings.TrimSpace(bl.Thinking) != "" {
				out = append(out, readview.Entry{ID: id, Role: readview.Assistant, Kind: readview.Thinking, Text: bl.Thinking})
			}
		case "tool_use":
			e := readview.Entry{ID: id, Role: readview.Assistant, Kind: readview.Tool, Name: bl.Name, Text: brief(bl.Input)}
			p.tools[bl.ID] = e
			out = append(out, e)
			if plan := planOf(bl); plan != "" {
				out = append(out, readview.Entry{ID: id + ".plan", Role: readview.Assistant, Kind: readview.Text, Text: plan})
			}
		case "tool_result":
			if e, ok := p.tools[bl.ToolUseID]; ok {
				delete(p.tools, bl.ToolUseID)
				e.Done, e.Error, e.Output = true, bl.IsError, result(bl.Content)
				if len(c.blocks) == 1 { // what the record says of the result is this one's
					e.Diff = diff(r.ToolUseResult)
				}
				out = append(out, e)
			}
		case "image":
			out = append(out, image(id, r.Type, bl))
		}
	}
	return out
}

// place puts r in the conversation, which is a tree: each record goes on
// from the one before it in the conversation (its parent), not necessarily
// the line before. A prompt going on from a record that something already
// goes on from is the conversation taken back there, by a rewind or a
// message taken back before its reply: what went on from it before is left
// behind, as is anything later going on from that.
func (p *parser) place(r record, c content) {
	switch {
	case p.left[r.ParentUUID]:
		p.left[r.UUID] = true
	case r.Type == "user" && c.prompt() && len(p.next[r.ParentUUID]) > 0:
		gone := append([]string(nil), p.next[r.ParentUUID]...)
		for len(gone) > 0 {
			id := gone[len(gone)-1]
			gone = gone[:len(gone)-1]
			if !p.left[id] {
				p.left[id] = true
				gone = append(gone, p.next[id]...)
			}
		}
		p.branches++
		p.context = 0 // until the next reply: the last was on the branch left behind
	}
	if r.ParentUUID != "" {
		p.next[r.ParentUUID] = append(p.next[r.ParentUUID], r.UUID)
	}
}

// prompt is whether a user's message is something they sent, rather than a
// tool's result.
func (c content) prompt() bool {
	if c.text != nil {
		return true
	}
	for _, b := range c.blocks {
		if b.Type == "text" || b.Type == "image" {
			return true
		}
	}
	return false
}

func (p *parser) Branches() int { return p.branches }

func (p *parser) Kept(entries []readview.Entry) []readview.Entry {
	if len(p.left) == 0 {
		return entries
	}
	var out []readview.Entry
	for _, e := range entries {
		if uuid, _, _ := strings.Cut(e.ID, "."); !p.left[uuid] {
			out = append(out, e)
		}
	}
	return out
}

var (
	pasted      = regexp.MustCompile(`</?pasted_content[^>]*>`)
	summary     = regexp.MustCompile(`(?s)<summary>(.*?)</summary>`)
	interrupted = regexp.MustCompile(`^\[Request interrupted by user[^\]]*\]$`)
)

// said is text either side wrote. claude also records slash commands and
// their output as user text, wrapped in tags; those aren't shown. Nor is what
// claude tells itself, like a background task finishing, but its summary is.
func (p *parser) said(r record, i int, text string) (readview.Entry, bool) {
	role := r.Type
	text = strings.TrimSpace(text)
	if role == readview.User {
		if strings.HasPrefix(text, "<command-") || strings.HasPrefix(text, "<local-command-") {
			return readview.Entry{}, false
		}
		if r.PromptSource == "system" {
			m := summary.FindStringSubmatch(text)
			if m == nil || strings.TrimSpace(m[1]) == "" {
				return readview.Entry{}, false
			}
			return readview.Entry{ID: fmt.Sprintf("%s.%d", r.UUID, i), Role: role, Kind: readview.Note, Text: strings.TrimSpace(m[1])}, true
		}
		text = strings.TrimSpace(pasted.ReplaceAllString(text, ""))
	}
	if text == "" {
		return readview.Entry{}, false
	}
	e := readview.Entry{ID: fmt.Sprintf("%s.%d", r.UUID, i), Role: role, Kind: readview.Text, Text: text}
	if r.IsCompactSummary || (role == readview.User && interrupted.MatchString(text)) {
		e.Kind = readview.Note
	}
	return e, true
}

// planOf is the plan in a plan mode write, which claude keeps in
// plans/<name>.md, for reading as a reply rather than a tool's input.
func planOf(b block) string {
	if b.Name != "Write" {
		return ""
	}
	var in struct {
		FilePath string `json:"file_path"`
		Content  string `json:"content"`
	}
	if json.Unmarshal(b.Input, &in) != nil || filepath.Base(filepath.Dir(in.FilePath)) != "plans" ||
		filepath.Base(filepath.Dir(filepath.Dir(in.FilePath))) != ".claude" || !strings.HasSuffix(in.FilePath, ".md") {
		return ""
	}
	return strings.TrimSpace(in.Content)
}

// briefKeys are tool inputs that say what a call does, best first.
var briefKeys = []string{"command", "file_path", "path", "pattern", "url", "query", "description", "prompt", "skill", "plan"}

func brief(input json.RawMessage) string {
	var m map[string]any
	if json.Unmarshal(input, &m) == nil {
		for _, k := range briefKeys {
			if s, ok := m[k].(string); ok && s != "" {
				return clip(s)
			}
		}
	}
	if s := string(input); s != "{}" && s != "null" {
		return clip(s)
	}
	return ""
}

var ansi = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)

// result is a tool's output as text: a string, or blocks of text and images.
func result(content json.RawMessage) string {
	var s string
	if json.Unmarshal(content, &s) != nil {
		var blocks []block
		json.Unmarshal(content, &blocks)
		var parts []string
		for _, b := range blocks {
			switch b.Type {
			case "text":
				parts = append(parts, b.Text)
			case "image":
				parts = append(parts, "[image]")
			}
		}
		s = strings.Join(parts, "\n")
	}
	return clip(strings.TrimSpace(ansi.ReplaceAllString(s, "")))
}

func image(id, role string, b block) readview.Entry {
	e := readview.Entry{ID: id, Role: role, Kind: readview.Image}
	switch {
	case b.Source == nil || b.Source.Type != "base64":
		e.Kind, e.Text = readview.Note, "[image]"
	case len(b.Source.Data) > maxImage:
		e.Kind, e.Text = readview.Note, "[image too big to show]"
	default:
		e.Mime, e.Data = b.Source.MediaType, b.Source.Data
	}
	return e
}

// edit is what claude records of a file tool's result: the change it made
// as hunks, which a new file has none of, and the file's content.
type edit struct {
	Type    string `json:"type"`
	Content string `json:"content"`
	Patch   *[]struct {
		OldStart int      `json:"oldStart"`
		OldLines int      `json:"oldLines"`
		NewStart int      `json:"newStart"`
		NewLines int      `json:"newLines"`
		Lines    []string `json:"lines"`
	} `json:"structuredPatch"`
}

// diff is the change a file tool made, as unified diff hunks, from what
// claude records of its result; "" for any other tool's.
func diff(raw json.RawMessage) string {
	var e edit
	if json.Unmarshal(raw, &e) != nil || e.Patch == nil {
		return ""
	}
	var lines []string
	for _, h := range *e.Patch {
		lines = append(lines, fmt.Sprintf("@@ -%d,%d +%d,%d @@", h.OldStart, h.OldLines, h.NewStart, h.NewLines))
		lines = append(lines, h.Lines...)
	}
	if len(lines) == 0 && e.Type == "create" && e.Content != "" {
		added := strings.Split(strings.TrimSuffix(e.Content, "\n"), "\n")
		lines = append(lines, fmt.Sprintf("@@ -0,0 +1,%d @@", len(added)))
		for _, l := range added {
			lines = append(lines, "+"+l)
		}
	}
	var b strings.Builder
	for i, l := range lines {
		if b.Len()+len(l) > maxDiff {
			fmt.Fprintf(&b, "… %d more lines\n", len(lines)-i)
			break
		}
		b.WriteString(l + "\n")
	}
	return strings.TrimSuffix(b.String(), "\n")
}

func clip(s string) string {
	if len(s) <= maxOutput {
		return s
	}
	cut := maxOutput
	for cut > 0 && s[cut]&0xC0 == 0x80 { // not mid-character
		cut--
	}
	return s[:cut] + "…"
}
