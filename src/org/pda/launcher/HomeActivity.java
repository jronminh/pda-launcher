package org.pda.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pda.launcher.files.FavoritesTab;
import org.pda.launcher.files.FilesTab;

import java.util.Locale;

/**
 * The home screen: five tabs (Apps, Favorites, Files, Note, Settings) over a tab
 * bar at the bottom. A tab's few options are in its own menu.
 */
public class HomeActivity extends Activity {

    public static final int APPS = 0, FAVORITES = 1, FILES = 2, NOTE = 3, SETTINGS = 4;
    private static final String[] NAMES = { "Apps", "Favorites", "Files", "Note", "Settings" };
    private static final int[] ICONS = { R.drawable.ic_apps, R.drawable.ic_star, R.drawable.ic_folder,
            R.drawable.ic_pencil, R.drawable.ic_gear };

    /** An intent extra: the tab to show. */
    public static final String EXTRA_TAB = "tab";

    private AppsTab apps;
    private FavoritesTab favorites;
    private FilesTab files;
    private NoteTab note;
    private SettingsScreen settingsTab;   // the Settings tab
    private Config.Listener onConfig;
    private final LinearLayout[] buttons = new LinearLayout[NAMES.length];   // an icon over a label
    private int tab = -1;
    private boolean stopped = true;   // no screen of ours is showing
    private boolean resumedOnce;
    private boolean appsRead;         // the apps are read once; LauncherApps tells changes after
    private View cover;               // black over the tabs while the system brings the launcher back
    private final Runnable uncoverLate = this::uncover;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Config.prepare(this);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // The tab bar: flat, the open tab bright and its label bold; icons, labels or
        // both (launcher.tabStyle).
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.addView(Ui.divider(this));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < NAMES.length; i++) {
            final int which = i;
            LinearLayout b = Ui.labeledAction(this, ICONS[i], NAMES[i], v -> show(which));
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            buttons[i] = b;
        }
        bar.addView(row);
        root.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The cover: the home screen comes back under a black sheet, lifted when
        // the system's transition is over (onEnterAnimationComplete). Returning
        // from Recents, One UI's Recents closes over the launcher, which is drawn
        // long before Recents is gone; with the sheet the eye sees black, then
        // the launcher.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(root);
        cover = new View(this);
        cover.setBackgroundColor(Ui.BG);
        cover.setClickable(true);   // nothing under it takes a tap meanwhile
        cover.setVisibility(View.GONE);
        frame.addView(cover);
        setContentView(frame);

        apps = new AppsTab(this);
        favorites = new FavoritesTab(this, dir -> {
            files.openFolder(dir);
            show(FILES);
        });
        files = new FilesTab(this);
        note = new NoteTab(this);
        settingsTab = new SettingsScreen(this);
        settingsTab.open(SettingsScreen.GENERAL);
        settingsTab.onHide();
        content.addView(apps.view());
        content.addView(favorites.view());
        content.addView(files.view());
        content.addView(note.view());
        content.addView(settingsTab.view());
        applyTabStyle();
        onConfig = keys -> {
            apps.onConfig(keys);
            if (keys.contains("launcher.tabStyle")) applyTabStyle();
        };
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

    /** Back from another app or Recents (not the first start): cover the tabs. */
    @Override
    protected void onRestart() {
        super.onRestart();
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        cover.removeCallbacks(uncoverLate);
        // In case the system never says. Measured on One UI 4: it says after
        // 280-380 ms, at most 550 ms seen.
        cover.postDelayed(uncoverLate, 1000);
    }

    /** The system's transition that brought the launcher back is over. */
    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        uncover();
    }

    private void uncover() {
        cover.removeCallbacks(uncoverLate);
        if (cover.getVisibility() != View.VISIBLE) return;
        if (!Anim.on()) { cover.setVisibility(View.GONE); return; }
        cover.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> cover.setVisibility(View.GONE)).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        apps.onStart();
    }

    @Override
    protected void onStop() {
        stopped = true;
        apps.onStop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Read the apps once. Coming back (from an app, from Recents) redraws
        // nothing during the system's transition: the package callback keeps the
        // list current, installs and removals included.
        if (!appsRead) {
            appsRead = true;
            apps.reload();
        }
        // Files reads its place when shown; at the first resume it was just shown.
        if (tab == FILES && resumedOnce) files.onResume();
        else if (tab == FAVORITES) favorites.onShow();
        else if (tab == SETTINGS) settingsTab.onShow();   // Android's state may have changed
        resumedOnce = true;
    }

    @Override
    protected void onDestroy() {
        Config.removeListener(onConfig);
        settingsTab.onHide();
        note.onDestroy();
        files.onDestroy();
        favorites.onDestroy();
        apps.onDestroy();
        super.onDestroy();
    }

    /** Every touch that goes down is noted, so a menu can open where the finger was. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) PopMenu.touchedAt(e.getRawX(), e.getRawY());
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (tab == APPS) {
            // Nothing left to go back to: a home screen stays. (The default would
            // close it, and the system would only open it again.)
            apps.onBack();
            return;
        }
        if (tab == FILES && files.onBack()) return;
        if (tab == FAVORITES && favorites.onBack()) return;
        if (tab == SETTINGS && settingsTab.onBack()) return;
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
        int from = tab;
        tab = which;
        apps.view().setVisibility(which == APPS ? View.VISIBLE : View.GONE);
        favorites.view().setVisibility(which == FAVORITES ? View.VISIBLE : View.GONE);
        files.view().setVisibility(which == FILES ? View.VISIBLE : View.GONE);
        note.view().setVisibility(which == NOTE ? View.VISIBLE : View.GONE);
        settingsTab.view().setVisibility(which == SETTINGS ? View.VISIBLE : View.GONE);
        // The new tab slides in from its side of the tab bar.
        if (from >= 0) Anim.slideIn(tabView(which), which - from);
        if (which == SETTINGS) settingsTab.onShow();
        else settingsTab.onHide();
        if (which == FAVORITES) favorites.onShow();
        if (which == FILES) files.onShow();
        else files.onHide();
        if (which != NOTE) note.onHide();
        if (which != FAVORITES) favorites.onHide();
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setAlpha(i == which ? 1f : 0.45f);
            label(i).setTypeface(null, i == which ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        }
    }

    private View tabView(int t) {
        switch (t) {
            case FAVORITES: return favorites.view();
            case FILES: return files.view();
            case NOTE: return note.view();
            case SETTINGS: return settingsTab.view();
            default: return apps.view();
        }
    }

    private TextView label(int tab) {
        return (TextView) buttons[tab].getChildAt(1);
    }

    /** Icons, labels or both on the tab bar; a label alone is larger. */
    private void applyTabStyle() {
        String style = Config.get("launcher.tabStyle");
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].getChildAt(0).setVisibility(style.equals("labels") ? View.GONE : View.VISIBLE);
            label(i).setVisibility(style.equals("icons") ? View.GONE : View.VISIBLE);
            label(i).setTextSize(style.equals("labels") ? 14 : 11);
        }
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
}
