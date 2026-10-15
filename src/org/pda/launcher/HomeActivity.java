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

import org.pda.launcher.files.FilesTab;
import org.pda.launcher.shell.ShellTab;

/** The home screen: three tabs (Apps, Files, Shell) over a tab bar at the bottom. */
public class HomeActivity extends Activity {

    public static final int APPS = 0, FILES = 1, SHELL = 2;
    private static final String[] NAMES = { "Apps", "Files", "Shell" };

    /** An intent extra: the tab to show (the shell's notification asks for SHELL). */
    public static final String EXTRA_TAB = "tab";

    private AppsTab apps;
    private ShellTab shell;
    private FilesTab files;
    private FrameLayout content;
    private final TextView[] buttons = new TextView[NAMES.length];
    private int tab = -1;
    private boolean stopped = true;   // no screen of ours is showing

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            b.setTextSize(15);
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
        files = new FilesTab(this, dir -> {
            shell.openAt(dir.getPath());
            show(SHELL);
        });
        content.addView(apps.view());
        content.addView(files.view());
        content.addView(shell.view());
        show(tabOf(getIntent(), APPS));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while the launcher is already on screen: back to the apps.
        // Pressed from another app: come back to the tab that was open.
        if (!stopped && tab == APPS) apps.reset();
        show(tabOf(intent, stopped ? tab : APPS));
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
    }

    @Override
    protected void onPause() {
        ShellTab.setVisible(false);
        shell.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
        files.view().setVisibility(which == FILES ? View.VISIBLE : View.GONE);
        shell.view().setVisibility(which == SHELL ? View.VISIBLE : View.GONE);
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
