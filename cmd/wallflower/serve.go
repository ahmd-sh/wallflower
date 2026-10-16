package main

import (
	"context"
	"flag"
	"os"
	"os/signal"
	"syscall"

	"github.com/ahmd-sh/wallflower/internal/gateway"
)

// serve speaks the protocol on stdin/stdout for one client; the phone runs
// it over ssh. Nothing else may write to stdout.
func serve(args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	socket := fs.String("L", "", "tmux socket name, or path if it contains a slash")
	session := fs.String("t", "", "session to attach to when the client doesn't name one")
	fs.Parse(args)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM, syscall.SIGHUP)
	defer stop()
	return gateway.Serve(ctx, os.Stdin, os.Stdout, gateway.Options{Socket: *socket, Session: *session})
}
