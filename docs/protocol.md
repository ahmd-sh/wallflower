# wallflower protocol, version 6

The client runs `wallflower serve` over ssh and talks to it on stdin/stdout. One
connection is one client, attached to a session in one of the Mac's
multiplexers or to none; the gateway exits when the client disconnects, and
has nothing to clean up beyond what the client changed for itself (tmux's
zooms, Rex's terminals).

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
skipped, so the protocol can grow by adding them. Ids are opaque strings,
the multiplexer's own (tmux's `%3`, `@1`, `$0`); don't parse them.

## Session

1. Client sends `hello`, naming the session it was in (`session`, in the
   multiplexer `mux` names: `tmux` or `rex`, by default the gateway's), if
   it was in one.
2. Gateway sends `hello`, then attaches to that session as any other client
   of the multiplexer does (a window switched to on the client is switched
   to on the others too), and sends `attached`, then `state`. If the client
   named none, or it's gone, or its multiplexer isn't running, the client
   is attached to nothing: the gateway sends `detached` (with why, if it
   named one), then `sessions`.
3. Client sends `open` for the panes it wants to show. For each, the gateway
   sends `reset` and one data frame that repaints the pane, then streams its
   output.

The client goes into another session, in the same multiplexer or another,
without connecting again: after `switch` (or `new-session`) the gateway
sends `attached` and `state` again. What it had open of the last session is
closed, and its mobile views are off. One that can't be had leaves the
client where it was: attached to nothing, it's told so again (`detached`,
with why). It leaves the session for none with `detach`; when the session's
multiplexer lets go of it (tmux exited), the gateway sends `detached`, with
why, as it does then.

While the client is attached to nothing, not `away`, and heard from in
the last 45 seconds (its pings will do), the gateway lists the sessions
every couple of seconds, and sends `sessions` whenever they change: they're
there to pick from. A multiplexer that takes more than a couple of seconds
to list them is listed with the `error` that it isn't answering, and isn't
asked again until it has. Messages about a session's panes and windows are
refused with an `error` until the client is attached to one.

When the session the client is in ends, it's attached to nothing, whatever
the multiplexer does with its clients (tmux moves them to another session).

`state` is resent, whole, whenever anything in it changes. When an open pane
changes size, the gateway sends a fresh `reset` and repaint. It does the same
if it had to drop the pane's output because the client fell behind, so a
client never has to resync on its own.

## Client → gateway

| t | fields | |
|---|--------|---|
| `hello` | `v`, `cols`, `rows`, `mux`?, `session`?, `away`? | first message; `v` is 6. `session` is the one to go back into, if it's there; see Session |
| `resize` | `cols`, `rows` | the client's terminal area changed |
| `open` | `pane`, `history`? | repaint and stream a pane; `history` lines of scrollback (default 1000) |
| `close` | `pane` | stop streaming a pane |
| `keys` | `pane`, `keys`, `bindings`? | keys by name (see Keys), encoded for the pane's modes. With `bindings`, as if typed on a keyboard attached to the multiplexer, so its key bindings apply (`prefix` stands for its prefix key), if it `can` |
| `submit` | `pane`, `text`, `enter`?, `clear`? | composed text in place of what the program's input line holds, see below |
| `select` | `window` or `pane` | make it current in the client's session (sizes it for the client) |
| `focus` | `pane` | make it the window's active pane, with the client's whole size (tmux zooms it if the window has others, undone on disconnect) |
| `split` | `pane` | open a pane beside it, in its directory, and focus it like `focus` |
| `new` | | open a window, current in the client's session only |
| `rename` | `window`, `name` | |
| `kill` | `window` or `pane` | close it; closing a window's last pane closes the window |
| `read` | `pane`, `on` | start or stop the pane's mobile view arriving as `entries`; for panes with `view` |
| `picture` | `pane`, `id` | a picture entry's bytes, which come in a picture frame |
| `upload` | `id`, `name`, `size` | send a file: `size` bytes follow in upload frames with this `id`, see below |
| `ping` | `id` | |
| `away` | `on` | the user has left the client for now (or is back): see Sizing |
| `sessions` | | list the sessions in every multiplexer the gateway has; they come in `sessions` |
| `switch` | `mux`, `session` | attach to a session, see Session |
| `new-session` | `mux`, `name`? | start a session in the user's home directory, named `name` or a name of the multiplexer's own (and the multiplexer, if it isn't running and `can` `new`), and switch to it |
| `rename-session` | `mux`, `session`, `name` | |
| `kill-session` | `mux`, `session` | end a session and the programs in it; not the client's own |
| `detach` | | leave the session for none, as the user chose; `detached` follows |

Messages are handled in order, so a `pong` means everything sent before its
`ping` has reached the multiplexer.

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
| `hello` | `v`, `uploads`?, `machine`?, `programs`? | first, before the client's attached to anything: the directory uploads are saved in; the machine's name, as its owner calls it (a Mac's Computer Name); the phone halves of the user's plugins, see below |
| `attached` | `mux` | the client is attached to a session in this multiplexer, see below; `state` follows |
| `detached` | `reason`? | the client is attached to nothing, and why if it didn't ask to be; `sessions` follows |
| `state` | `session`, `name`, `windows` | everything in the session, see below |
| `reset` | `pane`, `cols`, `rows` | recreate the pane's terminal at this size; the next data frame repaints it |
| `entries` | `pane`, `reset`?, `more`?, `entries`?, `error`?, `context`?, `title`?, `queued`? | a pane's mobile view, see below |
| `error` | `message` | a message failed |
| `pong` | `id` | |
| `uploaded` | `id`, `path`?, `error`? | where an upload was saved, or why it wasn't |
| `bye` | `reason` | the gateway is exiting: the client's `hello` couldn't be taken |
| `notification` | `pane`, `title`?, `body` | a desktop notification a program sent, see below |
| `sessions` | `muxes` | each multiplexer's sessions, see below; sent when asked, after a session is started, renamed or ended, and whenever they change while the client is attached to nothing |

`mux` in `attached` is the multiplexer the session is in: its `name`
(`tmux`), its `version`, and `can`, what it can do beyond what every one
does: `bindings`, keys through its own key bindings. A client offers only
what the multiplexer can.

Each of `muxes` in `sessions` is a multiplexer's `name`, whether it's
`running`, its `sessions` in its own order (`id`, `name`, `windows`), or the
`error` listing them, and `can`, what can be done with its sessions: `new`
(start one, and the multiplexer with it if it isn't running: tmux can, Rex's
server is Rex's to start), `rename`, `kill`. A client knows none of them by
name: it offers what each says it can.

A window is `id`, `index`, `name`, `active` and `panes`. A pane is:

| field | |
|-------|---|
| `id`, `index`, `active` | |
| `cols`, `rows` | its size |
| `alt` | a full-screen app owns the pane; no scrollback is kept for it |
| `title`, `path` | |
| `command` | the foreground program; tools that run from a versioned file (`.../claude/versions/2.1.300`) report the name they were run as |
| `program` | the program the pane runs, if the gateway knows it (`claude`, `zsh`, `shell`): a client knows its own half of the program by this name |
| `view` | the program has a mobile view of the pane, see below |
| `activity`, `since` | what a program with a mobile view is doing, if it says: `working` on a turn (through its steps, until it's finally done), `waiting` for the user to answer something mid-turn (a permission, a question), or `idle`; `since` when, in Unix milliseconds |

When a window is zoomed, only its active pane is visible.

`programs` in `hello` are the phone halves of plugins the user gave the
gateway (`docs/plugins.md`), as data, for programs the client has no half of
its own for: each `id` (as `program` names it in `state`), and optionally
`keys` (`name`, `keys` by key name, `stop`), `commands` (`text`, `about`?),
`shortcuts` (`keys` by key name, `label`?), `newline` and `clear` (key names).

## Mobile views

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
`title` is what the conversation is called (Claude Code: the name given with
`/rename`, else the one it made up), with a `reset` if it has one, and
whenever it changes. `queued` is what the user sent while the program was
busy that it hasn't taken yet, oldest first, with a `reset` if anything
waits and whenever that changes (`[]` once nothing does). Taken, a message
arrives as an entry of the user's where the program took it, mid-turn or
next.

What the program is asking isn't in the mobile view: it's on the pane's
screen, and answers are keys, as typed.

An entry is `id`, `from` (`user` or `program`), `kind` and, by kind:

| kind | fields | |
|------|--------|---|
| `said` | `text` | markdown |
| `thought` | `text` | |
| `step` | `name`, `text`, `status`, `output`?, `output_cut`?, `diff`?, `diff_cut`? | `text` is what the step was asked, in brief; `status` is `running`, `done` or `failed`; `output` is trimmed, `output_cut` characters short; `diff` is the change it made to a file, as unified diff hunks (`@@ -a,b +c,d @@`, then lines starting ` `, `-` or `+`), `diff_cut` lines short. From the `user`, it's a command they ran: `name` is the command (`/compact`, or `!` for a shell command), `text` its arguments |
| `picture` | `mime`, `size` | its bytes, `size` of them, come when asked for with `picture`; the gateway keeps a mobile view's recent pictures |
| `note` | `text`, `name`?, `output`? | an event rather than something said: an interruption, a compacted conversation, a notice; `output` is more about it, to show when asked (a compaction's summary). With a `name`, it's a message from another agent (a subagent or teammate) by that name: `text` what it's about, `output` the message |

A client shows an entry of a kind it doesn't know as a note of its `text`.

The gateway finds a Claude Code session's transcript, and what it's doing,
from `~/.claude/sessions/<pid>.json`, so it works for sessions already
running. A plan written in plan mode (`~/.claude/plans/*.md`) arrives as a
`said` entry after its write, to read as a reply. Neither file format is
documented; entries it can't read are skipped.

## Presentation is the client's

State is pushed whenever it changes and every open pane streams, so a client
can keep everything it shows local: scroll, switch windows, and draw panes it
already has open without asking. Messages are for changing the session, not
for looking at it.

## Keys

Keys go by wallflower's names, which are tmux's whatever the multiplexer:
`Enter`, `Escape`, `Tab`, `BTab` (shift-tab), `BSpace`, `Space`, `Up`,
`Down`, `Left`, `Right`, `Home`, `End`, `PageUp`, `PageDown`, `IC`
(insert), `DC` (delete) and `F1` to `F12`, each with any of `C-` (ctrl),
`M-` (alt) and `S-` (shift) before it: `C-c`, `M-Enter`, `S-Up`. Anything
else in `keys` is typed as it is, so `["Escape", ":w", "Enter"]` saves in
vim.

## Sizing

The client's size applies to the windows it shows and types into, however
the multiplexer shares a window among clients of different sizes. tmux
sizes a window for the client that last resized or selected it, and
doesn't count typing through control mode, so the gateway selects the
window again when the client types into it and it has another size. Rex
sizes each terminal for the client that owns it, so the gateway takes the
terminals the client shows and types into.

While the client says it's `away` (in `hello`, or since), it doesn't size
windows at all, as if it weren't attached (Rex's terminals go back to whoever
had them): a client left in the background
keeps a connection without the desktop's windows taking its size. Back, it
selects the window it shows to take it again.

## Notifications

Programs send desktop notifications through their terminal: OSC 9
(iTerm2's), OSC 777 `notify` (rxvt's) and OSC 99 (kitty's). A multiplexer
may not pass them on (tmux drops them, but from a pane on screen when a
program wraps them in its passthrough). The gateway reads them from every
pane's output, open or not, wrapped or not, and sends each as a
`notification`: `body` is what it says, `title` what it's about if the
program gave one. A program's bell comes as one too, with `bell` and an
empty `body`; a bell rung with a notification, nothing printed between, is
part of it. A client shows them however it likes, typically while the user
is away, and makes of a bell what the program would mean by it.

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
