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

// Server starts a Rex server of its own, with no config and nothing saved,
// holding one session "t" whose terminal runs /bin/sh. It returns the
// server's socket, the session's id and its terminal's, and a function
// that runs the rex CLI against it.
func Server(t *testing.T) (socket, session, block string, run func(args ...string) string) {
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
	socket = filepath.Join(dir, "s")
	server := exec.Command(bin, "server", "run", "--listen", socket, "--state-dir", filepath.Join(dir, "state"),
		"--restore=false", "--config", config, "--autostart=false")
	server.Env = append(os.Environ(), "SHELL=/bin/sh", "PS1=$ ", "ENV=")
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		server.Process.Kill()
		server.Wait()
	})
	run = func(args ...string) string {
		t.Helper()
		out, err := exec.Command(bin, append([]string{"-S", socket, "--autostart=false"}, args...)...).CombinedOutput()
		if err != nil {
			t.Fatalf("rex %v: %v: %s", args, err, out)
		}
		return string(out)
	}
	for deadline := time.Now().Add(10 * time.Second); ; time.Sleep(50 * time.Millisecond) {
		if _, err := os.Stat(socket); err == nil {
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
	if err := json.Unmarshal([]byte(run("new", "t", "--json", "--shell=none", "--", "/bin/sh")), &created); err != nil {
		t.Fatal(err)
	}
	return socket, created.Session, created.Windows[0].Blocks[0], run
}
