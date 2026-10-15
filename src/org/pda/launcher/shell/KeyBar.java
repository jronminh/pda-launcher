package org.pda.launcher.shell;

import org.pda.launcher.Ui;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import jackpal.androidterm.emulatorview.EmulatorView;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The extra-keys bar shown under the terminal. Buttons dispatch hardware-like
 * {@link KeyEvent}s into the {@link EmulatorView}, so the vendored
 * {@code TermKeyListener} produces the right escape codes (including cursor
 * application mode).
 *
 * A token is one of:
 *   - a named key: esc tab enter backspace del ins home end pgup pgdn
 *     up down left right space f1..f12
 *   - a sticky modifier: ctrl alt shift
 *   - a one-tap combo: ctrl+c, alt+f, ctrl+alt+del (modifiers + a key)
 *   - raw text: text:HELLO, text:%0A (the part after `text:` is percent-encoded)
 *   - an empty token: a gap
 *
 * Rows are separated by `|` and tokens by `,` in the `keybar` setting.
 */
public class KeyBar extends LinearLayout implements View.OnClickListener {

    private static final int BAR_BG = Ui.BG;
    private static final int BTN_ON = 0xFF333333;   // a sticky modifier that is on
    private static final int TEXT = 0xFFD0D0D0;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** What the bar calls when a raw-text key is tapped. */
    public interface Sink { void sendText(String s); }

    private final EmulatorView term;
    private final Sink sink;
    private final int cellDp;

    private boolean ctrl, alt, shift;
    private Button ctrlBtn, altBtn, shiftBtn;

    public KeyBar(Context context, EmulatorView term, Sink sink,
                  List<List<String>> rows, int heightLevel) {
        super(context);
        this.term = term;
        this.sink = sink;
        this.cellDp = heightLevel <= 1 ? 32 : (heightLevel >= 3 ? 52 : 40);
        setOrientation(VERTICAL);
        setBackgroundColor(BAR_BG);
        for (List<String> keys : rows) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            for (String k : keys) {
                row.addView((k == null || k.length() == 0) ? spacer() : button(k));
            }
            addView(row);
        }
    }

    private View spacer() {
        View v = new View(getContext());
        v.setLayoutParams(cellParams());
        return v;
    }

    private Button button(String k) {
        Button b = new Button(getContext());
        b.setLayoutParams(cellParams());
        b.setText(label(k));
        b.setTextSize(13);
        b.setTextColor(TEXT);
        b.setBackground(Ui.outline(getContext(), Ui.LINE));
        b.setStateListAnimator(null);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0, 0, 0, 0);
        // Keep the terminal focused so tapping the bar does not hide the IME.
        b.setFocusable(false);
        b.setFocusableInTouchMode(false);
        b.setTag(k);
        b.setOnClickListener(this);
        if (k.equals("ctrl")) ctrlBtn = b;
        else if (k.equals("alt")) altBtn = b;
        else if (k.equals("shift")) shiftBtn = b;
        return b;
    }

    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, dp(cellDp), 1f);
        int m = dp(2);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    @Override public void onClick(View v) {
        press((String) v.getTag());
    }

    private void press(String k) {
        if (k.equals("ctrl")) { toggleCtrl(); return; }
        if (k.equals("alt")) { toggleAlt(); return; }
        if (k.equals("shift")) { toggleShift(); return; }

        if (k.startsWith("text:")) {
            String s = decode(k.substring(5));
            if (sink != null) sink.sendText(s);
            clearMods();
            term.requestFocus();
            return;
        }

        String base = k;
        if (k.indexOf('+') >= 0) {
            String[] p = k.split("\\+");
            for (int i = 0; i < p.length - 1; i++) {
                String m = p[i].trim();
                if (m.equals("ctrl")) ctrl = true;
                else if (m.equals("alt")) alt = true;
                else if (m.equals("shift")) shift = true;
            }
            base = p[p.length - 1].trim();
        }

        int kc = keycode(base);
        if (kc == 0) { clearMods(); return; }

        if (ctrl) term.sendControlKey();
        int meta = 0;
        if (alt) meta |= KeyEvent.META_ALT_ON;
        if (shift) meta |= KeyEvent.META_SHIFT_ON;
        long t = System.currentTimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, kc, 0, meta);
        KeyEvent up = new KeyEvent(t, t, KeyEvent.ACTION_UP, kc, 0, meta);
        term.onKeyDown(kc, down);
        term.onKeyUp(kc, up);

        clearMods();
        term.requestFocus();
    }

    private void toggleCtrl() {
        ctrl = !ctrl;
        if (ctrl) term.sendControlKey();
        else term.releaseControlKey();
        highlight(ctrlBtn, ctrl);
    }

    private void toggleAlt() {
        alt = !alt;
        term.setAltSendsEsc(true);
        highlight(altBtn, alt);
    }

    private void toggleShift() {
        shift = !shift;
        highlight(shiftBtn, shift);
    }

    private void clearMods() {
        ctrl = alt = shift = false;
        highlight(ctrlBtn, false);
        highlight(altBtn, false);
        highlight(shiftBtn, false);
    }

    private void highlight(Button b, boolean on) {
        if (b == null) return;
        android.graphics.drawable.GradientDrawable d = Ui.outline(getContext(), on ? Ui.TEXT : Ui.LINE);
        if (on) d.setColor(BTN_ON);
        b.setBackground(d);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    // --- parsing / labels (also used by the editor) --------------------------

    /** Parse the `keybar` setting into rows (`|` between rows, `,` between keys,
     *  a blank key is a gap); an empty value gives the built-in two rows. */
    public static List<List<String>> parse(String v) {
        List<List<String>> rows = new ArrayList<List<String>>();
        if (v != null && v.trim().length() > 0) {
            for (String r : v.split("\\|", -1)) {
                List<String> row = new ArrayList<String>();
                for (String k : r.split(",", -1)) {
                    String kk = k.trim();
                    row.add(kk.length() == 0 ? null : kk);
                }
                rows.add(row);
            }
            return rows;
        }
        rows.add(new ArrayList<String>(Arrays.asList(
                "esc", "shift", null, "home", "up", "end", "pgup")));
        rows.add(new ArrayList<String>(Arrays.asList(
                "tab", "ctrl", "alt", "left", "down", "right", "pgdn")));
        return rows;
    }

    /** The `keybar` setting text for these rows. */
    public static String serialize(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append('|');
            List<String> row = rows.get(i);
            for (int j = 0; j < row.size(); j++) {
                if (j > 0) sb.append(',');
                String k = row.get(j);
                sb.append(k == null ? "" : k);
            }
        }
        return sb.toString();
    }

    public static String encode(String s) {
        StringBuilder b = new StringBuilder();
        for (byte by : s.getBytes(UTF8)) {
            int c = by & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                b.append((char) c);
            } else {
                b.append('%');
                String h = Integer.toHexString(c).toUpperCase();
                if (h.length() < 2) b.append('0');
                b.append(h);
            }
        }
        return b.toString();
    }

    public static String decode(String s) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    o.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                } catch (Throwable t) { o.write(c); }
            } else {
                o.write(c);
            }
        }
        return new String(o.toByteArray(), UTF8);
    }

    /** What a token shows on its button. */
    public static String label(String k) {
        if (k == null || k.length() == 0) return "";
        if (k.startsWith("text:")) {
            String s = decode(k.substring(5));
            return s.length() == 0 ? "Text" : (s.length() <= 4 ? s : "Text");
        }
        if (k.indexOf('+') >= 0) {
            String[] p = k.split("\\+");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) b.append('+');
                String x = p[i].trim();
                if (x.equals("ctrl")) b.append("Ctrl");
                else if (x.equals("alt")) b.append("Alt");
                else if (x.equals("shift")) b.append("Shift");
                else b.append(x.length() == 1 ? x.toUpperCase() : x);
            }
            return b.toString();
        }
        if (k.equals("esc")) return "Esc";
        if (k.equals("shift")) return "Shift";
        if (k.equals("ctrl")) return "Ctrl";
        if (k.equals("alt")) return "Alt";
        if (k.equals("tab")) return "Tab";
        if (k.equals("enter")) return "Enter";
        if (k.equals("backspace")) return "Bksp";
        if (k.equals("del")) return "Del";
        if (k.equals("ins")) return "Ins";
        if (k.equals("home")) return "Home";
        if (k.equals("end")) return "End";
        if (k.equals("up")) return "\u25B2";
        if (k.equals("down")) return "\u25BC";
        if (k.equals("left")) return "\u25C0";
        if (k.equals("right")) return "\u25B6";
        if (k.equals("pgup")) return "PgUp";
        if (k.equals("pgdn")) return "PgDn";
        if (k.equals("space")) return "Space";
        if (k.length() == 1) return k.toUpperCase();
        return k;
    }

    private static int keycode(String k) {
        if (k.length() == 1) {
            char c = k.charAt(0);
            if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
            if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
            if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
            return 0;
        }
        if (k.equals("esc")) return KeyEvent.KEYCODE_ESCAPE;
        if (k.equals("tab")) return KeyEvent.KEYCODE_TAB;
        if (k.equals("enter")) return KeyEvent.KEYCODE_ENTER;
        if (k.equals("backspace")) return KeyEvent.KEYCODE_DEL;
        if (k.equals("del")) return KeyEvent.KEYCODE_FORWARD_DEL;
        if (k.equals("ins")) return KeyEvent.KEYCODE_INSERT;
        if (k.equals("space")) return KeyEvent.KEYCODE_SPACE;
        if (k.equals("up")) return KeyEvent.KEYCODE_DPAD_UP;
        if (k.equals("down")) return KeyEvent.KEYCODE_DPAD_DOWN;
        if (k.equals("left")) return KeyEvent.KEYCODE_DPAD_LEFT;
        if (k.equals("right")) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if (k.equals("home")) return KeyEvent.KEYCODE_MOVE_HOME;
        if (k.equals("end")) return KeyEvent.KEYCODE_MOVE_END;
        if (k.equals("pgup")) return KeyEvent.KEYCODE_PAGE_UP;
        if (k.equals("pgdn")) return KeyEvent.KEYCODE_PAGE_DOWN;
        if (k.length() >= 2 && k.charAt(0) == 'f') {
            try {
                int n = Integer.parseInt(k.substring(1));
                if (n >= 1 && n <= 12) return KeyEvent.KEYCODE_F1 + (n - 1);
            } catch (Throwable ignore) {}
        }
        return 0;
    }
}
