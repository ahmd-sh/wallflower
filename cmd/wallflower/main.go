package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/ahmd-sh/wallflower/internal/gateway"
	"github.com/ahmd-sh/wallflower/internal/mux"
	"github.com/ahmd-sh/wallflower/internal/program"
	"github.com/ahmd-sh/wallflower/internal/rex"
	"github.com/ahmd-sh/wallflower/internal/tmux"
)

const usage = `usage: wallflower <command> [flags]

commands:
  serve      speak the wallflower protocol on stdin/stdout (run by the phone over ssh)
  authorize  add a phone's key to authorized_keys, restricted to running serve
  devices    list the phones whose keys can connect
  revoke     take a phone's key out of authorized_keys
  debug      run a gateway and bridge it to JSON lines, for poking at the protocol
  listen     serve the protocol over loopback TCP, for developing against an emulator
  watch      attach to tmux in control mode and print events as JSON lines
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	commands := map[string]func([]string) error{"serve": serve, "authorize": authorize, "devices": devices, "revoke": revoke, "debug": debug, "listen": listen, "watch": watch}
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
	claudeDir := fs.String("claude", defaultClaudeDir(), "Claude Code's data, for its mobile views (empty: none)")
	plugins := fs.String("plugins", defaultPluginDir(), "plugins of your own, a JSON file each (docs/plugins.md)")
	return func() (gateway.Options, error) {
		muxes := map[string]mux.Mux{"tmux": tmux.Server{Socket: *socket}, "rex": rex.Server{Endpoint: *rexServer}}
		if muxes[*multiplexer] == nil {
			return gateway.Options{}, fmt.Errorf("no multiplexer %q: tmux or rex", *multiplexer)
		}
		builtin := program.Builtin(*claudeDir)
		own, errs := program.Load(*plugins, builtin)
		for _, err := range errs {
			fmt.Fprintln(os.Stderr, "wallflower:", err)
		}
		// Yours first: a command one of yours claims is its, not a built-in's.
		return gateway.Options{Muxes: muxes, Mux: *multiplexer, Programs: append(own, builtin...)}, nil
	}
}

// stopPlugins stops the programs plugins run for their mobile views.
func stopPlugins(opts gateway.Options) {
	for _, p := range opts.Programs {
		if c, ok := p.View.(io.Closer); ok {
			c.Close()
		}
	}
}

func defaultPluginDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".config", "wallflower", "plugins")
}

func defaultClaudeDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	return filepath.Join(home, ".claude")
}
