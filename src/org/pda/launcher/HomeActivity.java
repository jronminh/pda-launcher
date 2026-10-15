package org.pda.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Home screen: a search box over a 4 x 6 grid of launchable apps. Nothing else. */
public class HomeActivity extends Activity {

    static final int COLUMNS = 4;
    static final int VISIBLE_ROWS = 6;

    /** Set while the home screen is in the foreground, so the package receiver can reload it. */
    static volatile HomeActivity current;

    /** Icons survive reloads, so a package change only loads the icon of the new app. */
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

    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private String query = "";
    private int cellHeight;
    private int cellHeightFallbackDp = 96;
    private EditText search;
    private GridView grid;
    private TextView footer;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        int pad = dp(8);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(getString(R.string.search_by_name));
        search.setBackground(null);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().replace("\n", "").trim();
                applyFilter();
            }
        });
        root.addView(search, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setVerticalSpacing(0);
        grid.setHorizontalSpacing(0);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int h = grid.getHeight() / VISIBLE_ROWS;
            if (h > 0 && h != cellHeight) {
                cellHeight = h;
                adapter.notifyDataSetChanged();
            }
        });
        adapter = new Adapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footer = new TextView(this);
        footer.setGravity(Gravity.CENTER);
        footer.setTextSize(12);
        footer.setPadding(0, pad, 0, pad);
        footer.setText("PDA Launcher " + versionName());
        root.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        reload();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (current == this) current = null;
    }

    @Override
    public void onBackPressed() {
        if (!query.isEmpty()) {
            search.setText("");
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            grid.setSelection(0);
            return;
        }
        super.onBackPressed();
    }

    /** Re-reads the launchable apps on a background thread; safe to call from a receiver. */
    void reload() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<App> list = new ArrayList<>();
            Map<String, Boolean> seen = new HashMap<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (seen.put(pkg, Boolean.TRUE) != null) continue;
                if (pkg.equals(getPackageName())) continue;
                list.add(load(pm, pkg, ri));
            }
            // The launcher itself has no LAUNCHER category, so add it explicitly.
            if (!seen.containsKey(getPackageName())) {
                list.add(load(pm, getPackageName(), null));
            }
            Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
            runOnUiThread(() -> {
                all.clear();
                all.addAll(list);
                applyFilter();
            });
        }).start();
    }

    private App load(PackageManager pm, String pkg, ResolveInfo ri) {
        String name;
        Drawable icon = ICONS.get(pkg);
        try {
            name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
            if (icon == null) {
                icon = ri != null ? ri.loadIcon(pm) : pm.getApplicationIcon(pkg);
                ICONS.put(pkg, icon);
            }
        } catch (PackageManager.NameNotFoundException e) {
            name = pkg;
        }
        return new App(pkg, name, icon);
    }

    private void applyFilter() {
        shown.clear();
        String q = query.toLowerCase(Locale.ROOT);
        for (App a : all) {
            if (q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
        }
        footer.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private void uninstall(String pkg) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    /** One cell: icon above a single-line label. */
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return shown.get(position).pkg.hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;
            if (convertView instanceof LinearLayout) {
                cell = (LinearLayout) convertView;
                icon = (ImageView) cell.getChildAt(0);
                label = (TextView) cell.getChildAt(1);
            } else {
                cell = new LinearLayout(HomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(dp(4), dp(4), dp(4), dp(4));

                icon = new ImageView(HomeActivity.this);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

                label = new TextView(HomeActivity.this);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setGravity(Gravity.CENTER);
                label.setTextSize(12);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                cell.addView(label, lp);
            }

            final App app = shown.get(position);
            icon.setImageDrawable(app.icon);
            label.setText(app.name);
            cell.setOnClickListener(v -> launch(app.pkg));
            cell.setOnLongClickListener(v -> { uninstall(app.pkg); return true; });
            cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                cellHeight > 0 ? cellHeight : dp(cellHeightFallbackDp)));
            return cell;
        }
    }
}
