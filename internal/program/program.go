// Package program is the programs wallflower knows, each a plugin in two
// halves that go by one ID. This half, on the Mac, says which panes run the
// program, and reads its mobile view if it has one. The client's half says
// how to drive it: what's on its screen, its keys, what its notifications
// mean.
package program

import (
	"slices"

	"github.com/ahmd-sh/wallflower/internal/claude"
	"github.com/ahmd-sh/wallflower/internal/codex"
	"github.com/ahmd-sh/wallflower/internal/mobileview"
)

// A Program is one plugin's Mac half, and its phone half if that's data
// (a plugin of the user's: see Load).
type Program struct {
	ID       string            // how both halves know it
	Commands []string          // what its panes report as their command
	View     mobileview.Source // its mobile view; nil if it has none
	Client   *Client           // its phone half, sent with hello; nil if the phone has its own
}

// Runs says whether a pane's command is the program's.
func (p Program) Runs(command string) bool { return slices.Contains(p.Commands, command) }

// Builtin is the programs wallflower ships: Claude Code and Codex (with
// their mobile views, read from claudeDir and codexDir, if they're set),
// zsh, and other shells.
func Builtin(claudeDir, codexDir string) []Program {
	cc := Program{ID: "claude", Commands: []string{"claude"}}
	if claudeDir != "" {
		cc.View = claude.New(claudeDir)
	}
	cx := Program{ID: "codex", Commands: []string{"codex"}}
	if codexDir != "" {
		cx.View = codex.New(codexDir)
	}
	return []Program{
		cc,
		cx,
		{ID: "zsh", Commands: []string{"zsh"}},
		{ID: "shell", Commands: []string{"bash", "fish", "sh", "dash", "ksh", "tcsh", "nu"}},
	}
}
