package org.pda.launcher.shell;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings, read from the files of the tree (docs/shell.md).
 *
 * <p>The app is only a <b>reader</b>. {@code lib/config.schema} says what every
 * setting is (type, default, constraint); a setting is the file
 * {@code etc/<key with dots as directories>}; a missing file is the default. The
 * files are written by the {@code config} command or by hand. This class reads
 * everything at {@link #init}, and watches {@code etc/} so that a change reaches
 * the {@link Listener} at once. An invalid file falls back to the default and is
 * logged to {@code var/log/dn.log}; it never stops the app.
 */
public final class Config {

    /** Called on the main thread after the settings were read again. */
    public interface Listener {
        void onChange();
    }

    private static final int WATCH = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO
            | FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static File root;
    private static Map<String, String[]> schema = new LinkedHashMap<>();  // key -> type, default, constraint
    private static volatile Map<String, String> values = new LinkedHashMap<>();
    private static final List<FileObserver> observers = new ArrayList<>();
    private static final Map<String, String> logged = new LinkedHashMap<>();   // key -> last message
    private static final List<Listener> listeners = new ArrayList<>();

    private static final Runnable RELOAD = new Runnable() {
        @Override public void run() {
            read();
            watch();
            for (Listener l : new ArrayList<>(listeners)) l.onChange();
        }
    };

    private Config() {}

    // --- start ------------------------------------------------------------------

    /** Create the missing files with their defaults: the app runs the command
     *  (the command is the writer, spec §2). Call after {@link Tree#prepare}. */
    static void seed(Context c) throws IOException {
        File r = Tree.root(c);
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh",
                new File(r, "bin/config").getPath(), "seed");
        pb.environment().put("DN_INSTDIR", r.getPath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) out.append(line).append('\n');
        }
        int rc;
        try { rc = p.waitFor(); } catch (InterruptedException e) { rc = -1; }
        if (rc != 0) throw new IOException("config seed failed (" + rc + "): " + out);
    }

    /** Make the tree, seed the settings and read them: what every tab needs before
     *  it reads a setting. A failure is logged; the defaults from the schema stay. */
    public static void prepare(Context c) {
        try {
            Tree.prepare(c);
            seed(c);
        } catch (Throwable t) {
            android.util.Log.e("pdash", "could not prepare the tree", t);
        }
        init(c);
    }

    /** Write a setting through the one validating writer, {@code bin/config set}.
     *  @return null, or why it was refused. */
    public static String set(Context c, String key, String value) {
        File r = Tree.root(c);
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh",
                    new File(r, "bin/config").getPath(), "set", key, value);
            pb.environment().put("DN_INSTDIR", r.getPath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) out.append(line).append('\n');
            }
            if (p.waitFor() == 0) {
                // The watcher follows too, a moment later; read now so the caller sees it.
                read();
                return null;
            }
            String msg = out.toString().trim();
            return msg.startsWith("config: ") ? msg.substring(8) : msg;
        } catch (IOException | InterruptedException e) {
            return e.toString();
        }
    }

    /** Read the schema and every setting, and start watching. Safe to call again. */
    public static synchronized void init(Context c) {
        if (root != null) return;
        root = Tree.root(c);
        read();
        watch();
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The schema's row for a key: type, default, constraint; null if unknown. */
    public static String[] schema(String key) {
        return schema.get(key);
    }

    // --- values -----------------------------------------------------------------

    public static String get(String key) {
        String v = values.get(key);
        if (v != null) return v;
        String[] s = schema.get(key);
        return s == null ? "" : s[1];
    }

    public static int getInt(String key) {
        try {
            return Integer.parseInt(get(key));
        } catch (NumberFormatException e) {
            return Integer.parseInt(schema.get(key)[1]);
        }
    }

    public static boolean getBool(String key) {
        return "true".equals(get(key));
    }

    public static int getColor(String key) {
        return (int) (0xFF000000L | Long.parseLong(get(key).substring(1), 16));
    }

    // --- reading ----------------------------------------------------------------

    private static synchronized void read() {
        Map<String, String[]> sc = new LinkedHashMap<>();
        String text = slurp(new File(root, "lib/config.schema"));
        if (text != null) {
            for (String line : text.split("\n", -1)) {
                if (line.length() == 0 || line.charAt(0) == '#') continue;
                String[] f = line.split("\t", -1);
                if (f.length < 4 || f[1].equals("family")) continue;
                sc.put(f[0], new String[] { f[1], f[2], f[3] });
            }
        }
        schema = sc;

        Map<String, String> vs = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : sc.entrySet()) {
            String key = e.getKey();
            String[] s = e.getValue();
            File f = new File(root, "etc/" + key.replace('.', '/'));
            String v = f.isFile() ? firstLine(slurp(f)) : null;
            if (v == null) { vs.put(key, s[1]); continue; }
            String why = invalid(s[0], s[2], v);
            if (why != null) {
                String msg = "etc/" + key.replace('.', '/') + ": " + why + "; using the default";
                if (!msg.equals(logged.put(key, msg))) log(msg);   // once, not on every reload
                v = s[1];
            } else {
                logged.remove(key);
            }
            vs.put(key, v);
        }
        values = vs;
    }

    /** Why a value is not valid for its type, or null. Mirrors `config set`. */
    private static String invalid(String type, String constraint, String v) {
        switch (type) {
            case "int":
                try {
                    int n = Integer.parseInt(v);
                    if (constraint.length() > 0) {
                        String[] r = constraint.split("-");
                        if (n < Integer.parseInt(r[0]) || n > Integer.parseInt(r[1])) {
                            return "out of range " + constraint;
                        }
                    }
                } catch (NumberFormatException e) {
                    return "not a whole number";
                }
                return null;
            case "bool":
                return v.equals("true") || v.equals("false") ? null : "must be true or false";
            case "enum":
                for (String x : constraint.split("\\|")) if (x.equals(v)) return null;
                return "must be one of " + constraint;
            case "color":
                return v.matches("#[0-9a-fA-F]{6}") ? null : "must be a colour like #rrggbb";
            default:
                return null;
        }
    }

    // --- watching ---------------------------------------------------------------

    /** Watch etc/ and each directory under it. FileObserver is not recursive, so
     *  the set is rebuilt after every change (a new directory appears that way). */
    private static synchronized void watch() {
        for (FileObserver o : observers) o.stopWatching();
        observers.clear();
        File etc = new File(root, "etc");
        List<File> dirs = new ArrayList<>();
        dirs.add(etc);
        File[] sub = etc.listFiles();
        if (sub != null) for (File f : sub) if (f.isDirectory()) dirs.add(f);
        for (File d : dirs) {
            FileObserver o = new FileObserver(d.getPath(), WATCH) {
                @Override public void onEvent(int event, String path) {
                    if (path != null && path.endsWith(".new")) return;   // a write in progress
                    MAIN.removeCallbacks(RELOAD);
                    MAIN.postDelayed(RELOAD, 100);
                }
            };
            o.startWatching();
            observers.add(o);
        }
    }

    // --- small helpers ----------------------------------------------------------

    private static String firstLine(String s) {
        if (s == null) return null;
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String slurp(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    static void log(String msg) {
        try (FileOutputStream o = new FileOutputStream(new File(root, "var/log/dn.log"), true)) {
            o.write(("config: " + msg + "\n").getBytes("UTF-8"));
        } catch (IOException ignore) {}
    }
}
