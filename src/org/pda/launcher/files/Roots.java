package org.pda.launcher.files;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.pda.launcher.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The places the Files tab starts from, after DocumentsUI's roots: Recent, the
 * whole of shared storage, Download, and the media kinds (Images, Videos, Audio,
 * Documents) that DocumentsUI gets from MediaDocumentsProvider. A folder root is
 * walked with java.io.File (the app targets 28 and has legacy storage); a media
 * root is a MediaStore query whose rows are paths, so every entry is a File too.
 */
final class Roots {

    enum Kind { FOLDER, RECENT, IMAGES, VIDEOS, AUDIO, DOCUMENTS }

    static final class Root {
        final Kind kind;
        final String title;
        final int icon;
        final File dir;   // for FOLDER

        Root(Kind kind, String title, int icon, File dir) {
            this.kind = kind;
            this.title = title;
            this.icon = icon;
            this.dir = dir;
        }
    }

    /** As DocumentsUI's RecentsLoader: files changed in the last 45 days, at most 64. */
    static final long RECENT_DAYS = 45;
    static final int RECENT_MAX = 64;
    static final int SEARCH_MAX = 200;

    private Roots() {}

    static File storage() {
        return Environment.getExternalStorageDirectory();
    }

    static List<Root> all() {
        List<Root> r = new ArrayList<>();
        r.add(new Root(Kind.RECENT, "Recent", R.drawable.ic_recent, null));
        r.add(new Root(Kind.FOLDER, "Internal storage", R.drawable.ic_storage, storage()));
        r.add(new Root(Kind.FOLDER, "Download", R.drawable.ic_download,
                new File(storage(), Environment.DIRECTORY_DOWNLOADS)));
        r.add(new Root(Kind.IMAGES, "Images", R.drawable.ic_image, null));
        r.add(new Root(Kind.VIDEOS, "Videos", R.drawable.ic_video, null));
        r.add(new Root(Kind.AUDIO, "Audio", R.drawable.ic_audio, null));
        r.add(new Root(Kind.DOCUMENTS, "Documents", R.drawable.ic_doc, null));
        return r;
    }

    /** The files of a media root, newest first. */
    static List<File> query(Context c, Kind kind) {
        String where;
        String[] args = null;
        int max = Integer.MAX_VALUE;
        switch (kind) {
            case RECENT:
                where = MediaStore.Files.FileColumns.DATE_MODIFIED + " > ? AND "
                        + MediaStore.Files.FileColumns.MIME_TYPE + " IS NOT NULL";
                long since = System.currentTimeMillis() / 1000 - RECENT_DAYS * 24 * 3600;
                args = new String[] { String.valueOf(since) };
                max = RECENT_MAX;
                break;
            case IMAGES: where = mediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE); break;
            case VIDEOS: where = mediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO); break;
            case AUDIO: where = mediaType(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO); break;
            case DOCUMENTS: where = DOCUMENT_MIMES; break;
            default: return new ArrayList<>();
        }
        return files(c, where, args, max);
    }

    /** Files whose name holds {@code text}, anywhere in shared storage. */
    static List<File> search(Context c, String text) {
        String like = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return files(c, MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\' AND "
                + MediaStore.Files.FileColumns.MIME_TYPE + " IS NOT NULL", new String[] { like }, SEARCH_MAX);
    }

    private static String mediaType(int t) {
        return MediaStore.Files.FileColumns.MEDIA_TYPE + " = " + t;
    }

    /** What DocumentsUI's Documents root holds: text, PDF, office files, e-books. */
    private static final String DOCUMENT_MIMES = "("
            + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'text/%' OR "
            + MediaStore.Files.FileColumns.MIME_TYPE + " IN ('application/pdf', 'application/msword',"
            + " 'application/rtf', 'application/epub+zip', 'application/x-mobipocket-ebook') OR "
            + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/vnd.ms-%' OR "
            + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/vnd.openxmlformats-%' OR "
            + MediaStore.Files.FileColumns.MIME_TYPE + " LIKE 'application/vnd.oasis.opendocument.%')";

    @SuppressWarnings("deprecation")   // DATA: a path is what this app works with (targetSdk 28)
    private static List<File> files(Context c, String where, String[] args, int max) {
        List<File> out = new ArrayList<>();
        Uri uri = MediaStore.Files.getContentUri("external");
        String[] cols = { MediaStore.Files.FileColumns.DATA };
        try (Cursor cur = c.getContentResolver().query(uri, cols, where, args,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < max) {
                String p = cur.getString(0);
                if (p == null) continue;
                File f = new File(p);
                if (f.isFile()) out.add(f);   // the index can be behind the disk
            }
        } catch (RuntimeException e) {
            // no permission yet, or a provider that refuses the query: an empty list
        }
        return out;
    }
}
