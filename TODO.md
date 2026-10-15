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

## Next features (most useful for the effort first)

Done: app shortcuts on long-press, the glance line over Apps, Share › Show on
Note, the Today tab (calendar, todo.txt, inbox.md, brief.md, the board),
Capture, Share › Add to Inbox, the tabs' order and which show.

Still for Today: something to write brief.md (Termux, `claude -p` on a schedule).

1. **A trash for Files.** Delete moves to a hidden folder on the same volume
   (a rename, so instant), with where it came from; a Trash root to restore or
   empty; items older than 30 days go for good. A setting for direct delete.
2. **Tablets and landscape.** Columns follow the width (the Apps grid's
   columns setting becomes a minimum, or "auto"); lists may go two columns;
   Files may show the folder list beside the content on a wide screen.
3. **One search for everything:** apps, files (MediaStore), favourites and the
   Settings tiles in one box, from the Apps tab or a swipe down.
4. **Notification dots** on app icons (NotificationListenerService, a
   permission the user turns on), off by default.
5. **Backup and restore** of the settings, favourites and hidden apps to a file.

## Built-in viewers

Open the common kinds inside the launcher, in its look (black, paged, line
icons), instead of handing every file to another app. "Open with…" stays in
the file's menu, and a setting chooses built-in or the system's app per kind.

- **Text editor:** plain text, .md, .json, .log, .sh, .conf. Open, edit, save
  (atomically, through FileOps so Favorites and MediaStore follow), find, wrap
  on/off, a monospace option; refuse files too large to hold (say over 2 MB)
  and binary ones. Pages instead of scrolling, like the lists.
- **Image viewer:** one image at a time, sampled to the screen (as Thumbs
  does, larger), pinch to zoom and pan, a swipe or ‹ › for the next image of
  the folder or of the media root, rotate, share, delete, details (size,
  date, dimensions; EXIF taken date if cheap).
- **Audio player:** MediaPlayer in a foreground service (a notification with
  play/pause, so it plays on with the screen off), the folder or the Audio
  root as the queue, cover from the embedded picture, seek bar, sleep timer.
  Media session for the headset buttons.
- Later, maybe: video (VideoView), PDF (PdfRenderer, pages fit the paged
  look well), and archives (zip with java.util.zip: list, extract).

## Network storage

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

- Network storage: smbj (SMB) and sshj (SFTP), Apache-2.0; JSch, BSD-style.
  WebDAV needs none. Avoid jcifs-ng (LGPL, awkward in one dex) and Google's
  samba-documents-provider (GPL: read only).
- Text editing: Markor (Apache-2.0).
- Zoomable images: PhotoView (its older ImageView-based version) and
  subsampling-scale-image-view, both Apache-2.0.
