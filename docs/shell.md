# The Shell tab

The Shell tab is dn-shell's terminal with no prefix: a TTY over Android's own
`sh` (mksh) and toybox, plus a small tree of scripts in the app's `files/`.
Ported from dn-shell (branch `spec/tree`, `docs/spec/tree.md`), minus prefixes.

## Platform contract

`targetSdk 28`. The scripts in `files/bin` run only because an app that targets
28 may exec from its own data directory (SELinux domain `untrusted_app_27`).
Raising `targetSdk` breaks the tree; it is a change to this document, not to the
build.

## The tree (`files/`)

```
files/
├── bin/            shipped: config, android, storage, net (POSIX scripts)
├── lib/            shipped: config.schema, config.storage.link.defaults
├── etc/
│   ├── profile     shipped: read once before a session's command
│   ├── mkshrc      shipped: the interactive rc (prompt, aliases), via ENV
│   ├── profile.d/  yours: *.sh run by the profile, in filename order
│   └── <settings>  yours: one file per setting
├── usr/local/bin/  yours: own commands, first on PATH
├── home/  tmp/
├── var/            written by the app: host.sock, host.token, log/, sessions/
└── mnt/            links into phone storage (made by `storage`)
```

Shipped files come from `assets/tree/` and are replaced when the installed build
changes; everything else is the user's and never touched. `PATH` is
`usr/local/bin:bin:/system/bin:/system/xbin`.

## Settings

One setting, one file: `etc/<key with dots as directories>`, one line.
`lib/config.schema` (TAB separated: key, type, default, constraint, group,
description) is the only list of keys. `config get|set|unset|reset|list|seed`
writes them atomically; the app seeds the missing ones at start, reads them,
and watches `etc/`, so a change (font, colours, key bar, screen on) shows at
once. An invalid file falls back to the default and is logged to `var/log/`.

The launcher keeps its own settings in the same store, so the Settings tab
and the shell change the same files: `launcher.homeTab`, `apps.columns`,
`apps.rows`, `files.showHidden`. The Favorites list is `etc/favorites`, one
absolute path per line (not a schema key).

## Sessions

A session runs `/system/bin/sh -c "<env>; cd $HOME; . etc/profile; exec <etc/shell/command>"`
(default `sh -i`) on a PTY. The app sets only `HOME PATH TERM SHELL TMPDIR ENV
DN_INSTDIR DN_HOST_SOCK DN_HOST_TOKEN`; the rest is the profile's. The variable
names keep dn-shell's `DN_` prefix so the scripts stay the same in both apps.

- The bar's title is the active tab (its OSC title, else the command); touch it
  for the list of tabs and *New tab*. `⋮` has *System shell*: Android's `sh`
  with no profile, on an amber bar, the way in when the command is broken.
- `shell.maxSessions` (1-8) limits the tabs. A tab closes when its shell exits.
- When the last one ends the tab shows a panel (*New session*, *Open system
  shell*); if it ended non-zero within `shell.failSeconds`, the panel shows the
  command, the exit code and its last lines, with *Try again*.
- The service starts the first time the tab is shown, and is a foreground
  service (a notification) only while a session is open. No session survives
  the process.
- Gestures: tap raises the keyboard, two-finger tap toggles it, two-finger drag
  scrolls the scrollback. Back returns to the Apps tab.

## The `android` command

What only Android can do, over a Unix socket in the tree (`var/host.sock`, mode
600, plus a per-run token in `var/host.token`); the client is toybox's `nc -U`.
One line of JSON each way; `android VERB` prints `key=value` lines, `--json`
the raw reply. Verbs: `status`, `describe`, `battery`, `net.dns`, `toast`,
`notify`, `clipboard.get`, `clipboard.set`, `open`, `share`, `raw METHOD ARGS`.
Exit codes: 0 ok, 1 failure, 2 unreachable, 3 denied, 4 timeout, 5 unsupported,
64 usage. `clipboard.get`, `open` and `share` need the home screen on screen
(Android 10+).
