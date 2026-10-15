# The Files tab

A file manager modelled on AOSP DocumentsUI (`packages/apps/DocumentsUI`) and the
Onyx Boox Storage screen, written with framework views only.

## What it reads

The app targets 28 and declares `requestLegacyExternalStorage`, so with
`READ/WRITE_EXTERNAL_STORAGE` it walks shared storage with `java.io.File`. A
third-party app cannot query DocumentsUI's own providers (ExternalStorageProvider,
MediaDocumentsProvider) for the whole storage, so the roots are rebuilt from
what it can reach (`files/Roots.java`):

| Root | Source |
| --- | --- |
| Recent | MediaStore, changed in the last 45 days, at most 64 (DocumentsUI's RecentsLoader numbers) |
| Internal storage, Download | the folders, through `java.io.File` |
| Images, Videos, Audio | MediaStore `MEDIA_TYPE` |
| Documents | MediaStore MIME types: text, PDF, office, e-books (as DocumentsUI's Documents root) |

Search filters the open folder by name; elsewhere it asks MediaStore for names
that contain the text (at most 200).

## The screens

- **First screen:** a banner (how full storage is, with a bar, and the size and
  count of its images, videos, audio and documents, counted from MediaStore in
  the background at most every half minute), over the roots,
  shown in the tab's view (details, list or grid).
- **A folder:** the path as crumbs (each one goes back there), folders first,
  then the sort: name, date modified, size or type (each file is read once for
  it: on shared storage every look at a file goes through FUSE). Recent has no
  sort, it is newest first by what it is. Choosing the current sort
  again turns the order round. Hidden files are off by default (⋮). ‹ in the
  header goes back, as Back does.
- Lists turn pages (`‹ 1/3 ›` or a horizontal swipe); the rows per page follow
  the screen height. Navigation is a history: Back returns to the place before,
  on the page it was on.

## Acting on files

- Tap a folder to open it, a file to open it in the app that handles it. A file
  leaves the app with a read grant, as MediaStore's link when MediaStore knows
  it, else as `content://org.pda.launcher.files/<path>` (`files/FileShare.java`,
  in place of androidx's FileProvider; only files under shared storage are
  served). MediaStore first because Android kills an app that holds a link to a
  provider whose process dies: updating the launcher killed Termux that way.
- **Trash**: Delete moves the selection to `.Trash` at the top of shared storage
  at once (a rename on the same volume; a `.nomedia` keeps it out of the media
  lists), each thing with where it came from. The Trash root lists them, last
  deleted first, with where from; a selection there has Restore (back to its
  folder, renamed "name (1)" if taken) and Delete (for good, after a
  question); ⋮ has Empty trash and Keep for 7, 30, 90 or 365 days. Older things
  go when the first screen next shows.
- **Servers**: "Add a location" › SFTP, FTP or FTPS server: host, port, user,
  password, a folder to start in, a name. It connects once at once (a mistake
  is said then). SFTP is JSch (third_party/jsch; RSA and ECDSA keys, not
  Ed25519 on Android); FTP and FTPS are the launcher's own client on the
  platform's sockets (passive, MLSD or Unix LIST, explicit TLS). The server's
  SSH key or TLS certificate is pinned the first time; a different one later
  stops and asks. Long-press the location: Edit, Use a key file (SFTP: copied
  into the app's private storage, with its passphrase), Forget the server's
  key, Remove. Passwords and keys stay in the app's private storage.
- **Locations from other apps**: "Add a location" › From another app opens Android's folder picker
  (ACTION_OPEN_DOCUMENT_TREE); any app that offers folders to the system can
  answer (an SMB provider for a NAS, Nextcloud, Termux, a USB drive, Drive).
  The grant is kept; the folder becomes a root (long-press: Rename, Remove).
- Either kind opens in its own screen in the Files look (LocationActivity over
  RemoteFs): folders in; a file opens where Files would open it (a server's
  file is fetched first into the app's cache under Android/data; another app's
  goes by its link), Open with, Download to the device, Rename, Delete (the
  place's own, no trash, after a question), Details; + for a new folder, to
  upload files from Android's picker, or to paste what Files copied (folders
  too). Hidden names follow Files' Show hidden files. One operation at a
  time, on a worker thread, with the progress in the status line.
- Long-press starts a selection: Copy, Move, Delete (to the Trash), Share, and
  ⋮ with Open with… and Details (one item: path, type, size, a folder's count,
  dates, a link's target, an image's pixels, a song's or a video's length, an
  APK's package and version), Rename, Select all, Show in folder (outside a folder), Add to or
  Remove from Briefcase (a star marks what is in it). Copied or cut items wait in a bar until *Paste here* in a folder.
- ⋮ outside a selection: New folder, Show/Hide hidden files,
  Storage (back to the first screen).
- Copy, move and delete run one at a time on a background thread
  (`files/FileOps.java`, after DocumentsUI's CopyJob, MoveJob, DeleteJob). A
  taken name gets ` (1)`, ` (2)`...; a move on one volume is a rename; a symlink
  is copied and deleted as a link; a folder cannot go inside itself. Every
  changed path goes to the media scanner, so the media roots follow.

## Thumbnails

Small and rough on purpose, enough to tell files apart (`files/Thumbs.java`): an
image itself, a frame of a video, an audio file's embedded cover, an APK's icon,
and for a folder the first image in it (of its first 200 entries). Two
background threads decode at about 128 px; the results live only in memory (an
LRU of 8 MB, keyed by path and date), and a file with none keeps its line icon.
Files and Briefcase show them in every view.

## Not done

Compressing and extracting, details, and progress in a notification for long
copies.
