package jackpal.androidterm.emulatorview;

import java.util.Arrays;

/**
 * Finds the http(s)/www links in a screen line. Used twice: the renderer
 * underlines and recolours them, and a tap opens the one under the finger. The
 * word-selection helper cannot be reused for this — it stops at ':' and would
 * cut "https:" off.
 */
final class Links {

    private Links() {}

    private static final String[] SCHEMES = { "https://", "http://", "www." };

    /** One char per screen column, so columns index directly (a wide character
     *  occupies its lead column and leaves the following one blank). */
    private static char[] cells(char[] line, int columns) {
        char[] cells = new char[columns];
        Arrays.fill(cells, ' ');
        if (line == null) return cells;
        int col = 0;
        for (int i = 0; i < line.length && col < columns && line[i] != '\0'; ) {
            int incr = 1;
            int width;
            if (Character.isHighSurrogate(line[i])) {
                width = UnicodeTranscript.charWidth(line, i);
                incr = 2;
            } else {
                width = UnicodeTranscript.charWidth(line[i]);
            }
            if (width > 0) {
                cells[col] = line[i];
                col += width;
            }
            i += incr;
        }
        return cells;
    }

    /** Per-column flag: is this column part of a link? */
    static boolean[] mask(char[] line, int columns) {
        boolean[] out = new boolean[columns];
        char[] c = cells(line, columns);
        for (int i = 0; i < columns; i++) {
            int start = schemeAt(c, i, columns);
            if (start < 0) continue;
            int end = spanEnd(c, start, columns);
            for (int k = start; k < end; k++) out[k] = true;
            i = end;
        }
        return out;
    }

    /** The link covering a screen column, or null. */
    static String at(char[] line, int columns, int column) {
        if (column < 0 || column >= columns) return null;
        char[] c = cells(line, columns);
        for (int i = 0; i < columns; i++) {
            int start = schemeAt(c, i, columns);
            if (start < 0) continue;
            int end = spanEnd(c, start, columns);
            if (column >= start && column < end) return new String(c, start, end - start);
            i = end;
        }
        return null;
    }

    private static int schemeAt(char[] c, int i, int columns) {
        for (String s : SCHEMES) {
            if (i + s.length() > columns) continue;
            boolean ok = true;
            for (int k = 0; k < s.length(); k++) {
                if (c[i + k] != s.charAt(k)) { ok = false; break; }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static int spanEnd(char[] c, int start, int columns) {
        int end = start;
        while (end < columns && !ends(c[end])) end++;
        while (end > start && trailing(c[end - 1])) end--;
        return end;
    }

    private static boolean ends(char ch) {
        return ch == ' ' || ch == '\t' || ch == '"' || ch == '\'' || ch == '<'
                || ch == '>' || ch == ')' || ch == ']' || ch == '}' || ch == '|'
                || ch == '`' || ch == '\0';
    }

    /** Punctuation that usually belongs to the sentence, not to the link. */
    private static boolean trailing(char ch) {
        return ch == '.' || ch == ',' || ch == ';' || ch == ':';
    }
}
