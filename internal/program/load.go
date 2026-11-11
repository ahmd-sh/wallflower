package program

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/ahmd-sh/wallflower/internal/plugin"
)

// A Client is a plugin's phone half as data, for a program the phone has
// no half of its own for: the key bar its panes get, the commands the
// composer offers, the shortcuts the key bar does, and the keys that start
// a new line and empty one.
type Client struct {
	Keys      *Keys      `json:"keys,omitempty"`
	Commands  []Command  `json:"commands,omitempty"`
	Shortcuts []Shortcut `json:"shortcuts,omitempty"`
	Newline   []string   `json:"newline,omitempty"`
	Clear     []string   `json:"clear,omitempty"`
}

// Keys is a key bar by wallflower's key names, and what the send button
// presses to stop the program.
type Keys struct {
	Name string   `json:"name"`
	Keys []string `json:"keys"`
	Stop []string `json:"stop,omitempty"`
}

// A Command is one the program takes, as the composer offers it.
type Command struct {
	Text  string `json:"text"`
	About string `json:"about,omitempty"`
}

// A Shortcut is what the key bar offers to send: keys by wallflower's key
// names, text typed as it is, and a label if not the keys.
type Shortcut struct {
	Keys  []string `json:"keys"`
	Label string   `json:"label,omitempty"`
}

// manifest is a plugin as its file says: docs/plugins.md.
type manifest struct {
	ID       string   `json:"id"`
	Commands []string `json:"commands"`
	Client   *Client  `json:"client"`
	View     []string `json:"view"`
}

var pluginID = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]*$`)

// Load reads the plugins in dir, one JSON file each, after the programs
// already known (taken), whose IDs they can't have. A file that doesn't
// make a plugin is an error, and the rest still load; no dir (or "") is none.
func Load(dir string, taken []Program) ([]Program, []error) {
	if dir == "" {
		return nil, nil
	}
	files, _ := filepath.Glob(filepath.Join(dir, "*.json"))
	sort.Strings(files)
	ids := map[string]bool{}
	for _, p := range taken {
		ids[p.ID] = true
	}
	var out []Program
	var errs []error
	for _, name := range files {
		p, err := load(name, dir)
		if err == nil && ids[p.ID] {
			err = fmt.Errorf("the id %q is taken", p.ID)
		}
		if err != nil {
			errs = append(errs, fmt.Errorf("plugin %s: %w", filepath.Base(name), err))
			continue
		}
		ids[p.ID] = true
		out = append(out, p)
	}
	return out, errs
}

func load(name, dir string) (Program, error) {
	b, err := os.ReadFile(name)
	if err != nil {
		return Program{}, err
	}
	var m manifest
	if err := json.Unmarshal(b, &m); err != nil {
		return Program{}, err
	}
	switch {
	case !pluginID.MatchString(m.ID):
		return Program{}, fmt.Errorf("id %q: lowercase letters, digits, . _ -", m.ID)
	case len(m.Commands) == 0:
		return Program{}, fmt.Errorf("no commands: which panes run it?")
	}
	p := Program{ID: m.ID, Commands: m.Commands, Client: m.Client}
	if len(m.View) > 0 {
		run := append([]string(nil), m.View...)
		if !filepath.IsAbs(run[0]) && strings.ContainsRune(run[0], filepath.Separator) {
			run[0] = filepath.Join(dir, run[0]) // beside the file
		}
		p.View = &plugin.Source{Command: run}
	}
	return p, nil
}
