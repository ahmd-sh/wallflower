package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"

	"github.com/ahmd-sh/wallflower/internal/protocol"
)

const debugUsage = `usage: wallflower debug [flags] [server command...]

Runs a gateway (default: this binary's serve) and bridges it to JSON lines.
Each stdin line is a client message, e.g.
  {"t":"open","pane":"%%1"}
  {"t":"keys","pane":"%%1","keys":["C-c"]}
  {"t":"data","pane":"%%1","text":"ls\r"}     (a pane data frame)
Gateway messages print as JSON; pane data prints as {"t":"data",...}.

flags:
`

func debug(args []string) error {
	fs := flag.NewFlagSet("debug", flag.ExitOnError)
	cols := fs.Int("cols", 80, "terminal columns to report in hello")
	rows := fs.Int("rows", 24, "terminal rows to report in hello")
	session := fs.String("t", "", "session to ask for")
	preview := fs.Int("preview", 200, "bytes of pane data to print (0 for all)")
	fs.Usage = func() {
		fmt.Fprintf(fs.Output(), debugUsage)
		fs.PrintDefaults()
	}
	fs.Parse(args)

	server := fs.Args()
	if len(server) == 0 {
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		server = []string{exe, "serve"}
	}
	cmd := exec.Command(server[0], server[1:]...)
	cmd.Stderr = os.Stderr
	toServer, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	fromServer, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		return err
	}

	hello, _ := protocol.AppendJSON(nil, protocol.ClientHello{V: protocol.Version, Cols: *cols, Rows: *rows, Session: *session})
	toServer.Write(hello)

	go func() {
		defer toServer.Close()
		sc := bufio.NewScanner(os.Stdin)
		sc.Buffer(nil, protocol.MaxFrame)
		for sc.Scan() {
			frame, err := encodeLine(sc.Bytes())
			if err != nil {
				fmt.Fprintln(os.Stderr, "wallflower debug:", err)
				continue
			}
			if _, err := toServer.Write(frame); err != nil {
				return
			}
		}
	}()

	enc := json.NewEncoder(os.Stdout)
	enc.SetEscapeHTML(false)
	r := protocol.NewReader(fromServer)
	for {
		f, err := r.Next()
		if err != nil {
			if !errors.Is(err, io.EOF) {
				fmt.Fprintln(os.Stderr, "wallflower debug:", err)
			}
			break
		}
		if f.Type == protocol.FrameJSON {
			os.Stdout.Write(append(f.Data, '\n'))
			continue
		}
		text := f.Data
		if *preview > 0 && len(text) > *preview {
			text = text[:*preview]
		}
		enc.Encode(map[string]any{"t": "data", "pane": f.ID, "bytes": len(f.Data), "text": string(text)})
	}
	return cmd.Wait()
}

func encodeLine(line []byte) ([]byte, error) {
	var data struct {
		T    string `json:"t"`
		Pane string `json:"pane"`
		Text string `json:"text"`
	}
	if err := json.Unmarshal(line, &data); err != nil {
		return nil, err
	}
	if data.T == "data" {
		return protocol.AppendData(nil, data.Pane, []byte(data.Text)), nil
	}
	m, err := protocol.DecodeClient(line)
	if err != nil {
		return nil, err
	}
	return protocol.AppendJSON(nil, m)
}
