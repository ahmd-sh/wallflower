# wallflower

A phone-first shell around your terminal. tmux stays the server; a small
gateway on the Mac speaks tmux control mode and serves a lean, versioned
protocol to clients (an Android app first) over SSH.

```
phone app ──ssh stdio──▶ wallflower serve (one per connection) ──tmux -C──▶ tmux server
```

## Status

The gateway works end to end; there is no phone app yet. See
[docs/protocol.md](docs/protocol.md).

```sh
go build -o bin/wallflower ./cmd/wallflower
bin/wallflower debug -cols 56 -rows 20          # local gateway as JSON lines
bin/wallflower debug -- ssh mac "~/go/bin/wallflower serve"   # through ssh
bin/wallflower watch -output=false              # raw control-mode events
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
- Each command in a `;` list gets its own reply block, and a parse error is
  one block for the whole line, so the client ends every line with a marker
  command. Commands in one line run back to back: an atomic read.
- Without a UTF-8 locale tmux replaces tabs and non-ASCII in replies with
  `_`; `-u` forces UTF-8. It assumes UTF-8 whenever `$TMUX` is set.
- A control client only affects window size after `refresh-client -C`.
  Re-sending the same size doesn't reclaim a window; `select-window` does.
- With an alternate screen active, `capture-pane` shows it; `-a` shows the
  normal screen behind it, and `-S -N -E -1` the normal screen's history.
- `capture-pane -e` carries attributes across lines.
