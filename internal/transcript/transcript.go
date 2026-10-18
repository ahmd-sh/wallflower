// Package transcript follows the conversations that AI coding CLIs record,
// as entries a client can render: what each side said, and the tools run. A
// Source knows where one CLI keeps its transcripts and how to read them
// (see internal/claude); Follow keeps a client current with one.
package transcript

import (
	"bytes"
	"context"
	"os"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

// A Source is a CLI that records its conversations to files, one JSON
// object per line.
type Source interface {
	// Name is how clients know the source.
	Name() string
	// Running maps the CLI's live processes to their sessions.
	Running() map[int]Session
	// Parser reads one transcript from its start, or from any line.
	Parser() Parser
}

// A Session is a running CLI's conversation: the transcript it's writing,
// which may not exist yet (a new conversation has none until its first
// message), and what it's doing, if it says (protocol.StateWorking...),
// since when (Unix milliseconds).
type Session struct {
	Path  string
	State string
	Since int64
}

// A Parser turns transcript lines, in order, into the entries they add or
// change: a tool's result changes the entry for its call.
type Parser interface {
	Line(b []byte) []protocol.Entry
}

// A Measure is a Parser that knows how many tokens the conversation takes of
// the CLI's context, as of the lines it has read; 0 until it does.
type Measure interface {
	Context() int
}

// A Brancher is a Parser of a CLI that can take its conversation back to an
// earlier point and go on from there, as a rewind does: what came after that
// point is left behind, no longer in the conversation.
type Brancher interface {
	// Branches counts the times the conversation went back, in the lines read.
	Branches() int
	// Kept is entries this parser read, less those left behind.
	Kept(entries []protocol.Entry) []protocol.Entry
}

// A Rewinder is a Source whose CLI can take its conversation back to before
// one of the user's messages, driven through the terminal it runs in.
type Rewinder interface {
	// Rewind takes pid's conversation back to before the message entry id
	// is from, restoring the files the CLI changed since if code, and
	// leaves its prompt empty.
	Rewind(ctx context.Context, t Terminal, pid int, id string, code bool) error
}

// A Terminal is the pane a CLI runs in.
type Terminal interface {
	// Screen is the rows on screen, as text.
	Screen() ([]string, error)
	// Keys presses keys, by tmux's names for them.
	Keys(keys ...string) error
	// Type types text as it is.
	Type(text string) error
}

// Read parses the whole transcript at path, for the conversation as it stands.
func Read(path string, p Parser) ([]protocol.Entry, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	lines, _ := split(b)
	return kept(p, collect(p, lines)), nil
}

func kept(p Parser, entries []protocol.Entry) []protocol.Entry {
	if b, ok := p.(Brancher); ok {
		return b.Kept(entries)
	}
	return entries
}

func branches(p Parser) int {
	if b, ok := p.(Brancher); ok {
		return b.Branches()
	}
	return 0
}

// known is n to send, if there's anything to say.
func known(n int) *int {
	if n == 0 {
		return nil
	}
	return &n
}

func contextOf(p Parser) int {
	if m, ok := p.(Measure); ok {
		return m.Context()
	}
	return 0
}

const (
	// Recent is how many entries a client gets first; older ones stay in
	// the terminal's scrollback.
	Recent = 200
	poll   = 400 * time.Millisecond
	// firstRead is how much of a transcript's end is read for its recent
	// entries, doubling until there are enough.
	firstRead = 2 << 20
)

// Follow sends pid's conversation: its recent entries, then entries as
// they're written, starting over when the process moves to another
// transcript (a cleared conversation) or takes its conversation back to an
// earlier point (a rewind), and what the process is doing, and how full its
// context is, as those change. It returns when ctx is done, or after saying
// so when the process stops having a transcript.
func Follow(ctx context.Context, src Source, pid int, send func(protocol.Entries)) error {
	var (
		f        *os.File
		path     string
		p        Parser
		offset   int64
		partial  []byte
		announce bool    // say there's a conversation, if its file isn't there yet
		said     Session // the state last sent
		measured int     // the context last sent
		branched int     // the parser's branches, as of the entries sent
	)
	defer func() {
		if f != nil {
			f.Close()
		}
	}()
	t := time.NewTicker(poll)
	defer t.Stop()
	for {
		cur, ok := src.Running()[pid]
		if !ok {
			send(protocol.Entries{Reset: true, Error: "the conversation ended"})
			return nil
		}
		if cur.Path != path {
			if f != nil {
				f.Close()
				f = nil
			}
			path, announce = cur.Path, true
		}
		if f == nil {
			if f, _ = os.Open(path); f != nil {
				var entries []protocol.Entry
				var more bool
				entries, more, p, offset = recent(f, src, Recent)
				partial, announce, said, measured, branched = nil, false, cur, contextOf(p), branches(p)
				send(protocol.Entries{Reset: true, More: more, Entries: entries, State: cur.State, Since: cur.Since, Context: known(measured)})
			} else if announce {
				announce, said, measured = false, cur, 0
				send(protocol.Entries{Reset: true, State: cur.State, Since: cur.Since})
			}
		} else if st, err := f.Stat(); err == nil && st.Size() < offset {
			f.Close() // rewritten: read it afresh
			f = nil
			continue
		} else if err == nil && st.Size() > offset {
			buf := make([]byte, st.Size()-offset)
			n, _ := f.ReadAt(buf, offset)
			offset += int64(n)
			var lines [][]byte
			lines, partial = split(append(partial, buf[:n]...))
			changed := collect(p, lines)
			if branches(p) != branched {
				f.Close() // gone back: entries sent are no longer all in it
				f = nil
				continue
			}
			if len(changed) > 0 {
				send(protocol.Entries{Entries: changed})
			}
		}
		now := measured
		if f != nil {
			now = contextOf(p)
		}
		if (f != nil || !announce) && (cur.State != said.State || cur.Since != said.Since || now != measured) {
			e := protocol.Entries{State: cur.State, Since: cur.Since}
			if now != measured {
				e.Context = &now
			}
			said, measured = cur, now
			send(e)
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-t.C:
		}
	}
}

// recent parses the end of the file for its last n entries, reading more
// of it until there are enough. more says there were earlier ones. It
// returns the parser that read them, to carry on from end, the offset after
// the last whole line.
func recent(f *os.File, src Source, n int) (entries []protocol.Entry, more bool, p Parser, end int64) {
	st, err := f.Stat()
	if err != nil {
		return nil, false, src.Parser(), 0
	}
	size := st.Size()
	for window := int64(firstRead); ; window *= 2 {
		start := max(size-window, 0)
		buf := make([]byte, size-start)
		got, _ := f.ReadAt(buf, start)
		buf = buf[:got]
		cut := 0
		if start > 0 { // the first line is cut short
			cut = bytes.IndexByte(buf, '\n') + 1
		}
		lines, rest := split(buf[cut:])
		p = src.Parser()
		entries = kept(p, collect(p, lines))
		end = start + int64(got-len(rest))
		if len(entries) >= n || start == 0 {
			more = start > 0 || len(entries) > n
			return entries[max(len(entries)-n, 0):], more, p, end
		}
	}
}

// collect parses lines into the entries they add or change, in order, each
// in its latest state.
func collect(p Parser, lines [][]byte) []protocol.Entry {
	var out []protocol.Entry
	at := map[string]int{}
	for _, l := range lines {
		for _, e := range p.Line(l) {
			if i, ok := at[e.ID]; ok {
				out[i] = e
			} else {
				at[e.ID] = len(out)
				out = append(out, e)
			}
		}
	}
	return out
}

// split returns the whole lines in b, and what's left after the last one.
func split(b []byte) (lines [][]byte, rest []byte) {
	for {
		i := bytes.IndexByte(b, '\n')
		if i < 0 {
			return lines, b
		}
		if line := bytes.TrimSpace(b[:i]); len(line) > 0 {
			lines = append(lines, line)
		}
		b = b[i+1:]
	}
}
