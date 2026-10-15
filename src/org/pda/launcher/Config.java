package org.pda.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The app's settings: a small schema in code (type, default, constraint) over
 * SharedPreferences. {@link #set} checks a value against the schema; a stored
 * value that is no longer valid reads as the default. A change reaches the
 * {@link Listener}s at once, on the main thread.
 */
public final class Config {

    /** Called on the main thread after a setting changed. */
    public interface Listener {
        void onChange();
    }

    /** key -> type, default, constraint (a range "lo-hi" or values "a|b|c"). */
    private static final Map<String, String[]> SCHEMA = new LinkedHashMap<>();
    static {
        SCHEMA.put("launcher.homeTab", new String[] { "enum", "apps", "apps|favorites|files|note|settings" });
        SCHEMA.put("apps.columns", new String[] { "int", "4", "3-6" });
        SCHEMA.put("apps.rows", new String[] { "int", "6", "4-8" });
        SCHEMA.put("files.showHidden", new String[] { "bool", "false", "" });
        SCHEMA.put("apps.view", new String[] { "enum", "grid", "details|list|grid" });
        SCHEMA.put("favorites.view", new String[] { "enum", "details", "details|list|grid" });
        SCHEMA.put("files.view", new String[] { "enum", "details", "details|list|grid" });
        SCHEMA.put("settings.view", new String[] { "enum", "grid", "details|list|grid" });
        SCHEMA.put("note.textSize", new String[] { "enum", "medium", "small|medium|large" });
    }

    private static SharedPreferences prefs;
    private static final List<Listener> listeners = new ArrayList<>();
    // Kept in a field: SharedPreferences holds its listeners weakly.
    private static final SharedPreferences.OnSharedPreferenceChangeListener ON_CHANGE = (p, key) -> {
        for (Listener l : new ArrayList<>(listeners)) l.onChange();
    };

    private Config() {}

    /** Open the store; call once before reading a setting. Safe to call again. */
    public static synchronized void prepare(Context c) {
        if (prefs != null) return;
        prefs = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(ON_CHANGE);
    }

    public static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The schema's row for a key: type, default, constraint; null if unknown. */
    public static String[] schema(String key) {
        return SCHEMA.get(key);
    }

    public static String get(String key) {
        String[] s = SCHEMA.get(key);
        if (s == null) return "";
        String v = prefs == null ? null : prefs.getString(key, null);
        return v != null && invalid(s, v) == null ? v : s[1];
    }

    public static int getInt(String key) {
        try {
            return Integer.parseInt(get(key));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean getBool(String key) {
        return "true".equals(get(key));
    }

    /** Store a setting. @return null, or why the value was refused. */
    public static String set(Context c, String key, String value) {
        prepare(c);
        String[] s = SCHEMA.get(key);
        if (s == null) return "unknown setting: " + key;
        String why = invalid(s, value);
        if (why != null) return key + ": " + why;
        prefs.edit().putString(key, value).apply();
        return null;
    }

    /** Why a value is not valid for its schema row, or null. */
    private static String invalid(String[] s, String v) {
        switch (s[0]) {
            case "int":
                try {
                    int n = Integer.parseInt(v);
                    String[] r = s[2].split("-");
                    if (n < Integer.parseInt(r[0]) || n > Integer.parseInt(r[1])) return "out of range " + s[2];
                    return null;
                } catch (NumberFormatException e) {
                    return "not a whole number";
                }
            case "bool":
                return v.equals("true") || v.equals("false") ? null : "must be true or false";
            case "enum":
                for (String x : s[2].split("\\|")) if (x.equals(v)) return null;
                return "must be one of " + s[2];
            default:
                return null;
        }
    }
}
