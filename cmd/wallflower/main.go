package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"github.com/ahmd-sh/wallflower/internal/claude"
	"github.com/ahmd-sh/wallflower/internal/gateway"
	"github.com/ahmd-sh/wallflower/internal/mobileview"
	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/rex"
	"github.com/ahmd-sh/wallflower/internal/tmux"
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

// gatewayFlags adds the flags that say how a gateway runs, and returns
// what they say, once parsed.
func gatewayFlags(fs *flag.FlagSet) func() (gateway.Options, error) {
	multiplexer := fs.String("mux", "tmux", "the multiplexer the sessions are in when the client doesn't say: tmux or rex")
	socket := fs.String("L", "", "tmux socket name, or path if it contains a slash")
	rexServer := fs.String("rex", "", "Rex server's socket (default: Rex.app's)")
	session := fs.String("t", "", "session to attach to when the client doesn't name one")
	claudeDir := fs.String("claude", defaultClaudeDir(), "Claude Code's data, for its mobile views (empty: none)")
	return func() (gateway.Options, error) {
		muxes := map[string]mux.Mux{"tmux": tmux.Server{Socket: *socket}, "rex": rex.Server{Endpoint: *rexServer}}
		if muxes[*multiplexer] == nil {
			return gateway.Options{}, fmt.Errorf("no multiplexer %q: tmux or rex", *multiplexer)
		}
		var readers []mobileview.Source
		if *claudeDir != "" {
			readers = append(readers, claude.New(*claudeDir))
		}
		return gateway.Options{Muxes: muxes, Mux: *multiplexer, Session: *session, Readers: readers}, nil
	}
}

func defaultClaudeDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".claude")
}
