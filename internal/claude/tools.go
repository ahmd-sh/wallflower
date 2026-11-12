package claude

import (
	"encoding/json"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

const (
	maxOutput = 4000    // characters of a tool's result kept
	maxDiff   = 8000    // characters of a change to a file kept, in whole lines
	maxImage  = 8 << 20 // base64 bytes of an image kept to show; bigger ones are noted
)

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

// toolName is a tool's name as it reads: an MCP server's tool by its own
// name (mcp__playwright__browser_click is browser_click).
func toolName(name string) string {
	if rest, ok := strings.CutPrefix(name, "mcp__"); ok {
		if _, tool, ok := strings.Cut(rest, "__"); ok && tool != "" {
			return tool
		}
	}
	return name
}

// answers is what the user answered claude's questions, a line each, from
// what it records of the question tool's result; "" for any other tool's.
func answers(raw json.RawMessage) string {
	var r struct {
		Questions []struct {
			Question string `json:"question"`
		} `json:"questions"`
		Answers map[string]string `json:"answers"`
	}
	if json.Unmarshal(raw, &r) != nil || len(r.Answers) == 0 {
		return ""
	}
	var lines []string
	for _, q := range r.Questions {
		if a, ok := r.Answers[q.Question]; ok {
			lines = append(lines, q.Question+" → "+a)
		}
	}
	return strings.Join(lines, "\n")
}

// briefKeys are tool inputs that say what a call does, best first.
var briefKeys = []string{"command", "file_path", "path", "pattern", "url", "query", "description", "prompt", "skill", "plan"}

func brief(input json.RawMessage) string {
	var m map[string]any
	if json.Unmarshal(input, &m) == nil {
		// Questions asked, by the first.
		if qs, ok := m["questions"].([]any); ok && len(qs) > 0 {
			if q, ok := qs[0].(map[string]any)["question"].(string); ok && q != "" {
				if len(qs) > 1 {
					q += fmt.Sprintf(" +%d more", len(qs)-1)
				}
				return q
			}
		}
		for _, k := range briefKeys {
			if s, ok := m[k].(string); ok && s != "" {
				s, _ = clip(s)
				return s
			}
		}
	}
	if s := string(input); s != "{}" && s != "null" {
		s, _ = clip(s)
		return s
	}
	return ""
}

var ansi = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)

// result is a tool's output as text (a string, or blocks of text and
// images), trimmed, and how many characters were trimmed.
func result(content json.RawMessage) (string, int) {
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

func image(id, who string, b block) mobileview.Entry {
	e := mobileview.Entry{ID: id, From: who, Kind: mobileview.Picture}
	switch {
	case b.Source == nil || b.Source.Type != "base64":
		e.Kind, e.Text = mobileview.Note, "[image]"
	case len(b.Source.Data) > maxImage:
		e.Kind, e.Text = mobileview.Note, "[image too big to show]"
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
// claude records of its result ("" for any other tool's), cut in whole
// lines, and how many lines were cut.
func diff(raw json.RawMessage) (string, int) {
	var e edit
	if json.Unmarshal(raw, &e) != nil || e.Patch == nil {
		return "", 0
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
