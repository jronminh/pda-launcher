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

import java.util.List;

/**
 * The home screen: five tabs (Applications, Briefcase, Files, Today, Prefs; see
 * {@link Tabs}) over a tab bar at the bottom, in the order and the choice the
 * user made. A tab's few options are in its own menu.
 */
public class HomeActivity extends Activity {

    // The tabs' ids (Tabs): fixed whatever the order the user chose.
    public static final int APPS = Tabs.APPS, FAVORITES = Tabs.BRIEFCASE, FILES = Tabs.FILES,
            TODAY = Tabs.TODAY, SETTINGS = Tabs.PREFS;

    /** An intent extra with EXTRA_TAB = TODAY: the part of Today to show (TodayTab.BOARD...). */
    public static final String EXTRA_PART = "part";

    /** An intent extra: the tab to show. */
    public static final String EXTRA_TAB = "tab";
    /** An intent extra with EXTRA_TAB = FILES: a folder to show (Open with › PDA Launcher). */
    public static final String EXTRA_FOLDER = "folder";

    private AppsTab apps;
    private FavoritesTab favorites;
    private FilesTab files;
    private TodayTab today;
    private SettingsScreen settingsTab;   // the Prefs tab
    private Config.Listener onConfig;
    private final LinearLayout[] buttons = new LinearLayout[Tabs.count()];   // by id: an icon over a label
    private LinearLayout tabRow;   // the buttons of the tabs shown, in the user's order
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
        tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < Tabs.count(); i++) {
            final int which = i;
            // A tab's button opens it as it was left; on the tab already open, its start.
            buttons[i] = Ui.labeledAction(this, Tabs.ICONS[i], Tabs.NAMES[i],
                    v -> { if (which == tab) toStart(which); else show(which); });
            ((TextView) buttons[i].getChildAt(1)).setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        bar.addView(tabRow);
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

        apps = new AppsTab(this, () -> open(TODAY));
        favorites = new FavoritesTab(this, dir -> {
            files.openFolder(dir);
            show(FILES);
        });
        files = new FilesTab(this);
        today = new TodayTab(this, dir -> {
            files.openFolder(dir);
            show(FILES);
        });
        settingsTab = new SettingsScreen(this);
        settingsTab.open(SettingsScreen.GENERAL);
        settingsTab.onHide();
        content.addView(apps.view());
        content.addView(favorites.view());
        content.addView(files.view());
        content.addView(today.view());
        content.addView(settingsTab.view());
        applyTabStyle();
        buildTabBar();
        onConfig = keys -> {
            apps.onConfig(keys);
            if (keys.contains("launcher.tabStyle")) applyTabStyle();
            if (keys.contains("launcher.tabs")) {
                buildTabBar();
                if (!Tabs.on(tab)) show(Tabs.shown().get(0));
            }
        };
        Config.addListener(onConfig);
        // A start (a new process: an update, low memory, a reboot) comes back where
        // the launcher was left, if so set; an intent naming a tab wins.
        android.content.SharedPreferences st = getSharedPreferences(STATE, MODE_PRIVATE);
        boolean resume = Config.getBool("launcher.resume") && st.contains("tab");
        int start = resume ? st.getInt("tab", homeTab()) : homeTab();
        if (resume) {
            today.show(st.getInt("todayPart", TodayTab.START));
            files.restore(st.getString("filesPlace", null));
        }
        open(tabOf(getIntent(), start));
        showPart(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while the launcher is already on screen: the tab of the
        // launcher.homeTab setting. Pressed from another app: the tab that was open.
        int home = homeTab();
        if (!stopped && home == APPS) apps.reset();
        open(tabOf(intent, stopped ? tab : home));
        showPart(intent);
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
        saveState();
        if (tab == TODAY) today.onHide();   // no folder to follow while off screen
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
        // Back on screen, a tab reads again what may have changed meanwhile. At the
        // first resume the tab was just shown (and read), so not twice.
        if (resumedOnce) {
            if (tab == FILES) files.onResume();
            else if (tab == FAVORITES) favorites.onShow();
            else if (tab == SETTINGS) settingsTab.onShow();   // Android's state may have changed
            else if (tab == TODAY) today.onShow();   // its files may have changed elsewhere
        }
        resumedOnce = true;
    }

    @Override
    protected void onDestroy() {
        Config.removeListener(onConfig);
        settingsTab.onHide();
        today.onDestroy();
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
        if (tab == TODAY && today.onBack()) return;
        if (tab == SETTINGS && settingsTab.onBack()) return;
        show(APPS);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == org.pda.launcher.files.FilesTab.REQUEST_TREE && result == RESULT_OK) files.onTreePicked(data);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == FilesTab.REQUEST_STORAGE) {
            files.onPermissionResult();
            if (tab == TODAY) today.onShow();
        }
        if (code == Glance.REQUEST_CALENDAR) {
            apps.onCalendarPermission();
            today.onCalendarPermission();
        }
    }

    /** EXTRA_PART: a part of the Today tab to open (Share › Show on Note opens the board). */
    private void showPart(Intent i) {
        if (i != null && i.hasExtra(EXTRA_FOLDER)) {
            String path = i.getStringExtra(EXTRA_FOLDER);
            i.removeExtra(EXTRA_FOLDER);
            if (tab == FILES && path != null && new java.io.File(path).isDirectory()) {
                files.openFolder(new java.io.File(path));
            }
        }
        if (i == null || !i.hasExtra(EXTRA_PART)) return;
        int p = i.getIntExtra(EXTRA_PART, TodayTab.START);
        i.removeExtra(EXTRA_PART);
        if (tab == TODAY) today.show(p);
    }

    private static int tabOf(Intent i, int fallback) {
        if (i == null) return fallback;
        int t = i.getIntExtra(EXTRA_TAB, fallback);
        i.removeExtra(EXTRA_TAB);
        return t >= 0 && t < Tabs.count() ? t : fallback;
    }

    private static final String STATE = "state";

    /** Where the launcher is, kept for the next start (launcher.resume). */
    private void saveState() {
        getSharedPreferences(STATE, MODE_PRIVATE).edit()
                .putInt("tab", tab)
                .putInt("todayPart", today.part())
                .putString("filesPlace", files.placeKey())
                .apply();
    }

    /** The open tab's button tapped again: the tab's start, at once. */
    private void toStart(int t) {
        switch (t) {
            case APPS: apps.reset(); break;
            case FAVORITES: favorites.toStart(); break;
            case FILES: files.toStart(); break;
            case TODAY: today.show(TodayTab.START); break;
            default: settingsTab.open(SettingsScreen.GENERAL);
        }
    }

    /** Show a tab asked for by name; a tab turned off says so (and the first shown opens). */
    private void open(int which) {
        if (which >= 0 && !Tabs.on(which)) {
            android.widget.Toast.makeText(this, Tabs.NAMES[which] + " is turned off (Prefs › Launcher › Tabs)",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        show(which);
    }

    void show(int which) {
        if (!Tabs.on(which)) which = Tabs.shown().get(0);   // a tab turned off: the first shown
        if (which == tab) return;
        int from = tab;
        tab = which;
        apps.view().setVisibility(which == APPS ? View.VISIBLE : View.GONE);
        favorites.view().setVisibility(which == FAVORITES ? View.VISIBLE : View.GONE);
        files.view().setVisibility(which == FILES ? View.VISIBLE : View.GONE);
        today.view().setVisibility(which == TODAY ? View.VISIBLE : View.GONE);
        if (which == TODAY) today.onShow();
        settingsTab.view().setVisibility(which == SETTINGS ? View.VISIBLE : View.GONE);
        // The new tab slides in from its side of the tab bar.
        List<Integer> shown = Tabs.shown();
        if (from >= 0) Anim.slideIn(tabView(which), shown.indexOf(which) - shown.indexOf(from));
        if (which == SETTINGS) settingsTab.onShow();
        else settingsTab.onHide();
        if (which == FAVORITES) favorites.onShow();
        if (which == FILES) files.onShow();
        else files.onHide();
        if (which != TODAY) today.onHide();
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
            case TODAY: return today.view();
            case SETTINGS: return settingsTab.view();
            default: return apps.view();
        }
    }

    /** The tab bar: the tabs shown, in the user's order (Prefs › Launcher › Tabs). */
    private void buildTabBar() {
        tabRow.removeAllViews();
        for (int id : Tabs.shown()) {
            tabRow.addView(buttons[id], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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

    /** The launcher.homeTab setting, as a tab; a tab turned off gives the first shown. */
    private static int homeTab() {
        int id = Tabs.idOf(Config.get("launcher.homeTab"));
        return id >= 0 && Tabs.on(id) ? id : Tabs.shown().get(0);
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
