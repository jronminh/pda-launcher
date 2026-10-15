package org.pda.launcher.files;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copy, move and delete, one job at a time on a background thread, after
 * DocumentsUI's FileOperationService jobs (CopyJob, MoveJob, DeleteJob). A
 * name that is taken gets " (1)", " (2)"... as DocumentsUI's providers do. A
 * symlink is copied as a link and deleted as a link: its target is never
 * touched. Every path that changed is handed to the media scanner, so the
 * media roots follow, and {@link Favorites} follow a move, a rename or a delete.
 */
public final class FileOps {

    interface Progress {
        /** On the main thread: {@code done} of {@code total} top-level items. */
        void onProgress(int done, int total);

        /** On the main thread: finished; {@code error} is null on success. */
        void onDone(String error);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "files-ops");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FileOps() {}

    static void copy(Context c, List<File> items, File into, boolean move, Progress p) {
        final Context app = c.getApplicationContext();
        run(c, p, items.size(), (changed, step) -> {
            for (File src : items) {
                if (isInside(into, src)) throw new IOException("cannot put " + src.getName() + " inside itself");
                File parent = src.getParentFile();
                if (move && parent != null && parent.equals(into)) { step.run(); continue; }   // already here
                File dst = unique(into, src.getName());
                if (move && src.renameTo(dst)) {           // the same volume: a rename
                    changed.add(src);
                    changed.add(dst);
                } else {
                    copyTree(src, dst, changed);
                    if (move) deleteTree(src, changed);
                }
                if (move) Favorites.moved(app, src, dst);
                step.run();
            }
        });
    }

    /** Moves one thing, as a rename on the same volume, else a copy and a delete. */
    interface Mover {
        void move(File from, File to) throws IOException;
    }

    private static Mover mover(List<File> changed) {
        return (from, to) -> {
            if (from.renameTo(to)) {
                changed.add(from);
                changed.add(to);
            } else {
                copyTree(from, to, changed);
                deleteTree(from, changed);
            }
        };
    }

    /** Delete as the user means it: into the trash ({@link Trash}), back with Restore. */
    static void trash(Context c, List<File> items, Progress p) {
        final Context app = c.getApplicationContext();
        run(c, p, items.size(), (changed, step) -> {
            Mover m = mover(changed);
            for (File f : items) {
                Trash.put(f, m);
                Favorites.deleted(app, f);
                step.run();
            }
        });
    }

    /** One file into the trash from outside Files (a viewer's Delete); {@code done} gets the error, or null. */
    public static void trash(Context c, File f, java.util.function.Consumer<String> done) {
        trash(c, java.util.Collections.singletonList(f), new Progress() {
            @Override public void onProgress(int d, int total) { }
            @Override public void onDone(String error) { done.accept(error); }
        });
    }

    /** Things in the trash back where they came from. */
    static void restore(Context c, List<Trash.Entry> entries, Progress p) {
        run(c, p, entries.size(), (changed, step) -> {
            Mover m = mover(changed);
            for (Trash.Entry e : entries) {
                Trash.restore(e, m);
                step.run();
            }
        });
    }

    static void delete(Context c, List<File> items, Progress p) {
        final Context app = c.getApplicationContext();
        run(c, p, items.size(), (changed, step) -> {
            for (File f : items) {
                deleteTree(f, changed);
                Favorites.deleted(app, f);
                step.run();
            }
        });
    }

    /** A rename in place, on the main thread (one call, no copying). */
    static String rename(Context c, File f, String name) {
        String why = badName(name);
        if (why != null) return why;
        File dst = new File(f.getParentFile(), name);
        if (dst.exists()) return "\"" + name + "\" already exists";
        if (!f.renameTo(dst)) return "could not rename " + f.getName();
        scan(c, f, dst);
        Favorites.moved(c, f, dst);
        return null;
    }

    static String mkdir(File in, String name) {
        String why = badName(name);
        if (why != null) return why;
        File d = new File(in, name);
        if (d.exists()) return "\"" + name + "\" already exists";
        return d.mkdir() ? null : "could not create " + name;
    }

    static String badName(String name) {
        if (name == null || name.trim().isEmpty()) return "a name is needed";
        if (name.contains("/") || name.equals(".") || name.equals("..")) return "not a valid name";
        return null;
    }

    // --- the work -------------------------------------------------------------------

    private interface Job {
        void run(List<File> changed, Runnable step) throws IOException;
    }

    private static void run(Context c, Progress p, int total, Job job) {
        final Context app = c.getApplicationContext();
        WORKER.execute(() -> {
            List<File> changed = new ArrayList<>();
            int[] done = { 0 };
            String error = null;
            try {
                job.run(changed, () -> {
                    int d = ++done[0];
                    MAIN.post(() -> p.onProgress(d, total));
                });
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            scan(app, changed.toArray(new File[0]));
            final String err = error;
            MAIN.post(() -> p.onDone(err));
        });
    }

    private static void copyTree(File src, File dst, List<File> changed) throws IOException {
        if (Files.isSymbolicLink(src.toPath())) {
            Files.createSymbolicLink(dst.toPath(), Files.readSymbolicLink(src.toPath()));
        } else if (src.isDirectory()) {
            if (!dst.mkdir()) throw new IOException("could not create " + dst.getName());
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dst, k.getName()), changed);
        } else {
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                dst.delete();   // no half file left behind
                throw new IOException("could not copy " + src.getName() + ": " + e.getMessage());
            }
            dst.setLastModified(src.lastModified());
        }
        changed.add(dst);
    }

    private static void deleteTree(File f, List<File> changed) throws IOException {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k, changed);
        }
        if (!f.delete() && f.exists()) throw new IOException("could not delete " + f.getName());
        changed.add(f);
    }

    /** {@code name} in {@code dir}, or "name (1).ext", "name (2).ext"... if taken. */
    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists() && !isLink(f)) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists() && !isLink(f)) return f;
        }
    }

    private static boolean isLink(File f) {
        return Files.isSymbolicLink(f.toPath());
    }

    /** Whether {@code dir} is {@code item} or lies inside it. */
    private static boolean isInside(File dir, File item) {
        try {
            String d = dir.getCanonicalPath(), i = item.getCanonicalPath();
            return d.equals(i) || d.startsWith(i + "/");
        } catch (IOException e) {
            return false;
        }
    }

    private static void scan(Context c, File... files) {
        if (files.length == 0) return;
        String[] paths = new String[files.length];
        for (int i = 0; i < files.length; i++) paths[i] = files[i].getAbsolutePath();
        MediaScannerConnection.scanFile(c, paths, null, null);
    }
}
