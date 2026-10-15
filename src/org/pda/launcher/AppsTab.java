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
import android.widget.PopupMenu;

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
    private final java.util.function.Consumer<String> openSettings;
    private final LauncherApps launcherApps;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final Header header;
    private final ImageView viewBtn;
    private final Pager pager;
    private final PagedView list;
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

    /** {@code openSettings} opens a settings screen: "apps" for this tab's. */
    AppsTab(Activity act, java.util.function.Consumer<String> openSettings) {
        this.act = act;
        this.openSettings = openSettings;
        launcherApps = (LauncherApps) act.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        pager = new Pager(act);
        // The header: search, sort, the view, the menu.
        viewBtn = Ui.action(act, PagedView.iconOf(Config.get("apps.view")), "View",
            v -> PagedView.chooseMode(act, v, Config.get("apps.view"), m -> Config.set(act, "apps.view", m)));
        header = new Header(act, "Apps")
            .search(q -> {
                pager.setPage(0);
                applyFilter();
            })
            .action(Header.sort(act, "apps.sort", "apps.desc", "Name", "Installed", "Updated"))
            .action(viewBtn)
            .action(Ui.textAction(act, "⋮", v -> showMenu(v)));
        root.addView(header.view());
        root.addView(Ui.divider(act));

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
        applyFilter();   // the order may have changed
    }

    private void applyView() {
        String mode = Config.get("apps.view");
        viewBtn.setImageResource(PagedView.iconOf(mode));
        list.setMode(mode);
        list.setGrid(Config.getInt("apps.columns"), Config.getInt("apps.rows"));
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Show/Hide apps");
        pm.getMenu().add(0, 2, 1, "Apps settings");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showHideDialog(); break;
                default: openSettings.accept(SettingsScreen.APPS);
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
            String self = act.getPackageName();
            for (LauncherActivityInfo li : launcherApps.getActivityList(null, Process.myUserHandle())) {
                String pkg = li.getApplicationInfo().packageName;
                if (!seen.add(pkg) || pkg.equals(self)) continue;
                Drawable icon = ICONS.get(pkg);
                if (icon == null) {
                    icon = li.getIcon(0);
                    ICONS.put(pkg, icon);
                }
                found.add(new App(pkg, String.valueOf(li.getLabel()), icon, info(pm, pkg)));
            }
            // The launcher itself has no LAUNCHER category, so add it explicitly.
            found.add(self(pm, self));
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

    private App self(PackageManager pm, String pkg) {
        Drawable icon = ICONS.get(pkg);
        try {
            if (icon == null) {
                icon = pm.getApplicationIcon(pkg);
                ICONS.put(pkg, icon);
            }
            return new App(pkg, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))),
                icon, info(pm, pkg));
        } catch (PackageManager.NameNotFoundException e) {
            return new App(pkg, pkg, icon, null);
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
    private void appMenu(View anchor, App app) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "App info");
        if (!app.pkg.equals(act.getPackageName())) pm.getMenu().add(0, 2, 1, "Hide");
        pm.getMenu().add(0, 3, 2, "Uninstall");
        pm.setOnMenuItemClickListener(item -> {
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

    private void start(Intent i) {
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    /** Launcher-only filter: checked apps show in the grid, unchecked ones are hidden. */
    void showHideDialog() {
        List<App> candidates = new ArrayList<>();
        for (App a : all) {
            if (!a.pkg.equals(act.getPackageName())) candidates.add(a);
        }
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
