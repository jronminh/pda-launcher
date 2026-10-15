# PDA Launcher

Fork of [Saranomy/NoLauncher](https://github.com/Saranomy/NoLauncher) (Apache-2.0).

A home screen with three tabs on a bar at the bottom (no swiping):

- **Apps**: a 4 x 6 grid of launchable apps, one page at a time (`‹ 1/3 ›` or a
  horizontal swipe), with search and Show/Hide apps in the header. No widgets, no
  wallpaper. Tap to open; long-press for App info, Hide, Uninstall. The grid
  follows installs, removals and suspended apps (`LauncherApps.Callback`).
- **Files**: a file manager after AOSP DocumentsUI and the Boox Storage screen.
  See [docs/files.md](docs/files.md).
- **Shell**: dn-shell's terminal without prefixes: Android's `sh` and toybox on a
  PTY, with tabs, a key bar, settings as files and an `android` command. See
  [docs/shell.md](docs/shell.md).

Back returns to Apps; Home on the home screen returns to Apps (first page),
Home from another app comes back to the tab that was open. The look is pure
black for an OLED screen, flat, with no animation (inspired by the Onyx Boox
launcher); the tab bar steps aside while the keyboard is up.

"Show/Hide apps" is in the Apps header menu (⋮). It opens a checklist; unchecked apps are removed from the grid. This is a launcher-only filter
stored in the launcher's preferences: it does not hide apps system-wide (that needs
Device Owner, which the PDA panel holds).

Changes from upstream:
- Java with framework views only (no Kotlin, no Compose, no Gradle), so it builds with
  `javac` + `d8` + `aapt2` in Termux, same toolchain as `pda-panel`.
- Vertical list replaced by a 4-column grid; one screen shows 6 rows, more rows scroll.
- Show/Hide apps filter (the upstream Play Store build has it; the GitHub source is older).
- Package `org.pda.launcher`, so it can be installed next to the upstream app.
- `targetSdk 28`, the Shell tab's platform contract (docs/shell.md).

Third-party code in the Shell tab (Apache-2.0): `src/jackpal/androidterm/**`,
`src/com/termoneplus/**` and `res/drawable-nodpi/atari_small_nodpi.png` from
[TermOnePlus](https://gitlab.com/termapps/termoneplus) (Android Terminal
Emulator); `src/com/google/ase/Exec.java` and `lib/arm64-v8a/libcom_google_ase_Exec.so`
from [ConnectBot](https://github.com/connectbot/connectbot). The rest of
`src/org/pda/launcher/shell` and `assets/tree` comes from dn-shell.

## Build

Needs `javac`, Android build-tools (`d8`, `aapt2`, `zipalign`, `apksigner`) and
`$ANDROID_HOME/platforms/android-31/android.jar`.

    bash build.sh        # -> pda-launcher.apk, signed with ~/.android/launcher.keystore (created if missing)

Set it as the default: Settings > Apps > Default apps > Home app > PDA Launcher.
