package tmux

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/tmuxtest"
)

// As sshd starts the gateway: a bare PATH, and the connection's variables.
func overSSH(t *testing.T) {
	t.Helper()
	tmux := binary()
	t.Setenv("PATH", filepath.Dir(tmux)+":/usr/bin:/bin")
	t.Setenv("SSH_CONNECTION", "100.64.0.9 5555 100.64.0.1 22")
}

func TestAttachingLeavesTheSessionsEnvironment(t *testing.T) {
	socket, tmux := tmuxtest.Server(t, 80, 24)
	before := tmux("show-environment", "-t", "t")
	overSSH(t)
	c, err := Start(context.Background(), Options{Socket: socket, Session: "t"})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Run("display -p ok"); err != nil {
		t.Fatal(err)
	}
	if after := tmux("show-environment", "-t", "t"); after != before {
		t.Errorf("attaching changed the session's environment:\n%s\nwas\n%s", after, before)
	}
}

func TestANewSessionHasTheServersPath(t *testing.T) {
	socket, tmux := tmuxtest.Server(t, 80, 24)
	tmux("set-environment", "-g", "PATH", "/the/servers:"+os.Getenv("PATH"))
	overSSH(t)
	id, err := Server{Socket: socket}.NewSession("n")
	if err != nil {
		t.Fatal(err)
	}
	if env := tmux("show-environment", "-t", id+":"); strings.Contains(env, "SSH_CONNECTION=") {
		t.Errorf("the new session took the connection's variables:\n%s", env)
	}
	tmux("send-keys", "-t", id+":", `echo "P=$PATH"`, "Enter")
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(50 * time.Millisecond) {
		for _, line := range strings.Split(tmux("capture-pane", "-p", "-J", "-t", id+":"), "\n") {
			if path, ok := strings.CutPrefix(line, "P="); ok {
				if !strings.Contains(path, "/the/servers") {
					t.Errorf("the new session's shell has PATH %s, not the server's", path)
				}
				return
			}
		}
	}
	t.Fatal("the new session's shell never said its PATH")
}
