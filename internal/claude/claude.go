// Package claude reads Claude Code's transcripts for internal/transcript:
// which conversation each running claude process is in, from
// sessions/<pid>.json, and the conversation, from
// projects/<dir>/<session>.jsonl, both under ~/.claude. Neither format is
// documented, so whatever doesn't parse is skipped.
package claude

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/transcript"
)

const (
	maxOutput = 4000    // characters of a tool's result kept
	maxImage  = 2 << 20 // base64 bytes of an image sent; bigger ones are named, not shown
	reglob    = 5 * time.Second
)

type Source struct {
	dir   string
	mu    sync.Mutex
	paths map[string]found // session id -> its transcript
}

type found struct {
	path string
	at   time.Time // when a missing file was last looked for
}

// New reads the Claude Code data in dir, usually ~/.claude.
func New(dir string) *Source { return &Source{dir: dir, paths: map[string]found{}} }

func (s *Source) Name() string { return "claude" }

var sessionID = regexp.MustCompile(`^[A-Za-z0-9-]+$`)

// states are what claude says it's doing: busy on a turn, waiting on a
// prompt or question mid-turn, or idle. Shell is idle with background tasks
// still running, which can go on long after the turn.
var states = map[string]string{
	"busy":    protocol.StateWorking,
	"shell":   protocol.StateIdle,
	"waiting": protocol.StateWaiting,
	"idle":    protocol.StateIdle,
}

func (s *Source) Running() map[int]transcript.Session {
	files, _ := filepath.Glob(filepath.Join(s.dir, "sessions", "*.json"))
	running := map[int]transcript.Session{}
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
		t := transcript.Session{Path: s.transcript(sess.SessionID, sess.CWD), State: states[sess.Status]}
		if t.State != "" {
			t.Since = sess.StatusUpdatedAt
		}
		running[sess.PID] = t
	}
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

func (s *Source) Parser() transcript.Parser {
	return &parser{tools: map[string]protocol.Entry{}, next: map[string][]string{}, left: map[string]bool{}}
}

type parser struct {
	tools    map[string]protocol.Entry // calls waiting for their result, by tool_use id
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
	Type             string `json:"type"`
	UUID             string `json:"uuid"`
	ParentUUID       string `json:"parentUuid"`
	IsMeta           bool   `json:"isMeta"`
	IsSidechain      bool   `json:"isSidechain"`
	IsCompactSummary bool   `json:"isCompactSummary"`
	PromptSource     string `json:"promptSource"`
	Subtype          string `json:"subtype"`
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

func (p *parser) Line(b []byte) []protocol.Entry {
	var r record
	if json.Unmarshal(b, &r) != nil || r.IsSidechain || r.UUID == "" {
		return nil
	}
	if p.place(r); p.left[r.UUID] {
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
	if r.IsMeta {
		return nil
	}
	if r.Type != "user" && r.Type != "assistant" {
		return nil
	}
	var text string
	if json.Unmarshal(r.Message.Content, &text) == nil {
		if e, ok := p.said(r, 0, text); ok {
			return []protocol.Entry{e}
		}
		return nil
	}
	var blocks []block
	if json.Unmarshal(r.Message.Content, &blocks) != nil {
		return nil
	}
	var out []protocol.Entry
	for i, bl := range blocks {
		id := fmt.Sprintf("%s.%d", r.UUID, i)
		switch bl.Type {
		case "text":
			if e, ok := p.said(r, i, bl.Text); ok {
				out = append(out, e)
			}
		case "thinking":
			if strings.TrimSpace(bl.Thinking) != "" {
				out = append(out, protocol.Entry{ID: id, Role: "assistant", Kind: "thinking", Text: bl.Thinking})
			}
		case "tool_use":
			e := protocol.Entry{ID: id, Role: "assistant", Kind: "tool", Name: bl.Name, Text: brief(bl.Input)}
			p.tools[bl.ID] = e
			out = append(out, e)
			if plan := planOf(bl); plan != "" {
				out = append(out, protocol.Entry{ID: id + ".plan", Role: "assistant", Kind: "text", Text: plan})
			}
		case "tool_result":
			if e, ok := p.tools[bl.ToolUseID]; ok {
				delete(p.tools, bl.ToolUseID)
				e.Done, e.Error, e.Output = true, bl.IsError, result(bl.Content)
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
func (p *parser) place(r record) {
	switch {
	case p.left[r.ParentUUID]:
		p.left[r.UUID] = true
	case r.prompt() && len(p.next[r.ParentUUID]) > 0:
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

// prompt is whether r is something the user sent, rather than a tool's result
// or a note claude adds.
func (r record) prompt() bool {
	if r.Type != "user" || r.IsMeta {
		return false
	}
	var text string
	if json.Unmarshal(r.Message.Content, &text) == nil {
		return true
	}
	var blocks []block
	json.Unmarshal(r.Message.Content, &blocks)
	for _, b := range blocks {
		if b.Type == "text" || b.Type == "image" {
			return true
		}
	}
	return false
}

func (p *parser) Branches() int { return p.branches }

func (p *parser) Kept(entries []protocol.Entry) []protocol.Entry {
	if len(p.left) == 0 {
		return entries
	}
	var out []protocol.Entry
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
func (p *parser) said(r record, i int, text string) (protocol.Entry, bool) {
	role := r.Type
	text = strings.TrimSpace(text)
	if role == "user" {
		if strings.HasPrefix(text, "<command-") || strings.HasPrefix(text, "<local-command-") {
			return protocol.Entry{}, false
		}
		if r.PromptSource == "system" {
			m := summary.FindStringSubmatch(text)
			if m == nil || strings.TrimSpace(m[1]) == "" {
				return protocol.Entry{}, false
			}
			return protocol.Entry{ID: fmt.Sprintf("%s.%d", r.UUID, i), Role: role, Kind: "note", Text: strings.TrimSpace(m[1])}, true
		}
		text = strings.TrimSpace(pasted.ReplaceAllString(text, ""))
	}
	if text == "" {
		return protocol.Entry{}, false
	}
	e := protocol.Entry{ID: fmt.Sprintf("%s.%d", r.UUID, i), Role: role, Kind: "text", Text: text}
	if r.IsCompactSummary || (role == "user" && interrupted.MatchString(text)) {
		e.Kind = "note"
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

func image(id, role string, b block) protocol.Entry {
	e := protocol.Entry{ID: id, Role: role, Kind: "image"}
	switch {
	case b.Source == nil || b.Source.Type != "base64":
		e.Kind, e.Text = "note", "[image]"
	case len(b.Source.Data) > maxImage:
		e.Kind, e.Text = "note", "[image too big to show]"
	default:
		e.Mime, e.Data = b.Source.MediaType, b.Source.Data
	}
	return e
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
