# PDA Launcher

Fork of [Saranomy/NoLauncher](https://github.com/Saranomy/NoLauncher) (Apache-2.0).

Home screen with a search box over a 4 x 6 grid of launchable apps. Nothing else:
no widgets, no wallpaper, no settings. Tap to open, long-press to uninstall,
the grid reloads when apps are installed or removed.

Changes from upstream:
- Java with framework views only (no Kotlin, no Compose, no Gradle), so it builds with
  `javac` + `d8` + `aapt2` in Termux, same toolchain as `pda-panel`.
- Vertical list replaced by a 4-column grid; one screen shows 6 rows, more rows scroll.
- Package `org.pda.launcher`, so it can be installed next to the upstream app.

## Build

Needs `javac`, Android build-tools (`d8`, `aapt2`, `zipalign`, `apksigner`) and
`$ANDROID_HOME/platforms/android-31/android.jar`.

    bash build.sh        # -> pda-launcher.apk, signed with ~/.android/launcher.keystore (created if missing)

Set it as the default: Settings > Apps > Default apps > Home app > PDA Launcher.
