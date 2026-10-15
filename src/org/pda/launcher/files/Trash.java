package org.pda.launcher.files;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Files tab's trash: {@code .Trash} at the top of shared storage, hidden,
 * with a {@code .nomedia} so the media lists (Recent, Images...) leave it out.
 * Each thing deleted gets a folder of its own, named by when it was deleted:
 * {@code .Trash/<millis>-<n>/} holds the thing under its own name and a file
 * {@code .origin} with the path it came from. On the same volume putting it
 * there is a rename: instant, whatever its size. Restoring renames it back
 * (as "name (1)" if the place is taken). Things older than the setting
 * (files.trashDays) go for good when the Files tab next shows its first screen.
 */
final class Trash {

    private static final String ORIGIN = ".origin";

    private Trash() {}

    static File dir() {
        return new File(Roots.storage(), ".Trash");
    }

    /** Whether {@code f} lies in the trash. */
    static boolean contains(File f) {
        String t = dir().getAbsolutePath() + "/";
        return f.getAbsolutePath().startsWith(t);
    }

    /** One thing in the trash: the thing itself, where it came from, when it was deleted. */
    static final class Entry {
        final File file;
        final String origin;
        final long deleted;

        Entry(File file, String origin, long deleted) {
            this.file = file;
            this.origin = origin;
            this.deleted = deleted;
        }
    }

    /** Everything in the trash, the last deleted first. */
    static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        File[] slots = dir().listFiles();
        if (slots == null) return out;
        for (File slot : slots) {
            Entry e = entryOf(slot);
            if (e != null) out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.deleted, a.deleted));
        return out;
    }

    /** The entry a slot (or the thing in it) stands for, or null. */
    static Entry entryOf(File slotOrItem) {
        File slot = slotOrItem.getParentFile() != null && slotOrItem.getParentFile().getParentFile() != null
                && slotOrItem.getParentFile().getParentFile().equals(dir()) ? slotOrItem.getParentFile() : slotOrItem;
        if (!slot.isDirectory()) return null;
        File[] kids = slot.listFiles();
        if (kids == null) return null;
        File item = null;
        for (File k : kids) if (!k.getName().equals(ORIGIN)) item = k;
        if (item == null) return null;
        String origin = new File(Roots.storage(), item.getName()).getPath();
        try {
            origin = new String(Files.readAllBytes(new File(slot, ORIGIN).toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException ignore) {}
        long when;
        try {
            when = Long.parseLong(slot.getName().split("-")[0]);
        } catch (NumberFormatException e) {
            when = slot.lastModified();
        }
        return new Entry(item, origin, when);
    }

    /** Put {@code f} in the trash; returns where it is now. A move across volumes copies. */
    static File put(File f, FileOps.Mover move) throws IOException {
        File d = dir();
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("could not make the trash folder");
        File nomedia = new File(d, ".nomedia");
        if (!nomedia.exists()) nomedia.createNewFile();
        File slot;
        int n = 0;
        do {
            slot = new File(d, System.currentTimeMillis() + "-" + n++);
        } while (slot.exists());
        if (!slot.mkdir()) throw new IOException("could not make room in the trash");
        try (OutputStream o = new FileOutputStream(new File(slot, ORIGIN))) {
            o.write(f.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        }
        File dst = new File(slot, f.getName());
        move.move(f, dst);
        return dst;
    }

    /** Put an entry back where it came from; returns where it went. */
    static File restore(Entry e, FileOps.Mover move) throws IOException {
        File to = new File(e.origin);
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not make " + parent.getPath());
        }
        to = FileOps.unique(parent, to.getName());
        move.move(e.file, to);
        drop(e.file.getParentFile());
        return to;
    }

    /** Remove a slot (what is left of it: its .origin) once its thing is gone. */
    static void drop(File slot) {
        if (slot == null || !slot.getParentFile().equals(dir())) return;
        new File(slot, ORIGIN).delete();
        slot.delete();
    }

    /** The slots older than {@code days}. */
    static List<File> expired(int days) {
        List<File> out = new ArrayList<>();
        long cut = System.currentTimeMillis() - days * 86_400_000L;
        for (Entry e : entries()) if (e.deleted < cut) out.add(e.file.getParentFile());
        return out;
    }
}
