package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"

	"github.com/ahmd-sh/wallflower/internal/gateway"
)

// listen serves the protocol over TCP on loopback, for developing the app
// against an emulator (adb reverse tcp:41777 tcp:41777). There is no
// authentication, so it refuses anything but a loopback address; real
// clients use serve over ssh.
func listen(args []string) error {
	fs := flag.NewFlagSet("listen", flag.ExitOnError)
	addr := fs.String("addr", "127.0.0.1:41777", "loopback address to listen on")
	options := gatewayFlags(fs)
	fs.Parse(args)
	if _, err := options(); err != nil {
		return err
	}

	host, _, err := net.SplitHostPort(*addr)
	if err != nil {
		return err
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return errors.New("listen only binds loopback addresses; use serve over ssh")
	}
	ln, err := net.Listen("tcp", *addr)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		ln.Close()
	}()
	fmt.Fprintf(os.Stderr, "wallflower: listening on %s (development only)\n", ln.Addr())

	// Shutting down waits for the connections, which stop their plugins' programs.
	var conns sync.WaitGroup
	defer conns.Wait()
	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		conns.Add(1)
		go func() {
			defer conns.Done()
			defer conn.Close()
			// Each connection its own, as serve's are: plugins' programs aren't shared.
			opts, _ := options()
			defer stopPlugins(opts)
			err := gateway.Serve(ctx, conn, conn, opts)
			fmt.Fprintf(os.Stderr, "wallflower: %s disconnected: %v\n", conn.RemoteAddr(), err)
		}()
	}
}
