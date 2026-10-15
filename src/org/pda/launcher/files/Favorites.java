package org.pda.launcher.files;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The favourite files and folders: {@code files/etc/favorites}, one absolute
 * path per line, in the order they were added. A plain file, so the shell (and
 * whatever runs in it) can read and change it: {@code echo PATH >> etc/favorites}.
 * Lines that are empty or not absolute are skipped. Written atomically.
 */
public final class Favorites {

    private Favorites() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), "etc/favorites");
    }

    public static List<File> load(Context c) {
        List<File> out = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            for (String line : b.toString("UTF-8").split("\n")) {
                String p = line.trim();
                if (p.startsWith("/")) {
                    File x = new File(p);
                    if (!out.contains(x)) out.add(x);
                }
            }
        } catch (IOException ignore) {
        }
        return out;
    }

    public static boolean contains(Context c, File f) {
        return load(c).contains(f);
    }

    /** Add the items not there yet, at the end. */
    public static void add(Context c, Collection<File> items) {
        List<File> all = load(c);
        for (File f : items) if (!all.contains(f)) all.add(f);
        save(c, all);
    }

    public static void remove(Context c, Collection<File> items) {
        List<File> all = load(c);
        all.removeAll(items);
        save(c, all);
    }

    static void save(Context c, List<File> all) {
        File f = file(c);
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        StringBuilder sb = new StringBuilder();
        for (File x : all) sb.append(x.getAbsolutePath()).append('\n');
        File tmp = new File(f.getPath() + ".new");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(sb.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        tmp.renameTo(f);
    }
}
