# wallflower protocol, version 2

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
| 1 | a JSON object; its `"t"` field, always first, names the message |
| 2 | pane data: `uint8 n`, `n` bytes of pane id, then raw bytes |
| 3 | upload data: `uint8 n`, `n` bytes of upload id, then the upload's next bytes |
| 4 | a picture: `uint8 n`, `n` bytes of entry id, then the picture's bytes (none if it's gone) |

Pane data from the gateway is output for an open pane. Pane data from the
client is typed into the pane, byte for byte (a mouse wheel's, say);
composed text goes in `submit`.

Frames are at most 16 MiB. A frame of a type a reader doesn't know is
skipped, so the protocol can grow by adding them. Ids are opaque strings
(`%3`, `@1`, `$0` today); don't parse them.

## Session

1. Client sends `hello`. The gateway attaches to the tmux session it asked
   for (default: the one tmux would pick), as any other client of it does:
   a window switched to on the client is switched to on the others too.
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
| `hello` | `v`, `cols`, `rows`, `session`? | first message; `v` is 2 |
| `resize` | `cols`, `rows` | the client's terminal area changed |
| `open` | `pane`, `history`? | repaint and stream a pane; `history` lines of scrollback (default 1000) |
| `close` | `pane` | stop streaming a pane |
| `keys` | `pane`, `keys`, `bindings`? | tmux key names: `Enter`, `Escape`, `Up`, `C-c`, `M-Enter`, `BTab`, …; encoded for the pane's modes. With `bindings`, as if typed on a keyboard attached to tmux, so its key bindings apply (`prefix` stands for the prefix key) |
| `submit` | `pane`, `text`, `enter`?, `clear`? | composed text in place of what the program's input line holds, see below |
| `select` | `window` or `pane` | make it current in the client's session (sizes it for the client) |
| `focus` | `pane` | make it the window's active pane, zoomed if the window has others (undone on disconnect) |
| `split` | `pane` | open a pane beside it, in its directory, and focus it like `focus` |
| `new` | | open a window, current in the client's session only |
| `rename` | `window`, `name` | |
| `kill` | `window` or `pane` | close it; closing a window's last pane closes the window |
| `read` | `pane`, `on` | start or stop the pane's read view arriving as `entries`; for panes whose `reader` is set |
| `picture` | `pane`, `id` | a picture entry's bytes, which come in a picture frame |
| `upload` | `id`, `name`, `size` | send a file: `size` bytes follow in upload frames with this `id`, see below |
| `ping` | `id` | |

Messages are handled in order, so a `pong` means everything sent before its
`ping` has reached tmux.

`submit` sends `clear` first, keys that empty the program's input line
(the client reads the line off the screen, and knows the program). Then
the text goes as the program takes it best: an upload's path is pasted on
its own, as a file dropped on a terminal is, and given a moment to be taken
in (Claude Code attaches an image); text with newlines is pasted, bracketed
if the program asked, so its lines don't run one by one; the rest is typed.
Then `enter` presses Enter, a moment after typed text: a program that reads
them at once takes it all as a paste, Enter and all.

## Gateway → client

| t | fields | |
|---|--------|---|
| `hello` | `v`, `tmux`, `uploads`? | tmux version; the directory uploads are saved in |
| `state` | `session`, `name`, `windows` | everything in the session, see below |
| `reset` | `pane`, `cols`, `rows` | recreate the pane's terminal at this size; the next data frame repaints it |
| `entries` | `pane`, `reset`?, `more`?, `entries`?, `error`?, `context`? | a pane's read view, see below |
| `error` | `message` | a message failed |
| `pong` | `id` | |
| `uploaded` | `id`, `path`?, `error`? | where an upload was saved, or why it wasn't |
| `bye` | `reason` | the gateway is exiting |

A window is `id`, `index`, `name`, `active` and `panes`. A pane is:

| field | |
|-------|---|
| `id`, `index`, `active` | |
| `cols`, `rows` | its size |
| `alt` | a full-screen app owns the pane; tmux keeps no scrollback for it |
| `title`, `path` | |
| `command` | the foreground program; tools that run from a versioned file (`.../claude/versions/2.1.300`) report the name they were run as |
| `reader` | what reads the pane's program for its read view (`claude`), if anything does |
| `activity`, `since` | what the program is doing, if it says: `working` on a turn (through its steps, until it's finally done), `waiting` for the user to answer something mid-turn (a permission, a question), or `idle`; `since` when, in Unix milliseconds |

When a window is zoomed, only its active pane is visible.

## Read views

Some programs can be read for what they're doing as it happens: Claude
Code records each conversation as it goes. For a pane running one, the
gateway sends that, for a client to render natively in place of the
terminal, which keeps streaming as usual. It's read-only: input still goes
to the terminal.

After `read` with `on`, `entries` with `reset` carries the latest entries
(up to 200, as many as fit in about 4 MiB; `more` says there are earlier
ones), then `entries` without `reset` as the program writes more, split over
several messages when there's a lot. An entry with an `id` already sent
replaces it: a step gets its result. An `error` says there's nothing to
read (any more), e.g. the program exited. A new conversation in the same
process (`/clear`) arrives as another `reset`, as does the conversation
going back to an earlier point (a rewind), without what it left behind.

`context` is how many tokens the conversation takes of the program's
context, as of its latest reply, sent when it says and whenever it changes;
0 when it no longer knows (just after a compaction, until the next reply).

What the program is asking isn't in the read view: it's on the pane's
screen, and answers are keys, as typed.

An entry is `id`, `from` (`user` or `program`), `kind` and, by kind:

| kind | fields | |
|------|--------|---|
| `said` | `text` | markdown |
| `thought` | `text` | |
| `step` | `name`, `text`, `status`, `output`?, `output_cut`?, `diff`?, `diff_cut`? | `text` is what the step was asked, in brief; `status` is `running`, `done` or `failed`; `output` is trimmed, `output_cut` characters short; `diff` is the change it made to a file, as unified diff hunks (`@@ -a,b +c,d @@`, then lines starting ` `, `-` or `+`), `diff_cut` lines short |
| `picture` | `mime`, `size` | its bytes, `size` of them, come when asked for with `picture`; the gateway keeps a read view's recent pictures |
| `note` | `text` | an event rather than something said: an interruption, a compacted conversation |

A client shows an entry of a kind it doesn't know as a note of its `text`.

The gateway finds a Claude Code session's transcript, and what it's doing,
from `~/.claude/sessions/<pid>.json`, so it works for sessions already
running. A plan written in plan mode (`~/.claude/plans/*.md`) arrives as a
`said` entry after its write, to read as a reply. Neither file format is
documented; entries it can't read are skipped.

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

## Uploads

A client sends a file to the Mac with `upload`, then its bytes in upload
frames, in order, as many as it likes (they're interleaved with everything
else, so a big file doesn't hold up typing). Once `size` bytes have arrived
the gateway replies `uploaded` with the file's path, or with an `error` (too
big, more bytes than it said); an unfinished upload is removed when the
client disconnects.

Uploads are saved in the directory `hello` names, under the name the client
gave, cut down to letters, digits, `.`, `-` and `_` so the path needs no
quoting, and made unique there. They're removed after two weeks.

The path is the client's to use: in what it submits to a pane, it lets the
pane's program find the file, and `submit` pastes it on its own.
