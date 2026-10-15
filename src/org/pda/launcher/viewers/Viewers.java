package org.pda.launcher.viewers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import org.pda.launcher.Config;
import org.pda.launcher.PopMenu;
import org.pda.launcher.files.FileDetails;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Which files the launcher opens itself, and with what: text in the editor,
 * images in the viewer, audio in the player, PDF in the reader. Each kind has a
 * setting (open.text, open.image, open.audio, open.pdf): "builtin" or "system".
 */
public final class Viewers {

    public static final String EXTRA_PATH = "path";

    private Viewers() {}

    /** Open {@code f} with a built-in viewer if its kind is set so; false: the system's app. */
    public static boolean open(Context c, File f) {
        Class<?> viewer = viewerFor(f);
        if (viewer == null) return false;
        // Its own task (as an app would be): Home leaves the launcher as it was. Out of
        // Recents unless asked for (launcher.viewerRecents); either way it leaves
        // Recents when closed (autoRemoveFromRecents), so files opened leave no trail.
        Intent i = new Intent(c, viewer).putExtra(EXTRA_PATH, f.getAbsolutePath())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!Config.getBool("launcher.viewerRecents")) i.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        c.startActivity(i);
        return true;
    }

    private static Class<?> viewerFor(File f) {
        String kind = kindOf(f);
        if (kind == null || !Config.get("open." + kind).equals("builtin")) return null;
        switch (kind) {
            case "text": return TextEditorActivity.class;
            case "image": return ImageViewerActivity.class;
            case "audio": return AudioPlayerActivity.class;
            default: return PdfViewerActivity.class;
        }
    }

    /** The files of {@code f}'s kind in its folder, by name, {@code f} among them. */
    static List<File> siblings(File f) {
        String kind = kindOf(f);
        List<File> out = new ArrayList<>();
        File[] all = f.getParentFile() == null ? null : f.getParentFile().listFiles();
        if (all != null) {
            for (File x : all) if (!x.getName().startsWith(".") && kind.equals(kindOf(x))) out.add(x);
        }
        if (!out.contains(f)) out.add(f);
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** A viewer's ⋮: Open with… and Details for the file on screen. */
    static void fileMenu(Activity a, View anchor, File f) {
        PopMenu m = new PopMenu(a, anchor);
        m.add(0, 1, 0, "Open with…");
        m.add(0, 2, 1, "Details");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) FileDetails.openWith(a, f);
            else FileDetails.show(a, f, a.getWindow().getDecorView());
            return true;
        });
        m.show();
    }

    /**
     * What a viewer shows: a file (from Files, or a link that names one), or else
     * only a link another app handed over ("Open with › PDA Launcher"), read
     * through the content resolver; then {@code file} is null.
     */
    static final class Source {
        final File file;
        final android.net.Uri uri;
        final String name, mime;

        Source(File file, android.net.Uri uri, String name, String mime) {
            this.file = file;
            this.uri = uri;
            this.name = name;
            this.mime = mime;
        }
    }

    /**
     * The viewer's source, or null (and the activity finished). Another app comes
     * in through an alias (org.pda.launcher.open.*) and only with a link: the path
     * extra is the launcher's own, honoured only from inside.
     */
    static Source sourceOf(Activity a) {
        Intent in = a.getIntent();
        boolean outside = in.getComponent() != null && in.getComponent().getClassName().contains(".open.");
        String path = outside ? null : in.getStringExtra(EXTRA_PATH);
        if (path != null) {
            File f = new File(path);
            if (f.isFile()) return new Source(f, null, f.getName(), null);
        } else if (in.getData() != null) {
            android.net.Uri u = in.getData();
            File f = fileFor(a, u);
            if (f != null && f.isFile() && f.canRead()) return new Source(f, null, f.getName(), null);
            if (!"file".equals(u.getScheme())) {
                String name = displayName(a, u);
                return new Source(null, u, name, in.getType() != null ? in.getType() : a.getContentResolver().getType(u));
            }
        }
        android.widget.Toast.makeText(a, "The file is gone", android.widget.Toast.LENGTH_SHORT).show();
        a.finish();
        return null;
    }

    /** The file a link names, when it can be told: file://, shared storage's documents, MediaStore, ours. */
    @SuppressWarnings("deprecation")   // DATA: the app targets 28 and works with paths
    public static File fileFor(Context c, android.net.Uri u) {
        try {
            if ("file".equals(u.getScheme())) return u.getPath() == null ? null : new File(u.getPath());
            if (!"content".equals(u.getScheme())) return null;
            String auth = u.getAuthority();
            if ("org.pda.launcher.files".equals(auth)) return u.getPath() == null ? null : new File(u.getPath());
            if ("com.android.externalstorage.documents".equals(auth)) {
                String id = android.provider.DocumentsContract.isTreeUri(u) && u.getPathSegments().size() < 4
                        ? android.provider.DocumentsContract.getTreeDocumentId(u)
                        : android.provider.DocumentsContract.getDocumentId(u);
                if (id.startsWith("primary:")) {
                    return new File(android.os.Environment.getExternalStorageDirectory(), id.substring(8));
                }
                return null;
            }
            try (android.database.Cursor cur = c.getContentResolver().query(u,
                    new String[] { android.provider.MediaStore.MediaColumns.DATA }, null, null, null)) {
                if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return new File(cur.getString(0));
            }
        } catch (RuntimeException ignore) {
            // a provider without that column, or one that refuses: the link alone
        }
        return null;
    }

    private static String displayName(Context c, android.net.Uri u) {
        try (android.database.Cursor cur = c.getContentResolver().query(u,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (RuntimeException ignore) {}
        String last = u.getLastPathSegment();
        return last != null ? last : "File";
    }

    /** A viewer's ⋮ for a link without a file: Open with… only. */
    static void linkMenu(Activity a, View anchor, Source s) {
        PopMenu m = new PopMenu(a, anchor);
        m.add(0, 1, 0, "Open with…");
        m.setOnMenuItemClickListener(item -> {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                a.startActivity(Intent.createChooser(view, "Open with"));
            } catch (RuntimeException ignore) {}
            return true;
        });
        m.show();
    }

    /** The ⋮ of a viewer: the file's, or the link's. */
    static void menu(Activity a, View anchor, Source s, File f) {
        if (s.file != null) fileMenu(a, anchor, f);
        else linkMenu(a, anchor, s);
    }

    /** text, image, audio, pdf, or null for a kind no viewer takes. */
    public static String kindOf(File f) {
        if (!f.isFile()) return null;
        String n = f.getName().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot >= 0 ? n.substring(dot + 1) : "";
        switch (ext) {
            case "txt": case "md": case "markdown": case "json": case "log": case "sh": case "bash":
            case "conf": case "cfg": case "ini": case "properties": case "csv": case "tsv": case "xml":
            case "yml": case "yaml": case "toml": case "py": case "js": case "java": case "c": case "h":
            case "kt": case "go": case "rs": case "html": case "css": case "lua": case "gitignore":
            case "env": case "todo":
                return "text";
            case "jpg": case "jpeg": case "png": case "webp": case "gif": case "bmp": case "heic": case "heif":
                return "image";
            case "mp3": case "m4a": case "aac": case "ogg": case "oga": case "opus": case "flac":
            case "wav": case "amr": case "3gp": case "mid": case "midi":
                return "audio";
            case "pdf":
                return "pdf";
            default:
                return dot < 0 && f.length() < 64 * 1024 ? "text" : null;   // a small file with no extension
        }
    }
}
