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
	options := gatewayFlags(fs)
	fs.Parse(args)
	opts, err := options()
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM, syscall.SIGHUP)
	defer stop()
	return gateway.Serve(ctx, os.Stdin, os.Stdout, opts)
}
