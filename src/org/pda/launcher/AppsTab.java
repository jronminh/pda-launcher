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
 * The Apps tab: the launchable apps, one page at a time, as a grid (the size in
 * the settings), a list or a detailed list, under a header with search, the view
 * button and a menu (Show/Hide apps, Settings).
 */
final class AppsTab {

    static final String PREFS = "launcher";
    static final String KEY_HIDDEN = "hidden";

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
    private final ImageView viewBtn;
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
        header = new Header(act, "")
            .search(q -> {
                pager.setPage(0);
                applyFilter();
            })
            .action(Header.sort(act, "apps.sort", "apps.desc", "Name", "Installed", "Updated"))
            .action(viewBtn)
            .action(Ui.action(act, R.drawable.ic_more, "More", v -> showMenu(v)));
        root.addView(header.view());
        root.addView(Ui.divider(act));

        glance = new Glance(act, openToday);
        glance.view().setVisibility(Config.getBool("apps.glance") ? View.VISIBLE : View.GONE);
        root.addView(glance.view());

        list = new PagedView(act, pager);
        list.setBinder(new PagedView.Binder() {
            @Override public int count() { return shown.size(); }
            @Override public Drawable icon(int i) { return shown.get(i).icon; }
            @Override public String title(int i) { return shown.get(i).name; }
            @Override public String summary(int i) {
                App a = shown.get(i);
                return a.version == null || a.version.isEmpty() ? a.pkg : a.version + " · " + a.pkg;
            }
            @Override public String mark(int i) { return null; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return false; }
            @Override public void click(int i, View v) { launch(shown.get(i).pkg); }
            @Override public boolean longClick(int i, View v) { appMenu(v, shown.get(i)); return true; }
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


    /** Back: close the search, then go to the first page. Returns true if it did something. */
    boolean onBack() {
        if (header.searching()) {
            header.closeSearch();
            return true;
        }
        if (pager.page() > 0) {
            pager.setPage(0);
            list.refresh();
            return true;
        }
        return false;
    }

    /** Home pressed on the home screen: the first page, no search. */
    void reset() {
        if (header.searching()) header.closeSearch();
        pager.setPage(0);
        applyFilter();
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
        for (App a : all) {
            if (hidden.contains(a.pkg)) continue;
            if (q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
        }
        sort(shown);
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
        start(i);
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
