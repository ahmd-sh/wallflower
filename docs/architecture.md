# wallflower architecture

wallflower is a messenger between a phone and the terminal sessions on a
Mac. The Mac's multiplexer keeps the sessions. A gateway on the Mac relays
them, shaped just enough to build on. The phone presents them in a
mobile-first way, and turns what the user does into intents the gateway
carries out. Neither end is tied to one multiplexer or one program: those
are plugged in.

```
phone                                          Mac
core client ◀──── wallflower protocol ────▶ core gateway ◀──▶ multiplexer adapter ──▶ tmux, …
    │              (docs/protocol.md)            │
program plugins, client half             program plugins, server half
```

## The core gateway

`internal/gateway` serves one client per connection (`wallflower serve`, run
by the phone over ssh). It speaks the protocol and relays the session:

- state pushed whenever it changes, and each open pane's output as it comes,
  repainted when the client falls behind or the pane resizes
- the client's input and changes, in order
- notifications, read from every pane's output by the terminal standards
  for them (OSC 9, 777 and 99, and the bell), whatever the program
- mobile views, read by the program plugins that have one
- uploads

It knows no particular multiplexer or program. What it holds can be rebuilt
from the multiplexer, so a connection can drop at any time.

## Multiplexer adapters

`internal/mux` is what the gateway needs of a multiplexer:

- the session as a snapshot of windows and panes
- events: a pane's output, or word that the snapshot changed
- a pane's screen, as bytes that repaint it in a fresh terminal, lined up
  with its output
- input: bytes, named keys, pastes
- changes: select, focus (zoomed), split, new window, rename, kill
- sizing: the client's size, and away, when it sizes nothing

An adapter (`tmux.Server`, driving tmux's control mode) translates these
into its multiplexer's own terms. Those terms stay inside it: ids, key
names, how windows are sized among clients, the zooms it undoes on detach,
and the repaint format. What only some multiplexers can do is a capability
(`About.Can`). It reaches the client as `can` in `hello`, so the phone
offers only what the multiplexer can. Keys through the multiplexer's own
bindings are one.

The gateway's tests are the contract. They run against tmux today. A new
adapter implements `mux.Mux` and runs them too.

## The core client

The Android app (`android/`) keeps the protocol (`protocol`), the
connection and the session's state (`app/.../session`), a terminal for
every open pane (`terminal`), and the UI. Presentation is local: switching
tabs, swiping panes, scrolling and drafting never wait on the Mac. Only
intents go out.

## Program plugins

A program, such as Claude Code or a shell, can have a plugin. A plugin has
two halves, each optional:

- **Server half**, a `mobileview.Source` (`internal/claude`). It finds the
  panes running its program and reads them for their mobile view: entries
  the phone renders natively (said, step, thought, picture, note), and what
  the program is doing. Those kinds are generic, so the phone renders any
  plugin's view the same way.
- **Client half**, a `Program` (`android/screen/.../program`). It reads its
  program's screen, such as the input line and the choices it offers. It
  says what its notifications mean, and how to clear a line, start a new one
  and compact.

The core asks a plugin and does what it says. It never checks which
program it is. Notifications show the split: the gateway reads them by
standards from any program, and the program's client half decides what
each one means (Claude Code's is a question exactly when its prompt box is
gone).

For now each half matches its program by the pane's command on its own
side. Claude Code's key bar defaults still sit in the core client. Next,
each plugin gets one id across both halves, which the gateway names for
each pane in `state`, and its defaults move into it.

## Plugins users provide

Built-in plugins are compiled in for now. The seams are meant to take
plugins from outside later, without changes to the core:

- A server half can be an external program that speaks the
  `mobileview.Source` calls as JSON lines on stdin and stdout, as language
  servers do. It can be written in any language and needs no rebuild.
- A client half can be data the gateway sends: key bar defaults, add-menu
  items, notification rules. The phone can't load code. Built-in plugins
  keep their native code for what data can't say, such as reading a
  screen.

So the core keeps to two rules. It never assumes a plugin is built in, and
it reaches one only through its seam.

## Security

The phone reaches the Mac over ssh, its key restricted to running
`wallflower serve` (`wallflower authorize`), usually over Tailscale. The
boundary is the forced command. A multiplexer adapter or plugin sits
behind it and doesn't change it.
