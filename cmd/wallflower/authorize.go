package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Tailscale's address ranges: the phone only ever connects over the tailnet.
const tailnet = "100.64.0.0/10,fd7a:115c:a1e0::/48"

// authorize adds a client's public key to authorized_keys, restricted so it
// can only run the gateway, only from the tailnet.
func authorize(args []string) error {
	fs := flag.NewFlagSet("authorize", flag.ExitOnError)
	from := fs.String("from", tailnet, "source addresses allowed for this key")
	file := fs.String("file", filepath.Join(os.Getenv("HOME"), ".ssh", "authorized_keys"), "authorized_keys file")
	dryRun := fs.Bool("n", false, "print the line instead of adding it")
	fs.Usage = func() {
		fmt.Fprintln(fs.Output(), "usage: wallflower authorize [flags] 'ecdsa-sha2-nistp256 AAAA... name'")
		fs.PrintDefaults()
	}
	fs.Parse(args)
	if fs.NArg() != 1 {
		fs.Usage()
		os.Exit(2)
	}

	key, err := parseKey(fs.Arg(0))
	if err != nil {
		return err
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	if exe, err = filepath.EvalSymlinks(exe); err != nil {
		return err
	}
	line := authorizedLine(key, *from, exe)
	if *dryRun {
		fmt.Println(line)
		return nil
	}

	existing, err := os.ReadFile(*file)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if hasKey(string(existing), key) {
		return fmt.Errorf("%s already has this key; remove its line to re-authorize", *file)
	}
	if err := os.MkdirAll(filepath.Dir(*file), 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(*file, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	if len(existing) > 0 && !strings.HasSuffix(string(existing), "\n") {
		line = "\n" + line
	}
	if _, err := fmt.Fprintln(f, line); err != nil {
		return err
	}
	fmt.Printf("authorized %s in %s\n", fingerprint(key), *file)
	return nil
}

type pubkey struct{ typ, data, comment string }

func parseKey(s string) (pubkey, error) {
	f := strings.Fields(s)
	if len(f) < 2 {
		return pubkey{}, errors.New("expected 'type base64 [comment]'")
	}
	switch f[0] {
	case "ecdsa-sha2-nistp256", "ssh-ed25519", "sk-ecdsa-sha2-nistp256@openssh.com", "sk-ssh-ed25519@openssh.com":
	default:
		return pubkey{}, fmt.Errorf("unsupported key type %q", f[0])
	}
	k := pubkey{typ: f[0], data: f[1], comment: strings.Join(f[2:], " ")}
	// Let OpenSSH validate the key itself.
	cmd := exec.Command("ssh-keygen", "-l", "-f", "-")
	cmd.Stdin = strings.NewReader(k.typ + " " + k.data + "\n")
	if out, err := cmd.CombinedOutput(); err != nil {
		return pubkey{}, fmt.Errorf("not a valid public key: %s", strings.TrimSpace(string(out)))
	}
	return k, nil
}

func authorizedLine(k pubkey, from, exe string) string {
	opts := fmt.Sprintf(`restrict,from="%s",command="%s serve"`, from, exe)
	line := opts + " " + k.typ + " " + k.data
	if k.comment != "" {
		line += " " + k.comment
	}
	return line
}

func hasKey(authorized string, k pubkey) bool {
	sc := bufio.NewScanner(strings.NewReader(authorized))
	for sc.Scan() {
		for _, field := range strings.Fields(sc.Text()) {
			if field == k.data {
				return true
			}
		}
	}
	return false
}

func fingerprint(k pubkey) string {
	cmd := exec.Command("ssh-keygen", "-l", "-f", "-")
	cmd.Stdin = strings.NewReader(strings.TrimSpace(k.typ+" "+k.data+" "+k.comment) + "\n")
	out, _ := cmd.Output()
	return strings.TrimSpace(string(out))
}
