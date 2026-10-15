# TODO

## Raise targetSdk (now 28)

Wanted for Google Play (new apps must target a recent API, about 35 in 2026)
and to leave Android's compatibility modes. Not a one-line change:

- **Storage.** From 30 the legacy storage the Files tab relies on is gone. Ask
  for "All files access" (`MANAGE_EXTERNAL_STORAGE`, a special app access the
  user turns on in Settings; Play reviews it, a file manager qualifies) and
  replace the runtime READ/WRITE request with a link to that screen.
- **Edge to edge.** From 35 the window is drawn under the status and
  navigation bars: apply the insets to every tab, the tab bar, the settings
  screens and PopMenu's frame.
- **Package visibility** (30+): the launcher queries with LauncherApps, which
  is fine; `getPackageArchiveInfo` and the APK icon are fine too. Check the
  intents for opening files and Android's settings screens (`<queries>`).
- **Smaller ones:** POST_NOTIFICATIONS is not needed (no notifications);
  PendingIntent mutability (none left); FileShare and MediaStore links keep
  working; REQUEST_INSTALL_PACKAGES is restricted on Play (drop it, or justify).
- Play also wants an App Bundle (AAB): `aapt2 link --proto-format` and
  bundletool, still without Gradle.

## Recents

With a third-party home, Recents is One UI Home's (com.sec.android.app.launcher,
RecentsActivity), so going to and from it swaps two launchers' windows. Done:
a black window background (it was the theme's grey), `resumeWhilePausing` (as
Launcher3), no app list reread on resume, and a black cover over the tabs from
onRestart until onEnterAnimationComplete (280-380 ms measured), so the
launcher is not seen drawn behind a Recents still closing. Left, the system's: Home pressed
in Recents closes One UI's Recents over this launcher instead of zooming into
it, as only One UI Home can.

## From the audit

- Files reads its place twice when the launcher starts on that tab.
- APK icons in thumbnails are cropped (centre crop for every thumbnail).
- An 8-row Apps grid can clip two-line labels.
