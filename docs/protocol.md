# wallflower protocol, version 1

The client runs `wallflower serve` over ssh and talks to it on stdin/stdout. One
connection is one client session; the gateway exits when the client
disconnects, and has nothing to clean up beyond undoing the client's zooms.

## Frames

Every frame is:

```
uint32  length of everything after this field (big-endian)
uint8   type
...     payload
```

| type | payload |
|------|---------|
| 1 | a JSON object; its `"t"` field names the message |
| 2 | pane data: `uint8 n`, `n` bytes of pane id, then raw bytes |

Pane data from the gateway is output for an open pane. Pane data from the
client is typed into the pane, byte for byte.

Frames are at most 16 MiB. Ids are opaque strings (`%3`, `@1`, `$0` today);
don't parse them.

## Session

1. Client sends `hello`. The gateway gives the client its own tmux session,
   grouped with the one it asked for (default: the one tmux would pick): same
   windows, its own current window, destroyed when the client disconnects.
   Switching windows on the client never moves another client's view.
2. Gateway sends `hello`, then `state`.
3. Client sends `open` for the panes it wants to show. For each, the gateway
   sends `reset` and one data frame that repaints the pane, then streams its
   output.

`state` is resent, whole, whenever anything in it changes. When an open pane
changes size, the gateway sends a fresh `reset` and repaint. It does the same
if it had to drop the pane's output because the client fell behind, so a
client never has to resync on its own.

## Client → gateway

| t | fields | |
|---|--------|---|
| `hello` | `v`, `cols`, `rows`, `session`? | first message; `v` is 1 |
| `resize` | `cols`, `rows` | the client's terminal area changed |
| `open` | `pane`, `history`? | repaint and stream a pane; `history` lines of scrollback (default 1000) |
| `close` | `pane` | stop streaming a pane |
| `keys` | `pane`, `keys`, `bindings`? | tmux key names: `Enter`, `Escape`, `Up`, `C-c`, `M-Enter`, `BTab`, …; encoded for the pane's modes. With `bindings`, as if typed on a keyboard attached to tmux, so its key bindings apply (`prefix` stands for the prefix key) |
| `paste` | `pane`, `text` | paste as a terminal would: bracketed if the app asked for it (newlines don't run anything), newlines as Enter |
| `select` | `window` or `pane` | make it current in the client's session (sizes it for the client) |
| `zoom` | `pane` | toggle zoom; zooms are undone when the client disconnects |
| `focus` | `pane` | make it the window's active pane, zoomed if the window has others (undone on disconnect, like `zoom`) |
| `scrollback` | `id`, `pane`, `start`, `end` | lines in tmux numbering: 0 is the top visible line, negative is history |
| `tmux` | `id`, `command` | run a raw tmux command line (escape hatch) |
| `ping` | `id` | |

Text goes in pane data frames; `keys` is for keys that aren't text. Text with
newlines goes in `paste`, so an app sees it as one paste rather than lines
typed and entered.

Messages are handled in order, so a `pong` means everything sent before its
`ping` has reached tmux.

## Gateway → client

| t | fields | |
|---|--------|---|
| `hello` | `v`, `tmux` | tmux version |
| `state` | `session`, `name`, `windows` | everything in the session, see below; `name` is the group the client asked for |
| `reset` | `pane`, `cols`, `rows` | recreate the pane's terminal at this size; the next data frame repaints it |
| `scrollback` | `id`, `pane`, `history_size`, `lines` | lines keep their SGR escapes |
| `result` | `id`, `lines`?, `error`? | reply to `tmux`, or a failed `scrollback` |
| `error` | `message` | a message failed |
| `pong` | `id` | |
| `bye` | `reason` | the gateway is exiting |

A window is `id`, `index`, `name`, `active`, `zoomed`, `cols`, `rows` and
`panes`. A pane is:

| field | |
|-------|---|
| `id`, `index`, `active` | |
| `x`, `y`, `cols`, `rows` | position and size within the window |
| `floating`, `z` | floating pane, and its stacking order (0 is the front) |
| `alt` | a full-screen app owns the pane; tmux keeps no scrollback for it |
| `agent` | the pane's `@agent_state` option, set by hooks (`tmux set -p @agent_state waiting`) |
| `title`, `path` | |
| `command` | the foreground program; tools that run from a versioned file (`.../claude/versions/2.1.300`) report the name they were run as |

When a window is zoomed, only its active pane is visible.

## Presentation is the client's

State is pushed whenever it changes and every open pane streams, so a client
can keep everything it shows local: scroll, switch windows, and draw panes it
already has open without asking. Messages are for changing tmux, not for
looking at it.

## Sizing

The client's size applies to windows it resizes or selects, and stays until
another client does the same. tmux doesn't count typing, so the gateway
reclaims the current window when the client types and the window has a
different size.
