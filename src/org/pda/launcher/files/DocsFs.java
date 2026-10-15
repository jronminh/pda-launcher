package org.pda.launcher.files;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.pda.launcher.remote.RemoteFs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Another app's folder (a location added through the Storage Access Framework)
 * as a {@link RemoteFs}: a path is a document id of the tree.
 */
final class DocsFs implements RemoteFs {

    private final Context c;
    private final Uri tree;

    DocsFs(Context c, Uri tree) {
        this.c = c.getApplicationContext();
        this.tree = tree;
    }

    Uri uriOf(String id) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, id);
    }

    @Override public String home() {
        return DocumentsContract.getTreeDocumentId(tree);
    }

    @Override public List<Entry> list(String dir) throws IOException {
        List<Entry> out = new ArrayList<>();
        try {
            for (Docs.Doc d : Docs.children(c, tree, dir)) {
                out.add(new Entry(d.id, d.name, d.isDir(), d.size, d.modified));
            }
        } catch (SecurityException e) {
            throw new IOException("the launcher is not allowed in (any more). Remove it from Files and add it again.");
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        return out;
    }

    @Override public InputStream read(String path) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uriOf(path));
        if (in == null) throw new IOException("cannot be opened");
        return in;
    }

    @Override public OutputStream write(String dir, String name) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String mime = FileShare.mimeOf(new java.io.File(name));
        Uri made;
        try {
            made = DocumentsContract.createDocument(cr, uriOf(dir), mime, name);
        } catch (Exception e) {
            throw new IOException("cannot create " + name + ": " + e.getMessage());
        }
        if (made == null) throw new IOException("cannot create " + name);
        OutputStream o = cr.openOutputStream(made, "w");
        if (o == null) throw new IOException("cannot write " + name);
        return o;
    }

    @Override public void delete(Entry e) throws IOException {
        try {
            if (!DocumentsContract.deleteDocument(c.getContentResolver(), uriOf(e.path))) {
                throw new IOException("could not delete " + e.name);
            }
        } catch (Exception x) {
            throw x instanceof IOException ? (IOException) x : new IOException(x.getMessage());
        }
    }

    @Override public void rename(Entry e, String name) throws IOException {
        try {
            if (DocumentsContract.renameDocument(c.getContentResolver(), uriOf(e.path), name) == null) {
                throw new IOException("could not rename " + e.name);
            }
        } catch (Exception x) {
            throw x instanceof IOException ? (IOException) x : new IOException(x.getMessage());
        }
    }

    @Override public void mkdir(String dir, String name) throws IOException {
        try {
            if (DocumentsContract.createDocument(c.getContentResolver(), uriOf(dir), Document.MIME_TYPE_DIR, name) == null) {
                throw new IOException("could not create " + name);
            }
        } catch (Exception x) {
            throw x instanceof IOException ? (IOException) x : new IOException(x.getMessage());
        }
    }

    @Override public void close() { }
}
