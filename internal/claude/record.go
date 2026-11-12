package claude

import (
	"bytes"
	"encoding/json"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

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

// human is whether r is something a person sent: what they paste that reads
// as another session's message is still theirs.
func (r record) human() bool {
	switch r.PromptSource {
	case "typed", "queued", "suggestion_accepted":
		return true
	}
	return r.Origin != nil && r.Origin.Kind == "human"
}

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

// from is who said a record of claude's type: the user, or claude.
func from(typ string) string {
	if typ == "user" {
		return mobileview.User
	}
	return mobileview.Program
}
