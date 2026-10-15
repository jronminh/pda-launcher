package org.pda.launcher;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The tabs: a fixed id each (its key in the settings never changes), a name in
 * the Palm OS manner, an icon, and the user's order and choice of which show
 * ({@code launcher.tabs}: the keys in order, a "-" before one turned off, as
 * {@code apps,favorites,-files,today,settings}). Prefs cannot be turned off:
 * it is the way back to this very choice.
 */
public final class Tabs {

    public static final int APPS = 0, BRIEFCASE = 1, FILES = 2, TODAY = 3, PREFS = 4;
    static final String[] KEYS = { "apps", "favorites", "files", "today", "settings" };
    static final String[] NAMES = { "Applications", "Briefcase", "Files", "Today", "Prefs" };
    static final int[] ICONS = { R.drawable.ic_apps, R.drawable.ic_briefcase, R.drawable.ic_folder,
            R.drawable.ic_calendar, R.drawable.ic_gear };
    static final String DEFAULT = "apps,favorites,files,today,settings";

    private Tabs() {}

    static int count() {
        return KEYS.length;
    }

    /** The id of a key, or -1. */
    static int idOf(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return -1;
    }

    /** Every tab, in the user's order (a tab the setting forgot goes last). */
    static List<Integer> order() {
        List<Integer> out = new ArrayList<>();
        for (String k : Config.get("launcher.tabs").split(",")) {
            int id = idOf(k.startsWith("-") ? k.substring(1) : k);
            if (id >= 0 && !out.contains(id)) out.add(id);
        }
        for (int i = 0; i < KEYS.length; i++) if (!out.contains(i)) out.add(i);
        return out;
    }

    static boolean on(int id) {
        if (id < 0 || id >= KEYS.length) return false;
        if (id == PREFS) return true;
        for (String k : Config.get("launcher.tabs").split(",")) {
            if (k.equals("-" + KEYS[id])) return false;
        }
        return true;
    }

    /** The tabs shown, in order. */
    static List<Integer> shown() {
        List<Integer> out = new ArrayList<>();
        for (int id : order()) if (on(id)) out.add(id);
        return out;
    }

    /** Store an order and which are on. */
    static void save(Context c, List<Integer> order, boolean[] on) {
        StringBuilder sb = new StringBuilder();
        for (int id : order) {
            if (sb.length() > 0) sb.append(',');
            if (!on[id] && id != PREFS) sb.append('-');
            sb.append(KEYS[id]);
        }
        Config.set(c, "launcher.tabs", sb.toString());
    }

    /** Move a tab one place (-1 up, +1 down) in the order. */
    static void move(Context c, int id, int by) {
        List<Integer> o = order();
        int at = o.indexOf(id), to = at + by;
        if (at < 0 || to < 0 || to >= o.size()) return;
        o.remove(at);
        o.add(to, id);
        save(c, o, states());
    }

    static void toggle(Context c, int id) {
        if (id == PREFS) return;
        boolean[] on = states();
        on[id] = !on[id];
        save(c, order(), on);
    }

    private static boolean[] states() {
        boolean[] on = new boolean[KEYS.length];
        for (int i = 0; i < on.length; i++) on[i] = on(i);
        return on;
    }
}
