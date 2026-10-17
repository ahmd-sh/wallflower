package main

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/ahmd-sh/wallflower/internal/claude"
	"github.com/ahmd-sh/wallflower/internal/transcript"
)

const usage = `usage: wallflower <command> [flags]

commands:
  serve      speak the wallflower protocol on stdin/stdout (run by the phone over ssh)
  authorize  add a phone's key to authorized_keys, restricted to running serve
  debug      run a gateway and bridge it to JSON lines, for poking at the protocol
  listen     serve the protocol over loopback TCP, for developing against an emulator
  watch      attach to tmux in control mode and print events as JSON lines
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	commands := map[string]func([]string) error{"serve": serve, "authorize": authorize, "debug": debug, "listen": listen, "watch": watch}
	run, ok := commands[os.Args[1]]
	if !ok {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	if err := run(os.Args[2:]); err != nil {
		fmt.Fprintln(os.Stderr, "wallflower:", err)
		os.Exit(1)
	}
}

func defaultClaudeDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".claude")
}

// transcripts are the CLIs whose conversations panes can show.
func transcripts(claudeDir string) []transcript.Source {
	if claudeDir == "" {
		return nil
	}
	return []transcript.Source{claude.New(claudeDir)}
}
