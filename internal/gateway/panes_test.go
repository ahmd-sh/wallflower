package gateway

import (
	"io"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
	"github.com/ahmd-sh/wallflower/internal/tmuxtest"
)

// A client that stops reading gets no more of a pane's output past the high
// water mark; once it catches up, the pane is repainted, once, and streams
// again.
func TestStalePaneRepaintsOnceCaughtUp(t *testing.T) {
	socket, run := tmuxtest.Server(t, 80, 24)
	c, err := tmux.Server{Socket: socket}.Attach(mux.Attach{Session: "t", Cols: 80, Rows: 24})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	r, w := io.Pipe() // nothing reads it yet: the client has stopped reading
	g := &gateway{mux: c, out: newOutbox(w), panes: map[string]*pane{}}
	id := run("display", "-p", "#{pane_id}")
	if err := g.open(id, 10); err != nil {
		t.Fatal(err)
	}
	seq := g.panes[id].openAt
	g.output(mux.Event{Pane: id, Seq: seq + 1, Data: make([]byte, highWater)})
	g.output(mux.Event{Pane: id, Seq: seq + 2, Data: []byte("dropped")})
	if !g.panes[id].stale || !g.stale {
		t.Fatalf("pane not stale past the high water mark (%d buffered)", g.out.buffered())
	}

	g.repaintStale() // still behind: nothing yet
	if !g.panes[id].stale {
		t.Fatal("repainted while the client was still behind")
	}

	frames := make(chan protocol.Frame, 16)
	go func() {
		pr := protocol.NewReader(r)
		for {
			f, err := pr.Next()
			if err != nil {
				return
			}
			frames <- f
		}
	}()
	for deadline := time.Now().Add(5 * time.Second); g.out.buffered() > lowWater; time.Sleep(time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("never caught up")
		}
	}
	g.repaintStale()
	if g.panes[id].stale || g.stale {
		t.Fatal("still stale after catching up")
	}
	g.output(mux.Event{Pane: id, Seq: g.panes[id].openAt + 1, Data: []byte("streams again")})

	var resets int
	for {
		select {
		case f := <-frames:
			if f.Type == protocol.FrameJSON {
				if m, _ := protocol.DecodeServer(f.Data); m != nil && m.Type() == "reset" {
					resets++
				}
			}
			if f.Type == protocol.FrameData && string(f.Data) == "streams again" {
				if resets != 2 { // the first open's, and the repaint's
					t.Fatalf("%d resets", resets)
				}
				return
			}
			if f.Type == protocol.FrameData && string(f.Data) == "dropped" {
				t.Fatal("output past the high water mark was sent")
			}
		case <-time.After(5 * time.Second):
			t.Fatal("no output after the repaint")
		}
	}
}
