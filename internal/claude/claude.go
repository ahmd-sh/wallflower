// Package claude reads Claude Code's panes for their mobile views: which
// conversation each running claude process is in, and what it's doing, from
// sessions/<pid>.json, and the conversation, from
// projects/<dir>/<session>.jsonl, both under ~/.claude. Neither format is
// documented, so whatever doesn't parse is skipped.
package claude

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"syscall"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mobileview"
	"github.com/ahmd-sh/wallflower/internal/proc"
)

const reglob = 5 * time.Second // how soon a transcript that isn't there yet is looked for again

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
