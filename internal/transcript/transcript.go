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
	// Running maps the CLI's live processes to the transcripts they're
	// writing. A file may not exist yet: a new conversation has none until
	// its first message.
	Running() map[int]string
	// Parser reads one transcript from its start, or from any line.
	Parser() Parser
}

// A Parser turns transcript lines, in order, into the entries they add or
// change: a tool's result changes the entry for its call.
type Parser interface {
	Line(b []byte) []protocol.Entry
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
// transcript (a cleared conversation). It returns when ctx is done, or
// after saying so when the process stops having a transcript.
func Follow(ctx context.Context, src Source, pid int, send func(protocol.Entries)) error {
	var (
		f        *os.File
		path     string
		p        Parser
		offset   int64
		partial  []byte
		announce bool // say there's a conversation, if its file isn't there yet
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
		if cur != path {
			if f != nil {
				f.Close()
				f = nil
			}
			path, announce = cur, true
		}
		if f == nil {
			if f, _ = os.Open(path); f != nil {
				var entries []protocol.Entry
				var more bool
				entries, more, p, offset = recent(f, src, Recent)
				partial, announce = nil, false
				send(protocol.Entries{Reset: true, More: more, Entries: entries})
			} else if announce {
				announce = false
				send(protocol.Entries{Reset: true})
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
			if changed := collect(p, lines); len(changed) > 0 {
				send(protocol.Entries{Entries: changed})
			}
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
		entries = collect(p, lines)
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
