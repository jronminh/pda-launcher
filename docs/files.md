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

- **First screen:** how full storage is (a bar), then the roots.
- **A folder:** the path as crumbs (each one goes back there), folders first,
  then the sort: name, date modified, size or type. Choosing the current sort
  again turns the order round. Hidden files are off by default (⋮).
- Lists turn pages (`‹ 1/3 ›` or a horizontal swipe); the rows per page follow
  the screen height. Navigation is a history: Back returns to the place before,
  on the page it was on.

## Acting on files

- Tap a folder to open it, a file to open it in the app that handles it. A file
  leaves the app as `content://org.pda.launcher.files/<path>` with a read grant
  (`files/FileShare.java`, in place of androidx's FileProvider); only files under
  shared storage are served.
- Long-press starts a selection: Copy, Move, Delete (after a question), Share, and
  ⋮ with Rename, Select all, Show in folder (outside a folder), Add to or
  Remove from favorites (a star marks a favourite in the list). Copied or cut items wait in a bar until *Paste here* in a folder.
- ⋮ outside a selection: New folder, Show/Hide hidden files,
  Storage (back to the first screen).
- Copy, move and delete run one at a time on a background thread
  (`files/FileOps.java`, after DocumentsUI's CopyJob, MoveJob, DeleteJob). A
  taken name gets ` (1)`, ` (2)`...; a move on one volume is a rename; a symlink
  is copied and deleted as a link; a folder cannot go inside itself. Every
  changed path goes to the media scanner, so the media roots follow.

## Not done

Thumbnails, a grid view, compressing and extracting, details, and progress in a
notification for long copies.
