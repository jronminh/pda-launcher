package org.pda.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pda.launcher.files.FavoritesTab;
import org.pda.launcher.files.FilesTab;
import org.pda.launcher.shell.Config;
import org.pda.launcher.shell.ShellTab;

import java.io.File;
import java.util.Locale;

/** The home screen: five tabs over a tab bar at the bottom. */
public class HomeActivity extends Activity {

    public static final int APPS = 0, FAVORITES = 1, FILES = 2, SHELL = 3, SETTINGS = 4;
    private static final String[] NAMES = { "Apps", "Favorites", "Files", "Shell", "Settings" };

    /** An intent extra: the tab to show (the shell's notification asks for SHELL). */
    public static final String EXTRA_TAB = "tab";

    private AppsTab apps;
    private ShellTab shell;
    private FilesTab files;
    private FavoritesTab favorites;
    private SettingsTab settings;
    private Config.Listener onConfig;
    private FrameLayout content;
    private final TextView[] buttons = new TextView[NAMES.length];
    private int tab = -1;
    private boolean stopped = true;   // no screen of ours is showing

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The settings are files in the shell's tree, and every tab reads them.
        Config.prepare(this);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The tab bar: flat, a line over the open tab.
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.addView(Ui.divider(this));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < NAMES.length; i++) {
            final int which = i;
            TextView b = new TextView(this);
            b.setText(NAMES[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(14);
            b.setSingleLine(true);
            b.setTextColor(Ui.TEXT);
            b.setPadding(0, dp(this, 14), 0, dp(this, 14));
            b.setOnClickListener(v -> show(which));
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            buttons[i] = b;
        }
        bar.addView(row);
        root.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        // While the keyboard is up the tab bar steps aside, so the shell's key bar
        // sits right on the keyboard. The window resizes for it (adjustResize).
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(r);
            int screen = root.getRootView().getHeight();
            boolean ime = screen - r.bottom > screen / 4;
            int want = ime ? View.GONE : View.VISIBLE;
            if (bar.getVisibility() != want) bar.setVisibility(want);
        });

        apps = new AppsTab(this);
        shell = new ShellTab(this);
        files = new FilesTab(this, this::openShellAt);
        favorites = new FavoritesTab(this, new FavoritesTab.Host() {
            @Override public void openFolder(File dir) {
                files.openFolder(dir);
                show(FILES);
            }
            @Override public void openShellAt(File dir) {
                HomeActivity.this.openShellAt(dir);
            }
        });
        settings = new SettingsTab(this, apps::showHideDialog);
        content.addView(apps.view());
        content.addView(favorites.view());
        content.addView(files.view());
        content.addView(shell.view());
        content.addView(settings.view());
        onConfig = apps::onConfig;
        Config.addListener(onConfig);
        show(tabOf(getIntent(), APPS));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while the launcher is already on screen: the tab of the
        // launcher.homeTab setting. Pressed from another app: the tab that was open.
        int home = homeTab();
        if (!stopped && home == APPS) apps.reset();
        show(tabOf(intent, stopped ? tab : home));
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
    }

    @Override
    protected void onStop() {
        stopped = true;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ShellTab.setVisible(true);
        apps.reload();
        shell.onResume();
        if (tab == FILES) files.onResume();
        if (tab == FAVORITES) favorites.onShow();
        if (tab == SETTINGS) settings.onShow();   // Android's state may have changed
    }

    @Override
    protected void onPause() {
        ShellTab.setVisible(false);
        shell.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Config.removeListener(onConfig);
        settings.onHide();
        apps.onDestroy();
        shell.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (tab == APPS) {
            if (!apps.onBack()) super.onBackPressed();
            return;
        }
        if (tab == FILES && files.onBack()) return;
        if (tab == FAVORITES && favorites.onBack()) return;
        if (tab == SETTINGS && settings.onBack()) return;
        show(APPS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == FilesTab.REQUEST_STORAGE) files.onPermissionResult();
    }

    private static int tabOf(Intent i, int fallback) {
        if (i == null) return fallback;
        int t = i.getIntExtra(EXTRA_TAB, fallback);
        i.removeExtra(EXTRA_TAB);
        return t >= 0 && t < NAMES.length ? t : fallback;
    }

    void show(int which) {
        if (which == tab) return;
        tab = which;
        apps.view().setVisibility(which == APPS ? View.VISIBLE : View.GONE);
        favorites.view().setVisibility(which == FAVORITES ? View.VISIBLE : View.GONE);
        files.view().setVisibility(which == FILES ? View.VISIBLE : View.GONE);
        shell.view().setVisibility(which == SHELL ? View.VISIBLE : View.GONE);
        settings.view().setVisibility(which == SETTINGS ? View.VISIBLE : View.GONE);
        if (which == FAVORITES) favorites.onShow();
        if (which == SETTINGS) settings.onShow();
        else settings.onHide();
        if (which == SHELL) shell.onShow();
        else shell.onHide();
        if (which == FILES) files.onShow();
        else files.onHide();
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setAlpha(i == which ? 1f : 0.45f);
            buttons[i].setTypeface(null, i == which ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        }
    }

    private void openShellAt(File dir) {
        shell.openAt(dir.getPath());
        show(SHELL);
    }

    /** The launcher.homeTab setting, as a tab. */
    private static int homeTab() {
        String want = Config.get("launcher.homeTab");
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].toLowerCase(Locale.ROOT).equals(want)) return i;
        }
        return APPS;
    }

    static String versionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static int dp(Context c, int v) {
        return Ui.dp(c, v);
    }
}
