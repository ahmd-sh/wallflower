// Package rextest starts isolated Rex servers for tests.
package rextest

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// Binary is the rex CLI: $REX, rex on the PATH, or Rex.app's.
func Binary() string {
	if b := os.Getenv("REX"); b != "" {
		return b
	}
	if b, err := exec.LookPath("rex"); err == nil {
		return b
	}
	return "/Applications/Rex Beta.app/Contents/Helpers/Rex Server.rex/Contents/MacOS/rex"
}

// A Server is a Rex server of a test's own.
type Server struct {
	Socket  string
	Session string // the session "t"
	Block   string // its terminal
	t       *testing.T
	bin     string
	cmd     *exec.Cmd
}

// Start starts a server with no config and nothing saved, holding one
// session "t" whose terminal runs /bin/sh, owned by no client.
func Start(t *testing.T) *Server {
	t.Helper()
	bin := Binary()
	if _, err := os.Stat(bin); err != nil {
		t.Skip("rex not installed")
	}
	// A Unix socket's path has to be short, which a test's temporary directory isn't.
	dir, err := os.MkdirTemp("/tmp", "wfrex")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	config := filepath.Join(dir, "init.lua")
	os.WriteFile(config, nil, 0o600)
	s := &Server{Socket: filepath.Join(dir, "s"), t: t, bin: bin}
	s.cmd = exec.Command(bin, "server", "run", "--listen", s.Socket, "--state-dir", filepath.Join(dir, "state"),
		"--restore=false", "--config", config, "--autostart=false")
	s.cmd.Env = append(os.Environ(), "SHELL=/bin/sh", "PS1=$ ", "ENV=")
	if err := s.cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Kill)
	for deadline := time.Now().Add(10 * time.Second); ; time.Sleep(50 * time.Millisecond) {
		if _, err := os.Stat(s.Socket); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("rex server didn't start")
		}
	}
	var created struct {
		Session string `json:"session_id"`
		Windows []struct {
			Blocks []string `json:"block_ids"`
		} `json:"initial_windows"`
	}
	if err := json.Unmarshal([]byte(s.Run("new", "t", "--json", "--shell=none", "--", "/bin/sh")), &created); err != nil {
		t.Fatal(err)
	}
	s.Session, s.Block = created.Session, created.Windows[0].Blocks[0]
	// The CLI that made it keeps it, gone or not.
	s.Run("api", "call", "-s", s.Session, "session.disown_block", "{}")
	return s
}

// Run runs the rex CLI against the server, and says what it printed.
func (s *Server) Run(args ...string) string {
	s.t.Helper()
	out, err := exec.Command(s.bin, append([]string{"-S", s.Socket, "--autostart=false"}, args...)...).CombinedOutput()
	if err != nil {
		s.t.Fatalf("rex %v: %v: %s", args, err, out)
	}
	return string(out)
}

// Kill stops the server at once.
func (s *Server) Kill() {
	if s.cmd.ProcessState == nil {
		s.cmd.Process.Kill()
		s.cmd.Wait()
	}
}
