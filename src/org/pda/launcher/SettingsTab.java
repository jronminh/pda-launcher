package org.pda.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.Toast;

import org.pda.launcher.shell.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Settings tab: the app's settings and shortcuts to the basic Android ones,
 * one page at a time.
 *
 * <p>The app's settings are the files of the shell's tree ({@code etc/}, described
 * by {@code lib/config.schema}). This screen writes them through {@link Config#set}
 * (the {@code config} command), so the same settings can be read and changed from
 * the Shell tab: {@code config set apps.columns 5}. Android's settings are not
 * the app's to change: their rows show the state and open the system screen.
 */
final class SettingsTab {

    private final Activity act;
    private final Runnable showHideApps;
    private final LinearLayout root;
    private final LinearLayout list;
    private final Pager pager;
    private int rowsPerPage = 8;

    private final Config.Listener onConfig = this::render;

    /** One row of the list: a section title, or a setting that builds its own row. */
    private interface Entry {
        View view();
    }

    SettingsTab(Activity act, Runnable showHideApps) {
        this.act = act;
        this.showHideApps = showHideApps;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.addView(Ui.header(act, Ui.title(act, "Settings")));
        root.addView(Ui.divider(act));

        android.view.GestureDetector flip = new android.view.GestureDetector(act,
                new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                return pager.turn(vx < 0 ? 1 : -1);
            }
        });
        list = new LinearLayout(act) {
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
        list.setOrientation(LinearLayout.VERTICAL);
        list.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int rows = Math.max(1, (b - t) / Ui.dp(act, Ui.ROW_DP));
            if (b - t > 0 && rows != rowsPerPage) {
                rowsPerPage = rows;
                list.post(this::render);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pager = new Pager(act);
        pager.setListener(p -> render());
        root.addView(pager);
    }

    View view() {
        return root;
    }

    void onShow() {
        Config.addListener(onConfig);
        render();
    }

    void onHide() {
        Config.removeListener(onConfig);
    }

    boolean onBack() {
        if (pager.page() == 0) return false;
        pager.setPage(0);
        render();
        return true;
    }

    // --- the list ------------------------------------------------------------------------

    private List<Entry> entries() {
        List<Entry> e = new ArrayList<>();
        e.add(section("Launcher"));
        e.add(choice("launcher.homeTab", "Home button opens"));
        e.add(link("Default home app", isDefaultHome() ? "PDA Launcher" : "Another app",
                new Intent(Settings.ACTION_HOME_SETTINGS)));
        e.add(action("Show/Hide apps", "Which apps the Apps tab lists", showHideApps));
        e.add(number("apps.columns", "App grid columns"));
        e.add(number("apps.rows", "App grid rows"));

        e.add(section("Files"));
        e.add(toggle("files.showHidden", "Show hidden files"));
        e.add(link("Storage access", storageGranted() ? "Allowed" : "Not allowed", appDetails()));

        e.add(section("Shell"));
        e.add(number("terminal.fontSize", "Font size"));
        e.add(choice("terminal.keybarHeight", "Key bar size", "1:Small", "2:Normal", "3:Large"));
        e.add(toggle("ui.keepScreenOn", "Keep the screen on"));
        e.add(number("shell.maxSessions", "Most tabs open at once"));
        e.add(number("terminal.scrollback", "Scrollback lines"));
        e.add(text("shell.command", "Command a new tab runs"));

        e.add(section("Android"));
        e.add(link("Wi-Fi", wifiState(), new Intent(Settings.ACTION_WIFI_SETTINGS)));
        e.add(link("Bluetooth", bluetoothState(), new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        e.add(link("Display", null, new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        e.add(link("Sound", null, new Intent(Settings.ACTION_SOUND_SETTINGS)));
        e.add(link("Battery", batteryState(), new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        e.add(link("Storage", storageState(), new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        e.add(link("Apps", null, new Intent(Settings.ACTION_APPLICATION_SETTINGS)));
        e.add(link("Date & time", null, new Intent(Settings.ACTION_DATE_SETTINGS)));
        e.add(link("Language & input", null, new Intent(Settings.ACTION_LOCALE_SETTINGS)));
        e.add(link("Developer options", null, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        e.add(link("About phone", "Android " + Build.VERSION.RELEASE + " · " + Build.MODEL,
                new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        e.add(link("All settings", null, new Intent(Settings.ACTION_SETTINGS)));

        e.add(section("About"));
        e.add(link("PDA Launcher", "Version " + HomeActivity.versionName(act) + " · App info", appDetails()));
        return e;
    }

    private void render() {
        List<Entry> all = entries();
        pager.setPages((all.size() + rowsPerPage - 1) / rowsPerPage);
        list.removeAllViews();
        int from = pager.page() * rowsPerPage;
        for (int i = from; i < Math.min(all.size(), from + rowsPerPage); i++) list.addView(all.get(i).view());
    }

    // --- kinds of row --------------------------------------------------------------------

    private Entry section(String title) {
        return () -> Ui.section(act, title);
    }

    /** Opens a screen of Android's. */
    private Entry link(String title, String summary, Intent intent) {
        return () -> {
            View r = Ui.row(act, 0, title, summary, "›", false);
            r.setOnClickListener(v -> start(intent));
            return r;
        };
    }

    private Entry action(String title, String summary, Runnable run) {
        return () -> {
            View r = Ui.row(act, 0, title, summary, "›", false);
            r.setOnClickListener(v -> run.run());
            return r;
        };
    }

    private Entry toggle(String key, String title) {
        return () -> {
            boolean on = Config.getBool(key);
            View r = Ui.row(act, 0, title, null, on ? "On" : "Off", false);
            r.setOnClickListener(v -> set(key, on ? "false" : "true"));
            return r;
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
            View r = Ui.row(act, 0, title, null, at >= 0 ? names[at] : cur, false);
            r.setOnClickListener(v -> new AlertDialog.Builder(act)
                    .setTitle(title)
                    .setSingleChoiceItems(names, at, (d, which) -> {
                        d.dismiss();
                        set(key, values[which]);
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            return r;
        };
    }

    /** A whole number within the schema's range. */
    private Entry number(String key, String title) {
        return () -> {
            String[] row = Config.schema(key);
            int lo = 0, hi = 1000;
            if (row != null && row[2].contains("-")) {
                String[] r = row[2].split("-");
                lo = Integer.parseInt(r[0]);
                hi = Integer.parseInt(r[1]);
            }
            final int min = lo, max = hi;
            int cur = Config.getInt(key);
            View r = Ui.row(act, 0, title, min + " to " + max, String.valueOf(cur), false);
            r.setOnClickListener(v -> {
                if (max - min <= 200) {
                    NumberPicker np = new NumberPicker(act);
                    np.setMinValue(min);
                    np.setMaxValue(max);
                    np.setValue(Math.max(min, Math.min(max, cur)));
                    np.setWrapSelectorWheel(false);
                    new AlertDialog.Builder(act)
                            .setTitle(title)
                            .setView(np)
                            .setPositiveButton("OK", (d, w) -> set(key, String.valueOf(np.getValue())))
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    EditText f = field(String.valueOf(cur));
                    f.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    ask(title, f, () -> set(key, f.getText().toString().trim()));
                }
            });
            return r;
        };
    }

    private Entry text(String key, String title) {
        return () -> {
            String cur = Config.get(key);
            View r = Ui.row(act, 0, title, cur, "›", false);
            r.setOnClickListener(v -> {
                EditText f = field(cur);
                ask(title, f, () -> set(key, f.getText().toString()));
            });
            return r;
        };
    }

    private EditText field(String value) {
        EditText f = new EditText(act);
        f.setSingleLine(true);
        f.setText(value);
        f.setSelection(f.getText().length());
        return f;
    }

    private void ask(String title, EditText field, Runnable ok) {
        LinearLayout box = new LinearLayout(act);
        int p = Ui.dp(act, 20);
        box.setPadding(p, Ui.dp(act, 8), p, 0);
        box.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("OK", (d, w) -> ok.run())
                .setNegativeButton("Cancel", null)
                .show();
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

    private boolean storageGranted() {
        return act.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
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
