package claude

import (
	"encoding/json"
	"fmt"
	"slices"
	"strings"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

func newParser() mobileview.Parser {
	return &parser{tools: map[string]mobileview.Entry{}, next: map[string][]string{}, left: map[string]bool{}}
}

type parser struct {
	tools     map[string]mobileview.Entry // calls waiting for their result, by tool_use id
	context   int                         // tokens in context as of the latest reply or compaction
	next      map[string][]string         // record -> the records going on from it
	left      map[string]bool             // records a rewind left behind
	branches  int
	command   *mobileview.Entry // the user's latest command, until its output
	compacted bool              // the latest record said was a compaction's summary
	custom    string            // the title the user gave the conversation
	named     string            // the title claude gave it
	queue     []string          // what was sent while claude was busy, not yet taken, oldest first
	dequeued  int               // taken off the queue to start a turn with, before the record says which
}

// Queued is what the user sent while claude was busy that it hasn't taken
// yet, oldest first. Claude queues its own notices the same way; those
// aren't the user's, so they aren't here.
func (p *parser) Queued() []string {
	var out []string
	for _, q := range p.queue {
		if t := strings.TrimSpace(pasted.ReplaceAllString(q, "")); t != "" && !tagged(t) && !fromAgent(t) && !strings.HasPrefix(t, "<task-notification>") {
			out = append(out, t)
		}
	}
	return out
}

// queued follows claude's queue: a message goes on it, and comes off as it's
// taken into the turn going on, or back into the prompt box to edit
// (remove), or to start a turn with (dequeue: the record after says which).
func (p *parser) queued(op string, raw json.RawMessage) {
	var text string
	json.Unmarshal(raw, &text)
	switch op {
	case "enqueue":
		p.queue = append(p.queue, text)
	case "dequeue":
		p.dequeued++
	case "remove":
		p.unqueue(strings.TrimSpace(text), false)
	case "clear", "popAll":
		p.queue, p.dequeued = nil, 0
	}
}

// took follows a message claude started a turn with: after a dequeue, the
// one taken off its queue. One the user typed means claude was idle, with
// nothing queued.
func (p *parser) took(source string, c content) {
	if source == "typed" || source == "suggestion_accepted" {
		p.queue, p.dequeued = nil, 0
		return
	}
	if p.dequeued == 0 {
		return
	}
	p.dequeued--
	text, _ := c.first()
	p.unqueue(strings.TrimSpace(text), true)
}

// unqueue takes text off the queue: the same text, else a message queued
// without any (a picture's), else, if [oldest], the oldest.
func (p *parser) unqueue(text string, oldest bool) {
	at := slices.IndexFunc(p.queue, func(q string) bool { return strings.TrimSpace(q) == text })
	if at < 0 {
		at = slices.IndexFunc(p.queue, func(q string) bool { return strings.TrimSpace(q) == "" })
	}
	if at < 0 && oldest && len(p.queue) > 0 {
		at = 0
	}
	if at >= 0 {
		p.queue = slices.Delete(p.queue, at, at+1)
	}
}

// Title is what the user named the conversation (/rename), or else what
// claude did.
func (p *parser) Title() string {
	if p.custom != "" {
		return p.custom
	}
	return p.named
}

// Context is what the latest reply's request held, cached or not, and the
// reply itself (the next request holds that too). A compaction leaves it
// unknown until the next reply: claude counts what's left without the system
// prompt and tools that the replies' counts include.
func (p *parser) Context() int { return p.context }

func (p *parser) Line(b []byte) []mobileview.Entry {
	var r record
	if json.Unmarshal(b, &r) != nil || r.IsSidechain {
		return nil
	}
	switch r.Type {
	case "custom-title":
		p.custom = strings.TrimSpace(r.CustomTitle)
	case "ai-title":
		p.named = strings.TrimSpace(r.AITitle)
	case "queue-operation":
		p.queued(r.Operation, r.Content)
	}
	if r.UUID == "" {
		return nil
	}
	// Another session's message is recorded as the user's, and a subagent's as meta.
	peer := r.Type == "user" && r.Origin.peer()
	talk := (!r.IsMeta || peer) && (r.Type == "user" || r.Type == "assistant")
	var c content
	if talk {
		c = decode(r.Message.Content)
	}
	if talk && r.Type == "user" && c.prompt() {
		p.took(r.PromptSource, c)
	}
	if p.place(r, c); p.left[r.UUID] {
		return nil
	}
	if a := r.Attachment; r.Type == "attachment" && a != nil && a.Type == "queued_command" {
		if e, ok := p.taken(r.UUID+".0", *a); ok {
			return []mobileview.Entry{e}
		}
		return nil
	}
	if r.Type == "system" {
		var text string
		json.Unmarshal(r.Content, &text)
		text = strings.TrimSpace(text)
		// Its notices, as an unknown command; not its recaps, which retell the turn the view shows.
		if r.Subtype == "informational" && text != "" {
			return []mobileview.Entry{{ID: r.UUID + ".0", From: mobileview.Program, Kind: mobileview.Note, Text: text}}
		}
	}
	if u := r.Message.Usage; r.Type == "assistant" && u != nil {
		if n := u.InputTokens + u.CacheCreationInputTokens + u.CacheReadInputTokens + u.OutputTokens; n > 0 {
			p.context = n
		}
	}
	if r.Subtype == "compact_boundary" {
		p.context = 0
	}
	if r.Type == "system" && r.Subtype == "local_command" {
		var text string
		if json.Unmarshal(r.Content, &text) == nil {
			if e, ok := p.commandOf(r.UUID+".0", strings.TrimSpace(text)); ok {
				return []mobileview.Entry{e}
			}
		}
		return nil
	}
	if !talk {
		return nil
	}
	compacted := p.compacted
	p.compacted = r.IsCompactSummary
	if r.IsCompactSummary {
		e := mobileview.Entry{ID: r.UUID + ".0", From: from(r.Type), Kind: mobileview.Note, Text: "Conversation compacted"}
		if c.text != nil {
			e.Output, e.OutputCut = clip(strings.TrimSpace(*c.text))
		}
		return []mobileview.Entry{e}
	}
	if text, ok := c.first(); ok && r.Type == "user" && (peer || fromAgent(text) && !r.human()) {
		if e, ok := agentOf(r.UUID+".0", text, r.Origin); ok {
			return []mobileview.Entry{e}
		}
		return nil
	}
	if text, ok := c.first(); ok && r.Type == "assistant" && r.IsAPIError {
		return []mobileview.Entry{{ID: r.UUID + ".0", From: mobileview.Program, Kind: mobileview.Note, Text: strings.TrimSpace(text)}}
	}
	if c.text != nil && r.Type == "user" && r.PromptSource == "" {
		// A command as typed, which claude records so before it runs (/compact).
		if m := typed.FindStringSubmatch(strings.TrimSpace(*c.text)); m != nil {
			e := mobileview.Entry{ID: r.UUID + ".0", From: mobileview.User, Kind: mobileview.Step, Name: m[1], Text: strings.TrimSpace(m[2]), Status: mobileview.Done}
			p.command = &e
			return []mobileview.Entry{e}
		}
	}
	if c.text != nil && r.Type == "user" && tagged(*c.text) {
		e, ok := p.commandOf(r.UUID+".0", *c.text)
		if !ok {
			return nil
		}
		if compacted && e.Name == "/compact" {
			p.command = nil // noted as the compaction it made
			return nil
		}
		return []mobileview.Entry{e}
	}
	if c.text != nil {
		if e, ok := p.said(r, 0, *c.text); ok {
			return []mobileview.Entry{e}
		}
		return nil
	}
	var out []mobileview.Entry
	for i, bl := range c.blocks {
		id := fmt.Sprintf("%s.%d", r.UUID, i)
		switch bl.Type {
		case "text":
			if e, ok := p.said(r, i, bl.Text); ok {
				out = append(out, e)
			}
		case "thinking":
			if strings.TrimSpace(bl.Thinking) != "" {
				out = append(out, mobileview.Entry{ID: id, From: mobileview.Program, Kind: mobileview.Thought, Text: bl.Thinking})
			}
		case "tool_use":
			e := mobileview.Entry{ID: id, From: mobileview.Program, Kind: mobileview.Step, Name: toolName(bl.Name), Text: brief(bl.Input), Status: mobileview.Running}
			p.tools[bl.ID] = e
			out = append(out, e)
			if plan := planOf(bl); plan != "" {
				out = append(out, mobileview.Entry{ID: id + ".plan", From: mobileview.Program, Kind: mobileview.Said, Text: plan})
			}
		case "tool_result":
			if e, ok := p.tools[bl.ToolUseID]; ok {
				delete(p.tools, bl.ToolUseID)
				e.Status = mobileview.Done
				if bl.IsError {
					e.Status = mobileview.Failed
				}
				e.Output, e.OutputCut = result(bl.Content)
				if len(c.blocks) == 1 { // what the record says of the result is this one's
					e.Diff, e.DiffCut = diff(r.ToolUseResult)
					if a := answers(r.ToolUseResult); a != "" {
						e.Output, e.OutputCut = clip(a)
					}
				}
				out = append(out, e)
			}
		case "image":
			out = append(out, image(id, from(r.Type), bl))
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
	case r.Type == "user" && c.prompt() && r.PromptSource != "system" && !r.Origin.peer() && len(p.next[r.ParentUUID]) > 0:
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

func (p *parser) Branches() int { return p.branches }

func (p *parser) Kept(entries []mobileview.Entry) []mobileview.Entry {
	if len(p.left) == 0 {
		return entries
	}
	var out []mobileview.Entry
	for _, e := range entries {
		if uuid, _, _ := strings.Cut(e.ID, "."); !p.left[uuid] {
			out = append(out, e)
		}
	}
	return out
}
