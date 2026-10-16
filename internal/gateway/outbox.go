package gateway

import (
	"bufio"
	"io"
	"sync"
	"time"
)

// outbox writes frames to the client from its own goroutine, so a slow
// connection never blocks the gateway loop. buffered counts bytes not yet
// written; the loop stops forwarding pane output above a high-water mark and
// repaints those panes once the backlog drains.
type outbox struct {
	mu      sync.Mutex
	cond    *sync.Cond
	queue   [][]byte
	size    int
	closing bool
	err     error
	done    chan struct{}
}

func newOutbox(w io.Writer) *outbox {
	o := &outbox{done: make(chan struct{})}
	o.cond = sync.NewCond(&o.mu)
	go o.run(w)
	return o
}

func (o *outbox) push(frame []byte) {
	o.mu.Lock()
	if !o.closing && o.err == nil {
		o.queue = append(o.queue, frame)
		o.size += len(frame)
	}
	o.mu.Unlock()
	o.cond.Signal()
}

func (o *outbox) buffered() int {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.size
}

// close flushes what is queued, giving up after timeout if the client has
// stopped reading.
func (o *outbox) close(timeout time.Duration) error {
	o.mu.Lock()
	o.closing = true
	o.mu.Unlock()
	o.cond.Signal()
	select {
	case <-o.done:
	case <-time.After(timeout):
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.err
}

func (o *outbox) run(w io.Writer) {
	defer close(o.done)
	bw := bufio.NewWriterSize(w, 64<<10)
	for {
		o.mu.Lock()
		for len(o.queue) == 0 && !o.closing {
			o.cond.Wait()
		}
		batch := o.queue
		o.queue = nil
		o.mu.Unlock()
		if len(batch) == 0 {
			return
		}

		var err error
		n := 0
		for _, f := range batch {
			if _, err = bw.Write(f); err != nil {
				break
			}
			n += len(f)
		}
		if err == nil {
			err = bw.Flush()
		}

		o.mu.Lock()
		o.size -= n
		if err != nil {
			o.err, o.queue, o.size = err, nil, 0
		}
		o.mu.Unlock()
		if err != nil {
			return
		}
	}
}
