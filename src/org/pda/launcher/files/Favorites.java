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
 * What the Briefcase holds, in the user's order: one item a line in the app's
 * preferences. A line starting with "/" is a file or a folder (the only kind
 * of earlier builds, so their list stays as it was); the others are a kind
 * and its fields by tabs: {@code url\tTitle\thttps://...},
 * {@code app\tTitle\tpackage}, {@code shortcut\tTitle\tpackage\tid}. Files
 * and folders follow the Files tab's moves, renames and deletes. The first read
 * takes over the list of the oldest builds, {@code files/etc/favorites}.
 */
public final class Favorites {

    private static final String PREFS = "favorites";
    private static final String KEY = "paths";

    private Favorites() {}

    /** One thing in the Briefcase. */
    public static final class Item {
        public static final String FILE = "file", URL = "url", APP = "app", SHORTCUT = "shortcut";
        public final String kind;
        public final String title;   // for a file, its name
        public final String[] args;  // file: path; url: address; app: package; shortcut: package, id

        Item(String kind, String title, String... args) {
            this.kind = kind;
            this.title = title;
            this.args = args;
        }

        public static Item file(File f) {
            return new Item(FILE, f.getName().isEmpty() ? f.getPath() : f.getName(), f.getAbsolutePath());
        }

        public static Item url(String title, String url) {
            return new Item(URL, title, url);
        }

        public static Item app(String title, String pkg) {
            return new Item(APP, title, pkg);
        }

        public static Item shortcut(String title, String pkg, String id) {
            return new Item(SHORTCUT, title, pkg, id);
        }

        public File file() {
            return FILE.equals(kind) ? new File(args[0]) : null;
        }

        public Item renamed(String title) {
            return new Item(kind, title, args);
        }

        String line() {
            if (FILE.equals(kind)) return args[0];
            StringBuilder sb = new StringBuilder(kind).append('\t').append(clean(title));
            for (String a : args) sb.append('\t').append(clean(a));
            return sb.toString();
        }

        static Item parse(String line) {
            if (line.startsWith("/")) return file(new File(line));
            String[] f = line.split("\t", -1);
            if (f.length < 3) return null;
            String[] args = java.util.Arrays.copyOfRange(f, 2, f.length);
            switch (f[0]) {
                case URL: case APP: return new Item(f[0], f[1], args[0]);
                case SHORTCUT: return f.length >= 4 ? new Item(f[0], f[1], args[0], args[1]) : null;
                default: return null;
            }
        }

        private static String clean(String s) {
            return s.replace('\t', ' ').replace('\n', ' ');
        }

        @Override public boolean equals(Object o) {
            return o instanceof Item && line().equals(((Item) o).line());
        }

        @Override public int hashCode() {
            return line().hashCode();
        }
    }

    private static SharedPreferences prefs(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains(KEY)) {
            File old = new File(c.getFilesDir(), "etc/favorites");
            p.edit().putString(KEY, read(old)).apply();
            old.delete();
        }
        return p;
    }

    /** Everything, in order. */
    public static List<Item> items(Context c) {
        List<Item> out = new ArrayList<>();
        for (String line : prefs(c).getString(KEY, "").split("\n")) {
            String l = line.trim();
            Item it = l.isEmpty() ? null : Item.parse(l);
            if (it != null && !out.contains(it)) out.add(it);
        }
        return out;
    }

    public static void saveItems(Context c, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) sb.append(it.line()).append('\n');
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    /** Add an item at the end, if not there yet. @return false if it was. */
    public static boolean addItem(Context c, Item it) {
        List<Item> all = items(c);
        if (all.contains(it)) return false;
        all.add(it);
        saveItems(c, all);
        return true;
    }

    /** The files and folders only (the Files tab's stars). */
    public static List<File> load(Context c) {
        List<File> out = new ArrayList<>();
        for (Item it : items(c)) if (it.file() != null) out.add(it.file());
        return out;
    }

    /** Add the files not there yet, at the end. */
    public static void add(Context c, Collection<File> files) {
        List<Item> all = items(c);
        for (File f : files) {
            Item it = Item.file(f);
            if (!all.contains(it)) all.add(it);
        }
        saveItems(c, all);
    }

    public static void remove(Context c, Collection<File> files) {
        List<Item> all = items(c);
        all.removeIf(it -> it.file() != null && files.contains(it.file()));
        saveItems(c, all);
    }

    /** {@code from} was moved or renamed to {@code to}: files at it or inside
     *  it follow. Called from the file operations' thread too. */
    static synchronized void moved(Context c, File from, File to) {
        String a = from.getAbsolutePath(), b = to.getAbsolutePath();
        List<Item> all = items(c);
        boolean any = false;
        for (int i = 0; i < all.size(); i++) {
            File f = all.get(i).file();
            if (f == null) continue;
            String p = f.getAbsolutePath();
            if (p.equals(a) || p.startsWith(a + "/")) {
                all.set(i, Item.file(new File(b + p.substring(a.length()))));
                any = true;
            }
        }
        if (any) saveItems(c, all);
    }

    /** {@code gone} was deleted: files at it or inside it go too. */
    static synchronized void deleted(Context c, File gone) {
        String a = gone.getAbsolutePath();
        List<Item> all = items(c);
        boolean any = all.removeIf(it -> it.file() != null && (it.file().getAbsolutePath().equals(a)
                || it.file().getAbsolutePath().startsWith(a + "/")));
        if (any) saveItems(c, all);
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
