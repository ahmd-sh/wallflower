package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func testKey(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	key := filepath.Join(dir, "k")
	if out, err := exec.Command("ssh-keygen", "-q", "-t", "ecdsa", "-b", "256", "-N", "", "-C", "pixel test", "-f", key).CombinedOutput(); err != nil {
		t.Skipf("ssh-keygen: %v %s", err, out)
	}
	pub, err := os.ReadFile(key + ".pub")
	if err != nil {
		t.Fatal(err)
	}
	return strings.TrimSpace(string(pub))
}

func TestAuthorize(t *testing.T) {
	pub := testKey(t)
	file := filepath.Join(t.TempDir(), "ssh", "authorized_keys")
	if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(file, []byte("ssh-ed25519 AAAAexisting other"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := authorize([]string{"-file", file, pub}); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(file)
	lines := strings.Split(strings.TrimSpace(string(got)), "\n")
	if len(lines) != 2 || lines[0] != "ssh-ed25519 AAAAexisting other" {
		t.Fatalf("authorized_keys = %q", got)
	}
	line := lines[1]
	for _, want := range []string{`restrict,`, `from="100.64.0.0/10,fd7a:115c:a1e0::/48"`, `command="`, ` serve"`, pub} {
		if !strings.Contains(line, want) {
			t.Errorf("line %q missing %q", line, want)
		}
	}

	// OpenSSH must accept the options as written.
	if out, err := exec.Command("ssh-keygen", "-l", "-f", file).CombinedOutput(); err != nil {
		t.Errorf("ssh-keygen rejects the file: %v %s", err, out)
	}

	if err := authorize([]string{"-file", file, pub}); err == nil || !strings.Contains(err.Error(), "already") {
		t.Errorf("second authorize = %v, want already-present error", err)
	}
}

func TestParseKeyRejects(t *testing.T) {
	for _, bad := range []string{"", "ssh-rsa AAAAB3Nza", "ecdsa-sha2-nistp256 notbase64", "ecdsa-sha2-nistp256"} {
		if _, err := parseKey(bad); err == nil {
			t.Errorf("parseKey(%q) accepted", bad)
		}
	}
}
