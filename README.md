# tether

A phone-first shell around your terminal. tmux stays the server; a small
gateway on the Mac speaks tmux control mode and serves a lean, versioned
protocol to clients (an Android app first) over SSH.

```
phone app ──ssh stdio──▶ tether (gateway, on demand) ──tmux -CC──▶ tmux server
```

## Status

Spike: `tether watch` attaches in control mode and prints a snapshot plus
every event as JSON lines.

```sh
go build -o bin/tether ./cmd/tether
bin/tether watch -output=false     # default server, current session
bin/tether watch -L test -t demo   # isolated server
```

Requires tmux 3.8 (JSON layouts, floating panes).

## tmux control mode notes

- Command replies are `%begin`/`%end` blocks whose last field is 1 for
  commands this client sent, 0 for the attach itself.
- `-f new-layouts` makes `%layout-change` use the JSON layout format; floating
  panes are pane cells with a `z` order. `#{window_layout}` is JSON regardless.
- `-f ignore-size` keeps a watching client from resizing windows.
- `read-only` only blocks keys; commands sent on stdin still run.
- Floating pane sizes from `new-pane -x/-y` include the border.
- `%session-renamed` carries `$id name`, despite the man page.
- Subscriptions (`refresh-client -B`) report current values on subscribe,
  then changes at most once a second.
