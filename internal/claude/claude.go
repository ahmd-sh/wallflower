// Package claude reads Claude Code's panes for their mobile views: which
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
	"slices"
	"strings"
	"sync"
	"syscall"
	"time"
	"unicode/utf8"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
	"github.com/ahmd-sh/wallflower/internal/proc"
)

const (
	maxOutput = 4000    // characters of a tool's result kept
	maxDiff   = 8000    // characters of a change to a file kept, in whole lines
	maxImage  = 8 << 20 // base64 bytes of an image kept to show; bigger ones are noted
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

// Find picks the panes a claude runs in, by the terminals they share, with
// what each is doing.
func (s *Source) Find(panes []mobileview.Pane) map[string]mobileview.Found {
	ids := map[string]string{}
	for _, p := range panes {
		if p.TTY != "" {
			ids[p.TTY] = p.ID
		}
	}
	found := map[string]mobileview.Found{}
	for pid, sess := range s.running() {
		if id, ok := ids[s.tty(pid)]; ok {
			found[id] = mobileview.Found{Activity: sess.activity, Since: sess.since}
		}
	}
	return found
}

var errNone = errors.New("no conversation in this pane")

// Read follows the conversation of the claude running in a pane.
func (s *Source) Read(ctx context.Context, pane mobileview.Pane, send func(mobileview.Update)) error {
	pid := 0
	for p := range s.running() {
		if pane.TTY != "" && s.tty(p) == pane.TTY {
			pid = p
		}
	}
	if pid == 0 {
		return errNone
	}
	recording := func() (mobileview.Recording, bool) {
		sess, ok := s.running()[pid]
		return mobileview.Recording{Path: sess.path}, ok
	}
	return mobileview.Tail(ctx, recording, newParser, send)
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
var activities = map[string]mobileview.Activity{
	"busy":    mobileview.Working,
	"shell":   mobileview.Idle,
	"waiting": mobileview.Waiting,
	"idle":    mobileview.Idle,
}

// A session is a running claude's conversation: where it's recorded, and
// what claude is doing, since when.
type session struct {
	path     string
	activity mobileview.Activity
	since    int64
}

// running maps the claude processes running now to their sessions.
func (s *Source) running() map[int]session {
	files, _ := filepath.Glob(filepath.Join(s.dir, "sessions", "*.json"))
	running := map[int]session{}
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
		r := session{path: s.transcript(sess.SessionID, sess.CWD), activity: activities[sess.Status]}
		if r.activity != "" {
			r.since = sess.StatusUpdatedAt
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

type record struct {
	Type             string          `json:"type"`
	UUID             string          `json:"uuid"`
	ParentUUID       string          `json:"parentUuid"`
	IsMeta           bool            `json:"isMeta"`
	IsSidechain      bool            `json:"isSidechain"`
	IsCompactSummary bool            `json:"isCompactSummary"`
	PromptSource     string          `json:"promptSource"`
	Subtype          string          `json:"subtype"`
	Content          json.RawMessage `json:"content"` // a system record's, or a queue operation's
	CustomTitle      string          `json:"customTitle"`
	AITitle          string          `json:"aiTitle"`
	Operation        string          `json:"operation"` // a queue operation's
	IsAPIError       bool            `json:"isApiErrorMessage"`
	Origin           *origin         `json:"origin"`
	Attachment       *attachment     `json:"attachment"`
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

// origin is where a message to claude came from, as it says: a person, a
// task's notice, or another session (a peer: a subagent or teammate).
type origin struct {
	Kind string `json:"kind"`
	Name string `json:"name"`
}

// peer is whether o is another Claude session.
func (o *origin) peer() bool { return o != nil && o.Kind == "peer" }

// An attachment is something claude adds to the conversation beside the
// messages: one sent while it was busy (queued_command), among others.
type attachment struct {
	Type        string          `json:"type"`
	Prompt      json.RawMessage `json:"prompt"`
	CommandMode string          `json:"commandMode"` // prompt, or task-notification
	Origin      *origin         `json:"origin"`
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
	if text, ok := c.first(); ok && r.Type == "user" && (peer || fromAgent(text)) {
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

// first is a message's text, or its first block of text.
func (c content) first() (string, bool) {
	if c.text != nil {
		return *c.text, true
	}
	for _, b := range c.blocks {
		if b.Type == "text" {
			return b.Text, true
		}
	}
	return "", false
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
	case a.Origin.peer() || fromAgent(text):
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

// from is who said a record of claude's type: the user, or claude.
func from(typ string) string {
	if typ == "user" {
		return mobileview.User
	}
	return mobileview.Program
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
