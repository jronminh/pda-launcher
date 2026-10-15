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
        // Its own task (as an app would be): Home leaves it in Recents, the launcher as it was.
        c.startActivity(new Intent(c, viewer).putExtra(EXTRA_PATH, f.getAbsolutePath())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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

    /** The viewer's file, or null (and the activity finished). */
    static File fileOf(Activity a) {
        String path = a.getIntent().getStringExtra(EXTRA_PATH);
        File f = path == null ? null : new File(path);
        if (f == null || !f.isFile()) {
            android.widget.Toast.makeText(a, "The file is gone", android.widget.Toast.LENGTH_SHORT).show();
            a.finish();
            return null;
        }
        return f;
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
