package org.pda.launcher.remote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * A place Files reaches over a protocol or through another app: a folder
 * tree of entries named by paths. One operation at a time; a stream from
 * {@link #read} or {@link #write} holds the place until it is closed. Plain
 * Java (no android.*), so the network ones are tried with a desktop JVM.
 */
public interface RemoteFs extends Closeable {

    final class Entry {
        public final String path, name;
        public final boolean dir;
        public final long size, modified;   // bytes (-1: unknown), millis (0: unknown)

        public Entry(String path, String name, boolean dir, long size, long modified) {
            this.path = path;
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    /** Where browsing starts. */
    String home() throws IOException;

    /** A folder's entries, folders first, then by name. */
    List<Entry> list(String dir) throws IOException;

    InputStream read(String path) throws IOException;

    /** A new file {@code name} in the folder {@code dir}, or that file replaced. */
    OutputStream write(String dir, String name) throws IOException;

    /** A file, or a folder with all it holds. */
    void delete(Entry e) throws IOException;

    /** The entry renamed in its folder. */
    void rename(Entry e, String name) throws IOException;

    /** A new folder {@code name} in {@code dir}. */
    void mkdir(String dir, String name) throws IOException;

    /** {@code name} inside the folder {@code dir}. */
    static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** The folder a path is in ("/" at the top). */
    static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    /** Folders first, then by name, as Files lists. */
    static void sort(List<Entry> list) {
        list.sort((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
    }

    /** A host's key or certificate is not the one met before: maybe another machine. */
    final class KeyChanged extends IOException {
        public final String expected, actual;

        public KeyChanged(String expected, String actual) {
            super("the server's key changed (" + actual + ", was " + expected + ")");
            this.expected = expected;
            this.actual = actual;
        }
    }

    /** Remembers a server's key the first time (trust on first use). */
    interface Pinning {
        /** The fingerprint met before, or null the first time. */
        String known();

        void remember(String fingerprint);
    }
}
