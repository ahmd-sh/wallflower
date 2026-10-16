package tmux

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/ahmd-sh/tether/internal/tmuxtest"
)

func newClient(t *testing.T) (*Client, func(args ...string) string) {
	t.Helper()
	socket, tmux := tmuxtest.Server(t, 80, 24)
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

func TestRun(t *testing.T) {
	c, _ := newClient(t)

	out, err := c.Run("display -p '#{session_name} #{window_width}x#{window_height}'")
	if err != nil || len(out) != 1 || out[0] != "t 80x24" {
		t.Fatalf("display = %q, %v", out, err)
	}

	_, err = c.Run("no-such-command")
	var cerr *CommandError
	if !errors.As(err, &cerr) || cerr.Command != "no-such-command" {
		t.Fatalf("want CommandError for bad command, got %v", err)
	}

	// A list answers with one block per command; the client must still line
	// up later replies.
	r, err := c.Command("display -p a ; display -p b ; display -p c")
	if err != nil || len(r.Blocks) != 3 || r.Blocks[2][0] != "c" {
		t.Fatalf("list reply = %+v, %v", r, err)
	}
	if _, err := c.Run("display -p {"); err == nil {
		t.Fatal("parse error not reported")
	}
	if out, err := c.Run("display -p after"); err != nil || out[0] != "after" {
		t.Fatalf("reply after errors = %q, %v", out, err)
	}

	tricky := `it's "q" $HOME ; #x`
	if out, err := c.Run("display -p " + Quote(tricky)); err != nil || out[0] != tricky {
		t.Fatalf("Quote round trip = %q, %v", out, err)
	}
}

func TestSnapshot(t *testing.T) {
	c, tmux := newClient(t)

	tmux("split-window", "-h", "-t", "t")
	tmux("new-pane", "-t", "t", "-x", "20", "-y", "5")
	tmux("rename-window", "-t", "t", "a name with spaces")
	tmux("set", "-p", "-t", "t", "@agent_state", "waiting")
	s, err := c.Snapshot()
	if err != nil {
		t.Fatal(err)
	}
	if s.Name != "t" || !strings.HasPrefix(s.ID, "$") {
		t.Errorf("session = %q %q", s.ID, s.Name)
	}
	windows := s.Windows
	if len(windows) != 1 || len(windows[0].Panes) != 3 {
		t.Fatalf("want 1 window with 3 panes, got %+v", windows)
	}
	w := windows[0]
	if w.Name != "a name with spaces" || w.Layout == nil || len(w.Layout.Root.Panes()) != 3 {
		t.Fatalf("window = %+v", w)
	}
	// new-pane sizes include the floating border, so 20x5 leaves 18x3 for content.
	if p := w.Panes[2]; !p.Floating || !p.Active || p.Width != 18 || p.Height != 3 || p.Agent != "waiting" {
		t.Errorf("want active 18x3 floating pane last with agent state, got %+v", p)
	}
	if p := w.Panes[0]; p.Floating || p.Path == "" || p.Command == "" {
		t.Errorf("tiled pane = %+v", p)
	}
}

func TestReplySeq(t *testing.T) {
	c, _ := newClient(t)

	// Output produced before a reply has a Seq at or below the reply's.
	if _, err := c.Run("send-keys 'echo before' Enter"); err != nil {
		t.Fatal(err)
	}
	before := waitFor(t, c, "output", func(n Notification) bool {
		return n.Type == "output" && strings.Contains(string(n.Data), "before\r\n")
	})
	r, err := c.Command("display -p x")
	if err != nil {
		t.Fatal(err)
	}
	if before.Seq > r.Seq {
		t.Fatalf("earlier output seq %d > reply seq %d", before.Seq, r.Seq)
	}
	if _, err := c.Run("send-keys 'echo after' Enter"); err != nil {
		t.Fatal(err)
	}
	after := waitFor(t, c, "output", func(n Notification) bool {
		return n.Type == "output" && strings.Contains(string(n.Data), "after\r\n")
	})
	if after.Seq <= r.Seq {
		t.Fatalf("later output seq %d <= reply seq %d", after.Seq, r.Seq)
	}
}

func TestNotifications(t *testing.T) {
	c, _ := newClient(t)

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
	c, tmux := newClient(t)

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
	c, _ := newClient(t)
	if err := c.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if _, err := c.Run("list-windows"); !errors.Is(err, ErrClosed) {
		t.Fatalf("Run after Close = %v, want ErrClosed", err)
	}
	for range c.Events() {
	}
}
