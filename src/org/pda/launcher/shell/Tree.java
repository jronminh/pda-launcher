package org.pda.launcher.shell;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The FHS tree in {@code files/} (docs/shell.md).
 *
 * <p>The <b>shipped</b> part is the asset directory {@code tree/}. It is copied
 * into {@code files/} when the installed build changes, and files that were
 * shipped before but are not any more are removed. Everything else in
 * {@code files/} is the user's and is never touched. The list of what was
 * shipped is kept in {@code var/shipped}, the build it came from in
 * {@code var/shipped.build}.
 */
final class Tree {

    private static final String ASSET_ROOT = "tree";

    /** Directories that exist from the first run (the user's included). */
    private static final String[] DIRS = {
        "bin", "lib", "etc", "etc/profile.d", "usr/local/bin",
        "home", "tmp", "var", "var/log", "var/sessions", "mnt",
    };

    private Tree() {}

    static File root(Context c) {
        return c.getFilesDir();
    }

    /** Create the directories and bring the shipped files up to date. */
    static void prepare(Context c) throws IOException {
        File root = root(c);
        for (String d : DIRS) {
            File f = new File(root, d);
            if (!f.isDirectory() && !f.mkdirs()) throw new IOException("mkdir " + f);
        }
        File build = new File(root, "var/shipped.build");
        String now = buildId(c);
        if (now.equals(read(build))) return;

        List<String> shipped = new ArrayList<>();
        list(c.getAssets(), ASSET_ROOT, "", shipped);
        Collections.sort(shipped);

        // Remove what an earlier build shipped and this one does not.
        Set<String> keep = new HashSet<>(shipped);
        String old = read(new File(root, "var/shipped"));
        if (old != null) {
            for (String p : old.split("\n")) {
                // var/shipped is a file in the user's reach: stay inside the tree.
                if (p.length() > 0 && !p.startsWith("/") && !p.contains("..")
                        && !keep.contains(p)) {
                    new File(root, p).delete();
                }
            }
        }
        for (String p : shipped) {
            File dst = new File(root, p);
            File dir = dst.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
            copy(c.getAssets().open(ASSET_ROOT + "/" + p), dst);
            // bin/ holds the commands; the rest of what is shipped is data.
            dst.setExecutable(p.startsWith("bin/"), false);
            dst.setReadable(true, false);
        }
        StringBuilder sb = new StringBuilder();
        for (String p : shipped) sb.append(p).append('\n');
        write(new File(root, "var/shipped"), sb.toString());
        write(build, now);
    }

    /** A new install, an update and a rebuild with the same version all differ. */
    private static String buildId(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode + ":" + pi.lastUpdateTime;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** Collect the asset files under {@code dir}, as paths relative to the root. */
    private static void list(AssetManager am, String dir, String rel, List<String> out)
            throws IOException {
        String[] names = am.list(dir);
        if (names == null) return;
        for (String n : names) {
            String path = dir + "/" + n;
            String r = rel.length() == 0 ? n : rel + "/" + n;
            String[] sub = am.list(path);
            if (sub != null && sub.length > 0) list(am, path, r, out);
            else out.add(r);
        }
    }

    private static void copy(InputStream in, File dst) throws IOException {
        File tmp = new File(dst.getPath() + ".new");
        try (InputStream i = in; OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
        if (!tmp.renameTo(dst)) throw new IOException("rename " + tmp);
    }

    private static String read(File f) {
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

    private static void write(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes("UTF-8"));
        }
    }
}
