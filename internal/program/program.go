// Package program is the programs wallflower knows, each a plugin in two
// halves that go by one ID. This half, on the Mac, says which panes run the
// program, and reads its mobile view if it has one. The client's half says
// how to drive it: what's on its screen, its keys, what its notifications
// mean.
package program

import (
	"slices"

	"github.com/ahmd-sh/wallflower/internal/claude"
	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

// A Program is one plugin's Mac half.
type Program struct {
	ID       string            // how both halves know it
	Commands []string          // what its panes report as their command
	View     mobileview.Source // its mobile view; nil if it has none
}

// Runs says whether a pane's command is the program's.
func (p Program) Runs(command string) bool { return slices.Contains(p.Commands, command) }

// Builtin is the programs wallflower ships: Claude Code (with its mobile
// view, read from claudeDir, if that's set), zsh, and other shells.
func Builtin(claudeDir string) []Program {
	cc := Program{ID: "claude", Commands: []string{"claude"}}
	if claudeDir != "" {
		cc.View = claude.New(claudeDir)
	}
	return []Program{
		cc,
		{ID: "zsh", Commands: []string{"zsh"}},
		{ID: "shell", Commands: []string{"bash", "fish", "sh", "dash", "ksh", "tcsh", "nu"}},
	}
}
