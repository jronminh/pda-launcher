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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.pda.launcher.shell.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Apps tab: a 4 x 6 grid of launchable apps, one page at a time ("‹ 1/3 ›",
 * or a horizontal swipe), under a header with search and a menu.
 */
final class AppsTab {

    // The grid's size is a setting (apps.columns, apps.rows), read when it changes.
    private int columns = 4, rows = 6;
    static final String PREFS = "launcher";
    static final String KEY_HIDDEN = "hidden";

    /** Icons survive reloads, so a package change only loads the icon of that app. */
    static final Map<String, Drawable> ICONS = new ConcurrentHashMap<>();

    static final class App {
        final String pkg;
        final String name;
        final Drawable icon;

        App(String pkg, String name, Drawable icon) {
            this.pkg = pkg;
            this.name = name;
            this.icon = icon;
        }
    }

    private final Activity act;
    private final LauncherApps launcherApps;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private final SharedPreferences prefs;
    private final LinearLayout root;
    private final TextView title;
    private final EditText search;
    private final GridView grid;
    private final Pager pager;
    private final BaseAdapter adapter;
    private String query = "";
    private int cellHeight;
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

    AppsTab(Activity act) {
        this.act = act;
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
        root.addView(Ui.header(act, titleBox,
            Ui.action(act, android.R.drawable.ic_menu_search, "Search", v -> toggleSearch()),
            Ui.textAction(act, "⋮", v -> showMenu(v))));
        root.addView(Ui.divider(act));

        GestureDetector flip = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                return pager.turn(vx < 0 ? 1 : -1);
            }
        });
        // A horizontal fling turns the page. The detector sees the touch before the
        // cells do, and a fling cancels the cell's tap.
        grid = new GridView(act) {
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                if (flip.onTouchEvent(e)) {
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    return true;
                }
                return super.dispatchTouchEvent(e);
            }
        };
        columns = Config.getInt("apps.columns");
        rows = Config.getInt("apps.rows");
        grid.setNumColumns(columns);
        grid.setVerticalSpacing(0);
        grid.setHorizontalSpacing(0);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setSelector(android.R.color.transparent);
        adapter = new Adapter();
        pager = new Pager(act);   // before the grid asks the adapter for its count
        pager.setListener(p -> adapter.notifyDataSetChanged());
        // Six rows fill the grid. The window resizes for the keyboard (the Shell tab
        // needs that): while searching the cells keep their size and the grid scrolls.
        grid.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int h = grid.getHeight() / rows;
            if (h > 0 && h != cellHeight && search.getVisibility() != View.VISIBLE) {
                cellHeight = h;
                adapter.notifyDataSetChanged();
            }
        });
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
            adapter.notifyDataSetChanged();
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

    private int perPage() {
        return columns * rows;
    }

    /** The settings changed: a new grid size takes effect at once. */
    void onConfig() {
        int c = Config.getInt("apps.columns"), r = Config.getInt("apps.rows");
        if (c == columns && r == rows) return;
        columns = c;
        rows = r;
        grid.setNumColumns(columns);
        cellHeight = 0;   // the layout listener measures again
        grid.requestLayout();
        applyFilter();
    }

    private int pages() {
        return Math.max(1, (shown.size() + perPage() - 1) / perPage());
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add("Show/Hide apps");
        pm.setOnMenuItemClickListener(item -> { showHideDialog(); return true; });
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
            List<App> list = new ArrayList<>();
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
                list.add(new App(pkg, String.valueOf(li.getLabel()), icon));
            }
            // The launcher itself has no LAUNCHER category, so add it explicitly.
            list.add(self(self));
            Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
            act.runOnUiThread(() -> {
                if (gen != generation) return;
                all.clear();
                all.addAll(list);
                applyFilter();
            });
        }).start();
    }

    private App self(String pkg) {
        PackageManager pm = act.getPackageManager();
        Drawable icon = ICONS.get(pkg);
        try {
            if (icon == null) {
                icon = pm.getApplicationIcon(pkg);
                ICONS.put(pkg, icon);
            }
            return new App(pkg, String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))), icon);
        } catch (PackageManager.NameNotFoundException e) {
            return new App(pkg, pkg, icon);
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
        pager.setPages(pages());
        adapter.notifyDataSetChanged();
    }

    private void launch(String pkg) {
        Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
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

    private int dp(int v) {
        return Ui.dp(act, v);
    }

    /** The apps of the current page; one cell is an icon above a single-line label. */
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() {
            return Math.max(0, Math.min(perPage(), shown.size() - pager.page() * perPage()));
        }
        @Override public Object getItem(int position) { return shown.get(pager.page() * perPage() + position); }
        @Override public long getItemId(int position) { return shown.get(pager.page() * perPage() + position).pkg.hashCode(); }

        // Cells are built fresh: reusing convertView left some labels blank.
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final App app = shown.get(pager.page() * perPage() + position);
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(4), dp(4), dp(4));

            ImageView icon = new ImageView(act);
            icon.setImageDrawable(app.icon);
            cell.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

            TextView label = new TextView(act);
            label.setText(app.name);
            label.setTextColor(Ui.TEXT);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setTextSize(12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            cell.addView(label, lp);

            cell.setOnClickListener(v -> launch(app.pkg));
            cell.setOnLongClickListener(v -> { appMenu(v, app); return true; });
            cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                cellHeight > 0 ? cellHeight : dp(96)));
            return cell;
        }
    }
}
