# Working on PDA Launcher

How this repo is worked on, by people and by coding agents. README.md says what
the app is; TODO.md what is next; docs/ how parts work.

## Lean on AOSP: the platform first

The launcher should work well on many devices: phones and tablets of any
maker, Android 8 (API 26) and newer, with or without Google. So it is built
on what every Android build has, the AOSP framework, and on nothing else
where that is possible.

- **Framework APIs before libraries.** Use what `android.*` already offers:
  `PdfRenderer`, `MediaPlayer`, `ImageDecoder`/`BitmapFactory`,
  `MediaMetadataRetriever`, `MediaStore`, `DocumentsContract`, `LauncherApps`
  (shortcuts, profiles), `CalendarContract`, `AlarmManager`, `FileObserver`,
  `PopupWindow`, plain views. No AndroidX, no Google Play Services, no
  third-party library unless the framework truly has no way (and then a small,
  Apache-2.0-compatible one, noted in TODO.md first).
- **No maker APIs.** Nothing Samsung-, Xiaomi- or Boox-only. What looks like a
  maker feature is done with public APIs or not at all.
- **Standard intents and settings screens.** Open Android's own screens
  (`Settings.ACTION_*`), the user's apps (`ACTION_VIEW`, `ACTION_SEND`,
  `createChooser`), the system's pickers (contacts, documents) rather than
  rebuilding them or asking for permissions the picker makes unneeded. Always
  keep a way to the system's app (Open with…, Prefs › Viewers).
- **Guard newer APIs.** minSdk is 26: an API from a later level goes behind
  `Build.VERSION.SDK_INT` with a fallback that works (as the image viewer does
  for `ImageDecoder`, API 28). Use only public, non-deprecated-for-removal
  APIs; no reflection into hidden ones.
- **AOSP apps as the reference.** Before designing a part, look at how the
  AOSP app does it (DocumentsUI for files, Launcher3 for apps and shortcuts,
  Gallery2, Music, Calendar, Settings) and copy its behaviour, or its code
  when it is Apache-2.0 (keep the notice). TODO.md › References lists them.
- **Behave like a platform citizen.** Respect the system's settings (animation
  scale, font size, dark theme, RTL), audio focus, scoped intents and
  permissions asked only when a feature needs them; degrade quietly when a
  permission, a provider or an app is missing (say so, never crash).

## Build and check

- `bash build.sh` → `pda-launcher.apk` (javac, d8, aapt2; no Gradle).
  targetSdk 28 on purpose (legacy storage; see docs/files.md).
- Icons: `python3 tools/icons.py` from the table in the script; never edit
  `res/drawable/ic_*.xml` by hand. Remove icons no code uses.
- Code style: plain Java, views built in code, comments that say why; match
  the file around the change.
- Before a commit, audit what changed for leftovers: names (the tabs are
  Applications, Briefcase, Files, Today, Prefs), settings declared but unread,
  docs (README, TODO, docs/) that no longer match.

## On a device

- Installing restarts the launcher (the Board's text is lost): ask first.
- Before installing, no other app may hold the FileShare provider
  (`dumpsys activity providers`), or Android kills that app with the launcher.
- Never type into the device with `adb input` without asking: it may land in
  another app. To try a screen that is not exported, build a test APK with it
  exported, open it with `am start`, then install the real build again.
- Wrap adb calls in `timeout`.

## Commits

- Small, described in the message body; README/TODO/docs in the same commit
  as the code they describe.
- Commit, push and install only when the owner agrees.
