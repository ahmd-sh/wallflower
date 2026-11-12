package claude

import (
	"regexp"
	"strings"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

var (
	typed       = regexp.MustCompile(`^(/[a-z][\w:-]*)(?:[ \t]+([^\n]*))?$`)
	commandName = regexp.MustCompile(`(?s)<command-name>(.*?)</command-name>`)
	commandArgs = regexp.MustCompile(`(?s)<command-args>(.*?)</command-args>`)
	bashInput   = regexp.MustCompile(`(?s)<bash-input>(.*?)</bash-input>`)
	stdout      = regexp.MustCompile(`(?s)<(?:local-command|bash)-stdout>(.*?)</(?:local-command|bash)-stdout>`)
	stderr      = regexp.MustCompile(`(?s)<(?:local-command|bash)-stderr>(.*?)</(?:local-command|bash)-stderr>`)
)

// tagged is whether text is what claude records of a command, in its tags,
// rather than something the user said.
func tagged(text string) bool {
	text = strings.TrimSpace(text)
	for _, t := range []string{"<command-", "<local-command-", "<bash-"} {
		if strings.HasPrefix(text, t) {
			return true
		}
	}
	return false
}

// commandOf reads what claude records of a command the user ran, a slash
// command or one in its shell mode (!), as a step of the user's: the
// command, then its output, which goes with it.
func (p *parser) commandOf(id, text string) (mobileview.Entry, bool) {
	name, args := "", ""
	if m := commandName.FindStringSubmatch(text); m != nil {
		name = strings.TrimSpace(m[1])
		if a := commandArgs.FindStringSubmatch(text); a != nil {
			args = strings.TrimSpace(a[1])
		}
	} else if m := bashInput.FindStringSubmatch(text); m != nil {
		name, args = "!", strings.TrimSpace(m[1])
	}
	if name == "/clear" {
		p.command = nil // what starts the new conversation it's at the top of, which is all it says
		return mobileview.Entry{}, false
	}
	if name != "" {
		e := mobileview.Entry{ID: id, From: mobileview.User, Kind: mobileview.Step, Name: name, Text: args, Status: mobileview.Done}
		p.command = &e
		return e, true
	}
	out, errs := stdout.FindStringSubmatch(text), stderr.FindStringSubmatch(text)
	if out == nil && errs == nil {
		return mobileview.Entry{}, false
	}
	if p.command == nil {
		return mobileview.Entry{}, false // what's left of one read before, or the noted compaction's
	}
	var parts []string
	for _, m := range [][]string{out, errs} {
		if m != nil && strings.TrimSpace(m[1]) != "" {
			parts = append(parts, strings.TrimSpace(m[1]))
		}
	}
	e := *p.command
	e.Output, e.OutputCut = clip(strings.TrimSpace(ansi.ReplaceAllString(strings.Join(parts, "\n"), "")))
	p.command = nil
	return e, true
}
