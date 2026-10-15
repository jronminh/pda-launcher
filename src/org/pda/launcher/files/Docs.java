package org.pda.launcher.files;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A document of a location, read with DocumentsContract (no AndroidX). */
final class Docs {

    static final class Doc {
        final Uri uri;
        final String id, name, mime;
        final long size, modified;
        final int flags;

        Doc(Uri uri, String id, String name, String mime, long size, long modified, int flags) {
            this.uri = uri;
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.modified = modified;
            this.flags = flags;
        }

        boolean isDir() {
            return Document.MIME_TYPE_DIR.equals(mime);
        }

        boolean can(int flag) {
            return (flags & flag) != 0;
        }
    }

    private static final String[] COLS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS };

    private Docs() {}

    /** A folder's documents, folders first, then by name. Throws if the provider cannot be reached. */
    static List<Doc> children(Context c, Uri tree, String parentId) {
        List<Doc> out = new ArrayList<>();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cur = c.getContentResolver().query(kids, COLS, null, null, null)) {
            if (cur == null) throw new IllegalStateException("the app that provides it does not answer");
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                out.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), id,
                        cur.getString(1), cur.getString(2),
                        cur.isNull(3) ? -1 : cur.getLong(3), cur.isNull(4) ? 0 : cur.getLong(4), cur.getInt(5)));
            }
        }
        Collections.sort(out, (a, b) -> a.isDir() != b.isDir() ? (a.isDir() ? -1 : 1)
                : String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        return out;
    }

    static String name(Context c, Uri doc) {
        try (Cursor cur = c.getContentResolver().query(doc, new String[] { Document.COLUMN_DISPLAY_NAME },
                null, null, null)) {
            return cur != null && cur.moveToFirst() ? cur.getString(0) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Copy a stream to another; both closed. */
    static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in; OutputStream o = out) {
            if (i == null || o == null) throw new IOException("cannot be opened");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    /** Copy {@code from} (any content or file link) into the folder {@code parent} of a location. */
    static void upload(Context c, Uri from, Uri parentDoc) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String name = name(c, from);
        if (name == null) name = from.getLastPathSegment();
        String mime = cr.getType(from);
        Uri made;
        try {
            made = DocumentsContract.createDocument(cr, parentDoc, mime == null ? "application/octet-stream" : mime, name);
        } catch (Exception e) {
            throw new IOException("cannot create " + name + ": " + e.getMessage());
        }
        if (made == null) throw new IOException("cannot create " + name);
        copy(cr.openInputStream(from), cr.openOutputStream(made));
    }
}
