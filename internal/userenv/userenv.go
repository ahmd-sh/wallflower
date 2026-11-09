// Package userenv is the environment the user has in a terminal on the Mac,
// for what the gateway starts on their behalf: sshd starts the gateway in
// the ssh connection's, with a bare PATH, no locale, and the connection's
// own variables.
package userenv

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Environ is the user's environment: what their login shell sets up, as
// it would for an ssh login, with a UTF-8 locale if it sets none (as
// Terminal does), without the ssh connection's variables. It's worked out
// once; if the shell can't say, it's the gateway's own, likewise.
var Environ = sync.OnceValue(func() []string {
	shell := os.Getenv("SHELL")
	if shell == "" {
		shell = "/bin/sh"
	}
	env, err := login(shell, os.Environ(), 5*time.Second)
	if err != nil {
		env = os.Environ()
	}
	return tidy(env, locale)
})

// login runs shell as a login shell, in env, and says the environment it
// sets up. What its startup files print comes before a mark, and is passed
// over.
func login(shell string, env []string, timeout time.Duration) ([]string, error) {
	b := make([]byte, 8)
	rand.Read(b)
	mark := "wallflower-" + hex.EncodeToString(b)
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, shell, "-l", "-c", "/usr/bin/printf '%s\\0' "+mark+"; exec /usr/bin/env -0")
	cmd.Env = env
	cmd.Dir, _ = os.UserHomeDir()
	cmd.WaitDelay = time.Second
	out, _ := cmd.Output()
	at := bytes.Index(out, []byte(mark+"\x00"))
	if ctx.Err() != nil || at < 0 {
		return nil, errors.New("userenv: the login shell didn't say its environment")
	}
	var vars []string
	for _, kv := range strings.Split(string(out[at+len(mark)+1:]), "\x00") {
		if k, _, ok := strings.Cut(kv, "="); ok && k != "" {
			vars = append(vars, kv)
		}
	}
	return vars, nil
}

// connection is what sshd sets for the connection, not the user, and the
// shell's own last command.
var connection = map[string]bool{"SSH_CONNECTION": true, "SSH_CLIENT": true, "SSH_TTY": true, "SSH_ORIGINAL_COMMAND": true, "_": true}

func tidy(env []string, locale func() string) []string {
	var out []string
	localized := false
	for _, kv := range env {
		k, v, _ := strings.Cut(kv, "=")
		if connection[k] {
			continue
		}
		if (k == "LANG" || k == "LC_ALL" || k == "LC_CTYPE") && v != "" {
			localized = true
		}
		out = append(out, kv)
	}
	if !localized {
		out = append(out, "LANG="+locale())
	}
	return out
}

// locale is the Mac's language and region in UTF-8, as Terminal sets LANG
// from them; en_US's if the Mac's has no such locale.
func locale() string {
	out, err := exec.Command("/usr/bin/defaults", "read", "-g", "AppleLocale").Output()
	name, _, _ := strings.Cut(strings.TrimSpace(string(out)), "@")
	if err == nil && name != "" {
		if _, err := os.Stat(filepath.Join("/usr/share/locale", name+".UTF-8")); err == nil {
			return name + ".UTF-8"
		}
	}
	return "en_US.UTF-8"
}

// Getenv is a variable in env, as os.Getenv is in the gateway's.
func Getenv(env []string, key string) string {
	for i := len(env) - 1; i >= 0; i-- {
		if v, ok := strings.CutPrefix(env[i], key+"="); ok {
			return v
		}
	}
	return ""
}

// LookPath finds a program as a shell in env would: on its PATH, only in
// directories named in full. A name with a slash is a path already.
func LookPath(name string, env []string) (string, error) {
	if strings.Contains(name, "/") {
		return name, nil
	}
	for _, dir := range filepath.SplitList(Getenv(env, "PATH")) {
		if !filepath.IsAbs(dir) {
			continue
		}
		p := filepath.Join(dir, name)
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() && st.Mode().Perm()&0o111 != 0 {
			return p, nil
		}
	}
	return "", &exec.Error{Name: name, Err: exec.ErrNotFound}
}

// With is env with key set to value, in its place if it's there.
func With(env []string, key, value string) []string {
	out := make([]string, 0, len(env)+1)
	set := false
	for _, kv := range env {
		if strings.HasPrefix(kv, key+"=") {
			if !set {
				out = append(out, key+"="+value)
				set = true
			}
			continue
		}
		out = append(out, kv)
	}
	if !set {
		out = append(out, key+"="+value)
	}
	return out
}
