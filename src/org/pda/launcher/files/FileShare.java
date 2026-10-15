package org.pda.launcher.files;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Hands a file in shared storage to another app (open, share): the part of
 * androidx's FileProvider this app needs. A file:// link may not leave an app
 * that targets 24+, so the Files tab passes {@code content://AUTHORITY/<path>}
 * with a read grant. Only files under the shared storage root are served; the
 * provider is not exported, so another app reaches a file only through a grant.
 */
public final class FileShare extends ContentProvider {

    static final String AUTHORITY = "org.pda.launcher.files";

    /**
     * The link to hand another app for {@code f}. MediaStore's when the file is in
     * its index: MediaProvider is the system's, so the reader never depends on this
     * app's process. A reader that holds a link to this provider is killed with it
     * when the launcher is updated (Android stops the apps that use a dying
     * provider), and Termux was, twice. Only files MediaStore does not know (hidden
     * folders, say) still go through this provider.
     */
    public static Uri linkFor(Context c, File f) {
        Uri media = mediaUri(c, f);
        return media != null ? media : uriFor(f);
    }

    @SuppressWarnings("deprecation")   // DATA: the app targets 28 and works with paths
    private static Uri mediaUri(Context c, File f) {
        Uri files = MediaStore.Files.getContentUri("external");
        String[] cols = { MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE };
        try (Cursor cur = c.getContentResolver().query(files, cols,
                MediaStore.Files.FileColumns.DATA + " = ?", new String[] { f.getAbsolutePath() }, null)) {
            if (cur == null || !cur.moveToFirst()) return null;
            long id = cur.getLong(0);
            Uri base;
            switch (cur.getInt(1)) {
                case MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE: base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; break;
                case MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO: base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; break;
                case MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO: base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; break;
                default: base = files;
            }
            return ContentUris.withAppendedId(base, id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .encodedPath(Uri.encode(f.getAbsolutePath(), "/")).build();
    }

    public static String mimeOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return m != null ? m : "application/octet-stream";
    }

    /** The file a link names, or null if it is outside shared storage. */
    private static File fileOf(Uri uri) {
        String path = uri.getPath();
        if (path == null) return null;
        try {
            File f = new File(path).getCanonicalFile();
            String root = Environment.getExternalStorageDirectory().getCanonicalPath();
            return f.getPath().startsWith(root + "/") ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        File f = fileOf(uri);
        return f == null ? null : mimeOf(f);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileOf(uri);
        if (f == null || !f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = fileOf(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
