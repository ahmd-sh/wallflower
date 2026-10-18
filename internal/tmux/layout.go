package tmux

import (
	"encoding/json"
	"fmt"
)

// Layout is tmux 3.8's JSON layout format (version 2), sent in
// %layout-change when the client has the new-layouts flag.
type Layout struct {
	Version int  `json:"V"`
	Root    Cell `json:"L"`
}

// Cell is a pane ("p") or a split ("h" side by side, "v" stacked).
// Floating panes are pane cells with a Z order (0 is the front).
type Cell struct {
	Type     string `json:"t"`
	Width    int    `json:"w"`
	Height   int    `json:"h"`
	X        int    `json:"x"`
	Y        int    `json:"y"`
	Index    int    `json:"i"`
	Pane     string `json:"I,omitempty"`
	Active   bool   `json:"a,omitempty"`
	Z        *int   `json:"z,omitempty"`
	Children []Cell `json:"c,omitempty"`
}

func (c Cell) Floating() bool { return c.Z != nil }

// Panes returns the pane cells in layout order.
func (c Cell) Panes() []Cell {
	if c.Type == "p" {
		return []Cell{c}
	}
	var out []Cell
	for _, child := range c.Children {
		out = append(out, child.Panes()...)
	}
	return out
}

func ParseLayout(s string) (*Layout, error) {
	var l Layout
	if err := json.Unmarshal([]byte(s), &l); err != nil {
		return nil, fmt.Errorf("tmux: layout is not the JSON format (new-layouts flag unset?): %w", err)
	}
	if l.Version != 2 {
		return nil, fmt.Errorf("tmux: unsupported layout version %d", l.Version)
	}
	return &l, nil
}
