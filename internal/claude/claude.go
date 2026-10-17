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

func (s *Source) Running() map[int]string {
	files, _ := filepath.Glob(filepath.Join(s.dir, "sessions", "*.json"))
	running := map[int]string{}
	for _, name := range files {
		b, err := os.ReadFile(name)
		if err != nil {
			continue
		}
		var sess struct {
			PID       int    `json:"pid"`
			SessionID string `json:"sessionId"`
			CWD       string `json:"cwd"`
		}
		if json.Unmarshal(b, &sess) != nil || sess.PID <= 0 || !sessionID.MatchString(sess.SessionID) {
			continue
		}
		if syscall.Kill(sess.PID, 0) != nil {
			continue // left behind by a claude that crashed
		}
		running[sess.PID] = s.transcript(sess.SessionID, sess.CWD)
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

func (s *Source) Parser() transcript.Parser { return &parser{tools: map[string]protocol.Entry{}} }

type parser struct {
	tools map[string]protocol.Entry // calls waiting for their result, by tool_use id
}

type record struct {
	Type             string `json:"type"`
	UUID             string `json:"uuid"`
	IsMeta           bool   `json:"isMeta"`
	IsSidechain      bool   `json:"isSidechain"`
	IsCompactSummary bool   `json:"isCompactSummary"`
	Message          struct {
		Content json.RawMessage `json:"content"`
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
	if json.Unmarshal(b, &r) != nil || r.IsSidechain || r.IsMeta || r.UUID == "" {
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

var (
	pasted      = regexp.MustCompile(`</?pasted_content[^>]*>`)
	interrupted = regexp.MustCompile(`^\[Request interrupted by user[^\]]*\]$`)
)

// said is text either side wrote. claude also records slash commands and
// their output as user text, wrapped in tags; those aren't shown.
func (p *parser) said(r record, i int, text string) (protocol.Entry, bool) {
	role := r.Type
	text = strings.TrimSpace(text)
	if role == "user" {
		if strings.HasPrefix(text, "<command-") || strings.HasPrefix(text, "<local-command-") {
			return protocol.Entry{}, false
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

// briefKeys are tool inputs that say what a call does, best first.
var briefKeys = []string{"command", "file_path", "path", "pattern", "url", "query", "description", "prompt", "skill"}

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
