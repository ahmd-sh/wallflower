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
| `split` | `pane` | open a pane beside it, in its directory, and focus it like `focus` |
| `new` | | open a window, current in the client's session only |
| `rename` | `window`, `name` | |
| `kill` | `window` or `pane` | close it; closing a window's last pane closes the window |
| `scrollback` | `id`, `pane`, `start`, `end` | lines in tmux numbering: 0 is the top visible line, negative is history |
| `transcript` | `pane`, `on` | start or stop the pane's conversation arriving as `entries`; for panes whose `transcript` is set |
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
| `entries` | `pane`, `reset`?, `more`?, `entries`?, `error`? | a pane's conversation, see below |
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
| `transcript` | the source of the conversation the pane's program records (`claude`), if any |

When a window is zoomed, only its active pane is visible.

## Conversations

Some programs record their conversation as they go: Claude Code writes a
transcript for each session. For a pane running one, the gateway can send
the conversation itself, for a client to render natively, alongside the
terminal, which keeps streaming as usual. It's read-only: input still goes
to the terminal.

After `transcript` with `on`, `entries` with `reset` carries the latest
entries (200; `more` says there are earlier ones), then `entries` without
`reset` as the program writes more. An entry with an `id` already sent
replaces it: a tool call gets its result. An `error` says there's no
conversation (any more), e.g. the program exited. A new conversation in the
same process (`/clear`) arrives as another `reset`.

An entry is `id`, `role` (`user` or `assistant`), `kind` and, by kind:

| kind | fields | |
|------|--------|---|
| `text` | `text` | markdown |
| `thinking` | `text` | |
| `tool` | `name`, `text`, `output`?, `done`?, `error`? | `text` is what the call was asked, in brief; `output` is trimmed |
| `image` | `mime`, `data` | base64 |
| `note` | `text` | an event rather than something said: an interruption, a compacted conversation |

The gateway finds a Claude Code session's transcript from
`~/.claude/sessions/<pid>.json`, so it works for sessions already running.
Neither file format is documented; entries it can't read are skipped.

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
