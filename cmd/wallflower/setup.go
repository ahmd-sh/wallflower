package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// setup readies this computer for the phone, saying what it finds: it puts
// wallflower where it stays, looks for tmux, an ssh server and Tailscale,
// offers to install or start what's missing, then shows a code to pair.
func setup(args []string) error {
	fs := flag.NewFlagSet("setup", flag.ExitOnError)
	o := pairFlags(fs)
	yes := fs.Bool("y", false, "run what's needed without asking")
	fs.Parse(args)

	exe, where, err := install()
	if err != nil {
		return err
	}
	o.exe = exe
	in := bufio.NewReader(os.Stdin)
	fmt.Println()
	line(true, "wallflower", where)
	sys := detect()
	for round := 0; ; round++ {
		found := look(o, sys)
		var packages, fixes, steps []string
		missing := false
		for _, f := range found {
			line(f.ok, f.name, f.detail)
			if !f.ok {
				missing = true
				packages = append(packages, f.packages...)
				fixes = append(fixes, f.fix...)
				if f.step != "" {
					steps = append(steps, f.step)
				}
			}
		}
		if len(packages) > 0 {
			fixes = append([]string{sys.install + " " + strings.Join(packages, " ")}, fixes...)
		}
		if round == 0 {
			fixes = append(fixes, sys.firewall(o)...)
		}
		if len(steps) > 0 {
			fmt.Println()
			for _, s := range steps {
				fmt.Println(s)
			}
		}
		if !missing && len(fixes) == 0 {
			break
		}
		if round > 0 || len(fixes) == 0 {
			if missing {
				return errors.New("not ready yet: run wallflower setup again once that's done")
			}
			break
		}
		fmt.Println("\nTo get it ready, this runs:")
		for _, f := range fixes {
			fmt.Println("  " + f)
		}
		if !*yes && !ask(in, "Run it?") {
			return errors.New("nothing changed: run wallflower setup again when you're ready")
		}
		for _, f := range fixes {
			fmt.Println("\n$ " + f)
			cmd := exec.Command("sh", "-c", f)
			cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
			if err := cmd.Run(); err != nil {
				return fmt.Errorf("%s: %v", f, err)
			}
		}
		fmt.Println()
	}
	if o.host == "" {
		t, err := tailscaleStatus()
		if err != nil {
			return err
		}
		o.host = t.address()
	}
	return showCode(o)
}

func line(ok bool, name, detail string) {
	mark := "\x1b[32m✓\x1b[0m"
	if !ok {
		mark = "\x1b[31m✗\x1b[0m"
	}
	fmt.Printf("  %s %-11s %s\n", mark, name, detail)
}

func ask(in *bufio.Reader, question string) bool {
	fmt.Print(question + " [Y/n] ")
	a, err := in.ReadString('\n')
	if err != nil && a == "" {
		fmt.Println()
		return false
	}
	a = strings.ToLower(strings.TrimSpace(a))
	return a == "" || a == "y" || a == "yes"
}

// install puts this wallflower in ~/.local/bin, where it stays for sshd to
// run for the phone, unless it's where the shell finds it already. It
// gives where it is, and that said for the user.
func install() (exe, where string, err error) {
	exe, err = executable()
	if err != nil {
		return "", "", err
	}
	if p, err := exec.LookPath("wallflower"); err == nil {
		if r, _ := filepath.EvalSymlinks(p); r == exe {
			return exe, tilde(exe), nil
		}
	}
	dest := filepath.Join(os.Getenv("HOME"), ".local", "bin", "wallflower")
	if exe != dest {
		if err := copyFile(exe, dest); err != nil {
			return "", "", err
		}
	}
	where = tilde(dest)
	if !onPath(filepath.Dir(dest)) {
		where += " (not on your PATH: add ~/.local/bin to run it by name)"
	}
	return dest, where, nil
}

func copyFile(from, to string) error {
	src, err := os.Open(from)
	if err != nil {
		return err
	}
	defer src.Close()
	if err := os.MkdirAll(filepath.Dir(to), 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(filepath.Dir(to), ".wallflower.*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := io.Copy(tmp, src); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(0o755); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), to) // a running one keeps its own copy
}

func onPath(dir string) bool {
	for _, d := range filepath.SplitList(os.Getenv("PATH")) {
		if d == dir {
			return true
		}
	}
	return false
}

func tilde(p string) string {
	if home := os.Getenv("HOME"); home != "" && strings.HasPrefix(p, home+"/") {
		return "~" + p[len(home):]
	}
	return p
}

// A finding is one thing the phone needs here: whether it's there, what's
// known of it, and the packages and commands that would fix it, or else
// what to do.
type finding struct {
	name, detail string
	ok           bool
	packages     []string
	fix          []string
	step         string
}

// system is how this computer installs and starts things.
type system struct {
	os         string
	install    string // the package manager's install command, packages after
	ssh        string // the ssh server's package
	sshService string
	tailscale  string // Tailscale's install: a package, or a command whole
	systemd    bool
}

func detect() system {
	s := system{os: runtime.GOOS}
	has := installed
	s.systemd = has("systemctl")
	switch {
	case runtime.GOOS == "darwin":
		if has("brew") {
			s.install = "brew install"
		}
	case has("pacman"):
		s.install, s.ssh, s.sshService, s.tailscale = "sudo pacman -S --needed --noconfirm", "openssh", "sshd", "tailscale"
	case has("apt-get"):
		s.install, s.ssh, s.sshService = "sudo apt-get install -y", "openssh-server", "ssh"
	case has("dnf"):
		s.install, s.ssh, s.sshService = "sudo dnf install -y", "openssh-server", "sshd"
	case has("zypper"):
		s.install, s.ssh, s.sshService = "sudo zypper --non-interactive install", "openssh", "sshd"
	}
	if s.os != "darwin" && s.tailscale == "" {
		s.tailscale = "curl -fsSL https://tailscale.com/install.sh | sh"
	}
	return s
}

// look checks for what the phone needs: tmux, an ssh server, and (unless
// told where the phone reaches it) Tailscale.
func look(o *pairOptions, s system) []finding {
	var out []finding

	tmux := finding{name: "tmux"}
	if v, err := exec.Command("tmux", "-V").Output(); err == nil {
		tmux.ok, tmux.detail = true, strings.TrimPrefix(strings.TrimSpace(string(v)), "tmux ")
	} else {
		tmux.detail = "not installed"
		if s.install != "" {
			tmux.packages = []string{"tmux"}
		} else {
			tmux.step = "Install tmux with your package manager (on a Mac, get Homebrew from brew.sh, then: brew install tmux)."
		}
	}
	out = append(out, tmux)

	ssh := finding{name: "ssh server"}
	if c, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(o.port)), time.Second); err == nil {
		c.Close()
		ssh.ok, ssh.detail = true, "on"
	} else {
		ssh.detail = "not on"
		switch {
		case s.os == "darwin":
			ssh.step = "Turn on Remote Login: System Settings › General › Sharing › Remote Login."
			exec.Command("open", "x-apple.systempreferences:com.apple.Sharing-Settings.extension").Start()
		case s.ssh != "" && s.systemd:
			if !installed("sshd") {
				ssh.packages = []string{s.ssh}
			}
			ssh.fix = []string{"sudo systemctl enable --now " + s.sshService}
		default:
			ssh.step = "Install and start an ssh server (OpenSSH's sshd)."
		}
	}
	out = append(out, ssh)

	if o.host != "" {
		return out
	}
	ts := finding{name: "Tailscale"}
	t, err := tailscaleStatus()
	switch {
	case errors.Is(err, errNoTailscale):
		ts.detail = "not installed"
		if s.os == "darwin" {
			ts.step = "Install Tailscale from tailscale.com/download and sign in."
		} else {
			if strings.Contains(s.tailscale, " ") {
				ts.fix = []string{s.tailscale}
			} else {
				ts.packages = []string{s.tailscale}
			}
			if s.systemd {
				ts.fix = append(ts.fix, "sudo systemctl enable --now tailscaled", "sudo tailscale up")
			}
		}
	case err != nil:
		ts.detail = "not running"
		if s.os == "darwin" {
			ts.step = "Open the Tailscale app and sign in."
		} else if s.systemd {
			ts.fix = []string{"sudo systemctl enable --now tailscaled", "sudo tailscale up"}
		}
	case t.address() == "":
		ts.detail = "signed out"
		if s.os == "darwin" {
			ts.step = "Sign in to Tailscale (its menu bar icon)."
		} else {
			ts.fix = []string{"sudo tailscale up"}
		}
	default:
		ts.ok, ts.detail = true, t.address()
	}
	out = append(out, ts)
	return out
}

// firewall is what lets the phone through ufw (Omarchy turns it on, letting
// nothing in) to ssh, over Tailscale only. ufw won't say its rules without
// root, so this offers the rule whenever ufw's on; adding it twice is
// nothing.
func (s system) firewall(o *pairOptions) []string {
	if s.os != "linux" || !s.systemd || o.host != "" {
		return nil
	}
	if exec.Command("systemctl", "is-active", "--quiet", "ufw").Run() != nil {
		return nil
	}
	return []string{fmt.Sprintf("sudo ufw allow in on tailscale0 to any port %d proto tcp", o.port)}
}

// installed is whether a command's on the PATH, or where sshd lives.
func installed(name string) bool {
	if _, err := exec.LookPath(name); err == nil {
		return true
	}
	for _, dir := range []string{"/usr/sbin", "/usr/local/sbin", "/sbin"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err == nil {
			return true
		}
	}
	return false
}

var errNoTailscale = errors.New("Tailscale isn't installed")

// tailscale is what `tailscale status --json` says, as far as pairing
// needs.
type tailscale struct {
	BackendState string
	Self         struct {
		TailscaleIPs []string
	}
}

// address is where the phone reaches this computer over Tailscale: its
// IPv4 address there, which stays the same for as long as it's on the
// tailnet, whatever it's renamed to; empty unless Tailscale's connected.
func (t tailscale) address() string {
	if t.BackendState != "Running" {
		return ""
	}
	for _, ip := range t.Self.TailscaleIPs {
		if !strings.Contains(ip, ":") {
			return ip
		}
	}
	return ""
}

func tailscaleStatus() (tailscale, error) {
	cli, err := exec.LookPath("tailscale")
	if err != nil {
		cli = "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
		if _, err := os.Stat(cli); err != nil {
			return tailscale{}, errNoTailscale
		}
	}
	out, err := exec.Command(cli, "status", "--json").Output()
	var t tailscale
	if jerr := json.Unmarshal(out, &t); jerr != nil {
		if err == nil {
			err = jerr
		}
		return tailscale{}, fmt.Errorf("Tailscale isn't running: %v", err)
	}
	return t, nil
}
