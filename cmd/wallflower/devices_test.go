package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func authorizedFile(t *testing.T) (string, string) {
	t.Helper()
	key := func(name string) pubkey {
		f := strings.Fields(testKey(t))
		return pubkey{f[0], f[1], name}
	}
	pixel := authorizedLine(key("wallflower@Pixel-10-Pro"), "100.64.0.0/10,fd7a:115c:a1e0::/48", "/Users/me/go/bin/wallflower")
	old := authorizedLine(key("wallflower@Pixel-7"), "100.64.0.0/10", "/Users/me/go/bin/wallflower")
	content := strings.Join([]string{
		"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMine me@laptop",
		pixel,
		`command="/usr/bin/backup",restrict ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBack backup`,
		old,
		"",
	}, "\n")
	file := filepath.Join(t.TempDir(), "authorized_keys")
	if err := os.WriteFile(file, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return file, content
}

func TestPhonesAreWallflowersKeysOnly(t *testing.T) {
	_, content := authorizedFile(t)
	ps := phones(content)
	if len(ps) != 2 || ps[0].name != "wallflower@Pixel-10-Pro" || ps[1].name != "wallflower@Pixel-7" || ps[0].line != 2 || ps[1].line != 4 {
		t.Fatalf("phones: %+v", ps)
	}
	if ps[0].from != "100.64.0.0/10,fd7a:115c:a1e0::/48" || !strings.HasPrefix(ps[0].fingerprint, "SHA256:") || ps[0].fingerprint == ps[1].fingerprint {
		t.Errorf("first: %+v", ps[0])
	}
}

func TestRevokeTakesOutOnlyThatPhone(t *testing.T) {
	file, content := authorizedFile(t)
	if err := revoke([]string{"-file", file, "-n", "wallflower@Pixel-7"}); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(file); string(b) != content {
		t.Fatal("a dry run changed the file")
	}
	if err := revoke([]string{"-file", file, "nobody"}); err == nil {
		t.Error("revoked a phone that isn't there")
	}
	if err := revoke([]string{"-file", file, "2"}); err != nil { // by its number in the list
		t.Fatal(err)
	}
	b, _ := os.ReadFile(file)
	want := strings.Join(append(strings.Split(content, "\n")[:3:3], ""), "\n")
	if string(b) != want {
		t.Errorf("after:\n%s\nwant:\n%s", b, want)
	}
	if st, _ := os.Stat(file); st.Mode().Perm() != 0o600 {
		t.Errorf("mode %v", st.Mode().Perm())
	}
	fp := phones(string(b))[0].fingerprint
	if err := revoke([]string{"-file", file, fp}); err != nil { // by its fingerprint
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(file); len(phones(string(b))) != 0 || !strings.Contains(string(b), "me@laptop") || !strings.Contains(string(b), "backup") {
		t.Errorf("left:\n%s", b)
	}
}
