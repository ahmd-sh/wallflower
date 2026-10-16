package tmux

import (
	"fmt"
	"strconv"
	"strings"
)

type Session struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Windows []Window `json:"windows"`
}

type Window struct {
	ID     string  `json:"id"`
	Index  int     `json:"index"`
	Name   string  `json:"name"`
	Active bool    `json:"active"`
	Zoomed bool    `json:"zoomed"`
	Width  int     `json:"width"`
	Height int     `json:"height"`
	Layout *Layout `json:"layout"`
	Panes  []Pane  `json:"panes"`
}

type Pane struct {
	ID        string `json:"id"`
	Index     int    `json:"index"`
	Active    bool   `json:"active"`
	Width     int    `json:"width"`
	Height    int    `json:"height"`
	Left      int    `json:"left"`
	Top       int    `json:"top"`
	Floating  bool   `json:"floating"`
	Z         int    `json:"z"` // floating panes only; 0 is the front
	AltScreen bool   `json:"alt_screen"`
	Agent     string `json:"agent"` // the @agent_state pane option, set by hooks
	Title     string `json:"title"`
	Path      string `json:"path"`
	Command   string `json:"command"`
	TTY       string `json:"tty"`
}

// Tab-separated so names, titles and paths can contain spaces.
var (
	windowFormat = strings.Join([]string{
		"#{window_id}", "#{window_index}", "#{window_active}", "#{window_zoomed_flag}",
		"#{window_width}", "#{window_height}", "#{window_layout}", "#{window_name}",
	}, "\t")
	paneFormat = strings.Join([]string{
		"#{pane_id}", "#{window_id}", "#{pane_index}", "#{pane_active}",
		"#{pane_width}", "#{pane_height}", "#{pane_left}", "#{pane_top}",
		"#{pane_floating_flag}", "#{alternate_on}", "#{@agent_state}",
		"#{pane_title}", "#{pane_current_path}", "#{pane_current_command}", "#{pane_tty}",
	}, "\t")
)

// Snapshot lists the attached session's windows and panes in one atomic
// read.
func (c *Client) Snapshot() (*Session, error) {
	r, err := c.Command("display -p " + Quote("#{session_id}\t#{session_name}") +
		" ; list-windows -F " + Quote(windowFormat) + " ; list-panes -s -F " + Quote(paneFormat))
	if err != nil {
		return nil, err
	}
	if len(r.Blocks) != 3 || len(r.Blocks[0]) != 1 {
		return nil, fmt.Errorf("tmux: unexpected snapshot reply %q", r.Blocks)
	}
	s := &Session{}
	s.ID, s.Name, _ = strings.Cut(r.Blocks[0][0], "\t")

	var windows []Window
	byID := map[string]int{}
	for _, line := range r.Blocks[1] {
		f := strings.Split(line, "\t")
		if len(f) != 8 {
			return nil, fmt.Errorf("tmux: unexpected list-windows line %q", line)
		}
		layout, err := ParseLayout(f[6])
		if err != nil {
			return nil, err
		}
		byID[f[0]] = len(windows)
		windows = append(windows, Window{
			ID: f[0], Index: atoi(f[1]), Active: f[2] == "1", Zoomed: f[3] == "1",
			Width: atoi(f[4]), Height: atoi(f[5]), Layout: layout, Name: f[7],
		})
	}
	for _, line := range r.Blocks[2] {
		f := strings.Split(line, "\t")
		if len(f) != 15 {
			return nil, fmt.Errorf("tmux: unexpected list-panes line %q", line)
		}
		i, ok := byID[f[1]]
		if !ok {
			continue
		}
		p := Pane{
			ID: f[0], Index: atoi(f[2]), Active: f[3] == "1",
			Width: atoi(f[4]), Height: atoi(f[5]), Left: atoi(f[6]), Top: atoi(f[7]),
			Floating: f[8] == "1", AltScreen: f[9] == "1",
			Agent: f[10], Title: f[11], Path: f[12], Command: f[13], TTY: f[14],
		}
		for _, cell := range windows[i].Layout.Root.Panes() {
			if cell.Pane == p.ID && cell.Z != nil {
				p.Z = *cell.Z
			}
		}
		windows[i].Panes = append(windows[i].Panes, p)
	}
	s.Windows = windows
	return s, nil
}

// Quote wraps s in single quotes for a tmux command line. tmux has no
// escapes inside single quotes, so embedded ones close and reopen the quote.
func Quote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'"'"'`) + "'"
}

// QuoteText double-quotes any text for a tmux command line, escaping
// newlines and control characters (control mode reads one command per line)
// and the $ that would otherwise expand. NUL can't be in a tmux argument and
// is dropped.
func QuoteText(s string) string {
	var b strings.Builder
	b.WriteByte('"')
	for i := 0; i < len(s); i++ {
		switch c := s[i]; {
		case c == 0:
		case c == '\\' || c == '"' || c == '$':
			b.WriteByte('\\')
			b.WriteByte(c)
		case c == '\n':
			b.WriteString(`\n`)
		case c == '\r':
			b.WriteString(`\r`)
		case c == '\t':
			b.WriteString(`\t`)
		case c < 0x20 || c == 0x7f:
			fmt.Fprintf(&b, `\%03o`, c)
		default:
			b.WriteByte(c)
		}
	}
	b.WriteByte('"')
	return b.String()
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}
