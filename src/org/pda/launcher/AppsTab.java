package org.pda.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

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

        App(String pkg, String name, Drawable icon, String version) {
            this.pkg = pkg;
            this.name = name;
            this.icon = icon;
            this.version = version;
        }
    }

    private final Activity act;
    private final java.util.function.Consumer<String> openSettings;
    private final LauncherApps launcherApps;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final TextView title;
    private final EditText search;
    private final ImageView viewBtn;
    private final Pager pager;
    private final PagedView list;
    private String query = "";
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

        // The header: the title, or the search box while searching.
        LinearLayout titleBox = new LinearLayout(act);
        title = Ui.title(act, "Apps");
        search = new EditText(act);
        search.setSingleLine(true);
        search.setHint(act.getString(R.string.search_by_name));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setBackground(null);
        search.setPadding(0, 0, 0, 0);
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().replace("\n", "").trim();
                pager.setPage(0);
                applyFilter();
            }
        });
        titleBox.addView(title);
        titleBox.addView(search, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        viewBtn = Ui.action(act, PagedView.iconOf(Config.get("apps.view")), "View",
            v -> PagedView.chooseMode(act, v, Config.get("apps.view"), m -> Config.set(act, "apps.view", m)));
        root.addView(Ui.header(act, titleBox,
            Ui.action(act, android.R.drawable.ic_menu_search, "Search", v -> toggleSearch()),
            viewBtn,
            Ui.textAction(act, "⋮", v -> showMenu(v))));
        root.addView(Ui.divider(act));

        pager = new Pager(act);
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
        if (search.getVisibility() == View.VISIBLE) {
            closeSearch();
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
        closeSearch();
        pager.setPage(0);
        applyFilter();
    }

    /** The settings changed: the view and the grid's size take effect at once. */
    void onConfig() {
        applyView();
        list.refresh();
    }

    private void applyView() {
        String mode = Config.get("apps.view");
        viewBtn.setImageResource(PagedView.iconOf(mode));
        list.setMode(mode);
        list.setGrid(Config.getInt("apps.columns"), Config.getInt("apps.rows"));
    }

    private void toggleSearch() {
        if (search.getVisibility() == View.VISIBLE) { closeSearch(); return; }
        title.setVisibility(View.GONE);
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.setText("");
        search.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
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
                found.add(new App(pkg, String.valueOf(li.getLabel()), icon, version(pm, pkg)));
            }
            // The launcher itself has no LAUNCHER category, so add it explicitly.
            found.add(self(pm, self));
            Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
            act.runOnUiThread(() -> {
                if (gen != generation) return;
                all.clear();
                all.addAll(found);
                applyFilter();
            });
        }).start();
    }

    private static String version(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).versionName;
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
                icon, version(pm, pkg));
        } catch (PackageManager.NameNotFoundException e) {
            return new App(pkg, pkg, icon, null);
        }
    }

    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
    }

    private void applyFilter() {
        shown.clear();
        String q = query.toLowerCase(Locale.ROOT);
        Set<String> hidden = hidden();
        for (App a : all) {
            if (hidden.contains(a.pkg)) continue;
            if (q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
        }
        list.refresh();
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
