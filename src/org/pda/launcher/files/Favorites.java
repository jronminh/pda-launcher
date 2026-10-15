package org.pda.launcher.files;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The favourite files and folders, in the order they were added: absolute
 * paths, one per line, in the app's preferences. They follow the Files tab's
 * moves, renames and deletes. The first read takes over the
 * list of earlier builds, the file {@code files/etc/favorites}, and deletes it.
 */
public final class Favorites {

    private static final String PREFS = "favorites";
    private static final String KEY = "paths";

    private Favorites() {}

    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains(KEY)) {
            File old = new File(c.getFilesDir(), "etc/favorites");
            p.edit().putString(KEY, read(old)).apply();
            old.delete();
        }
        return p;
    }

    public static List<File> load(Context c) {
        List<File> out = new ArrayList<>();
        for (String line : prefs(c).getString(KEY, "").split("\n")) {
            String p = line.trim();
            if (p.startsWith("/")) {
                File f = new File(p);
                if (!out.contains(f)) out.add(f);
            }
        }
        return out;
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

    /** {@code from} was moved or renamed to {@code to}: favourites at it or inside
     *  it follow. Called from the file operations' thread too. */
    static synchronized void moved(Context c, File from, File to) {
        String a = from.getAbsolutePath(), b = to.getAbsolutePath();
        List<File> all = load(c);
        boolean any = false;
        for (int i = 0; i < all.size(); i++) {
            String p = all.get(i).getAbsolutePath();
            if (p.equals(a) || p.startsWith(a + "/")) {
                all.set(i, new File(b + p.substring(a.length())));
                any = true;
            }
        }
        if (any) save(c, all);
    }

    /** {@code gone} was deleted: favourites at it or inside it go too. */
    static synchronized void deleted(Context c, File gone) {
        String a = gone.getAbsolutePath();
        List<File> all = load(c);
        boolean any = all.removeIf(f -> f.getAbsolutePath().equals(a) || f.getAbsolutePath().startsWith(a + "/"));
        if (any) save(c, all);
    }

    static void save(Context c, List<File> all) {
        StringBuilder sb = new StringBuilder();
        for (File f : all) sb.append(f.getAbsolutePath()).append('\n');
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    private static String read(File f) {
        if (!f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }
}
