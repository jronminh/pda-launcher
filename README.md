# PDA Launcher

Fork of [Saranomy/NoLauncher](https://github.com/Saranomy/NoLauncher) (Apache-2.0).

A home screen with five tabs on a bar at the bottom (no swiping), named as on
a Palm: Applications, Briefcase, Files, Today and Prefs. Their order, and which
show, are chosen in Prefs › Launcher › Tabs (Prefs always shows).

- **Applications**: after Pocket PC's Start menu, in the look of Prefs: one
  page of tiles, each a need (Phone, Messages, Contacts, Camera, Gallery,
  Browser, Email, Calendar, Clock, Music, Maps, Calculator, Store) with the
  launcher's own line icon, opening Android's default app for it or the one
  you chose (long-press › Opens ›; dim when there is none). ⋮ › Tiles turns
  them on or off; long-press moves them. The last tile, **More**, lists every
  app with its own icon, one page at a time (`‹ 1/3 ›` or a swipe), sorted as
  you like; long-press there › Use for a tile. New apps go only to More,
  marked • until first opened. The search in the header covers every app. All under a glance line: the time (tap for the alarms); the date (tap for Today) and, if
  turned on, the next calendar event (tap for the Calendar); the next alarm and
  the battery, with the time to full while charging (tap for the battery
  screen). No widgets, no wallpaper. Tap to open; long-press for the app's own
  shortcuts (as the default home may read them), then App info, Hide,
  Uninstall. The grid follows installs, removals and suspended apps
  (`LauncherApps.Callback`). The header menu (⋮) has Show/Hide apps, the grid's
  size, the glance line and its next event.
- **Briefcase**: favourite files and folders, in the order they were added. Tap a
  folder to show it in Files, a file to open it; long-press to reorder, show in
  folder, or remove. Items are added from Files (select, ⋮ › Add to Briefcase),
  and follow the files when Files moves, renames or deletes them.
- **Files**: a file manager after AOSP DocumentsUI and the Boox Storage screen.
  See [docs/files.md](docs/files.md).
- **Today**: the PDA's information centre, in four parts:
  - *Today*: today's calendar, the next alarm, the three most pressing tasks,
    the inbox, and a brief if something wrote one.
  - *Tasks*: `todo.txt` (the todo.txt format: `(A)` priorities, `due:` dates);
    tap to tick, long-press to edit or delete.
  - *Inbox*: quick captures in `inbox.md`, newest first; one can become a task.
  - *Board*: a whiteboard, text only, never scrolled and never saved (it lasts
    as long as the launcher's process); its Clear and Text size are in the
    tab's menu.

  The files are plain text in one folder (Documents/PDA by default; Today › ⋮),
  for Syncthing, Termux (todo.sh) or an AI to share, and the tab follows them as
  they change. **Capture** is a small app of its own (for a key, or a home
  screen): a line into the inbox or the tasks. Other apps' Share menus have
  *Add to Inbox* and *Show on Board*.
- **Prefs**: see below.

Applications, Briefcase and Files each show their items one of three ways, chosen with
the view button in the header and kept per tab: **Details**
(icon, name and a line of details), **List** (a small icon and the name) or
**Grid** (a big icon over the name). Every way turns pages; the Applications
grid takes its columns and rows from its menu. The Prefs tab's tiles have the
three ways too.

Search and sort are in the header of every tab but Today, kept per tab:
Applications by name, install or update date; Briefcase in the order added, or
by name, date, size, type; Files by name, date, size, type (folders first);
Prefs' tiles in
their own order or by name. Choosing the current order again turns it round.
Files has ‹ in its header whenever there is a place to go back to.

**Prefs.** A tab's few options are in its own menu (⋮): Show/Hide apps, the
grid's columns and rows and the glance line in Applications, hidden files in
Files, the folder in Today, the text size on the Board; the view and the order
are the header's buttons. The **Prefs** tab,
after the Boox launcher's settings but smaller, starts with a small banner of
static facts (the device's name, model and Android version, the launcher's
version; tap it for About phone) over tiles, a grid by default: Launcher (the
tab Home opens, the tabs' order and which show, the tab bar, animations, the
default home app) opens in place (Back returns); Wi-Fi,
Bluetooth, Display, Sound, Notifications, Battery, Storage, Applications,
Location, Security, Accounts, Date & time, Language, Developer and All settings
open Android's own screens, nothing hidden.

The headers carry no tab title (the tab bar names the tab): a title shows only
where it tells something, a media root or a selection in Files, Launcher or
Tabs in Prefs. The tab bar shows icons, labels or both (Prefs › Launcher › Tab
bar). In Files,
a selection's actions (Copy, Move, Delete, Share, more) are icons over small
labels, and so is *Paste here*.

Tapping the button of the tab already open takes it to its start (Files to its
first screen, Today to its Today part...). After the launcher's process was
killed (an update, low memory, a reboot) it starts where it was left: the tab,
Today's part, Files' place (Prefs › Launcher › Start where I left off).

Back returns to Applications (from More, to its tiles); Home on the home screen opens the tab chosen in
Prefs (Applications by default, on its first page), Home from another app comes back to the
tab that was open. The look is pure black for an OLED screen and flat (inspired
by the Onyx Boox launcher). Motion is short and optional (Prefs › Launcher ›
Animations, on by default): a tab, a page or a folder slides in from its
side, a menu grows from where it was asked for. The
system's animator duration scale applies too.

"Show/Hide apps" is a launcher-only filter stored in the launcher's preferences:
it does not hide apps system-wide (that needs Device Owner, which the PDA panel
holds).

Changes from upstream:
- Java with framework views only (no Kotlin, no Compose, no Gradle), so it builds with
  `javac` + `d8` + `aapt2` in Termux, same toolchain as `pda-panel`.
- Tabs, paged grid and lists, Briefcase, Files, Today, Prefs.
- Package `org.pda.launcher`, so it can be installed next to the upstream app.
- `targetSdk 28`, so the Files tab keeps legacy storage (docs/files.md).

## Viewers

Files, the Briefcase and Recent open text, images, audio and PDF in the
launcher's own viewers, each in its own task (Home leaves it in Recents):

- *Text*: edit and save (atomically), ⋮ for wrap, monospace, revert; asks
  before leaving changes unsaved. Over 2 MB or binary: the system's app.
- *Images*: fitted, pinch or double-tap to zoom, swipe for the folder's next.
- *Audio*: the folder from the file tapped, cover, seek, previous/next; plays
  on behind other apps, stops when closed.
- *PDF*: a page at a time, ‹ › or a swipe, pinch to zoom.

Prefs › Viewers chooses, per kind, the built-in one or the system's app; a
file's ⋮ › Open with… always offers the others.

## Icons

Every icon is a white line drawing in one style (24 x 24, stroke 1.5, round
ends), written by `python3 tools/icons.py` from the table in that script; the
XML in `res/drawable/` is its output, not to be edited by hand.

## Build

How the repo is worked on (AOSP first: framework APIs, no Play Services or
maker APIs, minSdk 26 with guards) is in [AGENTS.md](AGENTS.md).


Needs `javac`, Android build-tools (`d8`, `aapt2`, `zipalign`, `apksigner`) and
`$ANDROID_HOME/platforms/android-31/android.jar`.

    bash build.sh        # -> pda-launcher.apk, signed with ~/.android/launcher.keystore (created if missing)

Set it as the default: Settings > Apps > Default apps > Home app > PDA Launcher.
