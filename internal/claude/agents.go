package claude

import (
	"encoding/json"
	"regexp"
	"strings"
	"unicode/utf8"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

const anotherSession = "Another Claude session sent a message:"

var (
	teammate     = regexp.MustCompile(`(?s)^<teammate-message\b([^>]*)>(.*?)(?:</teammate-message>)?$`)
	agentMessage = regexp.MustCompile(`(?s)^<agent-message\b([^>]*)>(.*?)(?:</agent-message>)?$`)
	attribute    = regexp.MustCompile(`([\w-]+)="([^"]*)"`)
	heading      = regexp.MustCompile(`^[#>*_\s-]+`)
)

// fromAgent is whether text is another Claude session's message to this
// one, which claude records as the user's.
func fromAgent(text string) bool {
	text = strings.TrimSpace(text)
	return strings.HasPrefix(text, anotherSession) || strings.HasPrefix(text, "<teammate-message") || strings.HasPrefix(text, "<agent-message")
}

// agentOf is another Claude session's message, a teammate's or a subagent's
// report, as a note of theirs: who sent it, what it's about, and the report
// to open. A teammate gone idle with nothing to say is nothing.
func agentOf(id, text string, o *origin) (mobileview.Entry, bool) {
	text = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(text), anotherSession))
	name, about, body := "", "", text
	if m := teammate.FindStringSubmatch(text); m != nil {
		attrs := attributes(m[1])
		name, about, body = attrs["teammate_id"], attrs["summary"], m[2]
	} else if m := agentMessage.FindStringSubmatch(text); m != nil {
		name, body = attributes(m[1])["from"], m[2]
	}
	body = strings.TrimSpace(body)
	var idle struct {
		Type   string `json:"type"`
		Result string `json:"result"`
	}
	if strings.HasPrefix(body, "{") && json.Unmarshal([]byte(body), &idle) == nil && idle.Type != "" {
		if body = strings.TrimSpace(idle.Result); body == "" {
			return mobileview.Entry{}, false
		}
	}
	body = report(body)
	if o != nil && o.Name != "" {
		name = o.Name // a subagent's kind, where its message names it by id
	}
	if name == "" {
		name = "agent"
	}
	if about == "" {
		about = headline(body)
	}
	e := mobileview.Entry{ID: id, From: mobileview.Program, Kind: mobileview.Note, Name: name, Text: about}
	if body != about {
		e.Output, e.OutputCut = clip(body)
	}
	return e, true
}

func attributes(s string) map[string]string {
	out := map[string]string{}
	for _, m := range attribute.FindAllStringSubmatch(s, -1) {
		out[m[1]] = m[2]
	}
	return out
}

// report is a subagent's report out of the frame claude hands it back in,
// which indents its every line.
func report(body string) string {
	if !strings.HasPrefix(body, "[Subagent hand-back]") {
		return body
	}
	_, rest, ok := strings.Cut(body, "The report follows:")
	if !ok {
		return body
	}
	lines := strings.Split(strings.Trim(rest, "\n"), "\n")
	for i, l := range lines {
		lines[i] = strings.TrimPrefix(l, "  ")
	}
	return strings.TrimSpace(strings.Join(lines, "\n"))
}

// headline is the first line of a report, without its markdown marks, kept short.
func headline(body string) string {
	for _, l := range strings.Split(body, "\n") {
		if l = strings.TrimSpace(heading.ReplaceAllString(l, "")); l != "" {
			if utf8.RuneCountInString(l) > 120 {
				l = string([]rune(l)[:119]) + "…"
			}
			return l
		}
	}
	return ""
}
