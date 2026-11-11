package main

import (
	"bufio"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"os/user"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/ahmd-sh/wallflower/internal/gateway"
	"github.com/ahmd-sh/wallflower/internal/qr"
)

// A code is what pair shows the phone, as a QR code: where to reach this
// computer, its host key (so the phone needn't ask whether to trust it),
// and a key of its own that sshd lets in for a few minutes to do one
// thing, run claim. Claim takes the key the phone sends, lets it in to run
// serve like authorize would, and takes the code's key out: the code
// works once. No port opens: the phone comes in over ssh, as every time
// after.
type code struct {
	host, user, name string
	port             int
	hostKey          []byte // the host key the phone pins, in SSH's wire format
	seed             []byte // the code's own Ed25519 private key
}

// url is the code as the phone reads it: wallflower://pair?… with what
// it needs, base64url for bytes.
func (c code) url() string {
	b64 := base64.RawURLEncoding.EncodeToString
	q := []string{"h=" + escape(c.host), "u=" + escape(c.user)}
	if c.port != 22 {
		q = append(q, "p="+strconv.Itoa(c.port))
	}
	if c.name != "" {
		q = append(q, "n="+escape(c.name))
	}
	q = append(q, "k="+b64(c.hostKey), "c="+b64(c.seed))
	return "wallflower://pair?" + strings.Join(q, "&")
}

// escape is a query value with spaces as %20, which every reader takes,
// not +, which some don't.
func escape(s string) string { return strings.ReplaceAll(url.QueryEscape(s), "+", "%20") }

// pairOptions are how pair and setup make a code.
type pairOptions struct {
	host  string
	port  int
	from  string
	file  string
	ttl   time.Duration
	exe   string // the wallflower sshd runs, as installed
	quiet bool   // don't say what to run first: setup has
}

func pairFlags(fs *flag.FlagSet) *pairOptions {
	o := &pairOptions{}
	fs.StringVar(&o.host, "host", "", "the address the phone reaches this computer at (default: its Tailscale name)")
	fs.IntVar(&o.port, "port", 22, "ssh's port")
	fs.StringVar(&o.from, "from", tailnet, "addresses the phone may connect from")
	fs.StringVar(&o.file, "file", filepath.Join(os.Getenv("HOME"), ".ssh", "authorized_keys"), "authorized_keys file")
	fs.DurationVar(&o.ttl, "for", 10*time.Minute, "how long the code works")
	return o
}

// pair shows a code for a phone to scan, and waits for it.
func pair(args []string) error {
	fs := flag.NewFlagSet("pair", flag.ExitOnError)
	o := pairFlags(fs)
	fs.Parse(args)
	exe, err := executable()
	if err != nil {
		return err
	}
	o.exe = exe
	if o.host == "" {
		t, err := tailscaleStatus()
		if err != nil {
			return fmt.Errorf("%v: run wallflower setup, or say where the phone reaches this computer with -host", err)
		}
		if o.host = t.address(); o.host == "" {
			return errors.New("Tailscale isn't connected: run wallflower setup")
		}
	}
	return showCode(o)
}

func executable() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.EvalSymlinks(exe)
}

// pairingDir holds a file for each code that's up, by its id: claim takes
// it away, so only one phone gets in on a code.
func pairingDir() string {
	return filepath.Join(os.Getenv("HOME"), ".config", "wallflower", "pairing")
}

func showCode(o *pairOptions) error {
	hostKey, err := hostKey(o.port)
	if err != nil {
		return err
	}
	me, err := user.Current()
	if err != nil {
		return err
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	idBytes := make([]byte, 6)
	rand.Read(idBytes)
	id := hex.EncodeToString(idBytes)

	dir := pairingDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if err := sweepCodes(o.file, dir); err != nil {
		return err
	}
	ticket := filepath.Join(dir, id)
	if err := os.WriteFile(ticket, nil, 0o600); err != nil {
		return err
	}
	done := func() {
		os.Remove(ticket)
		os.Remove(ticket + ".paired")
		dropCode(o.file, id)
	}
	defer done()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM, syscall.SIGHUP)
	defer signal.Stop(stop)

	if err := appendLine(o.file, codeLine(o, id, pub, time.Now().Add(o.ttl))); err != nil {
		return err
	}
	c := code{host: o.host, port: o.port, user: me.Username, name: gateway.MachineName(), hostKey: hostKey, seed: priv.Seed()}
	q, err := qr.Encode([]byte(c.url()), qr.L)
	if err != nil {
		return err
	}
	fmt.Println()
	fmt.Print(q.Terminal(2))
	fmt.Println()
	fmt.Println("Scan this in wallflower on your phone. It lets one phone in, for the next", minutes(o.ttl)+".")
	fmt.Println("Your phone needs Tailscale too, signed in to the same account.")
	fmt.Println("Waiting… (ctrl-c to stop)")

	deadline := time.After(o.ttl)
	tick := time.NewTicker(300 * time.Millisecond)
	defer tick.Stop()
	var claimed time.Time
	for {
		select {
		case <-stop:
			return errors.New("stopped: the code no longer works")
		case <-deadline:
			return errors.New("the code ran out: run this again for a new one")
		case <-tick.C:
		}
		name, err := os.ReadFile(ticket + ".paired")
		if err == nil {
			fmt.Printf("\nPaired with %s: it can connect now.\n", phoneName(string(name)))
			return nil
		}
		if _, err := os.Stat(ticket); errors.Is(err, os.ErrNotExist) {
			if claimed.IsZero() {
				claimed = time.Now() // its name is on the way
			} else if time.Since(claimed) > 10*time.Second {
				return errors.New("a phone took the code but didn't send its key: run this again for a new one")
			}
		}
	}
}

// phoneName is what a phone's key calls it, as the apps name their keys
// (wallflower@Pixel-10-Pro), said plainly.
func phoneName(comment string) string {
	name := strings.ReplaceAll(strings.TrimPrefix(strings.TrimSpace(comment), "wallflower@"), "-", " ")
	return orElse(name, "your phone")
}

func minutes(d time.Duration) string {
	if m := int(d.Minutes()); m != 1 {
		return strconv.Itoa(m) + " minutes"
	}
	return "minute"
}

// codeLine lets the code's key in till expires, from where a phone may
// be, to run claim and nothing else.
func codeLine(o *pairOptions, id string, pub ed25519.PublicKey, expires time.Time) string {
	command := o.exe + " claim"
	if o.file != filepath.Join(os.Getenv("HOME"), ".ssh", "authorized_keys") {
		command += " -file " + o.file
	}
	if o.from != tailnet {
		command += " -from " + o.from
	}
	command += " " + id
	return fmt.Sprintf(`restrict,from="%s",expiry-time="%s",command="%s" ssh-ed25519 %s wallflower pairing code`,
		o.from, expires.Format("20060102150405"), command, base64.StdEncoding.EncodeToString(wireKey("ssh-ed25519", pub)))
}

func wireKey(typ string, parts ...[]byte) []byte {
	var b []byte
	for _, p := range append([][]byte{[]byte(typ)}, parts...) {
		b = binary.BigEndian.AppendUint32(b, uint32(len(p)))
		b = append(b, p...)
	}
	return b
}

// hostKey is the key sshd shows, Ed25519 if it has one, as ssh-keyscan
// hears it, or else as /etc/ssh keeps it.
func hostKey(port int) ([]byte, error) {
	for _, typ := range []string{"ed25519", "ecdsa"} {
		out, _ := exec.Command("ssh-keyscan", "-T", "3", "-p", strconv.Itoa(port), "-t", typ, "127.0.0.1").Output()
		for _, line := range strings.Split(string(out), "\n") {
			if f := strings.Fields(line); len(f) >= 3 && !strings.HasPrefix(line, "#") {
				if b, err := base64.StdEncoding.DecodeString(f[2]); err == nil {
					return b, nil
				}
			}
		}
		if b, err := os.ReadFile("/etc/ssh/ssh_host_" + typ + "_key.pub"); err == nil {
			if f := strings.Fields(string(b)); len(f) >= 2 {
				if k, err := base64.StdEncoding.DecodeString(f[1]); err == nil {
					return k, nil
				}
			}
		}
	}
	return nil, errors.New("no ssh server here to show the phone: run wallflower setup")
}

// claim is what sshd runs for a code's key: it reads the phone's public
// key, lets it in, and takes the code's key out.
func claim(args []string) error {
	fs := flag.NewFlagSet("claim", flag.ExitOnError)
	file := authorizedKeysFlag(fs)
	from := fs.String("from", tailnet, "")
	fs.Parse(args)
	if fs.NArg() != 1 {
		return errors.New("usage: wallflower claim [-file f] [-from cidrs] id")
	}
	id := fs.Arg(0)
	ticket := filepath.Join(pairingDir(), filepath.Base(id))
	if err := os.Remove(ticket); err != nil {
		dropCode(*file, id)
		return errors.New("this code has been used, or stopped: run wallflower pair for a new one")
	}
	defer dropCode(*file, id)
	line, err := bufio.NewReader(io.LimitReader(os.Stdin, 4096)).ReadString('\n')
	if err != nil && line == "" {
		return errors.New("no key came")
	}
	key, err := parseKey(line)
	if err != nil {
		return err
	}
	exe, err := executable()
	if err != nil {
		return err
	}
	existing, err := os.ReadFile(*file)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if !hasKey(string(existing), key) {
		if err := appendLine(*file, authorizedLine(key, *from, exe)); err != nil {
			return err
		}
	}
	os.WriteFile(ticket+".paired", []byte(key.comment), 0o600)
	fmt.Println("paired")
	return nil
}

// appendLine adds a line to authorized_keys, making it if need be.
func appendLine(file, line string) error {
	existing, err := os.ReadFile(file)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(file, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	if len(existing) > 0 && !strings.HasSuffix(string(existing), "\n") {
		line = "\n" + line
	}
	_, err = fmt.Fprintln(f, line)
	return err
}

// isCode is whether an authorized_keys line is a code's, and its id.
func isCode(line string) (string, bool) {
	opts, _ := options(strings.TrimSpace(line))
	f := strings.Fields(opts["command"])
	if len(f) < 3 || filepath.Base(f[0]) != "wallflower" || f[1] != "claim" {
		return "", false
	}
	return f[len(f)-1], true
}

// dropCode takes code id's line out of authorized_keys.
func dropCode(file, id string) error {
	return keepLines(file, func(l string) bool {
		c, ok := isCode(l)
		return !ok || c != id
	})
}

// sweepCodes takes out the lines of codes no longer up, left by a pair
// that didn't get to.
func sweepCodes(file, dir string) error {
	return keepLines(file, func(l string) bool {
		id, ok := isCode(l)
		if !ok {
			return true
		}
		_, err := os.Stat(filepath.Join(dir, filepath.Base(id)))
		return err == nil
	})
}

func keepLines(file string, keep func(string) bool) error {
	b, err := os.ReadFile(file)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	lines := strings.Split(string(b), "\n")
	var kept []string
	for _, l := range lines {
		if keep(l) {
			kept = append(kept, l)
		}
	}
	if len(kept) == len(lines) {
		return nil
	}
	return replace(file, strings.Join(kept, "\n"))
}
