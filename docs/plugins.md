# Plugins of your own

wallflower ships plugins for Claude Code, Codex and shells. Others are yours to
add, without rebuilding anything: a JSON file each in
`~/.config/wallflower/plugins/` (or the gateway's `-plugins` directory),
read when a connection starts.

```json
{
  "id": "aider",
  "commands": ["aider"],
  "client": {
    "keys": {"name": "aider", "keys": ["Escape", "Tab", "Up", "Down", "C-c"], "stop": ["C-c"]},
    "commands": [{"text": "/undo", "about": "undo the last change"}, {"text": "/diff"}],
    "newline": ["M-Enter"],
    "clear": ["C-u"]
  },
  "view": ["./aider-view", "--json"]
}
```

- `id`: lowercase letters, digits, `.`, `_` and `-`; not one wallflower
  ships (`claude`, `zsh`, `shell`).
- `commands`: what the panes running it report as their command. Your
  plugins come before the built-in ones, so one can claim a shell's panes.
- `client`, optional: the phone's half, as data, since the phone loads no
  code. `keys` is the key bar its panes get until you make one in the app
  (key names as tmux has them: `Escape`, `C-c`, `M-Enter`, `BTab`, `F5`…,
  and `arrows`, the arrows around enter in one key),
  and what the send button presses to stop it. `commands` are offered by
  the composer's +, and start what you send. `newline` is what the
  keyboard's return key presses, if the program takes several lines; `clear`
  empties a row of its input.
- `view`, optional: a program that reads the panes for a mobile view (a
  path beside the file can start with `./`). It runs in the environment
  your login shell sets up, so it's found on that PATH: what `.zprofile`
  (or `.bash_profile`) adds, not what only `.zshrc` does.

A file that doesn't make a plugin is skipped, and the gateway says why on
its stderr.

## A mobile view of your own

The `view` program runs while the gateway does, and speaks JSON, a message
a line, on its stdin and stdout. It's told:

- `{"find": [{"id": "%1", "tty": "/dev/ttys003", "command": "aider", "title": "aider", "path": "/Users/me/dev/x"}, …]}`,
  every moment or so: the panes there are, with the title the program gave
  its terminal and the directory it's in, when the multiplexer says. Say
  which you read, and what each is doing if you know:
  `{"found": {"%1": {"activity": "working", "since": 1730000000000}}}`. The
  gateway doesn't wait: what you said last stands until you say again. Read
  what you're sent promptly; messages that pile up unread are dropped.
  `activity` is `working`, `waiting` (for the user to answer something) or
  `idle`; `since` is Unix milliseconds.
- `{"read": {"id": "%1", "tty": …, "command": …}, "n": 3}`: send what's in
  the pane, then whatever changes, until `{"stop": "%1"}`, each message with
  the read's `n`, so what's left of an earlier read of the pane isn't taken
  for this one's:
  `{"pane": "%1", "n": 3, "update": {"reset": true, "entries": [...], "context": 1234, "title": "fix the tests"}}`.
  With `reset`, its entries replace any before (`more`: there were earlier
  ones); without, they're new, or replace those with their ids. `context`,
  `title` and `queued` (what the user sent that waits for the program, as
  a list of text; `[]` once nothing does) are optional. When there's nothing more to read, say why:
  `{"pane": "%1", "n": 3, "end": "it exited"}`.

Entries are as the protocol has them (`docs/protocol.md`, `entries`): `id`,
`from` (`user` or `program`), `kind` (`said`, `step`, `thought`, `picture`,
`note`; a note with a `name` is another agent's message) and its fields; a picture's bytes go in `data`, base64, with its
`mime`. Lines that don't parse are skipped. If the program stops, it's
started again a few seconds later.
