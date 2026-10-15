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
- **Smaller ones:** POST_NOTIFICATIONS (33+) for the audio player's
  notification, asked when it first plays (without it the sound still plays,
  the notification is hidden); a `mediaPlayback` foreground service type (34+);
  PendingIntent mutability (none left); FileShare and MediaStore links keep
  working; REQUEST_INSTALL_PACKAGES is restricted on Play (drop it, or justify).
- Play also wants an App Bundle (AAB): `aapt2 link --proto-format` and
  bundletool, still without Gradle.

## Release: Google Play and F-Droid

The app is ready in look; to ship it, beyond "Raise targetSdk" above:

- **Play's paperwork.** A developer account (25 USD). A new personal account
  must run a closed test with 12 testers for 14 days before production. A
  privacy policy page (GitHub Pages is enough) and the Data safety form: the
  app sends nothing anywhere, reads the calendar only on the device, and keeps
  its files in shared storage. Permissions to explain: All files access (a
  file manager), REQUEST_INSTALL_PACKAGES (Files opens an APK; file managers
  qualify) or drop it, READ_CALENDAR (the glance line, Today), QUERY_ALL_PACKAGES
  if needed after 30 (a launcher qualifies).
- **The listing.** Screenshots of each tab (phone and tablet), a 512 px icon,
  a feature graphic, a short and a full description; check that
  `org.pda.launcher` is free. Play App Signing with an upload key (the
  current key stays for the APKs built here).
- **F-Droid first, maybe.** Open source, no fee, no Google: it fits the app.
  Its build server needs a recipe: build.sh with a pinned android.jar and
  build-tools, reproducible; no Gradle is fine with a custom build. Fastlane
  metadata (descriptions, screenshots) in the repo serves both stores.
- **Devices.** First a pure AOSP ROM (vanilla LineageOS, or an AOSP emulator
  image without Google APIs): the goal of AGENTS.md. Then a tablet, Android 8
  to 16: the tiles' default apps, the viewers, the torch,
  storage access, edge to edge, a wide screen (the tablet layout above).
- **Words.** Move the UI's strings into res/values/strings.xml, then a
  Vietnamese translation (values-vi); dates and sizes already follow the locale.
- **Accessibility.** A content description on every icon-only button and tile
  (TalkBack), touch targets of 48 dp, text that grows with the system's font
  size without breaking the banner's one size.
- **Before each release.** The audit of AGENTS.md, a version code and name in
  the manifest, a changelog (fastlane/metadata/android/en-US/changelogs/).

## Next features (most useful for the effort first)

Done: app shortcuts on long-press, the glance line over Apps, Share › Show on
Note, the Today tab (calendar, todo.txt, inbox.md, brief.md, the board),
Capture, Share › Add to Inbox, the tabs' order and which show.

Still for Today: something to write brief.md (Termux, `claude -p` on a schedule).

1. ~~A trash for Files~~ done (docs/files.md). Left: a trash per volume (an SD
   card's own, so its deletes are renames too); MediaStore's trash on API 30+.
2. **Tablets and landscape.** Columns follow the width (the Apps grid's
   columns setting becomes a minimum, or "auto"); lists may go two columns;
   Files may show the folder list beside the content on a wide screen.
3. **One search for everything:** apps, files (MediaStore), favourites and the
   Settings tiles in one box, from the Apps tab or a swipe down.
4. **Notification dots** on app icons (NotificationListenerService, a
   permission the user turns on), off by default.
5. **Backup and restore** of the settings, favourites and hidden apps to a file.

## Files: Open with, Open as, Details

Done: Open with…, Open as… (text, image, audio, video, any app) and Details (a
Card, with an APK's icon) in Files' selection menu, the Briefcase's, a
location's and the viewers' ⋮.

## Remember where one was

Done: the open tab, Today's part and Files' place come back after the process
was killed ("Start where I left off", on by default); tapping the open tab's
button takes it to its start. Left: pages, Files' whole history, Prefs' group,
words typed and not sent.

The launcher should come back as it was left, also after its process was
killed (an update, low memory, a reboot), not only while it lives:

- the open tab, and in Today the open part;
- in Files the place (folder, media root, search) with its history and page,
  in Applications and Briefcase the page, in Prefs the group;
- the words typed and not yet sent (Today's entry lines, a search box).

A tab's button is the way back to its start, whatever was remembered: going
to a tab opens it as it was left; tapping the button of the tab already open
takes it to its start at once (Applications: page 1, no search; Briefcase:
page 1; Files: the first screen, the history cleared; Today: its tiles;
Prefs: the tiles), with no other state lost.

Kept in a small state file (or SharedPreferences) written when the launcher
leaves the screen (onStop), read in onCreate. A setting "Start where I left
off" (on by default); off, a start opens the Home tab as now. Home pressed on
the home screen still goes to the Home tab: that is a choice, not a state.

## Briefcase: more than files

The Briefcase is "what one carries": let it hold other kinds, each a line with
its kind in front (`file:/sdcard/...`, `shortcut:com.app/id`, `app:com.app`,
`url:https://...`, `contact:content://...`, `cmd:~/bin/brief.sh`); a line with
no kind is a file, so the list of today stays as it is. Order, views, search
and following renames keep working.

| Kind | Added from | A tap |
| --- | --- | --- |
| File, folder (done) | Files › Add to Briefcase | opens it, or the folder in Files |
| App shortcut | Applications, long-press, next to each shortcut | starts it (pinned with `LauncherApps.pinShortcuts`, so the app cannot drop it) |
| App | Applications, long-press › Add to Briefcase | opens the app |
| Link | + › Link, or Share › Add to Briefcase from a browser | opens the browser |
| Contact | + › Contact (Android's picker: no contacts permission) | Call or Message |
| Termux command | + › Command | runs it in Termux (`com.termux.RUN_COMMAND`; needs `allow-external-apps = true` in `~/.termux/termux.properties` and the RUN_COMMAND permission granted once) |

Done: files, folders, links, apps and app shortcuts (pinned), as tiles with a
More tile; + menu, Share › Add to Briefcase, Applications › Add to Briefcase.
Left: contacts and Termux commands.

## Built-in viewers

Open the common kinds inside the launcher, in its look (black, paged, line
icons), instead of handing every file to another app. "Open with…" stays in
the file's menu, and a setting chooses built-in or the system's app per kind.

Done (Prefs › Viewers, per kind; `viewers/`): text editor (save atomically,
find, wrap, monospace, revert; over 2 MB or binary goes to the system's app),
image viewer (fitted, pinch/double-tap zoom, swipe through the folder, EXIF
rotation; rotate the view, share, delete to the Trash), audio player (the
folder as the queue, cover, seek; PlaybackService: plays on when closed, a
notification, a media session, pauses when headphones go, sleep timer), video
player (VideoView, swipe through the folder, comes back where it was), PDF
reader (PdfRenderer, ‹ n/count ›, swipe, zoom). Each opens in its own task,
out of Recents unless asked for. Left:

- Archives: zip with java.util.zip (list, extract all or one), as a viewer.
- Images: rotate the file itself (EXIF orientation for a JPEG), not only the view.
- Audio: the queue kept across a killed process; a queue of the Audio root
  (MediaStore), not only a folder.
- Video: subtitles (.srt beside the file, `MediaPlayer.addTimedTextSource`).

## Network storage

Done: locations through the Storage Access Framework, and SFTP, FTP, FTPS
servers (src/org/pda/launcher/remote: RemoteFs, FtpFs, SftpFs; JSch in
third_party/jsch), tried against a local sshd and pyftpdlib on the JVM and in
the app; Paste from Files, fetch-to-open, upload, download. Left:
- WebDAV (HttpURLConnection alone), then SMB only through a provider app.
- FTPS data connections need TLS session reuse on many servers (vsftpd's
  require_ssl_reuse): not tried yet; plain FTP and SFTP are.
- Ed25519 keys (need Bouncy Castle or API 33's EdDSA: not now).
- A file opened from a server is a copy: send it back when the editor saved it.
- Transfers in the background (a foreground service with a notification) so a
  long copy survives leaving the screen; Copy from a location into Files' clip.

New roots in Files, under the local ones, each a place you add once (address,
user, password; the password in the app's private storage, never in a log).
The Files code works on java.io.File today: put a small interface between
(list, stat, open for read and write, rename, delete, mkdir) with a local and
a network implementation, and let FileOps copy across them with progress.

- **WebDAV** (Nextcloud, a NAS, `rclone serve webdav`): plain HTTP, doable
  with HttpURLConnection alone (PROPFIND, GET, PUT, MOVE, DELETE, MKCOL).
- **SMB** (Windows shares, most NAS): needs a library (jcifs-ng); its jar can
  be dexed by build.sh with no Gradle.
- **SFTP** (any SSH server, Termux's sshd on another phone): a library too
  (JSch or sshj).
- **FTP:** last, if at all.
- Open a remote file by copying it to the cache first (with progress), then
  the viewers or another app read the copy; offer to upload it back if the
  text editor changed it. Thumbnails only for local files at first.
- Tailscale is already on this phone, so a NAS or a PC at home is reachable
  by its tailnet name.

## Recents

With a third-party home, Recents is One UI Home's (com.sec.android.app.launcher,
RecentsActivity), so going to and from it swaps two launchers' windows. Done:
a black window background (it was the theme's grey), `resumeWhilePausing` (as
Launcher3), no app list reread on resume, and a black cover over the tabs from
onRestart until onEnterAnimationComplete (280-380 ms measured), so the
launcher is not seen drawn behind a Recents still closing. Left, the system's: Home pressed
in Recents closes One UI's Recents over this launcher instead of zooming into
it, as only One UI Home can.

## References

AOSP first: it is Apache-2.0, like this repo, so a piece may be copied with
credit. Launcher3 and the newer apps lean on androidx, so read them for the
way, and write it again with framework views as the rest of the app is.

| For | Read |
| --- | --- |
| App shortcuts | `platform/packages/apps/Launcher3`: `shortcuts/ShortcutRequest.java` (wraps `LauncherApps.ShortcutQuery`), `popup/PopupPopulator.java`, `popup/SystemShortcut.java` |
| Notification dots | Launcher3 `notification/NotificationListener.java`, `NotificationKeyData.java` |
| Tablets, landscape | Launcher3 `DeviceProfile.java` (columns and rows from the screen); DocumentsUI's two-pane tablet layout |
| One search | Launcher3 `allapps/search` |
| Audio player | `platform/packages/apps/Music`: `MediaPlaybackService.java` (`main` is cut down; read an older branch) |
| Image viewer | `platform/packages/apps/Gallery2` is heavy (OpenGL); take its tiled decoding with the framework's `BitmapRegionDecoder` |
| Text editor | none in AOSP; `platform/development/samples/NotePad` is only a sample |
| PDF | the framework's `PdfRenderer` |
| Trash | MediaStore's own (`IS_TRASHED`, `createTrashRequest`, API 30+) once targetSdk is raised; a hidden folder before |
| Glance line | `AlarmManager.getNextAlarmClock()` |

Outside AOSP (licences to check again before copying anything):

- Network storage: JSch (mwiede fork, BSD) is in, for SFTP (third_party/jsch,
  its README says what was left out and how to update it). smbj (SMB) and sshj
  (SFTP), Apache-2.0, were the other choices.
  WebDAV needs none. Avoid jcifs-ng (LGPL, awkward in one dex) and Google's
  samba-documents-provider (GPL: read only).
- Text editing: Markor (Apache-2.0).
- Zoomable images: PhotoView (its older ImageView-based version) and
  subsampling-scale-image-view, both Apache-2.0.
