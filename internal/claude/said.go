package claude

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

var (
	pasted      = regexp.MustCompile(`</?pasted_content[^>]*>`)
	summary     = regexp.MustCompile(`(?s)<summary>(.*?)</summary>`)
	interrupted = regexp.MustCompile(`^\[Request interrupted by user[^\]]*\]$`)
)

// said is text either side wrote. Commands, which claude records as user
// text in tags, are steps (commandOf). What claude tells itself, like a
// background task finishing, isn't shown, but its summary is.
func (p *parser) said(r record, i int, text string) (mobileview.Entry, bool) {
	who := from(r.Type)
	text = strings.TrimSpace(text)
	if who == mobileview.User {
		if strings.HasPrefix(text, "<command-") || strings.HasPrefix(text, "<local-command-") {
			return mobileview.Entry{}, false
		}
		if r.PromptSource == "system" {
			return notice(fmt.Sprintf("%s.%d", r.UUID, i), text)
		}
		text = strings.TrimSpace(pasted.ReplaceAllString(text, ""))
	}
	if text == "" {
		return mobileview.Entry{}, false
	}
	e := mobileview.Entry{ID: fmt.Sprintf("%s.%d", r.UUID, i), From: who, Kind: mobileview.Said, Text: text}
	if who == mobileview.User && interrupted.MatchString(text) {
		e.Kind = mobileview.Note
	}
	return e, true
}

// notice is what claude tells itself, as a background task finishing, by
// its summary; nothing if it has none.
func notice(id, text string) (mobileview.Entry, bool) {
	m := summary.FindStringSubmatch(text)
	if m == nil || strings.TrimSpace(m[1]) == "" {
		return mobileview.Entry{}, false
	}
	return mobileview.Entry{ID: id, From: mobileview.Program, Kind: mobileview.Note, Text: strings.TrimSpace(m[1])}, true
}

// taken is a message claude took into the turn it was on, having queued it
// while busy: the user's, a task's notice, or another session's.
func (p *parser) taken(id string, a attachment) (mobileview.Entry, bool) {
	text, ok := decode(a.Prompt).first()
	if !ok {
		return mobileview.Entry{}, false
	}
	switch {
	case a.Origin.peer() || fromAgent(text) && (a.Origin == nil || a.Origin.Kind != "human"):
		return agentOf(id, text, a.Origin)
	case a.CommandMode == "task-notification" || strings.HasPrefix(strings.TrimSpace(text), "<task-notification>"):
		return notice(id, text)
	case a.CommandMode == "prompt":
		if text = strings.TrimSpace(pasted.ReplaceAllString(text, "")); text != "" {
			return mobileview.Entry{ID: id, From: mobileview.User, Kind: mobileview.Said, Text: text}, true
		}
	}
	return mobileview.Entry{}, false
}
