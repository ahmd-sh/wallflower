package readview

import (
	"bytes"
	"context"
	"errors"
	"os"
	"time"
)

// A Recording is where a program writes what's said as it goes, one JSON
// object per line, as AI coding CLIs keep transcripts: the file, which may
// not exist yet (a new conversation has none until its first message), and
// what the program is doing, if it says.
type Recording struct {
	Path     string
	Activity Activity
	Since    int64 // Unix milliseconds
}

// A Parser turns a recording's lines, in order, into the entries they add
// or change: a tool's result changes the entry for its call.
type Parser interface {
	Line(b []byte) []Entry
}

// A Measure is a Parser that knows how many tokens the conversation takes of
// the program's context, as of the lines it has read; 0 until it does.
type Measure interface {
	Context() int
}

// A Brancher is a Parser of a program that can take its conversation back
// to an earlier point and go on from there, as a rewind does: what came
// after that point is left behind, no longer in the conversation.
type Brancher interface {
	// Branches counts the times the conversation went back, in the lines read.
	Branches() int
	// Kept is entries this parser read, less those left behind.
	Kept(entries []Entry) []Entry
}

func kept(p Parser, entries []Entry) []Entry {
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
	// firstRead is how much of a transcript's end is read for its recent
	// entries, doubling until there are enough.
	firstRead = 2 << 20
	// batch bounds what one update carries, well inside a wire frame's 16
	// MiB: entries are mostly small, but an image can be 2 MiB.
	batch = 4 << 20
)

// Poll is how often Tail looks for what's new.
var Poll = 400 * time.Millisecond

// ErrEnded is why Tail stops when the program does.
var ErrEnded = errors.New("the conversation ended")

// Tail sends a program's recording as it's written: its recent entries,
// then entries as they come, starting over when the program moves to
// another recording (a cleared conversation) or takes its conversation back
// to an earlier point (a rewind), and what the program is doing, and how
// full its context is, as those change. recording says where the program's
// is now, and false once the program is gone; parser reads one from its
// start, or from any line. Tail returns when ctx is done, or ErrEnded.
func Tail(ctx context.Context, recording func() (Recording, bool), parser func() Parser, send func(Update)) error {
	var (
		f        *os.File
		path     string
		p        Parser
		offset   int64
		partial  []byte
		announce bool      // say there's a conversation, if its file isn't there yet
		said     Recording // the activity last sent
		measured int       // the context last sent
		branched int       // the parser's branches, as of the entries sent
	)
	defer func() {
		if f != nil {
			f.Close()
		}
	}()
	t := time.NewTicker(Poll)
	defer t.Stop()
	for {
		cur, ok := recording()
		if !ok {
			return ErrEnded
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
				var entries []Entry
				var more bool
				entries, more, p, offset = recent(f, parser, Recent)
				partial, announce, said, measured, branched = nil, false, cur, contextOf(p), branches(p)
				send(Update{Reset: true, More: more, Entries: entries, Activity: cur.Activity, Since: cur.Since, Context: measured, Measured: measured != 0})
			} else if announce {
				announce, said, measured = false, cur, 0
				send(Update{Reset: true, Activity: cur.Activity, Since: cur.Since})
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
			for _, b := range batches(changed) {
				send(Update{Entries: b})
			}
		}
		now := measured
		if f != nil {
			now = contextOf(p)
		}
		if (f != nil || !announce) && (cur.Activity != said.Activity || cur.Since != said.Since || now != measured) {
			u := Update{Activity: cur.Activity, Since: cur.Since}
			if now != measured {
				u.Context, u.Measured = now, true
			}
			said, measured = cur, now
			send(u)
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
func recent(f *os.File, parser func() Parser, n int) (entries []Entry, more bool, p Parser, end int64) {
	st, err := f.Stat()
	if err != nil {
		return nil, false, parser(), 0
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
		p = parser()
		entries = kept(p, collect(p, lines))
		end = start + int64(got-len(rest))
		if len(entries) >= n || start == 0 {
			from := newest(entries, max(len(entries)-n, 0))
			return entries[from:], start > 0 || from > 0, p, end
		}
	}
}

// weight is about what an entry adds to a message.
func weight(e Entry) int {
	return len(e.Text) + len(e.Output) + len(e.Diff) + len(e.Data) + 256
}

// newest is where the newest entries from from on that fit in one message
// start; the last always does.
func newest(entries []Entry, from int) int {
	size := 0
	for i := len(entries) - 1; i >= from; i-- {
		if size += weight(entries[i]); size > batch && i < len(entries)-1 {
			return i + 1
		}
	}
	return from
}

// batches splits entries, in order, into runs that each fit in one message.
func batches(entries []Entry) [][]Entry {
	var out [][]Entry
	start, size := 0, 0
	for i, e := range entries {
		if size+weight(e) > batch && i > start {
			out = append(out, entries[start:i])
			start, size = i, 0
		}
		size += weight(e)
	}
	if start < len(entries) {
		out = append(out, entries[start:])
	}
	return out
}

// collect parses lines into the entries they add or change, in order, each
// in its latest state.
func collect(p Parser, lines [][]byte) []Entry {
	var out []Entry
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
