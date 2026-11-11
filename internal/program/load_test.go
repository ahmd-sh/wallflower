package program

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/ahmd-sh/wallflower/internal/plugin"
)

func TestLoad(t *testing.T) {
	dir := t.TempDir()
	for name, body := range map[string]string{
		"aider.json":   `{"id": "aider", "commands": ["aider"], "client": {"keys": {"name": "aider", "keys": ["Escape", "C-c"]}, "commands": [{"text": "/undo", "about": "undo the last change"}], "newline": ["M-Enter"]}, "view": ["./reader", "--json"]}`,
		"claude.json":  `{"id": "claude", "commands": ["claude"]}`,
		"broken.json":  `{"id": `,
		"Upper.json":   `{"id": "Upper", "commands": ["x"]}`,
		"nothing.json": `{"id": "nothing"}`,
		"notes.txt":    `not a plugin`,
	} {
		os.WriteFile(filepath.Join(dir, name), []byte(body), 0o600)
	}
	got, errs := Load(dir, Builtin("", ""))
	want := []Program{{
		ID: "aider", Commands: []string{"aider"},
		Client: &Client{Keys: &Keys{Name: "aider", Keys: []string{"Escape", "C-c"}}, Commands: []Command{{"/undo", "undo the last change"}}, Newline: []string{"M-Enter"}},
		View:   &plugin.Source{Command: []string{filepath.Join(dir, "reader"), "--json"}},
	}}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("loaded %+v", got)
	}
	var said []string
	for _, err := range errs {
		said = append(said, err.Error())
	}
	for _, what := range []string{"broken.json", `claude.json: the id "claude" is taken`, "Upper.json", "nothing.json: no commands"} {
		if !strings.Contains(strings.Join(said, "\n"), what) {
			t.Errorf("errors %q, want one about %s", said, what)
		}
	}
	if none, errs := Load(filepath.Join(dir, "missing"), nil); len(none) != 0 || len(errs) != 0 {
		t.Errorf("no dir: %v, %v", none, errs)
	}
	t.Chdir(dir) // "" isn't the working directory
	if none, errs := Load("", nil); len(none) != 0 || len(errs) != 0 {
		t.Errorf(`"": %v, %v`, none, errs)
	}
}
