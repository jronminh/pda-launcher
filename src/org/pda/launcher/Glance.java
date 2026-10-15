package org.pda.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * The glance line over the Apps tab: the time on the left; on the right the
 * date, the next calendar event (if asked for), the next alarm and the battery
 * (with the time to full while charging). Each part opens what it tells of: the
 * time the Clock's alarms, the date the Today tab, the event the Calendar, the battery
 * Android's battery screen. It follows the minute, the battery, the alarms and
 * the calendar while the launcher is on screen.
 */
final class Glance {

    static final int REQUEST_CALENDAR = 42;

    private final Activity act;
    private final LinearLayout root;
    private final TextView time, date, event, status;
    private int battery = -1;
    private boolean charging;
    private long eventStart = -1;   // the event shown, for the Calendar to open at
    private int generation;

    private final BroadcastReceiver ticker = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                battery = level < 0 ? -1 : Math.round(level * 100f / scale);
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING;
            }
            update();
        }
    };

    Glance(Activity act, Runnable openToday) {
        this.act = act;
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));

        time = new TextView(act);
        time.setTextColor(Ui.TEXT);
        time.setTextSize(30);
        time.setOnClickListener(v -> start(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        root.addView(time);

        LinearLayout right = new LinearLayout(act);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        date = line(v -> openToday.run());   // the day in full: the Today tab
        event = line(v -> openCalendar(eventStart > 0 ? eventStart : System.currentTimeMillis()));
        event.setTextColor(Ui.TEXT);
        status = line(v -> start(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)));
        right.addView(date);
        right.addView(event);
        right.addView(status);
        root.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    View view() {
        return root;
    }

    private TextView line(View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setTextColor(Ui.DIM);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.END);
        t.setOnClickListener(click);
        return t;
    }

    void onStart() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);   // sticky: the current level comes at once
        f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        f.addAction(Intent.ACTION_PROVIDER_CHANGED);   // the calendar
        act.registerReceiver(ticker, f);
        update();
    }

    void onStop() {
        try { act.unregisterReceiver(ticker); } catch (IllegalArgumentException ignore) {}
    }

    // --- the next event --------------------------------------------------------------------

    static boolean calendarAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    /** Show the next event (asks for the calendar first), or stop showing it. */
    void setEvents(boolean on) {
        if (on && !calendarAllowed(act)) {
            act.requestPermissions(new String[] { Manifest.permission.READ_CALENDAR }, REQUEST_CALENDAR);
            return;   // onPermission turns it on if granted
        }
        Config.set(act, "apps.glanceEvents", String.valueOf(on));
    }

    void onPermission() {
        if (calendarAllowed(act)) Config.set(act, "apps.glanceEvents", "true");
    }

    // --- drawing ----------------------------------------------------------------------------

    void update() {
        long now = System.currentTimeMillis();
        time.setText(DateFormat.getTimeFormat(act).format(new Date(now)));
        date.setText(DateUtils.formatDateTime(act, now, DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR | DateUtils.FORMAT_ABBREV_ALL));

        StringBuilder s = new StringBuilder();
        AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo alarm = am != null ? am.getNextAlarmClock() : null;
        if (alarm != null) s.append("Alarm ").append(when(alarm.getTriggerTime(), now));
        if (battery >= 0) {
            if (s.length() > 0) s.append("  ·  ");
            s.append(battery).append('%');
            if (charging) {
                BatteryManager bm = (BatteryManager) act.getSystemService(Context.BATTERY_SERVICE);
                long left = bm != null ? bm.computeChargeTimeRemaining() : -1;
                s.append(left > 0 ? ", full in " + duration(left) : ", charging");
            }
        }
        status.setText(s);
        status.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);

        if (Config.getBool("apps.glanceEvents") && calendarAllowed(act)) {
            final int gen = ++generation;
            new Thread(() -> {
                String[] e = nextEvent(now);
                act.runOnUiThread(() -> {
                    if (gen != generation) return;
                    eventStart = e == null ? -1 : Long.parseLong(e[1]);
                    event.setText(e == null ? "" : e[0]);
                    event.setVisibility(e == null ? View.GONE : View.VISIBLE);
                });
            }).start();
        } else {
            event.setVisibility(View.GONE);
        }
    }

    /** The next event of the visible calendars in the coming day: its line and its start. */
    private String[] nextEvent(long now) {
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, now);
        ContentUris.appendId(b, now + DateUtils.DAY_IN_MILLIS);
        String[] cols = { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY };
        try (Cursor c = act.getContentResolver().query(b.build(), cols,
                CalendarContract.Instances.VISIBLE + " = 1", null,
                CalendarContract.Instances.ALL_DAY + " ASC, " + CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return null;
            while (c.moveToNext()) {
                long begin = c.getLong(1), end = c.getLong(2);
                if (end <= now) continue;
                String title = c.getString(0) == null || c.getString(0).isEmpty() ? "(no title)" : c.getString(0);
                String at = c.getInt(3) == 1 ? "Today" : begin <= now ? "Now" : when(begin, now);
                return new String[] { at + "  " + title, String.valueOf(begin) };
            }
        } catch (RuntimeException e) {
            // no calendar provider, or the permission was taken back
        }
        return null;
    }

    /** A time within a day as the time alone, else with its weekday. */
    private String when(long t, long now) {
        if (t - now < DateUtils.DAY_IN_MILLIS) return DateFormat.getTimeFormat(act).format(new Date(t));
        return DateUtils.formatDateTime(act, t, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_ABBREV_ALL);
    }

    private static String duration(long ms) {
        long min = Math.round(ms / 60000.0);
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    private void openCalendar(long at) {
        Uri u = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build();
        start(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(u, at)));
    }

    private void start(Intent i) {
        try {
            act.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            if (i.getAction() != null && i.getAction().equals(Intent.ACTION_POWER_USAGE_SUMMARY)) {
                start(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }
}
