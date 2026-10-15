package org.pda.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Applications tab, after Pocket PC's Start menu: one page of tiles the
 * user chose, in the user's order, and a last tile, More, for every app (paged,
 * sorted, searched). New apps go only to More, marked • until first opened, so
 * the tiles never change by themselves. The first time, the tiles are the
 * device's default phone, messages, camera, browser and so on. The view (grid,
 * list, details) and the grid's size are shared by both; the search covers
 * every app. Hidden apps (Show/Hide apps) are in neither.
 */
final class AppsTab {

    static final String PREFS = "launcher";
    static final String KEY_HIDDEN = "hidden";
    static final String KEY_TILES = "tiles";       // the tiles' packages, in order, by commas
    static final String KEY_OPENED = "opened";     // apps opened since the tiles came (not new any more)
    static final String KEY_SINCE = "tilesSince";  // when the tiles came: an app installed later is new

    /** Icons survive reloads, so a package change only loads the icon of that app. */
    static final Map<String, Drawable> ICONS = new ConcurrentHashMap<>();

    static final class App {
        final String pkg;
        final String name;
        final Drawable icon;
        final String version;
        final long installed, updated;

        App(String pkg, String name, Drawable icon, PackageInfo info) {
            this.pkg = pkg;
            this.name = name;
            this.icon = icon;
            this.version = info != null ? info.versionName : null;
            this.installed = info != null ? info.firstInstallTime : 0;
            this.updated = info != null ? info.lastUpdateTime : 0;
        }
    }

    private final Activity act;
    private final LauncherApps launcherApps;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final Header header;
    private final ImageView viewBtn, sortBtn;
    private boolean more;   // in More (every app), not on the tiles
    private final Pager pager;
    private final PagedView list;
    private final Glance glance;   // the time; the date, the next event, the alarm, the battery
    private int generation;   // the newest reload; an older one that finishes late is dropped

    /** The system tells a launcher about package changes, suspended apps included. */
    private final LauncherApps.Callback onPackages = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackageAdded(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { changed(pkg); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { changed(pkgs); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { changed(pkgs); }
        @Override public void onPackagesSuspended(String[] pkgs, UserHandle user) { changed(pkgs); }
        @Override public void onPackagesUnsuspended(String[] pkgs, UserHandle user) { changed(pkgs); }
    };

    /** {@code openToday} shows the Today tab (the glance line's date opens it). */
    AppsTab(Activity act, Runnable openToday) {
        this.act = act;
        launcherApps = (LauncherApps) act.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        pager = new Pager(act);
        // The header: search, sort, the view, the menu.
        viewBtn = Ui.action(act, PagedView.iconOf(Config.get("apps.view")), "View",
            v -> PagedView.chooseMode(act, v, Config.get("apps.view"), m -> Config.set(act, "apps.view", m)));
        sortBtn = Header.sort(act, "apps.sort", "apps.desc", "Name", "Installed", "Updated");
        header = new Header(act, "")
            .back(() -> showMore(false))
            .search(q -> {
                pager.setPage(0);
                applyFilter();
            })
            .action(sortBtn)
            .action(viewBtn)
            .action(Ui.action(act, R.drawable.ic_more, "More", v -> showMenu(v)));
        root.addView(header.view());
        root.addView(Ui.divider(act));

        glance = new Glance(act, openToday);
        glance.view().setVisibility(Config.getBool("apps.glance") ? View.VISIBLE : View.GONE);
        root.addView(glance.view());

        list = new PagedView(act, pager);
        list.setBinder(new PagedView.Binder() {
            @Override public int count() { return onTiles() ? tileCount() + 1 : shown.size(); }
            @Override public Drawable icon(int i) {
                return isMore(i) ? act.getDrawable(R.drawable.ic_apps) : shown.get(i).icon;
            }
            @Override public String title(int i) { return isMore(i) ? "More" : shown.get(i).name; }
            @Override public String summary(int i) {
                if (isMore(i)) {
                    int n = newCount();
                    return visibleCount() + " apps" + (n > 0 ? " · " + n + " new" : "");
                }
                App a = shown.get(i);
                return a.version == null || a.version.isEmpty() ? a.pkg : a.version + " · " + a.pkg;
            }
            @Override public String mark(int i) {
                if (isMore(i)) return newCount() > 0 ? "•" : null;
                return isNew(shown.get(i)) ? "•" : null;
            }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return false; }
            @Override public void click(int i, View v) {
                if (isMore(i)) showMore(true);
                else launch(shown.get(i).pkg);
            }
            @Override public boolean longClick(int i, View v) {
                if (isMore(i)) return false;
                appMenu(v, shown.get(i));
                return true;
            }
        });
        applyView();
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(pager, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        launcherApps.registerCallback(onPackages, new Handler(Looper.getMainLooper()));
    }

    View view() {
        return root;
    }

    void onDestroy() {
        launcherApps.unregisterCallback(onPackages);
    }

    /** On screen: the glance line follows the minute, the battery, the alarms, the calendar. */
    void onStart() {
        glance.onStart();
    }

    void onStop() {
        glance.onStop();
    }

    /** The answer to the calendar permission the glance line asked for. */
    void onCalendarPermission() {
        glance.onPermission();
    }


    /** Back: close the search, then from More to the tiles. Returns true if it did something. */
    boolean onBack() {
        if (header.searching()) {
            header.closeSearch();
            return true;
        }
        if (more) {
            showMore(false);
            return true;
        }
        return false;
    }

    /** Home pressed on the home screen, or the tab's button again: the tiles, no search. */
    void reset() {
        if (header.searching()) header.closeSearch();
        more = false;
        pager.setPage(0);
        applyFilter();
    }

    // --- the tiles and More ----------------------------------------------------------------

    /** The tiles are on screen: not in More, not searching. */
    private boolean onTiles() {
        return !more && header.query().isEmpty();
    }

    private boolean isMore(int i) {
        return onTiles() && i == tileCount();
    }

    /** How many of the tiles fit: one page, less the More tile. */
    private int tileCount() {
        return Math.min(shown.size(), list.perPage() - 1);
    }

    private void showMore(boolean on) {
        if (header.searching()) header.closeSearch();
        more = on;
        list.slideNext(on ? 1 : -1);
        pager.setPage(0);
        applyFilter();
    }

    private List<String> tiles() {
        String s = prefs.getString(KEY_TILES, "");
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void saveTiles(List<String> t) {
        prefs.edit().putString(KEY_TILES, String.join(",", t)).apply();
        applyFilter();
    }

    private int visibleCount() {
        Set<String> hidden = hidden();
        int n = 0;
        for (App a : all) if (!hidden.contains(a.pkg)) n++;
        return n;
    }

    /** Installed after the tiles came, and not opened since. */
    private boolean isNew(App a) {
        return a.installed > prefs.getLong(KEY_SINCE, Long.MAX_VALUE)
                && !prefs.getStringSet(KEY_OPENED, Collections.emptySet()).contains(a.pkg);
    }

    private int newCount() {
        Set<String> hidden = hidden();
        int n = 0;
        for (App a : all) if (!hidden.contains(a.pkg) && isNew(a)) n++;
        return n;
    }

    /** The first tiles: the device's default app for each common need, those found. */
    private void firstTiles() {
        if (prefs.contains(KEY_TILES) || all.isEmpty()) return;
        PackageManager pm = act.getPackageManager();
        Intent[] needs = {
            new Intent(Intent.ACTION_DIAL),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")).addCategory(Intent.CATEGORY_BROWSABLE),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
            new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS),
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
        };
        Set<String> have = new HashSet<>();
        for (App a : all) have.add(a.pkg);
        List<String> t = new ArrayList<>();
        String sms = android.provider.Telephony.Sms.getDefaultSmsPackage(act);
        for (int i = 0; i < needs.length; i++) {
            String pkg = null;
            if (i == 1 && sms != null) {
                pkg = sms;
            } else {
                try {
                    android.content.pm.ResolveInfo r = pm.resolveActivity(needs[i], PackageManager.MATCH_DEFAULT_ONLY);
                    if (r != null && r.activityInfo != null) pkg = r.activityInfo.packageName;
                } catch (RuntimeException ignore) {}
            }
            // "android" is the chooser: no default for that need.
            if (pkg != null && have.contains(pkg) && !t.contains(pkg)) t.add(pkg);
        }
        prefs.edit().putString(KEY_TILES, String.join(",", t))
                .putLong(KEY_SINCE, System.currentTimeMillis()).apply();
    }

    /** The settings changed: the view and the grid's size take effect at once. */
    void onConfig(java.util.Set<String> keys) {
        if (!Config.touches(keys, "apps.")) return;
        applyView();
        glance.view().setVisibility(Config.getBool("apps.glance") ? View.VISIBLE : View.GONE);
        if (Config.touches(keys, "apps.glance")) glance.update();
        applyFilter();   // the order may have changed
    }

    private void applyView() {
        String mode = Config.get("apps.view");
        viewBtn.setImageResource(PagedView.iconOf(mode));
        list.setMode(mode);
        list.setGrid(Config.getInt("apps.columns"), Config.getInt("apps.rows"));
    }

    private void showMenu(View anchor) {
        PopMenu pm = new PopMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Show/Hide apps");
        // The grid's size, in the menu itself: a tab with so few settings needs no screen.
        pm.getMenu().add(0, 2, 1, "Columns: " + Config.get("apps.columns") + "  ›");
        pm.getMenu().add(0, 3, 2, "Rows: " + Config.get("apps.rows") + "  ›");
        pm.getMenu().add(0, 4, 3, "Glance line").setChecked(Config.getBool("apps.glance"));
        if (Config.getBool("apps.glance")) {
            pm.getMenu().add(0, 5, 4, "Next event").setChecked(
                    Config.getBool("apps.glanceEvents") && Glance.calendarAllowed(act));
        }
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showHideDialog(); break;
                case 2: PopMenu.choose(act, anchor, "apps.columns"); break;
                case 4: Config.set(act, "apps.glance", String.valueOf(!Config.getBool("apps.glance"))); break;
                case 5: glance.setEvents(!(Config.getBool("apps.glanceEvents") && Glance.calendarAllowed(act))); break;
                default: PopMenu.choose(act, anchor, "apps.rows");
            }
            return true;
        });
        pm.show();
    }

    private void changed(String... pkgs) {
        for (String p : pkgs) ICONS.remove(p);
        reload();
    }

    /** Re-reads the launchable apps on a background thread. */
    void reload() {
        final int gen = ++generation;
        new Thread(() -> {
            PackageManager pm = act.getPackageManager();
            List<App> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (LauncherActivityInfo li : launcherApps.getActivityList(null, Process.myUserHandle())) {
                String pkg = li.getApplicationInfo().packageName;
                if (!seen.add(pkg)) continue;   // the launcher's own: its Capture activity
                Drawable icon = ICONS.get(pkg);
                if (icon == null) {
                    icon = li.getIcon(0);
                    ICONS.put(pkg, icon);
                }
                found.add(new App(pkg, String.valueOf(li.getLabel()), icon, info(pm, pkg)));
            }
            act.runOnUiThread(() -> {
                if (gen != generation) return;
                all.clear();
                all.addAll(found);
                firstTiles();
                applyFilter();
            });
        }).start();
    }

    private static PackageInfo info(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }


    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
    }

    private void applyFilter() {
        shown.clear();
        String q = header.query().toLowerCase(Locale.ROOT);
        Set<String> hidden = hidden();
        if (onTiles()) {
            // The tiles, in the user's order; one gone or hidden leaves no hole.
            Map<String, App> byPkg = new java.util.HashMap<>();
            for (App a : all) byPkg.put(a.pkg, a);
            for (String p : tiles()) {
                App a = byPkg.get(p);
                if (a != null && !hidden.contains(p)) shown.add(a);
            }
        } else {
            for (App a : all) {
                if (hidden.contains(a.pkg)) continue;
                if (q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
            }
            sort(shown);
        }
        boolean inMore = more && q.isEmpty();
        header.setTitle(inMore ? "All apps" : "");
        header.showBack(more);
        sortBtn.setVisibility(onTiles() ? View.GONE : View.VISIBLE);
        list.refresh();
    }

    /** The order of apps.sort (a tie by name), turned round by apps.desc. */
    private static void sort(List<App> apps) {
        java.util.Comparator<App> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
        java.util.Comparator<App> order;
        switch (Config.get("apps.sort")) {
            case "installed": order = (a, b) -> Long.compare(a.installed, b.installed); break;
            case "updated": order = (a, b) -> Long.compare(a.updated, b.updated); break;
            default: order = byName;
        }
        java.util.Comparator<App> chosen = Config.getBool("apps.desc") ? order.reversed() : order;
        Collections.sort(apps, (a, b) -> {
            int c = chosen.compare(a, b);
            return c != 0 ? c : byName.compare(a, b);
        });
    }

    private void launch(String pkg) {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        Set<String> opened = new HashSet<>(prefs.getStringSet(KEY_OPENED, Collections.emptySet()));
        if (opened.add(pkg)) prefs.edit().putStringSet(KEY_OPENED, opened).apply();
        start(i);
        if (more || !header.query().isEmpty()) list.refresh();   // its • is gone
    }

    /** Long-press on an app. */
    private static final int MAX_SHORTCUTS = 5;

    /** Long-press on an app: its shortcuts first (as the default home may read them), then ours. */
    private void appMenu(View anchor, App app) {
        PopMenu pm = new PopMenu(act, anchor);
        final List<ShortcutInfo> shortcuts = shortcuts(app.pkg);
        for (int i = 0; i < shortcuts.size(); i++) {
            ShortcutInfo si = shortcuts.get(i);
            CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
            Drawable icon = null;
            try {
                icon = launcherApps.getShortcutIconDrawable(si, act.getResources().getDisplayMetrics().densityDpi);
            } catch (RuntimeException ignore) {}
            pm.getMenu().add(0, 100 + i, i, String.valueOf(label)).setIcon(icon);
        }
        final List<String> t = tiles();
        final int at = t.indexOf(app.pkg);
        // Up and down: next to the tile shown before or after it (one gone or hidden is skipped).
        final int pos = onTiles() ? shown.indexOf(app) : -1;
        final int prev = pos > 0 ? t.indexOf(shown.get(pos - 1).pkg) : -1;
        final int next = pos >= 0 && pos < tileCount() - 1 ? t.indexOf(shown.get(pos + 1).pkg) : -1;
        if (at < 0) {
            pm.getMenu().add(0, 4, 97, "Add to tiles");
        } else {
            pm.getMenu().add(0, 5, 97, "Remove from tiles");
            if (prev >= 0) pm.getMenu().add(0, 6, 98, "Move up");
            if (next >= 0) pm.getMenu().add(0, 7, 99, "Move down");
        }
        pm.getMenu().add(0, 1, 100, "App info");
        pm.getMenu().add(0, 2, 101, "Hide");
        pm.getMenu().add(0, 3, 102, "Uninstall");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() >= 100) {
                ShortcutInfo si = shortcuts.get(item.getItemId() - 100);
                try {
                    launcherApps.startShortcut(si, null, null);
                } catch (RuntimeException e) {   // gone, disabled, or the app refuses
                    android.widget.Toast.makeText(act, "Could not open the shortcut", android.widget.Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            switch (item.getItemId()) {
                case 1: start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + app.pkg))); break;
                case 2: {
                    Set<String> h = hidden();
                    h.add(app.pkg);
                    prefs.edit().putStringSet(KEY_HIDDEN, h).apply();
                    applyFilter();
                    break;
                }
                case 3: start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg))); break;
                case 4:
                    if (shownTiles() >= list.perPage() - 1) {
                        android.widget.Toast.makeText(act, "The tiles are full: remove one first",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        t.add(app.pkg);
                        saveTiles(t);
                        android.widget.Toast.makeText(act, "Added to the tiles", android.widget.Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 5: t.remove(app.pkg); saveTiles(t); break;
                case 6: Collections.swap(t, at, prev); saveTiles(t); break;
                case 7: Collections.swap(t, at, next); saveTiles(t); break;
            }
            return true;
        });
        pm.show();
    }

    /**
     * An app's shortcuts (its manifest's first, then the ones it adds at run
     * time, then the pinned ones), at most MAX_SHORTCUTS, in the app's own rank.
     * Only the default home may read them; otherwise there are none.
     */
    private List<ShortcutInfo> shortcuts(String pkg) {
        List<ShortcutInfo> out = new ArrayList<>();
        try {
            if (!launcherApps.hasShortcutHostPermission()) return out;
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery()
                    .setPackage(pkg)
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED);
            List<ShortcutInfo> found = launcherApps.getShortcuts(q, Process.myUserHandle());
            if (found == null) return out;
            List<ShortcutInfo> list = new ArrayList<>(found);
            Collections.sort(list, (a, b) -> {
                int ka = a.isDeclaredInManifest() ? 0 : a.isDynamic() ? 1 : 2;
                int kb = b.isDeclaredInManifest() ? 0 : b.isDynamic() ? 1 : 2;
                return ka != kb ? Integer.compare(ka, kb) : Integer.compare(a.getRank(), b.getRank());
            });
            for (ShortcutInfo si : list) {
                if (!si.isEnabled()) continue;
                out.add(si);
                if (out.size() == MAX_SHORTCUTS) break;
            }
        } catch (RuntimeException e) {
            // no permission (not the default home) or the user is locked: none
        }
        return out;
    }

    /** The tiles that would show now (installed, not hidden), whatever is on screen. */
    private int shownTiles() {
        Set<String> hidden = hidden();
        Set<String> have = new HashSet<>();
        for (App a : all) have.add(a.pkg);
        int n = 0;
        for (String p : tiles()) if (have.contains(p) && !hidden.contains(p)) n++;
        return n;
    }

    private void start(Intent i) {
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    /** Launcher-only filter: checked apps show in the grid, unchecked ones are hidden. */
    void showHideDialog() {
        // The launcher itself can be hidden too: it is reached with Home anyway.
        List<App> candidates = new ArrayList<>(all);
        Set<String> hidden = hidden();
        String[] names = new String[candidates.size()];
        boolean[] visible = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).name;
            visible[i] = !hidden.contains(candidates.get(i).pkg);
        }
        new AlertDialog.Builder(act)
            .setTitle("Show/Hide apps")
            .setMultiChoiceItems(names, visible, (d, which, checked) -> visible[which] = checked)
            .setPositiveButton("Done", (d, w) -> {
                Set<String> newHidden = new HashSet<>();
                for (int i = 0; i < candidates.size(); i++) {
                    if (!visible[i]) newHidden.add(candidates.get(i).pkg);
                }
                prefs.edit().putStringSet(KEY_HIDDEN, newHidden).apply();
                applyFilter();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
