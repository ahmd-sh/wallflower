package tmux

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"

	"github.com/ahmd-sh/wallflower/internal/mux"
)

// The server's sessions, through the tmux CLI: there's no client to attach
// to one that isn't the session's own.

// tmux runs a tmux command against the server, and says what it printed.
func (s Server) tmux(args ...string) (string, error) {
	cmd := exec.Command(binary(), append(append(socketArgs(s.Socket), "-u"), args...)...)
	if home, err := os.UserHomeDir(); err == nil {
		cmd.Dir = home
	}
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("tmux %s: %s", args[0], strings.TrimSpace(string(out)))
	}
	return strings.TrimRight(string(out), "\n"), nil
}

// notRunning is tmux saying there's no server to ask.
func notRunning(err error) bool {
	msg := err.Error()
	return strings.Contains(msg, "no server running") || strings.Contains(msg, "error connecting to")
}

func (s Server) Sessions() ([]mux.Listed, error) {
	out, err := s.tmux("list-sessions", "-F", "#{session_id}\t#{session_windows}\t#{session_name}")
	if err != nil {
		if notRunning(err) {
			return nil, nil
		}
		return nil, err
	}
	var list []mux.Listed
	for _, line := range strings.Split(out, "\n") {
		f := strings.SplitN(line, "\t", 3)
		if len(f) != 3 {
			continue
		}
		windows, _ := strconv.Atoi(f[1])
		list = append(list, mux.Listed{ID: f[0], Name: f[2], Windows: windows})
	}
	return list, nil
}

func (s Server) NewSession(name string) (string, error) {
	args := []string{"new-session", "-d", "-P", "-F", "#{session_id}"}
	if name != "" {
		args = append(args, "-s", name)
	}
	return s.tmux(args...)
}

func (s Server) RenameSession(session, name string) error {
	if err := checkSession(session); err != nil {
		return err
	}
	_, err := s.tmux("rename-session", "-t", session+":", name)
	return err
}

func (s Server) KillSession(session string) error {
	if err := checkSession(session); err != nil {
		return err
	}
	_, err := s.tmux("kill-session", "-t", session+":")
	return err
}

func checkSession(id string) error {
	if !sessionID.MatchString(id) {
		return fmt.Errorf("tmux: bad id %q", id)
	}
	return nil
}
