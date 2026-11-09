package userenv

import (
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"
)

func TestTheLoginShellSaysTheEnvironment(t *testing.T) {
	home := t.TempDir()
	profile := "echo 'welcome back'\nprintf 'no newline'\nexport FROM_PROFILE='a b'\nPATH=/from/profile:$PATH; export PATH\n"
	if err := os.WriteFile(filepath.Join(home, ".profile"), []byte(profile), 0o644); err != nil {
		t.Fatal(err)
	}
	env, err := login("/bin/sh", []string{"HOME=" + home, "PATH=/usr/bin:/bin", "SSH_CONNECTION=phone"}, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if got := Getenv(env, "FROM_PROFILE"); got != "a b" {
		t.Errorf("FROM_PROFILE = %q, want what .profile set", got)
	}
	if got := Getenv(env, "PATH"); !slices.Contains(filepath.SplitList(got), "/from/profile") {
		t.Errorf("PATH = %q, want .profile's addition", got)
	}
	if got := Getenv(env, "HOME"); got != home {
		t.Errorf("HOME = %q: what it printed spilled into the first variable", got)
	}
}

func TestAShellThatFailsSaysNothing(t *testing.T) {
	home := t.TempDir()
	os.WriteFile(filepath.Join(home, ".profile"), []byte("exit 1\n"), 0o644)
	if _, err := login("/bin/sh", []string{"HOME=" + home, "PATH=/usr/bin:/bin"}, 10*time.Second); err == nil {
		t.Error("a login shell that exits before saying anything said an environment")
	}
	os.WriteFile(filepath.Join(home, ".profile"), []byte("sleep 30\n"), 0o644)
	start := time.Now()
	if _, err := login("/bin/sh", []string{"HOME=" + home, "PATH=/usr/bin:/bin"}, 200*time.Millisecond); err == nil || time.Since(start) > 5*time.Second {
		t.Errorf("a login shell that hangs: err %v after %v", err, time.Since(start))
	}
}

func TestTidyLeavesTheConnectionOut(t *testing.T) {
	mac := func() string { return "en_CA.UTF-8" }
	got := tidy([]string{"HOME=/h", "SSH_CONNECTION=1 2 3 4", "SSH_CLIENT=1 2 3", "SSH_TTY=/dev/ttys1", "SSH_ORIGINAL_COMMAND=x", "_=/usr/bin/env", "SSH_AUTH_SOCK=/agent"}, mac)
	if want := []string{"HOME=/h", "SSH_AUTH_SOCK=/agent", "LANG=en_CA.UTF-8"}; !slices.Equal(got, want) {
		t.Errorf("tidy = %q, want %q", got, want)
	}
	for _, set := range []string{"LANG=fr_FR.UTF-8", "LC_ALL=C.UTF-8", "LC_CTYPE=UTF-8"} {
		if got := tidy([]string{set}, mac); !slices.Equal(got, []string{set}) {
			t.Errorf("with %s, tidy = %q: the user's locale is theirs", set, got)
		}
	}
}

func TestLookPath(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "prog"), []byte("#!/bin/sh\n"), 0o755)
	os.WriteFile(filepath.Join(dir, "plain"), []byte(""), 0o644)
	env := []string{"PATH=relative:" + dir}
	if got, err := LookPath("prog", env); err != nil || got != filepath.Join(dir, "prog") {
		t.Errorf("LookPath(prog) = %q, %v", got, err)
	}
	if _, err := LookPath("plain", env); err == nil {
		t.Error("found a file that isn't executable")
	}
	if got, _ := LookPath("./x/y", env); got != "./x/y" {
		t.Errorf("a path isn't looked up: got %q", got)
	}
}

func TestWith(t *testing.T) {
	if got := With([]string{"A=1", "PATH=/a", "B=2", "PATH=/b"}, "PATH", "/c"); !slices.Equal(got, []string{"A=1", "PATH=/c", "B=2"}) {
		t.Errorf("With = %q", got)
	}
	if got := With([]string{"A=1"}, "PATH", "/c"); !slices.Equal(got, []string{"A=1", "PATH=/c"}) {
		t.Errorf("With = %q", got)
	}
}
