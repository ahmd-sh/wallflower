package tmux

import (
	"errors"
	"fmt"
	"os/exec"
	"regexp"
	"strings"
)

// The commands a client drives tmux with, so tmux's syntax stays in this
// package. Pane (%N) and window (@N) ids go into command lines unquoted, so
// each command checks the ids it's given.

var (
	paneID    = regexp.MustCompile(`^%[0-9]+$`)
	windowID  = regexp.MustCompile(`^@[0-9]+$`)
	sessionID = regexp.MustCompile(`^\$[0-9]+$`)
)

func check(re *regexp.Regexp, id string) error {
	if !re.MatchString(id) {
		return fmt.Errorf("tmux: bad id %q", id)
	}
	return nil
}

// SnapshotChanges is a subscription format (refresh-client -B) whose value
// changes whenever a pane's part of the snapshot changes in a way no
// notification announces: its program, title, path, or screen.
var SnapshotChanges = "meta:%*:" + strings.Join([]string{
	"#{pane_current_command}", "#{pane_title}", "#{pane_current_path}", "#{alternate_on}",
}, "\t")

// ResolveSession names the session a client would attach to: session, or
// the one tmux picks if it's empty.
func ResolveSession(socket, session string) (string, error) {
	args := append(socketArgs(socket), "-u", "display-message", "-p")
	if session != "" {
		args = append(args, "-t", session+":")
	}
	out, err := exec.Command(binary(), append(args, "#{session_name}")...).CombinedOutput()
	name := strings.TrimSpace(string(out))
	if err != nil || name == "" {
		return "", fmt.Errorf("no tmux session %q: %s", session, name)
	}
	return name, nil
}

// Configure sizes this client, subscribes it to SnapshotChanges, and says
// tmux's version and the client's name.
func (c *Client) Configure(cols, rows int) (version, name string, err error) {
	r, err := c.Command(fmt.Sprintf("refresh-client -C %dx%d ; refresh-client -B %s ; display -p '#{version}\t#{client_name}'",
		cols, rows, Quote(SnapshotChanges)))
	if err != nil {
		return "", "", err
	}
	lines := r.Lines()
	if len(lines) != 1 {
		return "", "", fmt.Errorf("tmux: unexpected setup reply %q", lines)
	}
	version, name, _ = strings.Cut(lines[0], "\t")
	return version, name, nil
}

// Resize sets this client's size, which applies to windows it's the latest
// to resize, select or type into.
func (c *Client) Resize(cols, rows int) error {
	return c.run(fmt.Sprintf("refresh-client -C %dx%d", cols, rows))
}

// IgnoreSize says whether this client stays out of sizing windows, as one
// whose user isn't looking.
func (c *Client) IgnoreSize(on bool) error {
	flag := "ignore-size"
	if !on {
		flag = "!" + flag
	}
	return c.run("refresh-client -f " + Quote(flag))
}

// SendKeys sends tmux key names (Enter, C-c, BTab...) to a pane, encoded
// for its modes.
func (c *Client) SendKeys(pane string, keys ...string) error {
	if err := check(paneID, pane); err != nil {
		return err
	}
	return c.run("send-keys -t " + pane + " --" + quoteAll(keys))
}

// SendBound sends keys through a client's key tables, as if typed on a
// keyboard attached to tmux, so the prefix and bindings work; "prefix"
// stands for the prefix key.
func (c *Client) SendBound(client string, keys ...string) error {
	keys = append([]string(nil), keys...)
	for i, k := range keys {
		if k != "prefix" {
			continue
		}
		out, err := c.Run("show -gv prefix")
		if err != nil || len(out) != 1 {
			return fmt.Errorf("tmux: no prefix key: %v", err)
		}
		keys[i] = out[0]
	}
	return c.run("send-keys -K -c " + Quote(client) + " --" + quoteAll(keys))
}

// SendBytes types data into a pane byte for byte. send-keys -H takes hex,
// so nothing needs quoting.
func (c *Client) SendBytes(pane string, data []byte) error {
	if err := check(paneID, pane); err != nil {
		return err
	}
	const perCommand, perLine = 512, 16
	var cmds []string
	for len(data) > 0 {
		n := min(len(data), perCommand)
		var b strings.Builder
		b.WriteString("send-keys -t " + pane + " -H")
		for _, x := range data[:n] {
			fmt.Fprintf(&b, " %02x", x)
		}
		cmds = append(cmds, b.String())
		data = data[n:]
		if len(cmds) == perLine || len(data) == 0 {
			if err := c.run(strings.Join(cmds, " ; ")); err != nil {
				return err
			}
			cmds = cmds[:0]
		}
	}
	return nil
}

// Paste pastes text into a pane as a terminal does: bracketed if its app
// asked for that, newlines as Enter. buffer names the paste buffer it
// passes through, deleted after.
func (c *Client) Paste(pane, buffer, text string) error {
	if err := check(paneID, pane); err != nil {
		return err
	}
	return c.run(fmt.Sprintf("set-buffer -b %[1]s -- %[2]s ; paste-buffer -p -d -b %[1]s -t %[3]s", Quote(buffer), QuoteText(text), pane))
}

func (c *Client) SelectPane(pane string) error {
	if err := check(paneID, pane); err != nil {
		return err
	}
	return c.run("select-pane -t " + pane)
}

// Show selects a window in a session, its active pane zoomed if it has
// others, as Focus leaves one; it says whether the window was zoomed before.
func (c *Client) Show(session, window string) (zoomed bool, err error) {
	if err := check(windowID, window); err != nil {
		return false, err
	}
	r, err := c.Command(fmt.Sprintf("display -p -t %[1]s '#{window_zoomed_flag}' ; select-window -t %[2]s ; "+
		"if -F -t %[1]s '#{&&:#{!=:#{window_panes},1},#{!=:#{window_zoomed_flag},1}}' 'resize-pane -Z -t %[1]s'", window, Quote(session+":"+window)))
	if err != nil {
		return false, err
	}
	lines := r.Lines()
	if len(lines) == 0 {
		return false, errors.New("tmux: no window " + window)
	}
	return lines[0] == "1", nil
}

// Focus makes a pane its window's active one, zoomed if the window has
// others; select-pane -Z keeps a zoomed window zoomed on the new pane. It
// says which window that is, and whether it was zoomed before.
func (c *Client) Focus(pane string) (window string, zoomed bool, err error) {
	if err := check(paneID, pane); err != nil {
		return "", false, err
	}
	r, err := c.Command(fmt.Sprintf("display -p -t %[1]s '#{window_id} #{window_zoomed_flag}' ; select-pane -Z -t %[1]s ; "+
		"if -F -t %[1]s '#{&&:#{!=:#{window_panes},1},#{!=:#{window_zoomed_flag},1}}' 'resize-pane -Z -t %[1]s'", pane))
	if err != nil {
		return "", false, err
	}
	lines := r.Lines()
	if len(lines) == 0 {
		return "", false, errors.New("tmux: no window for " + pane)
	}
	w, flag, _ := strings.Cut(lines[0], " ")
	return w, flag == "1", nil
}

// SetZoom zooms or unzooms windows, each to how it's given.
func (c *Client) SetZoom(windows map[string]bool) error {
	var cmds []string
	for w, zoomed := range windows {
		if err := check(windowID, w); err != nil {
			return err
		}
		flag := 0
		if zoomed {
			flag = 1
		}
		cmds = append(cmds, fmt.Sprintf("if -F -t %[1]s '#{!=:#{window_zoomed_flag},%[2]d}' 'resize-pane -Z -t %[1]s'", w, flag))
	}
	if len(cmds) == 0 {
		return nil
	}
	return c.run(strings.Join(cmds, " ; "))
}

// Split opens a pane beside one, in its directory, and says the new pane's id.
func (c *Client) Split(pane string) (string, error) {
	if err := check(paneID, pane); err != nil {
		return "", err
	}
	out, err := c.Run(fmt.Sprintf("split-window -t %s -c %s -P -F '#{pane_id}'", pane, Quote("#{pane_current_path}")))
	if err != nil {
		return "", err
	}
	if len(out) != 1 || check(paneID, out[0]) != nil {
		return "", fmt.Errorf("tmux: unexpected split reply %q", out)
	}
	return out[0], nil
}

// NewWindow opens a window in the current pane's directory, current in this
// client's session.
func (c *Client) NewWindow() error {
	return c.run("new-window -c " + Quote("#{pane_current_path}"))
}

func (c *Client) RenameWindow(window, name string) error {
	if err := check(windowID, window); err != nil {
		return err
	}
	return c.run("rename-window -t " + window + " -- " + QuoteText(name))
}

func (c *Client) KillPane(pane string) error {
	if err := check(paneID, pane); err != nil {
		return err
	}
	return c.run("kill-pane -t " + pane)
}

func (c *Client) KillWindow(window string) error {
	if err := check(windowID, window); err != nil {
		return err
	}
	return c.run("kill-window -t " + window)
}

func (c *Client) run(line string) error {
	_, err := c.Command(line)
	return err
}

func quoteAll(args []string) string {
	var b strings.Builder
	for _, a := range args {
		b.WriteString(" " + Quote(a))
	}
	return b.String()
}
