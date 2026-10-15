package org.pda.launcher.today;

import android.os.Environment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Today tab's files, plain text in one folder of shared storage, so that
 * Syncthing, a laptop, Termux (todo.sh) and an AI can read and change them too:
 *
 * <ul>
 *   <li>{@code todo.txt}: one task a line, in the todo.txt format: "x " and a date
 *       when done, "(A) " for a priority, a creation date, "due:YYYY-MM-DD".</li>
 *   <li>{@code inbox.md}: quick captures, "- YYYY-MM-DD HH:MM text", newest last.</li>
 *   <li>{@code brief.md}: read only, written by something else (a morning brief).</li>
 * </ul>
 *
 * Every change reads the file again, finds its line by content and writes the
 * whole file atomically (a temporary file, then a rename), so an edit made
 * elsewhere meanwhile is kept rather than overwritten by a stale copy.
 */
public final class PdaStore {

    private static final Pattern DUE = Pattern.compile("(?:^|\\s)due:(\\d{4}-\\d{2}-\\d{2})(?=\\s|$)");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} ");
    private static final Pattern PRI = Pattern.compile("^\\(([A-Z])\\) ");

    private PdaStore() {}

    /** The folder of the files: today.folder, under shared storage unless absolute. */
    public static File folder() {
        String f = org.pda.launcher.Config.get("today.folder").trim();
        if (f.isEmpty()) f = "Documents/PDA";
        return f.startsWith("/") ? new File(f) : new File(Environment.getExternalStorageDirectory(), f);
    }

    static File todo() {
        return new File(folder(), "todo.txt");
    }

    static File inbox() {
        return new File(folder(), "inbox.md");
    }

    static File brief() {
        return new File(folder(), "brief.md");
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    // --- tasks -------------------------------------------------------------------------------

    /** One line of todo.txt, read. */
    public static final class Task {
        public final String raw;
        public final boolean done;
        public final char priority;   // 'A'..'Z', or 0
        public final String text;     // without the done mark, priority, dates and due:
        public final String due;      // YYYY-MM-DD, or null

        Task(String raw) {
            this.raw = raw;
            String s = raw;
            boolean d = false;
            if (s.startsWith("x ")) {
                d = true;
                s = s.substring(2);
                if (DATE.matcher(s).find()) s = s.substring(11);   // the completion date
            }
            char p = 0;
            Matcher pm = PRI.matcher(s);
            if (pm.find()) {
                p = pm.group(1).charAt(0);
                s = s.substring(4);
            }
            if (DATE.matcher(s).find()) s = s.substring(11);       // the creation date
            Matcher dm = DUE.matcher(s);
            String due = null;
            if (dm.find()) {
                due = dm.group(1);
                s = (s.substring(0, dm.start()) + s.substring(dm.end())).trim();
            }
            done = d;
            priority = p;
            text = s.trim();
            this.due = due;
        }
    }

    /** The tasks: open ones first, by priority, then by due date, then in the file's order. */
    public static List<Task> tasks() {
        List<Task> out = new ArrayList<>();
        for (String l : lines(todo())) if (!l.trim().isEmpty()) out.add(new Task(l));
        List<Task> sorted = new ArrayList<>(out);
        Collections.sort(sorted, (a, b) -> {
            if (a.done != b.done) return a.done ? 1 : -1;
            int pa = a.priority == 0 ? 'Z' + 1 : a.priority, pb = b.priority == 0 ? 'Z' + 1 : b.priority;
            if (pa != pb) return Integer.compare(pa, pb);
            if (a.due != null || b.due != null) {
                if (a.due == null) return 1;
                if (b.due == null) return -1;
                int c = a.due.compareTo(b.due);
                if (c != 0) return c;
            }
            return Integer.compare(out.indexOf(a), out.indexOf(b));
        });
        return sorted;
    }

    /** A new task; a leading "(A) " stays its priority. Today is its creation date. */
    public static void addTask(String text) throws IOException {
        String t = text.trim();
        if (t.isEmpty()) return;
        String line;
        Matcher pm = PRI.matcher(t);
        if (pm.find()) line = t.substring(0, 4) + today() + " " + t.substring(4);
        else line = today() + " " + t;
        List<String> all = lines(todo());
        all.add(line);
        write(todo(), all);
    }

    public static void setDone(Task t, boolean done) throws IOException {
        replace(todo(), t.raw, done ? "x " + today() + " " + t.raw
                : t.raw.replaceFirst("^x (\\d{4}-\\d{2}-\\d{2} )?", ""));
    }

    public static void deleteTask(Task t) throws IOException {
        replace(todo(), t.raw, null);
    }

    /** Change a task's words; its marks, priority, dates and due: stay. */
    public static void editTask(Task t, String text) throws IOException {
        String words = text.trim().replace('\n', ' ');
        String line;
        if (!t.text.isEmpty() && t.raw.contains(t.text)) {
            line = t.raw.replace(t.text, words);   // the words sit in one piece: swap them
        } else {
            line = (t.done ? "x " : "") + (t.priority != 0 ? "(" + t.priority + ") " : "")
                    + words + (t.due != null ? " due:" + t.due : "");
        }
        replace(todo(), t.raw, line);
    }

    // --- the inbox ---------------------------------------------------------------------------

    /** One capture: when, and what. */
    public static final class Note {
        public final String raw, when, text;

        Note(String raw) {
            this.raw = raw;
            String s = raw.startsWith("- ") ? raw.substring(2) : raw;
            String w = "";
            if (s.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} .*")) {
                w = s.substring(0, 16);
                s = s.substring(17);
            }
            when = w;
            text = s;
        }
    }

    /** The captures, newest first. */
    public static List<Note> inboxNotes() {
        List<Note> out = new ArrayList<>();
        for (String l : lines(inbox())) if (!l.trim().isEmpty()) out.add(new Note(l));
        Collections.reverse(out);
        return out;
    }

    public static void capture(String text) throws IOException {
        String t = text.trim().replace('\n', ' ');
        if (t.isEmpty()) return;
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());
        List<String> all = lines(inbox());
        all.add("- " + when + " " + t);
        write(inbox(), all);
    }

    public static void deleteNote(Note n) throws IOException {
        replace(inbox(), n.raw, null);
    }

    /** A capture becomes a task: added to todo.txt, gone from the inbox. */
    public static void toTask(Note n) throws IOException {
        addTask(n.text);
        deleteNote(n);
    }

    /** The brief, as written; empty if there is none. */
    public static String brief(int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines(brief())) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
            if (sb.length() > maxChars) break;
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) + "…" : sb.toString();
    }

    // --- files -------------------------------------------------------------------------------

    static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String s = b.toString("UTF-8");
            for (String l : s.split("\n", -1)) out.add(l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
            if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        } catch (IOException ignore) {
        }
        return out;
    }

    /** The first line equal to {@code old} becomes {@code line} (null: removed). */
    private static void replace(File f, String old, String line) throws IOException {
        List<String> all = lines(f);
        int at = all.indexOf(old);
        if (at < 0) throw new IOException("the line changed meanwhile; read again");
        if (line == null) all.remove(at);
        else all.set(at, line);
        write(f, all);
    }

    static void write(File f, List<String> lines) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(sb.toString().getBytes("UTF-8"));
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("cannot write " + f.getName());
        }
    }
}
