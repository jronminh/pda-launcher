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
 * The Applications tab, after Pocket PC's Start menu and in the look of Prefs:
 * one page of tiles, each a need (Phone, Messages, Camera, Browser...) drawn
 * with the launcher's own line icon and named by it, opening Android's default
 * app for that need or the one chosen for the tile; then a last tile, More,
 * for every app with its own icon (paged, sorted, searched). The tiles are a
 * fixed list, turned on or off and ordered by the user, so an install never
 * moves them; new apps go to More, marked • until first opened. The view
 * (grid, list, details) and the grid's size are shared; the search covers
 * every app.
 */
final class AppsTab {

    static final String PREFS = "launcher";
    static final String KEY_HIDDEN = "hidden";
    static final String KEY_ROLES = "roles";       // the tiles, in order, "-" before one off
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
                if (isMore(i)) return act.getDrawable(R.drawable.ic_apps);
                return onTiles() ? act.getDrawable(tiles.get(i).icon) : shown.get(i).icon;
            }
            @Override public String title(int i) {
                if (isMore(i)) return "More";
                return onTiles() ? tiles.get(i).name : shown.get(i).name;
            }
            @Override public String summary(int i) {
                if (isMore(i)) {
                    int n = newCount();
                    return visibleCount() + " apps" + (n > 0 ? " · " + n + " new" : "");
                }
                if (onTiles()) {
                    Role r = tiles.get(i);
                    String st = status.get(r.key);
                    if (st != null) return st;
                    App a = tileApp.get(r.key);
                    if (r.action) return ready(r) ? (a != null ? a.name : "") : "Not on this device";
                    return a == null ? "Choose an app" : a.name;
                }
                App a = shown.get(i);
                return a.version == null || a.version.isEmpty() ? a.pkg : a.version + " · " + a.pkg;
            }
            @Override public String mark(int i) {
                if (isMore(i)) return newCount() > 0 ? "•" : null;
                return !onTiles() && isNew(shown.get(i)) ? "•" : null;
            }
            @Override public boolean highlight(int i) {
                return onTiles() && !isMore(i) && tiles.get(i).key.equals("flashlight") && torchOn;
            }
            @Override public boolean dim(int i) {
                return onTiles() && !isMore(i) && !ready(tiles.get(i));
            }
            @Override public void click(int i, View v) {
                if (isMore(i)) showMore(true);
                else if (onTiles()) openTile(tiles.get(i), v);
                else launch(shown.get(i).pkg);
            }
            @Override public boolean longClick(int i, View v) {
                if (isMore(i)) return false;
                if (onTiles()) tileMenu(v, tiles.get(i));
                else appMenu(v, shown.get(i));
                return true;
            }
        });
        applyView();
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(pager, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        launcherApps.registerCallback(onPackages, new Handler(Looper.getMainLooper()));
        cameras = act.getSystemService(android.hardware.camera2.CameraManager.class);
        torchId = cameras == null ? null : findTorch(cameras);
        if (torchId != null) cameras.registerTorchCallback(onTorch, new Handler(Looper.getMainLooper()));
    }

    View view() {
        return root;
    }

    void onDestroy() {
        launcherApps.unregisterCallback(onPackages);
        if (torchId != null) cameras.unregisterTorchCallback(onTorch);
    }

    /** On screen: the glance line follows the minute, the battery, the alarms, the calendar. */
    void onStart() {
        glance.onStart();
        if (onTiles()) updateStatus();   // the alarm, the event, the inbox may have changed
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

    /**
     * A tile: a need (Phone, Camera...), drawn as Prefs' tiles are, opening an app
     * for it; or an action (Flashlight, Timer...) the tile does itself.
     */
    static final class Role {
        final String key, name;
        final int icon;
        final Intent intent;   // what the need is, to find Android's default app and the candidates
        final boolean action;  // the tile does it (see doAction); off until turned on

        Role(String key, String name, int icon, Intent intent) {
            this(key, name, icon, intent, false);
        }

        Role(String key, String name, int icon, Intent intent, boolean action) {
            this.key = key;
            this.name = name;
            this.icon = icon;
            this.intent = intent;
            this.action = action;
        }
    }

    /** Every tile there is, in the first order; standard intents only, so any device answers. */
    private static List<Role> allRoles() {
        List<Role> r = new ArrayList<>();
        r.add(new Role("phone", "Phone", R.drawable.ic_phone, new Intent(Intent.ACTION_DIAL)));
        r.add(new Role("messages", "Messages", R.drawable.ic_message,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)));
        r.add(new Role("contacts", "Contacts", R.drawable.ic_account,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)));
        r.add(new Role("camera", "Camera", R.drawable.ic_camera,
                new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)));
        r.add(new Role("gallery", "Gallery", R.drawable.ic_image,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)));
        r.add(new Role("browser", "Browser", R.drawable.ic_globe,
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")).addCategory(Intent.CATEGORY_BROWSABLE)));
        r.add(new Role("email", "Email", R.drawable.ic_mail,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL)));
        r.add(new Role("calendar", "Calendar", R.drawable.ic_calendar,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)));
        r.add(new Role("clock", "Clock", R.drawable.ic_clock, new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)));
        r.add(new Role("music", "Music", R.drawable.ic_audio,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC)));
        r.add(new Role("maps", "Maps", R.drawable.ic_location,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS)));
        r.add(new Role("calculator", "Calculator", R.drawable.ic_calc,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR)));
        r.add(new Role("store", "Store", R.drawable.ic_store,
                new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.pda.launcher"))));
        // Actions: the tile does a thing at once (intent: the app that must exist for it, or none).
        r.add(new Role("flashlight", "Flashlight", R.drawable.ic_flash, null, true));
        r.add(new Role("capture", "Capture", R.drawable.ic_pen, null, true));
        r.add(new Role("timer", "Timer", R.drawable.ic_timer,
                new Intent(android.provider.AlarmClock.ACTION_SET_TIMER), true));
        r.add(new Role("event", "New event", R.drawable.ic_event_add,
                new Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI), true));
        r.add(new Role("websearch", "Web search", R.drawable.ic_search,
                new Intent(Intent.ACTION_WEB_SEARCH), true));
        return r;
    }

    private final List<Role> roles = allRoles();
    private final List<Role> tiles = new ArrayList<>();       // on, in the user's order, as shown
    private final Map<String, App> tileApp = new java.util.HashMap<>();   // role key -> the app it opens

    /** The tiles are on screen: not in More, not searching. */
    private boolean onTiles() {
        return !more && header.query().isEmpty();
    }

    private boolean isMore(int i) {
        return onTiles() && i == tileCount();
    }

    /** How many of the tiles fit: one page, less the More tile. */
    private int tileCount() {
        return Math.min(tiles.size(), list.perPage() - 1);
    }

    private void showMore(boolean on) {
        if (header.searching()) header.closeSearch();
        more = on;
        list.slideNext(on ? 1 : -1);
        pager.setPage(0);
        applyFilter();
    }

    /** The order and the choice, as "phone,messages,-email": a minus is off; a tile not named is on, last. */
    private List<String> roleOrder() {
        List<String> out = new ArrayList<>();
        for (String k : prefs.getString(KEY_ROLES, "").split(",")) {
            String key = k.startsWith("-") ? k.substring(1) : k;
            if (role(key) != null && !out.contains(k) && !out.contains("-" + key) && !out.contains(key)) out.add(k);
        }
        for (Role r : roles) {
            if (!out.contains(r.key) && !out.contains("-" + r.key)) out.add(r.action ? "-" + r.key : r.key);
        }
        return out;
    }

    private void saveRoles(List<String> order) {
        prefs.edit().putString(KEY_ROLES, String.join(",", order)).apply();
        applyFilter();
    }

    private Role role(String key) {
        for (Role r : roles) if (r.key.equals(key)) return r;
        return null;
    }

    /** The app a tile opens: the one chosen for it, else Android's default for its need; null if none. */
    private App appFor(Role r, Map<String, App> byPkg) {
        String chosen = prefs.getString("role." + r.key, null);
        if (chosen != null && byPkg.containsKey(chosen)) return byPkg.get(chosen);
        try {
            android.content.pm.ResolveInfo ri = act.getPackageManager()
                    .resolveActivity(r.intent, PackageManager.MATCH_DEFAULT_ONLY);
            // "android" is the chooser: no default yet.
            if (ri != null && ri.activityInfo != null) return byPkg.get(ri.activityInfo.packageName);
        } catch (RuntimeException ignore) {}
        return null;
    }

    /** The tiles from the order; each with its app (hidden apps open too: the tile is the way to them). */
    private void buildTiles() {
        tiles.clear();
        tileApp.clear();
        Map<String, App> byPkg = new java.util.HashMap<>();
        for (App a : all) byPkg.put(a.pkg, a);
        for (String k : roleOrder()) {
            if (k.startsWith("-")) continue;
            Role r = role(k);
            tiles.add(r);
            App a = r.action && r.intent == null ? null : appFor(r, byPkg);
            if (a != null) tileApp.put(r.key, a);
        }
        updateStatus();
    }

    /** A tile can do its thing: an app for it, or (an action with none needed) the device can. */
    private boolean ready(Role r) {
        if (!r.action || r.intent != null) {
            if (tileApp.containsKey(r.key)) return true;
            // An action may be answered by an app that is not a launchable one (a timer service...).
            return r.action && act.getPackageManager().resolveActivity(r.intent, 0) != null;
        }
        return !r.key.equals("flashlight") || torchId != null;
    }

    private void openTile(Role r, View anchor) {
        if (r.action) {
            if (ready(r)) doAction(r, anchor);
            else android.widget.Toast.makeText(act, r.name + ": nothing on this device can do it",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        App a = tileApp.get(r.key);
        if (a != null) launch(a.pkg);
        else chooseApp(r);
    }

    private void doAction(Role r, View anchor) {
        switch (r.key) {
            case "flashlight":
                try {
                    cameras.setTorchMode(torchId, !torchOn);
                } catch (Exception e) {   // in use by the camera, or refused
                    android.widget.Toast.makeText(act, "The flashlight is busy", android.widget.Toast.LENGTH_SHORT).show();
                }
                break;
            case "capture":
                start(new Intent(act, CaptureActivity.class));
                break;
            case "timer": {
                final int[] minutes = { 1, 3, 5, 10, 15, 25, 30, 45, 60 };
                PopMenu pm = new PopMenu(act, anchor);
                for (int i = 0; i < minutes.length; i++) pm.getMenu().add(0, i, i, minutes[i] + " min");
                pm.setOnMenuItemClickListener(item -> {
                    start(new Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                            .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, minutes[item.getItemId()] * 60)
                            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true));
                    android.widget.Toast.makeText(act, "Timer: " + minutes[item.getItemId()] + " min",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return true;
                });
                pm.show();
                break;
            }
            case "event":
                start(new Intent(Intent.ACTION_INSERT, android.provider.CalendarContract.Events.CONTENT_URI));
                break;
            default: {   // websearch
                final android.widget.EditText q = new android.widget.EditText(act);
                q.setSingleLine(true);
                q.setHint("Search the web");
                q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
                final AlertDialog d = new AlertDialog.Builder(act)
                        .setView(q)
                        .setPositiveButton("Search", (dd, w) -> webSearch(q.getText().toString()))
                        .setNegativeButton("Cancel", null)
                        .create();
                q.setOnEditorActionListener((v, id, e) -> {
                    d.dismiss();
                    webSearch(q.getText().toString());
                    return true;
                });
                d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                d.show();
            }
        }
    }

    private void webSearch(String query) {
        if (query.trim().isEmpty()) return;
        start(new Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query.trim()));
    }

    // --- the tiles' state lines ----------------------------------------------------------

    private final Map<String, String> status = new java.util.HashMap<>();   // role key -> its line
    private int statusGen;
    private android.hardware.camera2.CameraManager cameras;
    private String torchId;     // the back camera with a flash, or null
    private boolean torchOn;
    private final android.hardware.camera2.CameraManager.TorchCallback onTorch =
            new android.hardware.camera2.CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean on) {
            if (!id.equals(torchId)) return;
            torchOn = on;
            status.put("flashlight", on ? "On" : "Off");
            if (onTiles()) list.refresh();
        }
    };

    private static String findTorch(android.hardware.camera2.CameraManager cm) {
        try {
            for (String id : cm.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (Exception ignore) {}
        return null;
    }

    /** What some tiles say: the next alarm, the next event, the inbox, the flashlight. */
    private void updateStatus() {
        long now = System.currentTimeMillis();
        android.app.AlarmManager am = (android.app.AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
        android.app.AlarmManager.AlarmClockInfo alarm = am != null ? am.getNextAlarmClock() : null;
        if (alarm != null) status.put("clock", "Alarm " + Glance.when(act, alarm.getTriggerTime(), now));
        else status.remove("clock");
        if (torchId != null) status.put("flashlight", torchOn ? "On" : "Off");
        final int gen = ++statusGen;
        final boolean events = Glance.calendarAllowed(act);
        new Thread(() -> {
            String[] e = events ? Glance.nextEvent(act, now) : null;
            int inbox = -1;
            try {
                inbox = org.pda.launcher.today.PdaStore.inboxNotes().size();
            } catch (RuntimeException ignore) {}
            final int n = inbox;
            act.runOnUiThread(() -> {
                if (gen != statusGen) return;
                if (e != null) status.put("calendar", e[0]);
                else status.remove("calendar");
                if (n > 0) status.put("capture", n + " in the inbox");
                else if (n == 0) status.put("capture", "Inbox empty");
                if (onTiles()) list.refresh();
            });
        }, "tiles-status").start();
    }

    /** Which app a tile opens: the apps for its need, any app, or Android's default again. */
    private void chooseApp(Role r) {
        List<App> cands = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, App> byPkg = new java.util.HashMap<>();
        for (App a : all) byPkg.put(a.pkg, a);
        for (android.content.pm.ResolveInfo ri : act.getPackageManager().queryIntentActivities(r.intent, 0)) {
            App a = byPkg.get(ri.activityInfo.packageName);
            if (a != null && seen.add(a.pkg)) cands.add(a);
        }
        Collections.sort(cands, (x, y) -> x.name.compareToIgnoreCase(y.name));
        final boolean chosen = prefs.contains("role." + r.key);
        String[] names = new String[cands.size() + (chosen ? 2 : 1)];
        int n = 0;
        if (chosen) names[n++] = "Android's default";
        for (App a : cands) names[n++] = a.name;
        names[n] = "Another app…";
        new AlertDialog.Builder(act)
                .setTitle(r.name + " opens")
                .setItems(names, (d, which) -> {
                    if (chosen && which == 0) {
                        prefs.edit().remove("role." + r.key).apply();
                        applyFilter();
                        return;
                    }
                    int i = which - (chosen ? 1 : 0);
                    if (i < cands.size()) setRoleApp(r, cands.get(i).pkg);
                    else chooseAnyApp(r);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseAnyApp(Role r) {
        final List<App> apps = new ArrayList<>(all);
        Collections.sort(apps, (x, y) -> x.name.compareToIgnoreCase(y.name));
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i).name;
        new AlertDialog.Builder(act)
                .setTitle(r.name + " opens")
                .setItems(names, (d, which) -> setRoleApp(r, apps.get(which).pkg))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** {@code pkg} opens the tile, which is turned on if it was off. */
    private void setRoleApp(Role r, String pkg) {
        prefs.edit().putString("role." + r.key, pkg).apply();
        List<String> order = roleOrder();
        int off = order.indexOf("-" + r.key);
        if (off >= 0) order.set(off, r.key);
        saveRoles(order);
    }

    /** ⋮ › Tiles: which are on (the order is changed on a tile, long-press). */
    private void tilesDialog() {
        final List<String> order = roleOrder();
        String[] names = new String[order.size()];
        boolean[] on = new boolean[order.size()];
        for (int i = 0; i < order.size(); i++) {
            String k = order.get(i);
            on[i] = !k.startsWith("-");
            names[i] = role(on[i] ? k : k.substring(1)).name;
        }
        new AlertDialog.Builder(act)
                .setTitle("Tiles")
                .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    for (int i = 0; i < order.size(); i++) {
                        String k = order.get(i), key = k.startsWith("-") ? k.substring(1) : k;
                        order.set(i, on[i] ? key : "-" + key);
                    }
                    saveRoles(order);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Long-press on a tile: its app's shortcuts, then which app, up, down, off. */
    private void tileMenu(View anchor, Role r) {
        final App a = tileApp.get(r.key);
        PopMenu pm = new PopMenu(act, anchor);
        final List<ShortcutInfo> shortcuts = a == null ? new ArrayList<>() : shortcuts(a.pkg);
        addShortcuts(pm, shortcuts);
        final int pos = tiles.indexOf(r);
        if (!r.action) pm.getMenu().add(0, 1, 100, "Opens: " + (a == null ? "nothing yet" : a.name) + "  ›");
        if (pos > 0) pm.getMenu().add(0, 2, 101, "Move up");
        if (pos < tileCount() - 1) pm.getMenu().add(0, 3, 102, "Move down");
        pm.getMenu().add(0, 4, 103, "Turn off");
        if (a != null) pm.getMenu().add(0, 5, 104, "App info");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() >= 100) {
                startShortcut(shortcuts.get(item.getItemId() - 100));
                return true;
            }
            List<String> order = roleOrder();
            switch (item.getItemId()) {
                case 1: chooseApp(r); break;
                case 2: Collections.swap(order, order.indexOf(r.key), order.indexOf(tiles.get(pos - 1).key)); saveRoles(order); break;
                case 3: Collections.swap(order, order.indexOf(r.key), order.indexOf(tiles.get(pos + 1).key)); saveRoles(order); break;
                case 4: order.set(order.indexOf(r.key), "-" + r.key); saveRoles(order); break;
                default: start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
            }
            return true;
        });
        pm.show();
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
        pm.getMenu().add(0, 6, -1, "Tiles…");
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
                case 6: tilesDialog(); break;
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
                if (!prefs.contains(KEY_SINCE)) prefs.edit().putLong(KEY_SINCE, System.currentTimeMillis()).apply();
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
            buildTiles();
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
        addShortcuts(pm, shortcuts);
        pm.getMenu().add(0, 4, 98, "Use for a tile  ›");
        pm.getMenu().add(0, 8, 99, "Add to Briefcase  ›");
        pm.getMenu().add(0, 1, 100, "App info");
        pm.getMenu().add(0, 2, 101, "Hide");
        pm.getMenu().add(0, 3, 102, "Uninstall");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() >= 100) {
                startShortcut(shortcuts.get(item.getItemId() - 100));
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
                case 4: useFor(anchor, app); break;
                case 8: org.pda.launcher.files.FavoritesTab.addFromApp(act, app.pkg, app.name, null); break;
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

    /** An app's shortcuts at the top of a menu, ids 100 and on. */
    private void addShortcuts(PopMenu pm, List<ShortcutInfo> shortcuts) {
        for (int i = 0; i < shortcuts.size(); i++) {
            ShortcutInfo si = shortcuts.get(i);
            CharSequence label = si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel();
            Drawable icon = null;
            try {
                icon = launcherApps.getShortcutIconDrawable(si, act.getResources().getDisplayMetrics().densityDpi);
            } catch (RuntimeException ignore) {}
            pm.getMenu().add(0, 100 + i, i, String.valueOf(label)).setIcon(icon);
        }
    }

    private void startShortcut(ShortcutInfo si) {
        try {
            launcherApps.startShortcut(si, null, null);
        } catch (RuntimeException e) {   // gone, disabled, or the app refuses
            android.widget.Toast.makeText(act, "Could not open the shortcut", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** More, long-press › Use for a tile: which tile opens this app (turned on if off). */
    private void useFor(View anchor, App app) {
        PopMenu pm = new PopMenu(act, anchor);
        for (int i = 0; i < roles.size(); i++) if (!roles.get(i).action) pm.getMenu().add(0, i, i, roles.get(i).name);
        pm.setOnMenuItemClickListener(item -> {
            setRoleApp(roles.get(item.getItemId()), app.pkg);
            android.widget.Toast.makeText(act, roles.get(item.getItemId()).name + " opens " + app.name,
                    android.widget.Toast.LENGTH_SHORT).show();
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
