package tmux

import (
	"fmt"
	"strconv"
	"strings"
)

type Window struct {
	ID     string  `json:"id"`
	Index  int     `json:"index"`
	Name   string  `json:"name"`
	Active bool    `json:"active"`
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
	AltScreen bool   `json:"alt_screen"`
	CursorX   int    `json:"cursor_x"`
	CursorY   int    `json:"cursor_y"`
	Command   string `json:"command"`
}

// Free-text fields go last so they can contain spaces.
const (
	windowFormat = "#{window_id} #{window_index} #{window_active} #{window_width} #{window_height} #{window_layout} #{window_name}"
	paneFormat   = "#{pane_id} #{window_id} #{pane_index} #{pane_active} #{pane_width} #{pane_height} #{pane_left} #{pane_top} #{pane_floating_flag} #{alternate_on} #{cursor_x} #{cursor_y} #{pane_current_command}"
)

// Snapshot lists the windows and panes of the attached session.
func (c *Client) Snapshot() ([]Window, error) {
	wlines, err := c.Run("list-windows -F '" + windowFormat + "'")
	if err != nil {
		return nil, err
	}
	plines, err := c.Run("list-panes -s -F '" + paneFormat + "'")
	if err != nil {
		return nil, err
	}

	var windows []Window
	byID := map[string]int{}
	for _, line := range wlines {
		f := strings.SplitN(line, " ", 7)
		if len(f) < 7 {
			return nil, fmt.Errorf("tmux: unexpected list-windows line %q", line)
		}
		layout, err := ParseLayout(f[5])
		if err != nil {
			return nil, err
		}
		byID[f[0]] = len(windows)
		windows = append(windows, Window{
			ID: f[0], Index: atoi(f[1]), Active: f[2] == "1",
			Width: atoi(f[3]), Height: atoi(f[4]), Layout: layout, Name: f[6],
		})
	}
	for _, line := range plines {
		f := strings.SplitN(line, " ", 13)
		if len(f) < 13 {
			return nil, fmt.Errorf("tmux: unexpected list-panes line %q", line)
		}
		i, ok := byID[f[1]]
		if !ok {
			continue
		}
		windows[i].Panes = append(windows[i].Panes, Pane{
			ID: f[0], Index: atoi(f[2]), Active: f[3] == "1",
			Width: atoi(f[4]), Height: atoi(f[5]), Left: atoi(f[6]), Top: atoi(f[7]),
			Floating: f[8] == "1", AltScreen: f[9] == "1",
			CursorX: atoi(f[10]), CursorY: atoi(f[11]), Command: f[12],
		})
	}
	return windows, nil
}

func atoi(s string) int {
	n, _ := strconv.Atoi(s)
	return n
}
