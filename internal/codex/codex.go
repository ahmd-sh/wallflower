// Package codex reads OpenAI's Codex CLI panes for their mobile views: which
// thread each pane's codex is on, by the title codex gives its terminal
// ("<thread name> | <project>") looked up in session_index.jsonl, and the
// thread, from its rollout, sessions/YYYY/MM/DD/rollout-<time>-<id>.jsonl,
// both under ~/.codex. Codex may run its threads in a daemon its terminal
// only talks to, so the pane's process can't say. Neither format is
// documented, so whatever doesn't parse is skipped.
package codex

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

const (
	command = "codex"
	reglob  = 5 * time.Second // how long a thread's rollout that wasn't found isn't looked for again
	restart = 2 * time.Second // how long when codex started on a terminal is taken as known
)

type Source struct {
	dir   string
	mu    sync.Mutex
	panes map[string]mobileview.Pane // what the last Find was told of codex panes, by ID
	paths map[string]found           // thread id -> its rollout
	metas map[string]meta            // rollout -> its thread
	turns map[string]*turn           // rollout -> what its thread is doing, as read so far
	index index
	ttys  map[string]started // terminal -> when the codex on it started
}

type found struct {
	path string
	at   time.Time // when a missing rollout was last looked for
}

// meta is what a rollout's first line says of its thread.
type meta struct {
	id, cwd, originator string
	at                  time.Time
}

// New reads the Codex data in dir, usually ~/.codex (or $CODEX_HOME).
func New(dir string) *Source {
	return &Source{
		dir:   dir,
		panes: map[string]mobileview.Pane{},
		paths: map[string]found{},
		metas: map[string]meta{},
		turns: map[string]*turn{},
		ttys:  map[string]started{},
	}
}

// Find picks the panes codex runs in, with what each thread is doing, as
// far as its rollout says: on a turn, or not. A thread waiting on the user
// mid-turn (an approval) records nothing until it's answered, so that's for
// the screen to tell.
func (s *Source) Find(panes []mobileview.Pane) map[string]mobileview.Found {
	out := map[string]mobileview.Found{}
	seen := map[string]mobileview.Pane{}
	for _, p := range panes {
		if p.Command != command {
			continue
		}
		seen[p.ID] = p
		f := mobileview.Found{Activity: mobileview.Idle}
		if path := s.rollout(p); path != "" {
			f.Activity, f.Since = s.doing(path)
		}
		out[p.ID] = f
	}
	s.mu.Lock()
	s.panes = seen
	s.mu.Unlock()
	return out
}

var errNone = errors.New("codex isn't running in this pane")

// Read follows the thread codex is on in a pane, onto another when it starts
// or resumes one, until it's no longer running there.
func (s *Source) Read(ctx context.Context, pane mobileview.Pane, send func(mobileview.Update)) error {
	s.mu.Lock()
	_, ok := s.panes[pane.ID]
	s.mu.Unlock()
	if !ok || pane.Command != command {
		return errNone
	}
	recording := func() (mobileview.Recording, bool) {
		s.mu.Lock()
		p, ok := s.panes[pane.ID]
		s.mu.Unlock()
		if !ok {
			return mobileview.Recording{}, false
		}
		path := s.rollout(p)
		if path == "" {
			// No thread yet: one that's named nothing and found nowhere,
			// which Tail announces as an empty conversation.
			path = filepath.Join(s.dir, "sessions", "none")
		}
		return mobileview.Recording{Path: path}, true
	}
	return mobileview.Tail(ctx, recording, func() mobileview.Parser { return newParser(s.name) }, send)
}

// rollout is the rollout of the thread codex is on in a pane: the thread its
// title names, or, before codex names it (it does after the first turn),
// the newest that codex started in the pane's directory since it started;
// "" if there's none.
func (s *Source) rollout(p mobileview.Pane) string {
	if name := nameIn(p.Title); name != "" {
		var any string
		for _, id := range s.index.ids(filepath.Join(s.dir, "session_index.jsonl"), name) {
			path := s.path(id)
			if path == "" {
				continue
			}
			if p.Path == "" || s.meta(path).cwd == p.Path {
				return path
			}
			if any == "" {
				any = path
			}
		}
		if any != "" {
			return any
		}
	}
	return s.unnamed(p)
}

// nameIn is the thread name in a codex terminal's title, which is the
// project's name alone until codex names the thread, after a mark that
// spins while it works.
func nameIn(title string) string {
	title = strings.TrimLeftFunc(title, func(r rune) bool { return !isWordy(r) })
	name, _, ok := cutLast(title, " | ")
	if !ok {
		return ""
	}
	return strings.TrimSpace(name)
}

func isWordy(r rune) bool {
	return r == '"' || r == '\'' || r == '(' || r == '[' || r == '`' || r == '/' || r == '.' ||
		(r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r > 0x2fff
}

func cutLast(s, sep string) (before, after string, found bool) {
	if i := strings.LastIndex(s, sep); i >= 0 {
		return s[:i], s[i+len(sep):], true
	}
	return s, "", false
}

// unnamed is the newest rollout of a thread codex's terminal started in the
// pane's directory since codex started on the pane's terminal, which no name
// in the index claims yet.
func (s *Source) unnamed(p mobileview.Pane) string {
	if p.TTY == "" || p.Path == "" {
		return ""
	}
	since, ok := s.started(p.TTY)
	if !ok {
		return ""
	}
	index := filepath.Join(s.dir, "sessions")
	var days []string
	for d := since.Add(-24 * time.Hour); !d.After(time.Now().Add(24 * time.Hour)); d = d.Add(24 * time.Hour) {
		days = append(days, filepath.Join(index, d.Format("2006/01/02")))
	}
	var files []string
	for _, d := range slices.Compact(days) {
		m, _ := filepath.Glob(filepath.Join(d, "rollout-*.jsonl"))
		files = append(files, m...)
	}
	slices.Sort(files) // named for when they started
	for i := len(files) - 1; i >= 0; i-- {
		m := s.meta(files[i])
		if m.id == "" {
			continue // its first line isn't written yet
		}
		if m.at.Before(since.Add(-restart)) {
			break
		}
		if m.cwd == p.Path && m.originator != "codex_exec" && s.name(m.id) == "" {
			return files[i]
		}
	}
	return ""
}

// path finds a thread's rollout, which lives under the day it started.
func (s *Source) path(id string) string {
	s.mu.Lock()
	f, ok := s.paths[id]
	s.mu.Unlock()
	if ok && f.path != "" {
		if _, err := os.Stat(f.path); err == nil {
			return f.path
		}
	} else if ok && time.Since(f.at) < reglob {
		return ""
	}
	m, _ := filepath.Glob(filepath.Join(s.dir, "sessions", "*", "*", "*", "rollout-*-"+id+".jsonl"))
	f = found{at: time.Now()}
	if len(m) > 0 {
		f.path = m[len(m)-1]
	}
	s.mu.Lock()
	s.paths[id] = f
	s.mu.Unlock()
	return f.path
}

// meta reads a rollout's first line, once it has one.
func (s *Source) meta(path string) meta {
	s.mu.Lock()
	m, ok := s.metas[path]
	s.mu.Unlock()
	if ok {
		return m
	}
	f, err := os.Open(path)
	if err != nil {
		return meta{}
	}
	defer f.Close()
	line, err := bufio.NewReaderSize(f, 64<<10).ReadBytes('\n')
	if err != nil {
		return meta{} // not written yet
	}
	var r struct {
		Type    string `json:"type"`
		Payload struct {
			ID         string `json:"id"`
			CWD        string `json:"cwd"`
			Originator string `json:"originator"`
			Timestamp  string `json:"timestamp"`
		} `json:"payload"`
	}
	if json.Unmarshal(line, &r) == nil && r.Type == "session_meta" {
		m = meta{id: r.Payload.ID, cwd: r.Payload.CWD, originator: r.Payload.Originator}
		m.at, _ = time.Parse(time.RFC3339Nano, r.Payload.Timestamp)
	}
	s.mu.Lock()
	s.metas[path] = m
	s.mu.Unlock()
	return m
}

// name is what the index calls a thread, "" if nothing yet.
func (s *Source) name(id string) string {
	return s.index.name(filepath.Join(s.dir, "session_index.jsonl"), id)
}

// started says when the codex on a terminal started, asking ps at most so
// often.
type started struct {
	at      time.Time
	ok      bool
	checked time.Time
}

func (s *Source) started(tty string) (time.Time, bool) {
	s.mu.Lock()
	st, ok := s.ttys[tty]
	s.mu.Unlock()
	if ok && time.Since(st.checked) < restart {
		return st.at, st.ok
	}
	st = started{checked: time.Now()}
	out, err := exec.Command("ps", "-o", "lstart=,comm=", "-t", strings.TrimPrefix(tty, "/dev/")).Output()
	if err == nil {
		for _, line := range strings.Split(string(out), "\n") {
			f := strings.Fields(line)
			if len(f) < 6 || filepath.Base(strings.Join(f[5:], " ")) != command {
				continue
			}
			if at, err := time.ParseInLocation("Mon Jan 2 15:04:05 2006", strings.Join(f[:5], " "), time.Local); err == nil && at.After(st.at) {
				st.at, st.ok = at, true
			}
		}
	}
	s.mu.Lock()
	s.ttys[tty] = st
	s.mu.Unlock()
	return st.at, st.ok
}

// A turn is what a rollout says its thread is doing, read as it grows.
type turn struct {
	size     int64
	partial  []byte
	activity mobileview.Activity
	since    int64
}

var turnMarks = [][]byte{[]byte(`"task_started"`), []byte(`"task_complete"`), []byte(`"turn_aborted"`)}

// doing reads what's new of a rollout for its turns starting and ending.
func (s *Source) doing(path string) (mobileview.Activity, int64) {
	s.mu.Lock()
	t := s.turns[path]
	if t == nil {
		t = &turn{activity: mobileview.Idle}
		s.turns[path] = t
	}
	s.mu.Unlock()
	f, err := os.Open(path)
	if err != nil {
		return t.activity, t.since
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.Size() == t.size {
		return t.activity, t.since
	}
	if st.Size() < t.size {
		*t = turn{activity: mobileview.Idle}
	}
	buf := make([]byte, st.Size()-t.size)
	n, _ := f.ReadAt(buf, t.size)
	t.size += int64(n)
	data := append(t.partial, buf[:n]...)
	end := bytes.LastIndexByte(data, '\n')
	t.partial = slices.Clone(data[end+1:])
	for _, line := range bytes.Split(data[:end+1], []byte("\n")) {
		if !bytes.Contains(line, []byte(`"event_msg"`)) || !slices.ContainsFunc(turnMarks, func(m []byte) bool { return bytes.Contains(line, m) }) {
			continue
		}
		var r struct {
			Timestamp string `json:"timestamp"`
			Type      string `json:"type"`
			Payload   struct {
				Type string `json:"type"`
			} `json:"payload"`
		}
		if json.Unmarshal(line, &r) != nil || r.Type != "event_msg" {
			continue
		}
		at, _ := time.Parse(time.RFC3339Nano, r.Timestamp)
		switch r.Payload.Type {
		case "task_started":
			t.activity, t.since = mobileview.Working, at.UnixMilli()
		case "task_complete", "turn_aborted":
			t.activity, t.since = mobileview.Idle, at.UnixMilli()
		}
	}
	return t.activity, t.since
}

// index is session_index.jsonl, a line each time a thread is named, read
// again when it changes.
type index struct {
	mu    sync.Mutex
	size  int64
	mod   time.Time
	names map[string]string    // id -> latest name
	at    map[string]time.Time // id -> when it was named
}

func (x *index) load(path string) {
	st, err := os.Stat(path)
	if err != nil {
		x.names, x.at = nil, nil
		return
	}
	if x.names != nil && st.Size() == x.size && st.ModTime().Equal(x.mod) {
		return
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	x.size, x.mod = st.Size(), st.ModTime()
	x.names, x.at = map[string]string{}, map[string]time.Time{}
	for _, line := range bytes.Split(b, []byte("\n")) {
		var r struct {
			ID      string `json:"id"`
			Name    string `json:"thread_name"`
			Updated string `json:"updated_at"`
		}
		if json.Unmarshal(line, &r) != nil || r.ID == "" {
			continue
		}
		x.names[r.ID] = r.Name
		x.at[r.ID], _ = time.Parse(time.RFC3339Nano, r.Updated)
	}
}

func (x *index) name(path, id string) string {
	x.mu.Lock()
	defer x.mu.Unlock()
	x.load(path)
	return x.names[id]
}

// ids are the threads called name, the latest named first.
func (x *index) ids(path, name string) []string {
	x.mu.Lock()
	defer x.mu.Unlock()
	x.load(path)
	var ids []string
	for id, n := range x.names {
		if n == name {
			ids = append(ids, id)
		}
	}
	slices.SortFunc(ids, func(a, b string) int { return x.at[b].Compare(x.at[a]) })
	return ids
}
