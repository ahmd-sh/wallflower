package gateway

import (
	"bytes"
	"context"
	"crypto/rand"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"runtime"
	"slices"
	"syscall"
	"testing"
	"time"

	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/protocol"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

// browse connects a client attached to nothing, with no multiplexer
// running: files are the gateway's own, whatever the session.
func browse(t *testing.T) *client {
	t.Helper()
	none := backend{name: "tmux", start: func(t *testing.T) (mux.Mux, desk) {
		return tmux.Server{Socket: filepath.Join(t.TempDir(), "none")}, nil
	}}
	return none.dial(t, context.Background(), Options{Uploads: t.TempDir()}, protocol.ClientHello{V: protocol.Version, Cols: 60, Rows: 20})
}

func (c *client) dirListing(id string) protocol.Listing {
	c.t.Helper()
	_, m := c.expect("listing "+id, func(_ protocol.Frame, m protocol.Message) bool {
		l, ok := m.(*protocol.Listing)
		return ok && l.ID == id
	})
	return *m.(*protocol.Listing)
}

// fetched is the File answering fetch id, and the bytes that follow it.
func (c *client) fetched(id string) (protocol.File, []byte) {
	c.t.Helper()
	_, m := c.expect("file "+id, func(_ protocol.Frame, m protocol.Message) bool {
		f, ok := m.(*protocol.File)
		return ok && f.ID == id
	})
	f := *m.(*protocol.File)
	var data []byte
	if f.Error != "" || f.Size == 0 {
		return f, data
	}
	c.expect("all of "+id, func(fr protocol.Frame, m protocol.Message) bool {
		if fr.Type == protocol.FrameFile && fr.ID == id {
			if len(fr.Data) > fetchChunk {
				c.t.Errorf("a file frame of %d bytes", len(fr.Data))
			}
			data = append(data, fr.Data...)
			c.send(protocol.Got{ID: id, Size: int64(len(data))})
		}
		if e, ok := m.(*protocol.File); ok && e.ID == id {
			c.t.Fatalf("partway, %+v", e)
		}
		return int64(len(data)) >= f.Size
	})
	return f, data
}

func TestHelloSaysFiles(t *testing.T) {
	c := browse(t)
	_, m := c.expect("hello", func(_ protocol.Frame, m protocol.Message) bool { _, ok := m.(*protocol.ServerHello); return ok })
	if can := m.(*protocol.ServerHello).Can; !slices.Contains(can, protocol.CanFiles) {
		t.Errorf("hello can %v, want files", can)
	}
}

func TestListing(t *testing.T) {
	dir := t.TempDir()
	os.Mkdir(filepath.Join(dir, "b"), 0o755)
	os.WriteFile(filepath.Join(dir, "a.txt"), []byte("hello"), 0o644)
	os.WriteFile(filepath.Join(dir, ".env"), nil, 0o644)
	os.Symlink("b", filepath.Join(dir, "l"))
	os.Symlink("a.txt", filepath.Join(dir, "m"))
	os.Symlink("nowhere", filepath.Join(dir, "x"))
	syscall.Mkfifo(filepath.Join(dir, "p"), 0o644)
	os.Mkdir(filepath.Join(dir, "Library"), 0o755)
	mac := runtime.GOOS == "darwin"
	if mac {
		exec.Command("chflags", "hidden", filepath.Join(dir, "Library")).Run()
	}
	c := browse(t)

	c.send(protocol.List{ID: "1", Path: dir + "/b/.."})
	l := c.dirListing("1")
	if l.Error != "" || l.More || l.Path != dir {
		t.Fatalf("listing %+v", l)
	}
	for _, e := range l.Entries {
		if e.Kind != protocol.KindOther && e.Mtime == 0 {
			t.Errorf("%s has no mtime", e.Name)
		}
	}
	got := l.Entries
	for i := range got {
		got[i].Mtime = 0
	}
	want := []protocol.FileEntry{
		{Name: ".env", Kind: protocol.KindFile, Hidden: true},
		{Name: "Library", Kind: protocol.KindDir, Hidden: mac},
		{Name: "a.txt", Kind: protocol.KindFile, Size: 5},
		{Name: "b", Kind: protocol.KindDir},
		{Name: "l", Kind: protocol.KindDir, Link: true},
		{Name: "m", Kind: protocol.KindFile, Link: true, Size: 5},
		{Name: "p", Kind: protocol.KindOther},
		{Name: "x", Kind: protocol.KindOther, Link: true},
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("entries\n%+v\nwant\n%+v", got, want)
	}
}

// Home is ~, and where a client with nowhere better starts.
func TestListingHome(t *testing.T) {
	home := t.TempDir()
	os.Mkdir(filepath.Join(home, "dev"), 0o755)
	os.WriteFile(filepath.Join(home, "dev", "go.mod"), nil, 0o644)
	t.Setenv("HOME", home)
	c := browse(t)
	for id, path := range map[string]string{"empty": "", "tilde": "~", "under": "~/dev"} {
		c.send(protocol.List{ID: id, Path: path})
		l := c.dirListing(id)
		want := home
		if id == "under" {
			want = filepath.Join(home, "dev")
		}
		if l.Error != "" || l.Path != want || len(l.Entries) != 1 {
			t.Errorf("listing %q: %+v", path, l)
		}
	}
}

func TestListingErrors(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "f"), nil, 0o644)
	shut := filepath.Join(dir, "shut")
	os.Mkdir(shut, 0o000)
	t.Cleanup(func() { os.Chmod(shut, 0o755) })
	c := browse(t)
	for path, want := range map[string]string{
		"dev/wallflower":           "a path starts at / or ~",
		filepath.Join(dir, "none"): "no such file or directory",
		filepath.Join(dir, "f"):    "not a directory",
		shut:                       "permission denied",
	} {
		c.send(protocol.List{ID: path, Path: path})
		if l := c.dirListing(path); l.Error != want || l.Entries != nil && want != "" {
			t.Errorf("listing %q: %+v, want error %q", path, l, want)
		}
	}
}

func TestListingCap(t *testing.T) {
	dir := t.TempDir()
	for i := range maxListed + 1 {
		os.WriteFile(filepath.Join(dir, fmt.Sprintf("f%05d", i)), nil, 0o644)
	}
	c := browse(t)
	c.send(protocol.List{ID: "big", Path: dir})
	if l := c.dirListing("big"); len(l.Entries) != maxListed || !l.More {
		t.Errorf("a directory of %d entries listed %d, more %v", maxListed+1, len(l.Entries), l.More)
	}
}

func TestFetch(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	big := make([]byte, 5*fetchChunk/2)
	rand.Read(big)
	os.WriteFile(filepath.Join(home, "big.bin"), big, 0o644)
	os.WriteFile(filepath.Join(home, "empty"), nil, 0o644)
	os.WriteFile(filepath.Join(home, "notes.md"), []byte("# notes ✓\n"), 0o644)
	c := browse(t)

	c.send(protocol.Fetch{ID: "big", Path: filepath.Join(home, "big.bin")})
	f, data := c.fetched("big")
	if f.Error != "" || f.Size != int64(len(big)) || !bytes.Equal(data, big) {
		t.Errorf("fetched %+v, %d bytes, want all %d", f, len(data), len(big))
	}

	c.send(protocol.Fetch{ID: "empty", Path: "~/empty"})
	if f, _ := c.fetched("empty"); f.Error != "" || f.Size != 0 || f.Path != filepath.Join(home, "empty") {
		t.Errorf("fetched an empty file: %+v", f)
	}

	c.send(protocol.Fetch{ID: "md", Path: "~/notes.md"})
	if _, data := c.fetched("md"); string(data) != "# notes ✓\n" {
		t.Errorf("fetched %q", data)
	}

	// A limit sends the start of it, to show.
	c.send(protocol.Fetch{ID: "head", Path: "~/big.bin", Limit: 1000})
	if f, data := c.fetched("head"); f.Size != 1000 || !bytes.Equal(data, big[:1000]) {
		t.Errorf("fetched the head: %+v, %d bytes", f, len(data))
	}
}

func TestFetchErrors(t *testing.T) {
	dir := t.TempDir()
	syscall.Mkfifo(filepath.Join(dir, "pipe"), 0o644)
	os.WriteFile(filepath.Join(dir, "shut"), []byte("x"), 0o000)
	huge := filepath.Join(dir, "huge")
	os.WriteFile(huge, nil, 0o644)
	os.Truncate(huge, maxFetch+1) // sparse: nothing written
	c := browse(t)
	for path, want := range map[string]string{
		dir:                        "it's a directory",
		filepath.Join(dir, "pipe"): "not a file that can be read", // and nothing waits for a writer
		filepath.Join(dir, "shut"): "permission denied",
		filepath.Join(dir, "none"): "no such file or directory",
		huge:                       errFetchTooBig.Error(),
		"notes.md":                 "a path starts at / or ~",
	} {
		c.send(protocol.Fetch{ID: path, Path: path})
		if f, _ := c.fetched(path); f.Error != want || f.Size != 0 {
			t.Errorf("fetched %q: %+v, want error %q", path, f, want)
		}
	}
	// Its start can still be had.
	c.send(protocol.Fetch{ID: "head", Path: huge, Limit: 10})
	if f, data := c.fetched("head"); f.Error != "" || !bytes.Equal(data, make([]byte, 10)) {
		t.Errorf("fetched the start of a huge file: %+v, %q", f, data)
	}
}

// Fetches take turns, a chunk each, so a picture asked for during a big
// download isn't kept waiting for it.
func TestFetchesTakeTurns(t *testing.T) {
	dir := t.TempDir()
	big := filepath.Join(dir, "big")
	os.WriteFile(big, nil, 0o644)
	os.Truncate(big, 200<<20)
	os.WriteFile(filepath.Join(dir, "small"), []byte("small"), 0o644)
	c := browse(t)

	c.send(protocol.Fetch{ID: "big", Path: big})
	c.send(protocol.Fetch{ID: "small", Path: filepath.Join(dir, "small")})
	var bigBytes int64
	c.expect("small", func(f protocol.Frame, _ protocol.Message) bool {
		if f.Type == protocol.FrameFile && f.ID == "big" {
			bigBytes += int64(len(f.Data))
			c.send(protocol.Got{ID: "big", Size: bigBytes})
		}
		return f.Type == protocol.FrameFile && f.ID == "small"
	})
	if bigBytes > fetchAhead {
		t.Errorf("%d bytes of the big file came before the small one", bigBytes)
	}
}

// A fetch goes only so far ahead of what the client says has arrived, so
// what's typed meanwhile isn't stuck behind it in the network's buffers;
// cancelled, it stops.
func TestFetchesWaitForTheClient(t *testing.T) {
	big := filepath.Join(t.TempDir(), "big")
	os.WriteFile(big, nil, 0o644)
	os.Truncate(big, 10<<20)
	c := browse(t)
	quiet := func() int64 {
		var n int64
		for deadline := time.After(300 * time.Millisecond); ; {
			select {
			case f := <-c.frames:
				if f.Type == protocol.FrameFile {
					n += int64(len(f.Data))
				}
			case <-deadline:
				return n
			}
		}
	}

	c.send(protocol.Fetch{ID: "big", Path: big})
	if n := quiet(); n != fetchAhead {
		t.Fatalf("%d bytes came before the client said any had arrived, want %d", n, fetchAhead)
	}
	c.send(protocol.Got{ID: "big", Size: fetchAhead})
	if n := quiet(); n != fetchAhead {
		t.Fatalf("%d more came once it had, want %d", n, fetchAhead)
	}
	c.send(protocol.Cancel{ID: "big"})
	c.send(protocol.Got{ID: "big", Size: 2 * fetchAhead})
	if n := quiet(); n != 0 {
		t.Errorf("%d bytes came after the fetch was cancelled", n)
	}
}
