package tmux

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// newServer starts an isolated tmux server (own socket, no config) with one
// 80x24 session and attaches a control client to it.
func newServer(t *testing.T) (*Client, func(args ...string)) {
	t.Helper()
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not installed")
	}
	socket := fmt.Sprintf("tether-test-%d-%s", os.Getpid(), strings.ReplaceAll(t.Name(), "/", "-"))
	tmux := func(args ...string) {
		t.Helper()
		cmd := exec.Command("tmux", append([]string{"-L", socket, "-f", "/dev/null"}, args...)...)
		cmd.Env = append(os.Environ(), "SHELL=/bin/sh", "PS1=$ ")
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("tmux %v: %v: %s", args, err, out)
		}
	}
	tmux("new-session", "-d", "-s", "t", "-x", "80", "-y", "24")
	t.Cleanup(func() { exec.Command("tmux", "-L", socket, "kill-server").Run() })

	c, err := Start(context.Background(), Options{Socket: socket, Session: "t", Flags: []string{"ignore-size", "new-layouts"}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c, tmux
}

func waitFor(t *testing.T, c *Client, what string, match func(Notification) bool) Notification {
	t.Helper()
	timeout := time.After(5 * time.Second)
	for {
		select {
		case n, ok := <-c.Events():
			if !ok {
				t.Fatalf("client exited waiting for %s: %v", what, c.Err())
			}
			if match(n) {
				return n
			}
		case <-timeout:
			t.Fatalf("timed out waiting for %s", what)
		}
	}
}

func TestRunAndSnapshot(t *testing.T) {
	c, _ := newServer(t)

	out, err := c.Run("display -p '#{session_name} #{window_width}x#{window_height}'")
	if err != nil || len(out) != 1 || out[0] != "t 80x24" {
		t.Fatalf("display = %q, %v", out, err)
	}

	_, err = c.Run("no-such-command")
	var cerr *CommandError
	if !errors.As(err, &cerr) || cerr.Command != "no-such-command" {
		t.Fatalf("want CommandError for bad command, got %v", err)
	}

	if _, err := c.Run("split-window -h"); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Run("new-pane -x 20 -y 5"); err != nil {
		t.Fatal(err)
	}
	windows, err := c.Snapshot()
	if err != nil {
		t.Fatal(err)
	}
	if len(windows) != 1 || len(windows[0].Panes) != 3 {
		t.Fatalf("want 1 window with 3 panes, got %+v", windows)
	}
	w := windows[0]
	if w.Layout == nil || len(w.Layout.Root.Panes()) != 3 {
		t.Fatalf("layout doesn't match panes: %+v", w.Layout)
	}
	// new-pane sizes include the floating border, so 20x5 leaves 18x3 for content.
	if p := w.Panes[2]; !p.Floating || !p.Active || p.Width != 18 || p.Height != 3 {
		t.Errorf("want active 18x3 floating pane last, got %+v", p)
	}
}

func TestNotifications(t *testing.T) {
	c, _ := newServer(t)

	if _, err := c.Run("split-window -v"); err != nil {
		t.Fatal(err)
	}
	n := waitFor(t, c, "layout-change", func(n Notification) bool { return n.Type == "layout-change" })
	l, err := ParseLayout(n.Layout)
	if err != nil {
		t.Fatal(err)
	}
	if got := len(l.Root.Panes()); got != 2 {
		t.Errorf("layout has %d panes after split, want 2", got)
	}

	if _, err := c.Run("send-keys 'echo tether-$((6*7))' Enter"); err != nil {
		t.Fatal(err)
	}
	waitFor(t, c, "echo output", func(n Notification) bool {
		return n.Type == "output" && strings.Contains(string(n.Data), "tether-42")
	})

	if _, err := c.Run("new-window -d -n second"); err != nil {
		t.Fatal(err)
	}
	waitFor(t, c, "window-add", func(n Notification) bool { return n.Type == "window-add" })
}

func TestSubscription(t *testing.T) {
	c, tmux := newServer(t)

	if _, err := c.Run("refresh-client -B 'agent:%*:#{@agent_state}'"); err != nil {
		t.Fatal(err)
	}
	// A hook outside the gateway sets the option; the subscription reports it.
	tmux("set", "-p", "-t", "t", "@agent_state", "waiting")
	n := waitFor(t, c, "agent subscription", func(n Notification) bool {
		return n.Type == "subscription-changed" && n.Name == "agent" && n.Value == "waiting"
	})
	if !strings.HasPrefix(n.Pane, "%") || !strings.HasPrefix(n.Window, "@") {
		t.Errorf("want pane and window ids, got %+v", n)
	}
}

func TestCloseFailsPending(t *testing.T) {
	c, _ := newServer(t)
	if err := c.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if _, err := c.Run("list-windows"); !errors.Is(err, ErrClosed) {
		t.Fatalf("Run after Close = %v, want ErrClosed", err)
	}
	for range c.Events() {
	}
}
