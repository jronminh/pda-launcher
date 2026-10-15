package org.pda.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The Prefs tab, after the Boox launcher's settings but smaller, and hiding
 * nothing of Android's: a banner (the device and the app) over tiles, a grid by
 * default (or details, or a list). The Launcher tile opens the launcher's
 * settings here ({@link #LAUNCHER}, a list of rows; Back returns); the others
 * open Android's screens. A tab's few options are in that tab's own menu.
 */
final class SettingsScreen {

    /** What the screen shows: the tiles, or the launcher's settings. */
    static final String GENERAL = "general", LAUNCHER = "launcher", TABS = "tabs", VIEWERS = "viewers";

    private final Activity act;
    private final LinearLayout root;
    private final Header header;
    private final ImageView viewBtn, sortBtn;
    private final LinearLayout banner;
    private final PagedView list;         // a group's rows
    private final Pager pager;            // the rows' pages
    private final List<Row> rows = new ArrayList<>();
    private final PagedView tiles;        // the general screen's tiles
    private final Pager tilePager;
    private final List<Tile> tileList = new ArrayList<>();
    private final List<Tile> shownTiles = new ArrayList<>();   // after the search and the order
    private String scope = GENERAL;
    private boolean fromGeneral;   // a group opened from the tiles: Back returns there
    private boolean fromLauncher;  // Tabs opened from the Launcher group: Back returns there

    private final Config.Listener onConfig = keys -> render();   // it shows the values

    /** One row of a group: a section title, or a setting that builds its own row. */
    private interface Entry {
        Row row();
    }

    /** What a row of a group shows: a title, a dim line, a value or "›", and what a tap does. */
    private static final class Row {
        final String title, summary, value;
        final Runnable click;

        Row(String title, String summary, String value, Runnable click) {
            this.title = title;
            this.summary = summary;
            this.value = value;
            this.click = click;
        }
    }

    /** One tile of the general screen. */
    private static final class Tile {
        final int icon;
        final String title;
        final Supplier<String> summary;
        final Runnable open;

        Tile(int icon, String title, Supplier<String> summary, Runnable open) {
            this.icon = icon;
            this.title = title;
            this.summary = summary;
            this.open = open;
        }
    }

    SettingsScreen(Activity act) {
        this.act = act;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        // The header: ‹ (inside a group), the title; on the tiles, search, sort and
        // the view button.
        tilePager = new Pager(act);
        viewBtn = Ui.action(act, PagedView.iconOf(Config.get("settings.view")), "View",
                v -> PagedView.chooseMode(act, v, Config.get("settings.view"), m -> Config.set(act, "settings.view", m)));
        sortBtn = Header.sort(act, "settings.sort", "settings.desc", "Default", "Name");
        header = new Header(act, "")
                .back(this::onBack)
                .search(q -> {
                    tilePager.setPage(0);
                    render();
                })
                .action(sortBtn)
                .action(viewBtn);
        root.addView(header.view());
        root.addView(Ui.divider(act));

        banner = Ui.banner(act);
        banner.setOnClickListener(v -> start(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        root.addView(banner);

        tiles = new PagedView(act, tilePager);
        buildTiles();
        tiles.setBinder(new PagedView.Binder() {
            @Override public int count() { return shownTiles.size(); }
            @Override public Drawable icon(int i) { return Ui.icon(act, shownTiles.get(i).icon); }
            @Override public String title(int i) { return shownTiles.get(i).title; }
            @Override public String summary(int i) {
                Supplier<String> s = shownTiles.get(i).summary;
                return s == null ? null : s.get();
            }
            @Override public String mark(int i) { return null; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return false; }
            @Override public void click(int i, View v) { shownTiles.get(i).open.run(); }
            @Override public boolean longClick(int i, View v) { return false; }
        });
        tiles.setGrid(4, 0);
        root.addView(tiles, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(tilePager);

        pager = new Pager(act);
        list = new PagedView(act, pager);
        list.setBinder(new PagedView.Binder() {
            @Override public int count() { return rows.size(); }
            @Override public Drawable icon(int i) { return null; }
            @Override public String title(int i) { return rows.get(i).title; }
            @Override public String summary(int i) { return rows.get(i).summary; }
            @Override public String mark(int i) { return rows.get(i).value; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return false; }
            @Override public void click(int i, View v) { rows.get(i).click.run(); }
            @Override public boolean longClick(int i, View v) { return false; }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(pager);
    }

    View view() {
        return root;
    }

    /** Show {@code scope} (GENERAL or a group), from the first page. */
    void open(String scope) {
        fromGeneral = false;
        fromLauncher = false;
        this.scope = scope;
        pager.setPage(0);
        onShow();
    }

    /** Prefs › Launcher, as if opened from the tiles (Back returns to them). */
    void openLauncher() {
        open(LAUNCHER);
        fromGeneral = true;
    }

    void onShow() {
        Config.addListener(onConfig);
        render();
    }

    void onHide() {
        Config.removeListener(onConfig);
    }

    /**
     * Back (and ‹): the search, then from the launcher's settings back to the tiles.
     * @return false when there is nothing to go back to.
     */
    boolean onBack() {
        if (header.searching()) { header.closeSearch(); return true; }
        if (fromLauncher) {
            open(LAUNCHER);
            fromGeneral = true;
            return true;
        }
        if (fromGeneral) { open(GENERAL); return true; }
        return false;
    }

    private void openGroup(String group) {
        if (header.searching()) header.closeSearch();
        open(group);
        fromGeneral = true;
        render();
    }

    // --- the tiles -----------------------------------------------------------------------

    private void buildTiles() {
        // The launcher's own: their settings open here.
        tileList.add(new Tile(R.drawable.ic_home, "Launcher",
                () -> "Home button: " + Tabs.NAMES[Math.max(0, Tabs.idOf(Config.get("launcher.homeTab")))], () -> openGroup(LAUNCHER)));
        // Android's: each opens its screen.
        tileList.add(android(R.drawable.ic_wifi, "Wi-Fi", this::wifiState, Settings.ACTION_WIFI_SETTINGS));
        tileList.add(android(R.drawable.ic_bluetooth, "Bluetooth", this::bluetoothState, Settings.ACTION_BLUETOOTH_SETTINGS));
        tileList.add(android(R.drawable.ic_display, "Display", null, Settings.ACTION_DISPLAY_SETTINGS));
        tileList.add(android(R.drawable.ic_sound, "Sound", null, Settings.ACTION_SOUND_SETTINGS));
        tileList.add(android(R.drawable.ic_battery, "Battery", this::batteryState, Intent.ACTION_POWER_USAGE_SUMMARY));
        tileList.add(android(R.drawable.ic_storage, "Storage", this::storageState, Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
        tileList.add(android(R.drawable.ic_package, "Applications", null, Settings.ACTION_APPLICATION_SETTINGS));
        // The rest (notifications, location, security, accounts, date, language,
        // developer...) is one tap further, in Android's own list: few tiles, each daily.
        tileList.add(android(R.drawable.ic_gear, "All settings", null, Settings.ACTION_SETTINGS));
    }

    /** The tiles whose title matches the search, in the default order or by name. */
    private void filterTiles() {
        shownTiles.clear();
        String q = header.query().toLowerCase(Locale.ROOT);
        for (Tile t : tileList) if (q.isEmpty() || t.title.toLowerCase(Locale.ROOT).contains(q)) shownTiles.add(t);
        if (Config.get("settings.sort").equals("name")) {
            shownTiles.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
        }
        if (Config.getBool("settings.desc")) java.util.Collections.reverse(shownTiles);
    }

    private Tile android(int icon, String title, Supplier<String> summary, String action) {
        return new Tile(icon, title, summary, () -> start(new Intent(action)));
    }

    /** The banner: the device and the app, at a glance. */
    private void fillBanner() {
        // Static facts only, kept short so the tiles below have the room. Tap it
        // for Android's About phone (so no tile for it). Battery and storage live
        // in their own tiles' details.
        banner.removeAllViews();
        String name = Settings.Global.getString(act.getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name == null || name.isEmpty()) name = Build.MODEL;
        banner.addView(Ui.bannerTitle(act, name));
        String maker = Build.MANUFACTURER.substring(0, 1).toUpperCase(Locale.ROOT) + Build.MANUFACTURER.substring(1);
        banner.addView(Ui.bannerLine(act, maker + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " · Launcher " + HomeActivity.versionName(act)));
    }



    // --- a group's rows ------------------------------------------------------------------

    private List<Entry> entries() {
        List<Entry> e = new ArrayList<>();
        switch (scope) {
            case LAUNCHER:
                e.add(choice("launcher.homeTab", "Home button opens", homeChoices()));
                e.add(() -> new Row("Tabs", tabsSummary(), "›", () -> {
                    open(TABS);
                    fromGeneral = true;
                    fromLauncher = true;
                }));
                e.add(() -> new Row("Viewers", viewersSummary(), "›", () -> {
                    open(VIEWERS);
                    fromGeneral = true;
                    fromLauncher = true;
                }));
                e.add(choice("launcher.theme", "Theme", "oled:OLED (black)", "dark:Dark", "light:Light", "eink:E-ink"));
                e.add(choice("launcher.pattern", "Background", "none:Plain", "dots:Dots", "grid:Grid",
                        "lines:Lines", "diagonal:Diagonal", "palm:Palm LCD"));
                e.add(toggle("launcher.resume", "Start where I left off"));
                e.add(toggle("launcher.animations", "Animations"));
                e.add(choice("launcher.tabStyle", "Tab bar", "both:Icons and labels", "icons:Icons", "labels:Labels"));
                e.add(link("Default home app", isDefaultHome() ? "PDA Launcher" : "Another app",
                        new Intent(Settings.ACTION_HOME_SETTINGS)));
                e.add(link("App info", "PDA Launcher " + HomeActivity.versionName(act), appDetails()));
                break;
            case VIEWERS:
                // Per kind: the launcher's own viewer, or the app Android picks.
                e.add(choice("open.text", "Text files", "builtin:Built-in editor", "system:System app"));
                e.add(choice("open.image", "Images", "builtin:Built-in viewer", "system:System app"));
                e.add(choice("open.audio", "Audio", "builtin:Built-in player", "system:System app"));
                e.add(choice("open.pdf", "PDF", "builtin:Built-in reader", "system:System app"));
                break;
            case TABS:
                // Each tab: tap for on or off, up, down (Prefs stays on: the way back here).
                for (final int id : Tabs.order()) {
                    e.add(() -> new Row(Tabs.NAMES[id], null,
                            id == Tabs.PREFS ? "Always" : Tabs.on(id) ? "On" : "Off", () -> tabMenu(id)));
                }
                break;
            default:
                break;
        }
        return e;
    }

    private void render() {
        boolean general = GENERAL.equals(scope);
        // The tab bar names the tab; a title says only where inside it one is.
        header.setTitle(general ? "" : label(scope));
        header.showBack(fromGeneral);
        header.setSearchEnabled(general);
        viewBtn.setVisibility(general ? View.VISIBLE : View.GONE);
        sortBtn.setVisibility(general ? View.VISIBLE : View.GONE);
        banner.setVisibility(general && header.query().isEmpty() ? View.VISIBLE : View.GONE);
        tiles.setVisibility(general ? View.VISIBLE : View.GONE);
        tilePager.setVisibility(general ? View.VISIBLE : View.GONE);
        list.setVisibility(general ? View.GONE : View.VISIBLE);
        pager.setVisibility(general ? View.GONE : View.VISIBLE);
        if (general) {
            String mode = Config.get("settings.view");
            viewBtn.setImageResource(PagedView.iconOf(mode));
            fillBanner();
            filterTiles();
            tiles.setMode(mode);
            tiles.refresh();
            return;
        }
        rows.clear();
        for (Entry e : entries()) rows.add(e.row());
        list.refresh();
    }

    /** "key:Name" for each tab, " (off)" after one turned off (Home then opens the first shown). */
    private static String[] homeChoices() {
        String[] out = new String[Tabs.count()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Tabs.KEYS[i] + ":" + Tabs.NAMES[i] + (Tabs.on(i) ? "" : " (off)");
        }
        return out;
    }

    /** "Built-in: Text, Images" or "All system apps". */
    private String viewersSummary() {
        StringBuilder sb = new StringBuilder();
        String[] keys = { "text", "image", "audio", "pdf" }, names = { "Text", "Images", "Audio", "PDF" };
        for (int i = 0; i < keys.length; i++) {
            if (!Config.get("open." + keys[i]).equals("builtin")) continue;
            sb.append(sb.length() == 0 ? "Built-in: " : ", ").append(names[i]);
        }
        return sb.length() == 0 ? "All system apps" : sb.toString();
    }

    private String tabsSummary() {
        StringBuilder sb = new StringBuilder();
        for (int id : Tabs.shown()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Tabs.NAMES[id]);
        }
        return sb.toString();
    }

    /** A tab's row was tapped: on or off, up, down; opened at the finger. */
    private void tabMenu(int id) {
        PopMenu m = new PopMenu(act, list);
        List<Integer> order = Tabs.order();
        int at = order.indexOf(id);
        if (id != Tabs.PREFS) m.add(0, 1, 0, Tabs.on(id) ? "Turn off" : "Turn on");
        if (at > 0) m.add(0, 2, 1, "Move up");
        if (at < order.size() - 1) m.add(0, 3, 2, "Move down");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: Tabs.toggle(act, id); break;
                case 2: Tabs.move(act, id, -1); break;
                default: Tabs.move(act, id, 1);
            }
            return true;
        });
        m.show();
    }

    // --- kinds of row --------------------------------------------------------------------

    /** Opens a screen of Android's. */
    private Entry link(String title, String summary, Intent intent) {
        return () -> {
            return new Row(title, summary, "›", () -> start(intent));
        };
    }

    private Entry toggle(String key, String title) {
        return () -> {
            boolean on = Config.getBool(key);
            return new Row(title, null, on ? "On" : "Off", () -> set(key, on ? "false" : "true"));
        };
    }

    /** One of the schema's values; {@code labels} are "value:Label" pairs (default: the value). */
    private Entry choice(String key, String title, String... labels) {
        return () -> {
            String[] row = Config.schema(key);
            String[] values;
            if (labels.length > 0) {
                values = new String[labels.length];
                for (int i = 0; i < labels.length; i++) values[i] = labels[i].substring(0, labels[i].indexOf(':'));
            } else {
                values = row != null ? row[2].split("\\|") : new String[0];
            }
            String[] names = new String[values.length];
            for (int i = 0; i < values.length; i++) names[i] = labels.length > 0
                    ? labels[i].substring(labels[i].indexOf(':') + 1) : label(values[i]);
            String cur = Config.get(key);
            int at = java.util.Arrays.asList(values).indexOf(cur);
            return new Row(title, null, at >= 0 ? names[at] : cur, () -> new AlertDialog.Builder(act)
                    .setTitle(title)
                    .setSingleChoiceItems(names, at, (d, which) -> {
                        d.dismiss();
                        set(key, values[which]);
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        };
    }

    private void set(String key, String value) {
        String err = Config.set(act, key, value);
        if (err != null) Toast.makeText(act, err, Toast.LENGTH_LONG).show();
        render();
    }

    private static String label(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }

    // --- Android's state, for the summaries ----------------------------------------------

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = act.getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && act.getPackageName().equals(ri.activityInfo.packageName);
    }

    private String wifiState() {
        WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        try {
            return wm != null && wm.isWifiEnabled() ? "On" : "Off";
        } catch (SecurityException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private String bluetoothState() {
        try {
            BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
            return b == null ? null : b.isEnabled() ? "On" : "Off";
        } catch (SecurityException e) {
            return null;
        }
    }

    private String batteryState() {
        Intent i = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return null;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        return Math.round(level * 100f / scale) + "%" + (charging ? " · charging" : "");
    }

    private String storageState() {
        try {
            StatFs s = new StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            return Formatter.formatShortFileSize(act, s.getAvailableBytes()) + " free";
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Intent appDetails() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName()));
    }

    private void start(Intent i) {
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(act, "This phone has no such screen", Toast.LENGTH_SHORT).show();
        }
    }
}
