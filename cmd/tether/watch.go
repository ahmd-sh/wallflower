package main

import (
	"context"
	"encoding/json"
	"flag"
	"os"
	"os/signal"
	"syscall"
	"unicode/utf8"

	"github.com/ahmd-sh/tether/internal/tmux"
)

// subscriptions the gateway will forward: agent state set by hooks, and what
// each pane is running.
var subscriptions = []string{
	"agent:%*:#{@agent_state}",
	"cmd:%*:#{pane_current_command}",
}

// event shadows the layout strings with raw JSON so they print as objects.
type event struct {
	tmux.Notification
	Layout        json.RawMessage `json:"layout,omitempty"`
	VisibleLayout json.RawMessage `json:"visible_layout,omitempty"`
	Bytes         int             `json:"bytes,omitempty"`
	Output        string          `json:"output,omitempty"`
}

func rawJSON(s string) json.RawMessage {
	if s == "" {
		return nil
	}
	if json.Valid([]byte(s)) {
		return json.RawMessage(s)
	}
	b, _ := json.Marshal(s)
	return b
}

func watch(args []string) error {
	fs := flag.NewFlagSet("watch", flag.ExitOnError)
	socket := fs.String("L", "", "tmux socket name, or path if it contains a slash")
	session := fs.String("t", "", "session to attach to")
	output := fs.Bool("output", true, "include pane output previews")
	preview := fs.Int("preview", 120, "bytes of pane output to preview")
	fs.Parse(args)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// ignore-size: watching must not resize windows for the desktop client.
	c, err := tmux.Start(context.Background(), tmux.Options{
		Socket:  *socket,
		Session: *session,
		Flags:   []string{"ignore-size", "new-layouts", "no-detach-on-destroy"},
	})
	if err != nil {
		return err
	}
	go func() {
		<-ctx.Done()
		c.Close()
	}()

	enc := json.NewEncoder(os.Stdout)
	enc.SetEscapeHTML(false)

	windows, err := c.Snapshot()
	if err != nil {
		c.Close()
		return err
	}
	enc.Encode(map[string]any{"type": "snapshot", "windows": windows})

	for _, s := range subscriptions {
		if _, err := c.Run("refresh-client -B '" + s + "'"); err != nil {
			c.Close()
			return err
		}
	}

	for n := range c.Events() {
		e := event{Notification: n, Layout: rawJSON(n.Layout), VisibleLayout: rawJSON(n.VisibleLayout)}
		if len(n.Data) > 0 {
			if !*output {
				continue
			}
			e.Bytes = len(n.Data)
			e.Output = truncate(n.Data, *preview)
		}
		enc.Encode(e)
	}
	if ctx.Err() != nil {
		return nil
	}
	return c.Err()
}

func truncate(b []byte, n int) string {
	if len(b) <= n {
		return string(b)
	}
	for n > 0 && !utf8.RuneStart(b[n]) {
		n--
	}
	return string(b[:n]) + "…"
}
