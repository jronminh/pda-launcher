package org.pda.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Prefs' own pages for Android's daily settings: Wi-Fi, Bluetooth, Display,
 * Sound, Battery, Storage. Each holds the few things done every day, set here
 * with public APIs only (AOSP first: what any build has), and ends with a row
 * to Android's own screen for everything else. What an app may not change
 * (choosing a network, pairing, the location...) is only ever a link there.
 *
 * Display's settings need "Modify system settings" (WRITE_SETTINGS, a special
 * access the user grants once): until then its rows lead to that screen. Silent
 * needs Do Not Disturb access, asked the same way when picked. Wi-Fi and
 * Bluetooth switch from here while targetSdk is under 29 and 33; later only
 * Android's panels can (TODO › Raise targetSdk).
 */
final class DeviceSettings {

    static final String WIFI = "wifi", BLUETOOTH = "bluetooth", DISPLAY = "display", SOUND = "sound",
            BATTERY = "battery", STORAGE = "storage";

    private final Activity act;
    private final SettingsScreen screen;
    private final Consumer<File> openFolder;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long[] media;          // Storage's kinds, counted in the background; null until then
    private boolean counting;
    private boolean listening;

    // Android's state changed while a page is on screen: show it.
    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            screen.refresh();
        }
    };

    DeviceSettings(Activity act, SettingsScreen screen, Consumer<File> openFolder) {
        this.act = act;
        this.screen = screen;
        this.openFolder = openFolder;
    }

    static boolean owns(String scope) {
        switch (scope) {
            case WIFI: case BLUETOOTH: case DISPLAY: case SOUND: case BATTERY: case STORAGE: return true;
            default: return false;
        }
    }

    static String title(String scope) {
        switch (scope) {
            case WIFI: return "Wi-Fi";
            case BLUETOOTH: return "Bluetooth";
            case DISPLAY: return "Display";
            case SOUND: return "Sound";
            case BATTERY: return "Battery";
            default: return "Storage";
        }
    }

    /** While one of these pages shows, follow what changes it (or stop). */
    void listen(boolean on) {
        if (on == listening) return;
        listening = on;
        if (on) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            f.addAction(Intent.ACTION_BATTERY_CHANGED);
            f.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            f.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            act.registerReceiver(changed, f);
        } else {
            try {
                act.unregisterReceiver(changed);
            } catch (IllegalArgumentException ignore) {}
        }
    }

    void entries(String scope, List<SettingsScreen.Entry> e) {
        switch (scope) {
            case WIFI: wifi(e); break;
            case BLUETOOTH: bluetooth(e); break;
            case DISPLAY: display(e); break;
            case SOUND: sound(e); break;
            case BATTERY: battery(e); break;
            default: storage(e);
        }
    }

    private static SettingsScreen.Entry row(String title, String summary, String value, Runnable click) {
        return () -> new SettingsScreen.Row(title, summary, value, click != null ? click : () -> { });
    }

    private SettingsScreen.Entry more(String action) {
        return more(new Intent(action));
    }

    private SettingsScreen.Entry more(Intent i) {
        return row("More in Android settings", null, "›", () -> screen.start(i));
    }

    /** Wi-Fi and Bluetooth answer after a moment: look again then. */
    private void later() {
        main.postDelayed(screen::refresh, 700);
        main.postDelayed(screen::refresh, 2500);
    }

    // --- Wi-Fi --------------------------------------------------------------------------------

    private WifiManager wifiManager() {
        return (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    @SuppressWarnings("deprecation")   // setWifiEnabled: allowed below targetSdk 29
    private void wifi(List<SettingsScreen.Entry> e) {
        WifiManager wm = wifiManager();
        boolean on = wm != null && wm.isWifiEnabled();
        e.add(row("Wi-Fi", null, on ? "On" : "Off", () -> {
            boolean ok = false;
            try {
                ok = wm != null && wm.setWifiEnabled(!on);
            } catch (SecurityException ignore) {}
            if (!ok) screen.start(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else later();
        }));
        if (on) {
            String[] c = wifiConnection(wm);
            e.add(row(c == null ? "Not connected" : c[0], c == null ? "Choose a network in Android's settings" : c[1],
                    null, () -> screen.start(new Intent(Settings.ACTION_WIFI_SETTINGS))));
        }
        boolean plane = Settings.Global.getInt(act.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        e.add(row("Airplane mode", null, plane ? "On" : "Off",
                () -> screen.start(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))));
        e.add(more(Settings.ACTION_WIFI_SETTINGS));
    }

    /** The network joined: its name (when Android tells it) and "signal · IP address"; null when none. */
    @SuppressWarnings("deprecation")   // getConnectionInfo: still the way below API 31
    private String[] wifiConnection(WifiManager wm) {
        ConnectivityManager cm = act.getSystemService(ConnectivityManager.class);
        NetworkCapabilities nc = cm == null || cm.getActiveNetwork() == null ? null
                : cm.getNetworkCapabilities(cm.getActiveNetwork());
        WifiInfo info = wm.getConnectionInfo();
        boolean joined = info != null && info.getNetworkId() != -1
                || nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        if (!joined || info == null) return null;
        String ssid = info.getSSID();
        // Without the location permission Android hides the name: say only that it is joined.
        String name = ssid == null || ssid.contains("<unknown") ? "Connected"
                : "Connected to " + ssid.replace("\"", "");
        String[] levels = { "weak", "fair", "good", "strong", "excellent" };
        StringBuilder sb = new StringBuilder("Signal ").append(levels[WifiManager.calculateSignalLevel(info.getRssi(), 5)]);
        int ip = info.getIpAddress();
        if (ip != 0) {
            sb.append(" · ").append(String.format(Locale.ROOT, "%d.%d.%d.%d",
                    ip & 0xff, ip >> 8 & 0xff, ip >> 16 & 0xff, ip >> 24 & 0xff));
        }
        return new String[] { name, sb.toString() };
    }

    String wifiSummary() {
        WifiManager wm = wifiManager();
        try {
            if (wm == null || !wm.isWifiEnabled()) return "Off";
            String[] c = wifiConnection(wm);
            return c == null ? "On" : c[0];
        } catch (SecurityException e) {
            return null;
        }
    }

    // --- Bluetooth ----------------------------------------------------------------------------

    @SuppressWarnings({ "deprecation", "MissingPermission" })   // enable/disable: allowed below targetSdk 33
    private void bluetooth(List<SettingsScreen.Entry> e) {
        BluetoothAdapter b = BluetoothAdapter.getDefaultAdapter();
        if (b == null) {
            e.add(row("This device has no Bluetooth", null, null, null));
            return;
        }
        boolean on = b.isEnabled();
        e.add(row("Bluetooth", null, on ? "On" : "Off", () -> {
            boolean ok = false;
            try {
                ok = on ? b.disable() : b.enable();
            } catch (SecurityException ignore) {}
            if (!ok) screen.start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            else later();
        }));
        try {
            e.add(row("Name", null, b.getName(), () -> screen.start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
            if (on) {
                for (BluetoothDevice d : b.getBondedDevices()) {
                    String name = d.getName() != null ? d.getName() : d.getAddress();
                    e.add(row(name, "Paired", null, () -> screen.start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
                }
            }
        } catch (SecurityException ignore) {}
        e.add(more(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    // --- Display ------------------------------------------------------------------------------

    private boolean canWrite() {
        return Settings.System.canWrite(act);
    }

    private Intent writeAccess() {
        return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act.getPackageName()));
    }

    private int system(String key, int fallback) {
        return Settings.System.getInt(act.getContentResolver(), key, fallback);
    }

    /** A system setting, if allowed; else to the screen that allows it. */
    private void putSystem(String key, int value) {
        if (!canWrite()) {
            askWrite();
            return;
        }
        try {
            Settings.System.putInt(act.getContentResolver(), key, value);
        } catch (RuntimeException e) {
            Toast.makeText(act, "Android did not allow it", Toast.LENGTH_SHORT).show();
        }
    }

    private void askWrite() {
        Toast.makeText(act, "Allow \"Modify system settings\" for PDA Launcher", Toast.LENGTH_LONG).show();
        screen.start(writeAccess());
    }

    private static final int[] TIMEOUTS = { 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000 };
    private static final String[] TIMEOUT_NAMES = { "15 seconds", "30 seconds", "1 minute", "2 minutes",
            "5 minutes", "10 minutes", "30 minutes" };

    private void display(List<SettingsScreen.Entry> e) {
        boolean write = canWrite();
        if (!write) {
            e.add(row("Allow changes here", "Brightness, the timeout and rotation need \"Modify system settings\"",
                    "›", () -> screen.start(writeAccess())));
        }
        int level = system(Settings.System.SCREEN_BRIGHTNESS, 128);
        boolean auto = system(Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        e.add(row("Brightness", auto ? "Adapts to the light around" : null, percent(level, 255), () -> {
            if (!canWrite()) { askWrite(); return; }
            Slider.show(act, screen.anchor(), "Brightness", level, 255, n -> percent(n, 255),
                    n -> putSystem(Settings.System.SCREEN_BRIGHTNESS, Math.max(1, n)), screen::refresh);
        }));
        e.add(row("Adaptive brightness", null, auto ? "On" : "Off", () -> {
            putSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, auto ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            screen.refresh();
        }));
        int timeout = system(Settings.System.SCREEN_OFF_TIMEOUT, 30_000);
        e.add(row("Screen timeout", null, timeoutName(timeout), () -> {
            if (!canWrite()) { askWrite(); return; }
            int at = -1;
            for (int i = 0; i < TIMEOUTS.length; i++) if (TIMEOUTS[i] == timeout) at = i;
            new AlertDialog.Builder(act).setTitle("Screen timeout")
                    .setSingleChoiceItems(TIMEOUT_NAMES, at, (d, which) -> {
                        d.dismiss();
                        putSystem(Settings.System.SCREEN_OFF_TIMEOUT, TIMEOUTS[which]);
                        screen.refresh();
                    })
                    .setNegativeButton("Cancel", null).show();
        }));
        boolean rotate = system(Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        e.add(row("Auto-rotate screen", null, rotate ? "On" : "Off", () -> {
            putSystem(Settings.System.ACCELEROMETER_ROTATION, rotate ? 0 : 1);
            screen.refresh();
        }));
        // An app may not change the font size: shown here, changed in Android's screen.
        float scale = act.getResources().getConfiguration().fontScale;
        e.add(row("Font size", null, Math.abs(scale - 1f) < 0.01f ? "Default" : Math.round(scale * 100) + "%",
                () -> screen.start(new Intent(Settings.ACTION_DISPLAY_SETTINGS))));
        e.add(more(Settings.ACTION_DISPLAY_SETTINGS));
    }

    private static String percent(int v, int max) {
        return Math.round(v * 100f / max) + "%";
    }

    private static String timeoutName(int ms) {
        for (int i = 0; i < TIMEOUTS.length; i++) if (TIMEOUTS[i] == ms) return TIMEOUT_NAMES[i];
        return ms >= 60_000 ? ms / 60_000 + " minutes" : ms / 1000 + " seconds";
    }

    // --- Sound --------------------------------------------------------------------------------

    private static final int[] STREAMS = { AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM };
    private static final String[] STREAM_NAMES = { "Ring", "Notifications", "Media", "Alarms" };

    private void sound(List<SettingsScreen.Entry> e) {
        AudioManager am = act.getSystemService(AudioManager.class);
        int mode = am.getRingerMode();
        e.add(row("Ringer", null, ringerName(mode), () -> {
            String[] names = { "Sound", "Vibrate", "Silent" };
            int[] modes = { AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT };
            int at = mode == AudioManager.RINGER_MODE_VIBRATE ? 1 : mode == AudioManager.RINGER_MODE_SILENT ? 2 : 0;
            new AlertDialog.Builder(act).setTitle("Ringer")
                    .setSingleChoiceItems(names, at, (d, which) -> {
                        d.dismiss();
                        setRinger(am, modes[which]);
                    })
                    .setNegativeButton("Cancel", null).show();
        }));
        for (int i = 0; i < STREAMS.length; i++) {
            final int stream = STREAMS[i];
            final String name = STREAM_NAMES[i];
            int max = am.getStreamMaxVolume(stream);
            int cur = am.getStreamVolume(stream);
            e.add(row(name, null, cur == 0 ? "Muted" : cur + " of " + max, () -> Slider.show(act, screen.anchor(),
                    name, am.getStreamVolume(stream), max, n -> n == 0 ? "Muted" : n + " of " + max, n -> {
                        try {
                            am.setStreamVolume(stream, n, stream == AudioManager.STREAM_MUSIC ? 0 : AudioManager.FLAG_PLAY_SOUND);
                        } catch (SecurityException x) {   // Do Not Disturb holds it
                            askPolicy();
                        }
                    }, screen::refresh)));
        }
        e.add(more(Settings.ACTION_SOUND_SETTINGS));
    }

    private static String ringerName(int mode) {
        return mode == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : mode == AudioManager.RINGER_MODE_SILENT ? "Silent" : "Sound";
    }

    /** Silent (or leaving it) needs Do Not Disturb access on Android 7 and later: asked when needed. */
    private void setRinger(AudioManager am, int mode) {
        try {
            am.setRingerMode(mode);
        } catch (SecurityException x) {
            askPolicy();
        }
        screen.refresh();
    }

    private void askPolicy() {
        NotificationManager nm = act.getSystemService(NotificationManager.class);
        if (nm != null && nm.isNotificationPolicyAccessGranted()) return;
        Toast.makeText(act, "Allow Do Not Disturb access for PDA Launcher", Toast.LENGTH_LONG).show();
        screen.start(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
    }

    String soundSummary() {
        AudioManager am = act.getSystemService(AudioManager.class);
        return am == null ? null : ringerName(am.getRingerMode());
    }

    // --- Battery ------------------------------------------------------------------------------

    private void battery(List<SettingsScreen.Entry> e) {
        Intent i = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        Runnable usage = () -> screen.start(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
        if (i != null) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            String state = status == BatteryManager.BATTERY_STATUS_FULL ? "Full"
                    : status == BatteryManager.BATTERY_STATUS_CHARGING ? "Charging" + pluggedName(plugged)
                    : plugged != 0 ? "Plugged in, not charging" : "On battery";
            e.add(row("Level", state, Math.round(level * 100f / scale) + "%", usage));
            if (Build.VERSION.SDK_INT >= 28 && status == BatteryManager.BATTERY_STATUS_CHARGING) {
                long left = act.getSystemService(BatteryManager.class).computeChargeTimeRemaining();
                if (left > 0) e.add(row("Full in", null, android.text.format.DateUtils.formatElapsedTime(left / 1000), usage));
            }
            e.add(row("Health", null, healthName(i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)), usage));
            int temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (temp != Integer.MIN_VALUE) e.add(row("Temperature", null, String.format(Locale.ROOT, "%.1f °C", temp / 10f), usage));
            int mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            if (mv > 0) e.add(row("Voltage", null, String.format(Locale.ROOT, "%.2f V", mv / 1000f), usage));
            String tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (tech != null && !tech.isEmpty()) e.add(row("Technology", null, tech, usage));
        }
        PowerManager pm = act.getSystemService(PowerManager.class);
        e.add(row("Battery saver", null, pm != null && pm.isPowerSaveMode() ? "On" : "Off",
                () -> screen.start(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))));
        e.add(more(Intent.ACTION_POWER_USAGE_SUMMARY));
    }

    private static String pluggedName(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC: return " from the charger";
            case BatteryManager.BATTERY_PLUGGED_USB: return " over USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return " wirelessly";
            default: return "";
        }
    }

    private static String healthName(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheating";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Failing";
            default: return "Unknown";
        }
    }

    // --- Storage ------------------------------------------------------------------------------

    private void storage(List<SettingsScreen.Entry> e) {
        File root = Environment.getExternalStorageDirectory();
        try {
            StatFs s = new StatFs(root.getPath());
            long total = s.getTotalBytes(), free = s.getAvailableBytes();
            e.add(row("Internal storage", Formatter.formatFileSize(act, free) + " free",
                    Formatter.formatShortFileSize(act, total - free) + " of " + Formatter.formatShortFileSize(act, total),
                    () -> openFolder.accept(root)));
        } catch (IllegalArgumentException ignore) {}
        // What it holds, by kind: one pass over MediaStore, in the background.
        String[] names = { "Images", "Videos", "Audio", "Documents" };
        if (media == null && !counting) {
            counting = true;
            final Context app = act.getApplicationContext();
            new Thread(() -> {
                long[] k = org.pda.launcher.files.FilesTab.mediaKinds(app);
                main.post(() -> {
                    media = k;
                    counting = false;
                    screen.refresh();
                });
            }, "prefs-storage").start();
        }
        for (int k = 0; k < names.length; k++) {
            String v = media == null ? "Counting…" : Formatter.formatShortFileSize(act, media[k * 2]);
            String n = media == null ? null : media[k * 2 + 1] + (media[k * 2 + 1] == 1 ? " file" : " files");
            e.add(row(names[k], n, v, null));
        }
        e.add(row("Download", null, "›", () -> openFolder.accept(new File(root, Environment.DIRECTORY_DOWNLOADS))));
        e.add(more(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
    }

    /** Storage's page left: count again next time. */
    void forget() {
        media = null;
    }
}
