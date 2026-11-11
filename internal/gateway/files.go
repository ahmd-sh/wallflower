package gateway

import (
	"cmp"
	"context"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const (
	maxListed  = 5000      // a directory's entries sent, at most
	maxFetch   = 256 << 20 // bytes of a file sent, at most
	fetchChunk = 64 << 10
	fetchAhead = 256 << 10 // bytes of fetches sent that the client hasn't said have arrived, at most
	fetchWater = 128 << 10 // send a fetch's next chunk only below this backlog, so pane output and repaints go first
	fetchPoll  = 10 * time.Millisecond
)

// files lists directories and sends files as the client asks, read-only,
// anywhere the user can read. It works off the loop, so a slow disk never
// holds up typing: listings each on their own, and fetches in one sender
// that takes turns among them, a chunk at a time, while the client's
// backlog is low and it has said most of what was sent has arrived, so
// what's typed meanwhile waits behind a little of a file at most, not all
// of it in the network's buffers.
type files struct {
	out  *outbox
	home string
	asks chan any // *protocol.Fetch, *protocol.Got or *protocol.Cancel, in the order they came
}

func newFiles(ctx context.Context, out *outbox) *files {
	home, _ := os.UserHomeDir()
	f := &files{out: out, home: home, asks: make(chan any, 64)}
	go f.run(ctx)
	return f
}

// resolve is a client's path as a clean absolute one: from /, or from the
// user's home for ~ (and for nothing at all).
func (f *files) resolve(path string) (string, error) {
	switch {
	case path == "" || path == "~":
		path = f.home
	case strings.HasPrefix(path, "~/"):
		path = filepath.Join(f.home, path[2:])
	case !filepath.IsAbs(path):
		return "", errors.New("a path starts at / or ~")
	}
	if path == "" {
		return "", errors.New("no home directory")
	}
	return filepath.Clean(path), nil
}

// list sends a directory's entries.
func (f *files) list(m *protocol.List) {
	go func() {
		l := protocol.Listing{ID: m.ID}
		path, err := f.resolve(m.Path)
		if err == nil {
			l.Path = path
			l.Entries, l.More, err = listDir(path)
		}
		if err != nil {
			l.Path, l.Entries, l.Error = cmp.Or(l.Path, m.Path), nil, why(err)
		}
		f.send(l)
	}()
}

// listDir is a directory's entries, by name, up to maxListed of them.
func listDir(dir string) (entries []protocol.FileEntry, more bool, err error) {
	des, err := os.ReadDir(dir)
	if err != nil && len(des) == 0 {
		return nil, false, err
	}
	if len(des) > maxListed {
		des, more = des[:maxListed], true
	}
	entries = make([]protocol.FileEntry, 0, len(des))
	for _, de := range des {
		info, err := de.Info()
		if err != nil {
			continue // gone since it was read
		}
		e := protocol.FileEntry{Name: de.Name(), Hidden: strings.HasPrefix(de.Name(), ".") || hiddenFlag(info)}
		if info.Mode()&fs.ModeSymlink != 0 {
			e.Link = true
			if to, err := os.Stat(filepath.Join(dir, de.Name())); err == nil {
				info = to
			} else {
				info = nil
			}
		}
		switch {
		case info == nil:
			e.Kind = protocol.KindOther
		case info.IsDir():
			e.Kind = protocol.KindDir
		case info.Mode().IsRegular():
			e.Kind, e.Size = protocol.KindFile, info.Size()
		default:
			e.Kind = protocol.KindOther
		}
		if info != nil {
			e.Mtime = info.ModTime().UnixMilli()
		}
		entries = append(entries, e)
	}
	return entries, more, nil
}

// fetch has a file sent, after those asked for before it have started.
func (f *files) fetch(m *protocol.Fetch) {
	select {
	case f.asks <- m:
	default:
		f.send(protocol.File{ID: m.ID, Path: m.Path, Error: "too many files asked for at once"})
	}
}

// cancel stops sending a file, if it's still on its way.
func (f *files) cancel(m *protocol.Cancel) {
	select {
	case f.asks <- m:
	default:
	}
}

// got hears how much of a file has arrived. It waits for room, so none is
// lost: the sender only ever takes from asks.
func (f *files) got(m *protocol.Got) { f.asks <- m }

// A sending is a fetch on its way: its file, the bytes of it left to send,
// and those sent that the client hasn't yet said have arrived.
type sending struct {
	id, path string
	file     *os.File
	left     int64
	sent     int64
	got      int64
}

func (s *sending) unheard() int64 { return s.sent - s.got }

// run opens the files fetches ask for and sends them, until ctx ends.
func (f *files) run(ctx context.Context) {
	var open []*sending
	defer func() {
		for _, s := range open {
			s.file.Close()
		}
	}()
	drop := func(i int) {
		open[i].file.Close()
		open = append(open[:i], open[i+1:]...)
	}
	ask := func(a any) {
		switch a := a.(type) {
		case *protocol.Fetch:
			if s := f.start(a); s != nil {
				open = append(open, s)
			}
		case *protocol.Got:
			for _, s := range open {
				if s.id == a.ID {
					s.got = max(s.got, min(a.Size, s.sent))
				}
			}
		case *protocol.Cancel:
			for i, s := range open {
				if s.id == a.ID {
					drop(i)
					break
				}
			}
		}
	}
	turn := 0
	for {
		// Those sent whole stay until the client has them all, as they count towards what's on its way.
		for i := 0; i < len(open); i++ {
			if open[i].left == 0 && open[i].unheard() <= 0 {
				drop(i)
				i--
			}
		}
		var unheard int64
		sending := 0
		for _, s := range open {
			unheard += s.unheard()
			if s.left > 0 {
				sending++
			}
		}
		room := sending > 0 && unheard < fetchAhead
		if room && f.out.buffered() < fetchWater {
			select {
			case a := <-f.asks:
				ask(a)
				continue
			case <-ctx.Done():
				return
			default:
			}
			for open[turn%len(open)].left == 0 {
				turn++
			}
			turn %= len(open)
			s := open[turn]
			if !f.next(s) {
				drop(turn)
			} else {
				turn++
			}
			continue
		}
		var wait <-chan time.Time
		if room {
			wait = time.After(fetchPoll) // until the backlog's down
		}
		select {
		case a := <-f.asks:
			ask(a)
		case <-wait:
		case <-ctx.Done():
			return
		}
	}
}

// start opens what a fetch asks for and says how much of it follows, or
// why none does.
func (f *files) start(m *protocol.Fetch) *sending {
	reply := protocol.File{ID: m.ID, Path: m.Path}
	path, err := f.resolve(m.Path)
	var file *os.File
	if err == nil {
		reply.Path = path
		file, reply.Size, err = openFile(path, m.Limit)
	}
	if err != nil {
		reply.Error = why(err)
		f.send(reply)
		return nil
	}
	f.send(reply)
	if reply.Size == 0 {
		file.Close()
		return nil
	}
	return &sending{id: m.ID, path: reply.Path, file: file, left: reply.Size}
}

// openFile opens a regular file to send, and says how many of its bytes
// go: all of them, up to maxFetch, or the first limit.
func openFile(path string, limit int64) (*os.File, int64, error) {
	// Not blocking, so a named pipe can't hold it up; it's turned away below.
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, 0, err
	}
	info, err := file.Stat()
	switch {
	case err != nil:
	case info.IsDir():
		err = errors.New("it's a directory")
	case !info.Mode().IsRegular():
		err = errors.New("not a file that can be read")
	case limit <= 0 && info.Size() > maxFetch:
		err = errFetchTooBig
	}
	if err != nil {
		file.Close()
		return nil, 0, err
	}
	size := info.Size()
	if limit > 0 {
		size = min(size, limit, maxFetch)
	}
	return file, size, nil
}

var errFetchTooBig = errors.New("too big to send (at most 256 MiB)")

// next sends a fetch's next chunk; false if the file can't be read any more
// (it shrank as it went), said in a File in place of the rest.
func (f *files) next(s *sending) bool {
	buf := make([]byte, min(fetchChunk, s.left))
	n, err := io.ReadFull(s.file, buf)
	if err != nil {
		f.send(protocol.File{ID: s.id, Path: s.path, Error: "it couldn't be read to the end"})
		return false
	}
	f.out.push(protocol.AppendFile(nil, s.id, buf[:n]))
	s.left -= int64(n)
	s.sent += int64(n)
	if s.left == 0 {
		s.file.Close() // sent whole: it waits only to hear it's arrived
	}
	return true
}

func (f *files) send(m protocol.Message) {
	b, err := protocol.AppendJSON(nil, m)
	if errors.Is(err, protocol.ErrTooBig) {
		b, err = protocol.AppendJSON(nil, protocol.Error{Message: "a " + m.Type() + " message too big to send"})
	}
	if err == nil {
		f.out.push(b)
	}
}

// why is what went wrong with a path, without the path, which the client
// knows: "permission denied". macOS keeps some folders from remote users
// unless Remote Login lets them have full disk access, and says only that
// it isn't permitted.
func why(err error) string {
	var pe *fs.PathError
	if errors.As(err, &pe) {
		err = pe.Err
	}
	if runtime.GOOS == "darwin" && errors.Is(err, syscall.EPERM) {
		return "not permitted: macOS lets remote users in here only if Remote Login allows full disk access"
	}
	return err.Error()
}
