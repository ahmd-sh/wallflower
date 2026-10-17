package gateway

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const (
	maxUpload   = 256 << 20
	uploadsKept = 14 * 24 * time.Hour
)

// UploadDir is where uploads go unless Options say otherwise: the user's
// cache, so they don't clutter anything and nothing else depends on them.
func UploadDir() string {
	dir, err := os.UserCacheDir()
	if err != nil {
		dir = os.TempDir()
	}
	return filepath.Join(dir, "wallflower", "uploads")
}

// uploads saves the files a client sends into dir as their frames arrive,
// each under its own name, made safe for a shell and unique in dir.
type uploads struct {
	dir  string
	open map[string]*upload // by the client's id
}

type upload struct {
	f    *os.File
	left int64
}

// begin creates the file for m; a file with nothing in it is done at once.
func (u *uploads) begin(m *protocol.Upload) (path string, err error) {
	if u.open[m.ID] != nil {
		return "", fmt.Errorf("upload %q already started", m.ID)
	}
	if m.Size < 0 || m.Size > maxUpload {
		return "", fmt.Errorf("%s is too big (at most %d MiB)", m.Name, maxUpload>>20)
	}
	if err := os.MkdirAll(u.dir, 0o700); err != nil {
		return "", err
	}
	u.prune()
	f, err := create(u.dir, safeName(m.Name))
	if err != nil {
		return "", err
	}
	if m.Size == 0 {
		return f.Name(), f.Close()
	}
	u.open[m.ID] = &upload{f: f, left: m.Size}
	return "", nil
}

// write saves the next of an upload's bytes. Once it has them all, path says
// where it is.
func (u *uploads) write(id string, data []byte) (path string, err error) {
	up := u.open[id]
	if up == nil {
		return "", fmt.Errorf("no upload %q", id)
	}
	if int64(len(data)) > up.left {
		u.drop(id)
		return "", errors.New("more bytes than the upload said")
	}
	if _, err := up.f.Write(data); err != nil {
		u.drop(id)
		return "", err
	}
	up.left -= int64(len(data))
	if up.left > 0 {
		return "", nil
	}
	delete(u.open, id)
	return up.f.Name(), up.f.Close()
}

// drop removes an upload that won't finish.
func (u *uploads) drop(id string) {
	if up := u.open[id]; up != nil {
		up.f.Close()
		os.Remove(up.f.Name())
		delete(u.open, id)
	}
}

// close drops whatever hasn't finished: the client left partway.
func (u *uploads) close() {
	for id := range u.open {
		u.drop(id)
	}
}

// prune removes uploads old enough that no draft still needs them.
func (u *uploads) prune() {
	entries, _ := os.ReadDir(u.dir)
	for _, e := range entries {
		if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > uploadsKept {
			os.Remove(filepath.Join(u.dir, e.Name()))
		}
	}
}

// safeName keeps a name's letters, digits, dots, dashes and underscores, so
// its path needs no quoting in a shell or a prompt.
func safeName(name string) string {
	var b strings.Builder
	dash := false
	for _, r := range filepath.Base(name) {
		ok := r < 128 && (r == '.' || r == '_' || r == '-' || r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z')
		switch {
		case ok:
			b.WriteRune(r)
			dash = false
		case !dash:
			b.WriteByte('-')
			dash = true
		}
	}
	s := strings.Trim(b.String(), ".-")
	if s == "" {
		return "upload"
	}
	return s
}

// create makes a new file named name in dir, or name-2, name-3, ... if taken.
func create(dir, name string) (*os.File, error) {
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 1; ; i++ {
		n := name
		if i > 1 {
			n = stem + "-" + strconv.Itoa(i) + ext
		}
		f, err := os.OpenFile(filepath.Join(dir, n), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if !errors.Is(err, fs.ErrExist) || i > 999 {
			return f, err
		}
	}
}
