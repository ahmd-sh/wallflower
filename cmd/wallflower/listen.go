package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
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
	socket := fs.String("L", "", "tmux socket name, or path if it contains a slash")
	session := fs.String("t", "", "session to attach to when the client doesn't name one")
	fs.Parse(args)

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

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		go func() {
			defer conn.Close()
			err := gateway.Serve(ctx, conn, conn, gateway.Options{Socket: *socket, Session: *session})
			fmt.Fprintf(os.Stderr, "wallflower: %s disconnected: %v\n", conn.RemoteAddr(), err)
		}()
	}
}
