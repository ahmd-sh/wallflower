package main

import (
	"fmt"
	"os"
)

const usage = `usage: tether <command> [flags]

commands:
  watch    attach to tmux in control mode and print events as JSON lines
`

func main() {
	if len(os.Args) < 2 {
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "watch":
		err = watch(os.Args[2:])
	default:
		fmt.Fprint(os.Stderr, usage)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "tether:", err)
		os.Exit(1)
	}
}
