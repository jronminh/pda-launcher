package org.pda.launcher.files;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.List;

/**
 * The places added to Files from other apps through Android's Storage Access
 * Framework (ACTION_OPEN_DOCUMENT_TREE): a NAS through an SMB provider, a
 * Nextcloud folder, Termux's home, a USB drive, a cloud drive... whatever app
 * offers its folders to the system. The launcher speaks no network protocol:
 * the app that provides the folder does. Each is a line "uri\tname" in the
 * app's preferences; its read and write grant is kept across restarts.
 */
final class Locations {

    static final class Location {
        final Uri tree;
        final String name;

        Location(Uri tree, String name) {
            this.tree = tree;
            this.name = name;
        }
    }

    private static final String PREFS = "locations", KEY = "list";

    private Locations() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Location> all(Context c) {
        List<Location> out = new ArrayList<>();
        for (String line : prefs(c).getString(KEY, "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            out.add(new Location(Uri.parse(line.substring(0, tab)), line.substring(tab + 1)));
        }
        return out;
    }

    private static void save(Context c, List<Location> list) {
        StringBuilder sb = new StringBuilder();
        for (Location l : list) sb.append(l.tree).append('\t').append(l.name.replace('\n', ' ')).append('\n');
        prefs(c).edit().putString(KEY, sb.toString()).apply();
    }

    /** The answer of the tree picker: keep the grant, name it after the folder. @return its name, or null. */
    static String add(Context c, Intent result) {
        Uri tree = result == null ? null : result.getData();
        if (tree == null) return null;
        try {
            c.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            try {   // a provider that grants reading only
                c.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignore) {}
        }
        String name = Docs.name(c, DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree)));
        if (name == null || name.isEmpty()) name = tree.getAuthority();
        List<Location> list = all(c);
        for (Location l : list) if (l.tree.equals(tree)) return l.name;
        list.add(new Location(tree, name));
        save(c, list);
        return name;
    }

    static void rename(Context c, Uri tree, String name) {
        List<Location> list = all(c);
        for (int i = 0; i < list.size(); i++) if (list.get(i).tree.equals(tree)) list.set(i, new Location(tree, name));
        save(c, list);
    }

    /** Forget a place, and give its grant back. */
    static void remove(Context c, Uri tree) {
        List<Location> list = all(c);
        list.removeIf(l -> l.tree.equals(tree));
        save(c, list);
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree)) {
                try {
                    c.getContentResolver().releasePersistableUriPermission(tree,
                            (p.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0)
                                    | (p.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
                } catch (SecurityException ignore) {}
            }
        }
    }

    /** Whether the grant is still held (the providing app may be gone, or took it back). */
    static boolean reachable(Context c, Uri tree) {
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isReadPermission()) return true;
        }
        return false;
    }
}
