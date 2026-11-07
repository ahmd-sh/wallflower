package main

import (
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// A phone is a key in authorized_keys that may only run wallflower: its
// line (counting from 1), name (the key's comment), fingerprint, and the
// addresses it's allowed from.
type phone struct {
	line        int
	name        string
	key         string
	fingerprint string
	from        string
}

// phones are the keys in authorized that may only run wallflower's serve,
// as authorize adds them; the file's other keys aren't wallflower's to list.
func phones(authorized string) []phone {
	var out []phone
	for i, l := range strings.Split(authorized, "\n") {
		opts, rest := options(strings.TrimSpace(l))
		command, ok := opts["command"]
		if !ok || !strings.HasSuffix(command, " serve") || filepath.Base(strings.TrimSuffix(command, " serve")) != "wallflower" {
			continue
		}
		f := strings.Fields(rest)
		if len(f) < 2 {
			continue
		}
		out = append(out, phone{line: i + 1, name: strings.Join(f[2:], " "), key: f[1], fingerprint: keyFingerprint(f[1]), from: opts["from"]})
	}
	return out
}

// options splits an authorized_keys line into its options, by name (quoted
// values unquoted), and the key after them; a line with no options has none.
func options(line string) (map[string]string, string) {
	if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "ssh-") || strings.HasPrefix(line, "ecdsa-") || strings.HasPrefix(line, "sk-") {
		return nil, line
	}
	quoted := false
	end := len(line)
	for i, c := range line {
		if c == '"' {
			quoted = !quoted
		} else if (c == ' ' || c == '\t') && !quoted {
			end = i
			break
		}
	}
	opts := map[string]string{}
	var field strings.Builder
	quoted = false
	flush := func() {
		name, value, _ := strings.Cut(field.String(), "=")
		opts[name] = strings.Trim(value, `"`)
		field.Reset()
	}
	for _, c := range line[:end] {
		switch {
		case c == '"':
			quoted = !quoted
			field.WriteRune(c)
		case c == ',' && !quoted:
			flush()
		default:
			field.WriteRune(c)
		}
	}
	flush()
	return opts, strings.TrimSpace(line[end:])
}

// keyFingerprint is a key's SHA256 fingerprint as ssh-keygen -l prints it.
func keyFingerprint(data string) string {
	raw, err := base64.StdEncoding.DecodeString(data)
	if err != nil {
		return "?"
	}
	sum := sha256.Sum256(raw)
	return "SHA256:" + base64.RawStdEncoding.EncodeToString(sum[:])
}

func authorizedKeysFlag(fs *flag.FlagSet) *string {
	return fs.String("file", filepath.Join(os.Getenv("HOME"), ".ssh", "authorized_keys"), "authorized_keys file")
}

// devices lists the phones that can connect.
func devices(args []string) error {
	fs := flag.NewFlagSet("devices", flag.ExitOnError)
	file := authorizedKeysFlag(fs)
	fs.Parse(args)
	b, err := os.ReadFile(*file)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	ps := phones(string(b))
	if len(ps) == 0 {
		fmt.Println("no phones can connect: add one with wallflower authorize")
		return nil
	}
	for i, p := range ps {
		fmt.Printf("%d  %s  %s  from %s\n", i+1, orElse(p.name, "(unnamed)"), p.fingerprint, orElse(p.from, "anywhere"))
	}
	return nil
}

// revoke takes a phone's key out of authorized_keys, by its name, its
// fingerprint, or its number in devices' list; only wallflower's keys.
func revoke(args []string) error {
	fs := flag.NewFlagSet("revoke", flag.ExitOnError)
	file := authorizedKeysFlag(fs)
	dryRun := fs.Bool("n", false, "say what would go instead of taking it out")
	fs.Usage = func() {
		fmt.Fprintln(fs.Output(), "usage: wallflower revoke [flags] name|fingerprint|number  (see wallflower devices)")
		fs.PrintDefaults()
	}
	fs.Parse(args)
	if fs.NArg() != 1 {
		fs.Usage()
		os.Exit(2)
	}
	b, err := os.ReadFile(*file)
	if err != nil {
		return err
	}
	ps := phones(string(b))
	which := fs.Arg(0)
	var matched []phone
	for i, p := range ps {
		if p.name == which || p.fingerprint == which || strconv.Itoa(i+1) == which {
			matched = append(matched, p)
		}
	}
	switch {
	case len(matched) == 0:
		return fmt.Errorf("no phone %q: see wallflower devices", which)
	case len(matched) > 1:
		return fmt.Errorf("%d phones are %q: name one by its number or fingerprint", len(matched), which)
	}
	p := matched[0]
	if *dryRun {
		fmt.Printf("would revoke %s %s (line %d of %s)\n", orElse(p.name, "(unnamed)"), p.fingerprint, p.line, *file)
		return nil
	}
	lines := strings.Split(string(b), "\n")
	kept := append(lines[:p.line-1:p.line-1], lines[p.line:]...)
	if err := replace(*file, strings.Join(kept, "\n")); err != nil {
		return err
	}
	if strings.Contains(strings.Join(kept, "\n"), p.key) {
		fmt.Printf("revoked %s %s's wallflower line, but its key is on another line of %s too, which still lets it in\n", orElse(p.name, "(unnamed)"), p.fingerprint, *file)
		return nil
	}
	fmt.Printf("revoked %s %s: it can't connect any more\n", orElse(p.name, "(unnamed)"), p.fingerprint)
	return nil
}

// replace writes data to file in one step, keeping its mode: a reader sees
// the old or the new, never part of either.
func replace(file, data string) error {
	file, err := filepath.EvalSymlinks(file) // a link stays one, to the file it names
	if err != nil {
		return err
	}
	st, err := os.Stat(file)
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(file), ".authorized_keys.*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := tmp.WriteString(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(st.Mode().Perm()); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), file)
}

func orElse(s, otherwise string) string {
	if s == "" {
		return otherwise
	}
	return s
}
