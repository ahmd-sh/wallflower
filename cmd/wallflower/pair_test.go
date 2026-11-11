package main

import (
	"crypto/ed25519"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCodeURL(t *testing.T) {
	c := code{host: "100.101.102.103", port: 2222, user: "sam", name: "Sam's Laptop", hostKey: []byte{1, 2, 3}, seed: make([]byte, 32)}
	u, err := url.Parse(c.url())
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	if u.Scheme != "wallflower" || u.Host != "pair" || q.Get("h") != "100.101.102.103" || q.Get("p") != "2222" || q.Get("u") != "sam" || q.Get("k") != "AQID" || len(q.Get("c")) != 43 {
		t.Errorf("url %s", c.url())
	}
	if strings.Contains(c.url(), "+") || q.Get("n") != "Sam's Laptop" {
		t.Errorf("name in %s", c.url())
	}
	c.port = 22
	if strings.Contains(c.url(), "p=") {
		t.Errorf("ssh's port said: %s", c.url())
	}
}

// pairing makes a HOME with a code up in it: its options, and its id.
func pairing(t *testing.T) (*pairOptions, string) {
	t.Helper()
	home := t.TempDir()
	t.Setenv("HOME", home)
	o := &pairOptions{port: 22, from: tailnet, file: filepath.Join(home, ".ssh", "authorized_keys"), ttl: time.Minute, exe: "/home/m/.local/bin/wallflower"}
	if err := appendLine(o.file, "ssh-ed25519 AAAAexisting other"); err != nil {
		t.Fatal(err)
	}
	pub, _, _ := ed25519.GenerateKey(nil)
	id := "0123456789ab"
	if err := os.MkdirAll(pairingDir(), 0o700); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(pairingDir(), id), nil, 0o600)
	if err := appendLine(o.file, codeLine(o, id, pub, time.Now().Add(time.Minute))); err != nil {
		t.Fatal(err)
	}
	return o, id
}

func TestCodeLine(t *testing.T) {
	o, id := pairing(t)
	b, _ := os.ReadFile(o.file)
	line := strings.Split(strings.TrimSpace(string(b)), "\n")[1]
	for _, want := range []string{`restrict,from="100.64.0.0/10,fd7a:115c:a1e0::/48",expiry-time="`, `command="/home/m/.local/bin/wallflower claim ` + id + `"`, "ssh-ed25519 "} {
		if !strings.Contains(line, want) {
			t.Errorf("%q lacks %q", line, want)
		}
	}
	if got, ok := isCode(line); !ok || got != id {
		t.Errorf("isCode = %q %v", got, ok)
	}
	if out, err := exec.Command("ssh-keygen", "-l", "-f", o.file).CombinedOutput(); err != nil {
		t.Errorf("ssh-keygen rejects it: %v %s", err, out)
	}
	if len(phones(string(b))) != 0 {
		t.Error("a code is listed as a phone")
	}
}

func claimWith(t *testing.T, o *pairOptions, id, key string) error {
	t.Helper()
	r, w, _ := os.Pipe()
	w.WriteString(key + "\n")
	w.Close()
	old := os.Stdin
	os.Stdin = r
	defer func() { os.Stdin = old }()
	return claim([]string{"-file", o.file, id})
}

func TestClaim(t *testing.T) {
	o, id := pairing(t)
	pub := testKey(t)
	if err := claimWith(t, o, id, pub); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(o.file)
	lines := strings.Split(strings.TrimSpace(string(b)), "\n")
	if len(lines) != 2 || lines[0] != "ssh-ed25519 AAAAexisting other" {
		t.Fatalf("authorized_keys = %q", b)
	}
	if !strings.HasPrefix(lines[1], `restrict,from="`+tailnet+`",command="`) || !strings.HasSuffix(lines[1], ` serve" `+pub) {
		t.Errorf("the phone's line: %q", lines[1])
	}
	if name, _ := os.ReadFile(filepath.Join(pairingDir(), id+".paired")); string(name) != "pixel test" {
		t.Errorf("paired = %q", name)
	}
	if err := claimWith(t, o, id, testKey(t)); err == nil {
		t.Error("a code worked twice")
	}
	if b2, _ := os.ReadFile(o.file); string(b2) != string(b) {
		t.Errorf("a second claim changed authorized_keys: %q", b2)
	}
}

func TestClaimRejectsNonsense(t *testing.T) {
	o, id := pairing(t)
	if err := claimWith(t, o, id, "hello"); err == nil {
		t.Error("took a non-key")
	}
	b, _ := os.ReadFile(o.file)
	if strings.Contains(string(b), "claim") || len(strings.Split(strings.TrimSpace(string(b)), "\n")) != 1 {
		t.Errorf("authorized_keys = %q", b)
	}
}

func TestSweepCodes(t *testing.T) {
	o, id := pairing(t)
	if err := sweepCodes(o.file, pairingDir()); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(o.file); !strings.Contains(string(b), "claim") {
		t.Error("swept a code that's up")
	}
	os.Remove(filepath.Join(pairingDir(), id))
	sweepCodes(o.file, pairingDir())
	if b, _ := os.ReadFile(o.file); strings.Contains(string(b), "claim") || !strings.Contains(string(b), "existing") {
		t.Errorf("after the sweep: %q", b)
	}
}

func TestTailscaleAddress(t *testing.T) {
	var ts tailscale
	ts.BackendState = "Running"
	ts.Self.TailscaleIPs = []string{"fd7a:115c:a1e0::1", "100.101.102.103"}
	if ts.address() != "100.101.102.103" {
		t.Errorf("address %q", ts.address())
	}
	ts.BackendState = "NeedsLogin"
	if ts.address() != "" {
		t.Error("an address while signed out")
	}
}
