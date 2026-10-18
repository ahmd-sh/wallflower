package gateway

import (
	"context"
	"os/exec"
	"strconv"
	"strings"

	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/transcript"
)

// A conversation is a process recording a transcript, in some pane.
type conversation struct {
	name string // its source's
	src  transcript.Source
	pid  int
}

// conversations finds the transcripts being written, by the tty of the
// process writing each.
func (g *gateway) conversations() map[string]conversation {
	found := map[string]conversation{}
	for _, src := range g.sources {
		for pid := range src.Running() {
			if tty := g.tty(pid); tty != "" {
				found[tty] = conversation{src.Name(), src, pid}
			}
		}
	}
	return found
}

func (g *gateway) tty(pid int) string {
	tty, ok := g.ttys[pid]
	if !ok {
		out, _ := exec.Command("ps", "-o", "tty=", "-p", strconv.Itoa(pid)).Output()
		if t := strings.TrimSpace(string(out)); t != "" && !strings.HasPrefix(t, "?") {
			tty = "/dev/" + t
		}
		g.ttys[pid] = tty
	}
	return tty
}

// follow sends the conversation in pane as it's written, until unfollow.
func (g *gateway) follow(pane string) {
	var c conversation
	for _, w := range g.windows() {
		for _, p := range w.Panes {
			if p.ID == pane {
				c = g.conversations()[p.TTY]
			}
		}
	}
	if c.src == nil {
		g.send(protocol.Entries{Pane: pane, Reset: true, Error: "no conversation in this pane"})
		return
	}
	ctx, cancel := context.WithCancel(g.ctx)
	g.following[pane] = cancel
	go transcript.Follow(ctx, c.src, c.pid, func(e protocol.Entries) {
		if ctx.Err() == nil { // a follower being replaced says nothing more
			e.Pane = pane
			g.send(e)
		}
	})
}

func (g *gateway) unfollow(pane string) {
	if cancel, ok := g.following[pane]; ok {
		cancel()
		delete(g.following, pane)
	}
}
