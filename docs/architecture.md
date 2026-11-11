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

A connection is to the gateway, not to a session: attaching to one is
something the client asks for, and it can be attached to none, with the
sessions of every multiplexer to pick from, as each lists them and says
what can be done with them. It's attached to none only when the user
chooses, or the session goes: a connection made again, after a network
change or with the app back, goes back into the session it was in.

sshd runs it in the connection's environment: a bare PATH, maybe no
locale, and the connection's own variables. None of that is to reach the
user's sessions. What the gateway starts for them (a multiplexer's server,
a plugin's program) runs in the environment their login shell sets up
(`internal/userenv`), and an adapter keeps the connection's variables out
of the sessions it attaches to and makes.

## Multiplexer adapters

`internal/mux` is what the gateway needs of a multiplexer:

- the session as a snapshot of windows and panes
- events: a pane's output, or word that the snapshot changed
- a pane's screen, as bytes that repaint it in a fresh terminal, lined up
  with its output
- input: bytes, named keys, pastes
- changes: select, focus (zoomed), split, new window, rename, kill
- sizing: the client's size, and away, when it sizes nothing

An adapter translates these into its multiplexer's own terms.
`tmux.Server` drives tmux's control mode. `rex.Server` speaks Rex's server
API: a control WebSocket for methods and events, and a data WebSocket
streaming each terminal's output (`internal/ws` is the small client both
use, so wallflower keeps no dependencies). Those terms stay inside it: ids, key
names, how windows are sized among clients, the zooms it undoes on detach,
and the repaint format. What only some multiplexers can do is a capability
(`About.Can`). It reaches the client as `can` in `hello`, so the phone
offers only what the multiplexer can. Keys through the multiplexer's own
bindings are one.

The gateway's tests are the contract, and run against every adapter, each
on a throwaway server of its own (`internal/tmuxtest`, `internal/rextest`).
A test acts as the phone through the protocol, and as someone at the Mac
through the multiplexer's own tools (a `desk`), so an adapter can't agree
with itself and pass. Where multiplexers differ, the test says what both
promise, and tests of what only one does run against it alone. Adding a
multiplexer is an adapter and a desk.

Where the multiplexers differ, the adapter decides, and the doc comment
says how. Rex sizes each terminal for the client that owns it, so the
client takes the terminals it shows and types into, and gives them back
while away. A Rex repaint is the terminal as VT, from its own formatter,
which has the screen on show only: under a full-screen program, the shell's
screen behind it isn't repainted. Rex starts a stream with a snapshot in
Ghostty's binary format, which would fix that and line up exactly, but the
format isn't stable yet. Output that arrives while a pane is being read can
show twice; none is lost. A terminal's output reaches the gateway from when
it first sees the terminal, so what a program writes the moment it starts
in a new window can be missed.

## The core client

The Android app (`android/`) keeps the protocol (`protocol`), the
connection and the session's state (`app/.../session`), a terminal for
every open pane (`terminal`), and the UI. Presentation is local: switching
tabs, swiping panes, scrolling and drafting never wait on the Mac. Only
intents go out.

## Program plugins

A program, such as Claude Code or a shell, can have a plugin. A plugin has
two halves, each optional:

- **Server half**, a `program.Program` (`internal/program`): its id, the
  commands its panes report, and its mobile view if it has one, a
  `mobileview.Source` (`internal/claude`). The view finds the panes it can
  read and reads them: entries the phone renders natively (said, step,
  thought, picture, note), and what the program is doing. Those kinds are
  generic, so the phone renders any plugin's view the same way.
- **Client half**, a `Program` (`android/screen/.../program`). It reads its
  program's screen, such as the input line and the choices it offers. It
  says what its notifications mean, how to clear a line, start a new one
  and compact, and which key bar its panes get until the user sets one. It
  names the commands the composer's + offers (Claude Code's `/compact`,
  `/rewind`…), the shortcuts the key bar offers on held ctrl (Claude
  Code's transcript, mode, its commands sent at once…; the user hides those
  they don't want and adds their own), and the key that takes the
  program's suggestion into its line whole (Claude Code's Tab), since one
  cut short to fit the screen can't be read off it. It says how a sent
  message comes back to edit (Claude Code: ^C before the reply, else
  `/rewind`, each putting the message back in its box, where the composer
  takes it), and what a tab shows for a window the multiplexer named after
  the program's command (Claude Code's conversation name, from the title
  it sets).

The halves go by one id. The gateway says which program each pane runs
(`program` in `state`), and whether it has a view (`view`); the phone looks
its half up by that id. Which panes run a program is decided only on the
Mac. A key bar the user makes still follows panes by their command, or by
a program's id.

The core asks a plugin and does what it says. It never checks which
program it is. Notifications show the split: the gateway reads them by
standards from any program, and the program's client half decides what
each one means (Claude Code's is a question exactly when its prompt box is
gone).

## Plugins users provide

A plugin of the user's is a JSON file in `~/.config/wallflower/plugins/`
(`docs/plugins.md`), loaded by `program.Load` beside the built-in ones,
without changes to the core:

- Its server half's mobile view can be an external program that speaks the
  `mobileview.Source` calls as JSON lines on stdin and stdout, as language
  servers do (`internal/plugin`). It can be written in any language and
  needs no rebuild.
- Its client half is data the gateway sends in `hello` (`programs`): the key
  bar its panes get, the + menu's commands, and the keys that start a new
  line and empty one. The phone can't load code, so it makes a `Supplied`
  program of it. Built-in plugins keep their native code for what data
  can't say, such as reading a screen.

So the core keeps to two rules. It never assumes a plugin is built in, and
it reaches one only through its seam.

## Security

The phone reaches the Mac over ssh, its key restricted to running
`wallflower serve` (`wallflower authorize`), usually over Tailscale. The
boundary is the forced command. A multiplexer adapter or plugin sits
behind it and doesn't change it. `wallflower devices` lists the phones
whose keys can connect, and `wallflower revoke` takes one out; neither
touches the file's other keys.
