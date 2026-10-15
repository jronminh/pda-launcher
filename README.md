# PDA Launcher

Fork of [Saranomy/NoLauncher](https://github.com/Saranomy/NoLauncher) (Apache-2.0).

A home screen with five tabs on a bar at the bottom (no swiping):

- **Apps**: a grid of launchable apps (4 x 6 by default), one page at a time
  (`‹ 1/3 ›` or a horizontal swipe), with search in the header. No widgets, no
  wallpaper. Tap to open; long-press for App info, Hide, Uninstall. The grid
  follows installs, removals and suspended apps (`LauncherApps.Callback`). The
  header menu (⋮) has Show/Hide apps and Apps settings.
- **Favorites**: favourite files and folders, in the order they were added. Tap a
  folder to show it in Files, a file to open it; long-press to reorder, show in
  folder, or remove. Items are added from Files (select, ⋮ › Add to favorites),
  and follow the files when Files moves, renames or deletes them.
- **Files**: a file manager after AOSP DocumentsUI and the Boox Storage screen.
  See [docs/files.md](docs/files.md).
- **Note**: a whiteboard. Text only, written anywhere, never scrolled and never
  saved: it lasts as long as the launcher's process (a screen made again, for
  DeX or a new font size, keeps it). It takes text only while
  the text fits the board. *Lock* makes it display only (no cursor, no
  keyboard); ⋮ › Clear empties it.

Apps, Favorites and Files each show their items one of three ways, chosen with
the view button in the header (or in the tab's settings) and kept per tab: **Details**
(icon, name and a line of details), **List** (a small icon and the name) or
**Grid** (a big icon over the name). Every way turns pages; the Apps grid takes
its columns and rows from Settings. The Settings tab's tiles have the three
ways too.

Search and sort are in the header of every tab but Note, kept per tab: Apps by
name, install or update date; Favorites in the order added, or by name, date,
size, type; Files by name, date, size, type (folders first); Settings' tiles in
their own order or by name. Choosing the current order again turns it round.
Files has ‹ in its header whenever there is a place to go back to.

**Settings.** Each tab has its own, in its menu (⋮ › *Apps settings*, *Favorites
settings*, *Files settings*, *Note settings*): the view, the grid size and
Show/Hide apps, hidden files and storage access, the note's text size; they
cover the tabs until Back, ‹ or a tab. The **Settings** tab, after the Boox
launcher's settings but smaller, starts with a banner (the device's name, model
and Android version, battery, storage, the launcher's version) over tiles, a
grid by default: Launcher (the tab Home opens, the default home app), Apps,
Favorites, Files and Note open their settings in place (Back returns); Wi-Fi,
Bluetooth, Display, Sound, Notifications, Battery, Storage, Applications,
Location, Security, Accounts, Date & time, Language, Developer, About phone and
All settings open Android's own screens, nothing hidden.

Back returns to Apps; Home on the home screen opens the tab chosen in Settings
(Apps by default, on its first page), Home from another app comes back to the
tab that was open. The look is pure black for an OLED screen, flat, with no
animation (inspired by the Onyx Boox launcher).

"Show/Hide apps" is a launcher-only filter stored in the launcher's preferences:
it does not hide apps system-wide (that needs Device Owner, which the PDA panel
holds).

Changes from upstream:
- Java with framework views only (no Kotlin, no Compose, no Gradle), so it builds with
  `javac` + `d8` + `aapt2` in Termux, same toolchain as `pda-panel`.
- Tabs, paged grid and lists, Files, Favorites, Note, Settings.
- Package `org.pda.launcher`, so it can be installed next to the upstream app.
- `targetSdk 28`, so the Files tab keeps legacy storage (docs/files.md).

## Icons

Every icon is a white line drawing in one style (24 x 24, stroke 1.5, round
ends), written by `python3 tools/icons.py` from the table in that script; the
XML in `res/drawable/` is its output, not to be edited by hand.

## Build

Needs `javac`, Android build-tools (`d8`, `aapt2`, `zipalign`, `apksigner`) and
`$ANDROID_HOME/platforms/android-31/android.jar`.

    bash build.sh        # -> pda-launcher.apk, signed with ~/.android/launcher.keystore (created if missing)

Set it as the default: Settings > Apps > Default apps > Home app > PDA Launcher.
