// Package tmuxtest starts isolated tmux servers for tests.
package tmuxtest

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
)

// Server starts a tmux server on its own socket, with no config and /bin/sh
// as the shell, holding one session "t" of the given size. It returns the
// socket name and a function that runs tmux commands against it.
func Server(t *testing.T, cols, rows int) (string, func(args ...string) string) {
	t.Helper()
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not installed")
	}
	// No locale and no $TMUX (tmux assumes UTF-8 if it is set at all), as
	// in an ssh session; clients must cope with -u.
	for _, v := range []string{"LANG", "LC_ALL", "LC_CTYPE", "TMUX"} {
		t.Setenv(v, "")
		os.Unsetenv(v)
	}
	socket := fmt.Sprintf("tether-test-%d-%s", os.Getpid(), strings.ReplaceAll(t.Name(), "/", "-"))
	run := func(args ...string) string {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-L", socket, "-f", "/dev/null"}, args...)...)
		cmd.Env = append(os.Environ(), "SHELL=/bin/sh", "PS1=$ ")
		out, err := cmd.CombinedOutput()
		if err != nil {
			t.Fatalf("tmux %v: %v: %s", args, err, out)
		}
		return strings.TrimRight(string(out), "\n")
	}
	run("new-session", "-d", "-s", "t", "-x", fmt.Sprint(cols), "-y", fmt.Sprint(rows))
	t.Cleanup(func() { exec.Command("tmux", "-L", socket, "kill-server").Run() })
	return socket, run
}
