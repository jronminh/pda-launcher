package org.pda.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.pda.launcher.today.PdaStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * The Today tab, the PDA's information centre. A bar at the top switches its
 * four parts:
 *
 * <ul>
 *   <li><b>Today</b>: the day at a glance, today's calendar, the next alarm, the
 *       most pressing tasks, the inbox, the brief.</li>
 *   <li><b>Tasks</b>: todo.txt; tap to tick, long-press to edit or delete.</li>
 *   <li><b>Inbox</b>: quick captures; long-press to make a task or delete.</li>
 *   <li><b>Board</b>: the whiteboard ({@link Board}), never saved.</li>
 * </ul>
 *
 * The files are plain text in {@link PdaStore#folder()}, read again each time the
 * tab or a part is shown, so what Syncthing, Termux or a laptop changed shows.
 */
final class TodayTab {

    static final int TODAY = 0, TASKS = 1, INBOX = 2, BOARD = 3;
    private static final String[] PARTS = { "Today", "Tasks", "Inbox", "Board" };

    private final Activity act;
    private final Board board;
    private final LinearLayout root;
    private final TextView[] partButtons = new TextView[PARTS.length];
    private final FrameLayout body;
    private final View[] partViews = new View[PARTS.length];
    private int part = -1;
    private final Host host;
    private android.os.FileObserver watcher;   // the folder, while the tab is on screen
    private final Runnable reread = this::refresh;

    /** What the tab asks of the launcher: show a folder in the Files tab. */
    interface Host {
        void openFolder(java.io.File dir);
    }

    // Today
    private final LinearLayout todayList;
    // Tasks
    private final Pager taskPager;
    private final PagedView taskList;
    private List<PdaStore.Task> tasks = new ArrayList<>();
    // Inbox
    private final Pager notePager;
    private final PagedView noteList;
    private List<PdaStore.Note> notes = new ArrayList<>();

    TodayTab(Activity act, Host host) {
        this.act = act;
        this.host = host;
        board = new Board(act);

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // The parts' bar: the open one white and bold, the others dim.
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setMinimumHeight(Ui.dp(act, 52));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < PARTS.length; i++) {
            final int which = i;
            TextView b = new TextView(act);
            b.setText(PARTS[i]);
            b.setTextSize(15);
            b.setTextColor(Ui.TEXT);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, Ui.dp(act, 14), 0, Ui.dp(act, 14));
            b.setOnClickListener(v -> show(which));
            bar.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            partButtons[i] = b;
        }
        bar.addView(Ui.action(act, R.drawable.ic_more, "More", v -> showMenu(v)));
        root.addView(bar);
        root.addView(Ui.divider(act));

        body = new FrameLayout(act);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Today: a column of short blocks.
        todayList = new LinearLayout(act);
        todayList.setOrientation(LinearLayout.VERTICAL);
        todayList.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
        partViews[TODAY] = todayList;

        // Tasks: an entry line over the paged list.
        taskPager = new Pager(act);
        taskList = new PagedView(act, taskPager);
        taskList.setMode(PagedView.DETAILS);
        taskList.setBinder(new PagedView.Binder() {
            @Override public int count() { return tasks.size(); }
            @Override public Drawable icon(int i) {
                return act.getDrawable(tasks.get(i).done ? R.drawable.ic_task_done : R.drawable.ic_task);
            }
            @Override public String title(int i) { return tasks.get(i).text; }
            @Override public String summary(int i) { return taskDetails(tasks.get(i)); }
            @Override public String mark(int i) {
                char p = tasks.get(i).priority;
                return p != 0 && !tasks.get(i).done ? String.valueOf(p) : null;
            }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return tasks.get(i).done; }
            @Override public void click(int i, View v) { tick(tasks.get(i)); }
            @Override public boolean longClick(int i, View v) { taskMenu(v, tasks.get(i)); return true; }
        });
        partViews[TASKS] = listPart("Add a task: (A) text due:2026-10-20", this::addTask, taskList, taskPager);

        // Inbox: an entry line over the captures, newest first.
        notePager = new Pager(act);
        noteList = new PagedView(act, notePager);
        noteList.setMode(PagedView.DETAILS);
        noteList.setBinder(new PagedView.Binder() {
            @Override public int count() { return notes.size(); }
            @Override public Drawable icon(int i) { return act.getDrawable(R.drawable.ic_inbox); }
            @Override public String title(int i) { return notes.get(i).text; }
            @Override public String summary(int i) { return notes.get(i).when; }
            @Override public String mark(int i) { return null; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return false; }
            @Override public void click(int i, View v) { noteMenu(v, notes.get(i)); }
            @Override public boolean longClick(int i, View v) { noteMenu(v, notes.get(i)); return true; }
        });
        partViews[INBOX] = listPart("Capture…", this::capture, noteList, notePager);

        partViews[BOARD] = board.view();
        for (View v : partViews) {
            v.setVisibility(View.GONE);
            body.addView(v);
        }
        show(TODAY);
    }

    View view() {
        return root;
    }

    /** On screen (or back to it): read the files again, and follow them while shown. */
    void onShow() {
        refresh();
        watch();
    }

    void onHide() {
        board.onHide();
        hideIme();
        unwatch();
    }

    /**
     * Follow the folder: a file Syncthing, Termux or a laptop changed shows at
     * once (a quarter second after the last change, as a sync writes several).
     */
    private void watch() {
        unwatch();
        if (!storageAllowed()) return;
        java.io.File dir = PdaStore.folder();
        dir.mkdirs();
        watcher = new android.os.FileObserver(dir.getPath(), android.os.FileObserver.CLOSE_WRITE
                | android.os.FileObserver.MOVED_TO | android.os.FileObserver.DELETE
                | android.os.FileObserver.CREATE) {
            @Override public void onEvent(int event, String path) {
                if (path == null || path.endsWith(".tmp")) return;   // a write in progress
                root.removeCallbacks(reread);
                root.postDelayed(reread, 250);
            }
        };
        watcher.startWatching();
    }

    private void unwatch() {
        if (watcher != null) watcher.stopWatching();
        watcher = null;
        root.removeCallbacks(reread);
    }

    private void showMenu(View anchor) {
        PopMenu m = new PopMenu(act, anchor);
        if (part == BOARD) {
            // The board's own: it has no bar of its own.
            m.add(0, 3, 0, "Clear").setEnabled(!board.isEmpty());
            m.add(0, 4, 1, "Text size  ›");
        }
        m.add(0, 1, 2, "Folder: " + Config.get("today.folder") + "  ›");
        m.add(0, 2, 3, "Open the folder in Files");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: askFolder(); break;
                case 2: host.openFolder(PdaStore.folder()); break;
                case 3: board.clear(); break;
                default: PopMenu.choose(act, anchor, "note.textSize", "Small", "Medium", "Large");
            }
            return true;
        });
        m.show();
    }

    /** today.folder: under shared storage (Documents/PDA), or an absolute path. */
    private void askFolder() {
        final EditText box = new EditText(act);
        box.setSingleLine(true);
        box.setText(Config.get("today.folder"));
        box.setSelection(box.getText().length());
        LinearLayout pad = new LinearLayout(act);
        pad.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        pad.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(act)
                .setTitle("The folder of todo.txt, inbox.md, brief.md")
                .setView(pad)
                .setPositiveButton("OK", (d, w) -> {
                    String f = box.getText().toString().trim();
                    Config.set(act, "today.folder", f.isEmpty() ? "Documents/PDA" : f);
                    watch();
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void onDestroy() {
        board.onDestroy();
    }

    /** Back: from a part to Today. */
    boolean onBack() {
        if (part == TODAY) return false;
        show(TODAY);
        return true;
    }

    /** The calendar permission was answered. */
    void onCalendarPermission() {
        if (part == TODAY) refresh();
    }

    void show(int which) {
        if (which == part) return;
        int from = part;
        part = which;
        for (int i = 0; i < PARTS.length; i++) {
            partViews[i].setVisibility(i == which ? View.VISIBLE : View.GONE);
            partButtons[i].setAlpha(i == which ? 1f : 0.45f);
            partButtons[i].setTypeface(null, i == which ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (which != BOARD) board.onHide();
        if (from >= 0) Anim.slideIn(partViews[which], which - from);
        refresh();
    }

    private void refresh() {
        if (!storageAllowed()) {
            showNoStorage();
            return;
        }
        // The part shown is the part chosen (the storage message may have taken its place).
        for (int i = 0; i < PARTS.length; i++) partViews[i].setVisibility(i == part ? View.VISIBLE : View.GONE);
        switch (part) {
            case TODAY: drawToday(); break;
            case TASKS: tasks = PdaStore.tasks(); taskList.refresh(); break;
            case INBOX: notes = PdaStore.inboxNotes(); noteList.refresh(); break;
            default: break;
        }
    }

    // --- Today --------------------------------------------------------------------------------

    private void drawToday() {
        todayList.removeAllViews();
        long now = System.currentTimeMillis();

        heading(DateUtils.formatDateTime(act, now, DateUtils.FORMAT_SHOW_WEEKDAY
                | DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR), null);

        // The calendar.
        if (act.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            item("Show today's calendar", "Tap to allow the launcher to read it", v -> act.requestPermissions(
                    new String[] { Manifest.permission.READ_CALENDAR }, Glance.REQUEST_CALENDAR));
        } else {
            List<String[]> events = events(now);
            if (events.isEmpty()) item("Nothing on the calendar today", null, null);
            for (String[] e : events) {
                long begin = Long.parseLong(e[2]);
                item(e[0] + "   " + e[1], null, v -> openCalendar(begin));
            }
        }

        // The next alarm.
        AlarmManager am = (AlarmManager) act.getSystemService(Context.ALARM_SERVICE);
        AlarmManager.AlarmClockInfo alarm = am != null ? am.getNextAlarmClock() : null;
        if (alarm != null) {
            long t = alarm.getTriggerTime();
            item("Alarm " + (t - now < DateUtils.DAY_IN_MILLIS ? DateFormat.getTimeFormat(act).format(new Date(t))
                    : DateUtils.formatDateTime(act, t, DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_TIME)),
                    null, v -> start(new android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)));
        }

        // The most pressing tasks.
        List<PdaStore.Task> open = new ArrayList<>();
        for (PdaStore.Task t : PdaStore.tasks()) if (!t.done) open.add(t);
        heading("Tasks", v -> show(TASKS));
        if (open.isEmpty()) item("Nothing to do", null, v -> show(TASKS));
        for (int i = 0; i < Math.min(3, open.size()); i++) {
            final PdaStore.Task t = open.get(i);
            item((t.priority != 0 ? "(" + t.priority + ") " : "") + t.text, taskDetails(t), v -> tick(t));
        }
        if (open.size() > 3) item("and " + (open.size() - 3) + " more", null, v -> show(TASKS));

        // The inbox.
        int n = PdaStore.inboxNotes().size();
        heading("Inbox", v -> show(INBOX));
        item(n == 0 ? "Empty" : n + (n == 1 ? " capture" : " captures"), null, v -> show(INBOX));

        // The brief, if something wrote one.
        String brief = PdaStore.brief(600);
        if (!brief.isEmpty()) {
            heading("Brief", null);
            TextView b = new TextView(act);
            b.setText(brief);
            b.setTextColor(Ui.DIM);
            b.setTextSize(13);
            b.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), Ui.dp(act, 8));
            todayList.addView(b);
        }
    }

    private void heading(String text, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(Ui.DIM);
        t.setTextSize(13);
        t.setAllCaps(true);
        t.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 16), Ui.dp(act, 4));
        if (click != null) t.setOnClickListener(click);
        todayList.addView(t);
    }

    private void item(String title, String summary, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(Ui.dp(act, 16), Ui.dp(act, 6), Ui.dp(act, 16), Ui.dp(act, 6));
        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(t);
        if (summary != null && !summary.isEmpty()) {
            TextView s = new TextView(act);
            s.setText(summary);
            s.setTextColor(Ui.DIM);
            s.setTextSize(12);
            r.addView(s);
        }
        if (click != null) r.setOnClickListener(click);
        todayList.addView(r);
    }

    /** Today's events of the visible calendars, not over yet: time, title, start. */
    private List<String[]> events(long now) {
        List<String[]> out = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        long start = c.getTimeInMillis();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, start);
        ContentUris.appendId(b, start + DateUtils.DAY_IN_MILLIS);
        String[] cols = { CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY };
        try (Cursor cur = act.getContentResolver().query(b.build(), cols, CalendarContract.Instances.VISIBLE + " = 1",
                null, CalendarContract.Instances.ALL_DAY + " DESC, " + CalendarContract.Instances.BEGIN + " ASC")) {
            while (cur != null && cur.moveToNext() && out.size() < 6) {
                long begin = cur.getLong(1), end = cur.getLong(2);
                if (end <= now) continue;
                String when = cur.getInt(3) == 1 ? "All day" : begin <= now ? "Now"
                        : DateFormat.getTimeFormat(act).format(new Date(begin));
                String title = cur.getString(0) == null || cur.getString(0).isEmpty() ? "(no title)" : cur.getString(0);
                out.add(new String[] { when, title, String.valueOf(begin) });
            }
        } catch (RuntimeException e) {
            // no calendar provider, or the permission was taken back
        }
        return out;
    }

    private void openCalendar(long at) {
        Uri u = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build();
        start(new android.content.Intent(android.content.Intent.ACTION_VIEW, ContentUris.withAppendedId(u, at)));
    }

    // --- Tasks and Inbox ----------------------------------------------------------------------

    /** An entry line (a box and a + button) over a paged list. */
    private View listPart(String hint, java.util.function.Consumer<String> submit, PagedView list, Pager pager) {
        LinearLayout part = new LinearLayout(act);
        part.setOrientation(LinearLayout.VERTICAL);

        LinearLayout entry = new LinearLayout(act);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 4), 0);
        final EditText box = new EditText(act);
        box.setHint(hint);
        box.setTextColor(Ui.TEXT);
        box.setHintTextColor(Ui.DIM);
        box.setTextSize(15);
        box.setSingleLine(true);
        box.setBackground(null);
        box.setImeOptions(EditorInfo.IME_ACTION_DONE);
        Runnable send = () -> {
            String t = box.getText().toString();
            if (t.trim().isEmpty()) return;
            submit.accept(t);
            box.setText("");
        };
        box.setOnEditorActionListener((v, id, ev) -> { send.run(); return true; });
        entry.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        entry.addView(Ui.action(act, R.drawable.ic_add, "Add", v -> send.run()));
        part.addView(entry);
        part.addView(Ui.divider(act));
        part.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        part.addView(pager);
        return part;
    }

    private String taskDetails(PdaStore.Task t) {
        if (t.due == null) return null;
        String today = PdaStore.today();
        int c = t.due.compareTo(today);
        return c < 0 ? "Overdue, due " + t.due : c == 0 ? "Due today" : "Due " + t.due;
    }

    private void addTask(String text) {
        run(() -> PdaStore.addTask(text));
    }

    private void capture(String text) {
        run(() -> PdaStore.capture(text));
    }

    private void tick(PdaStore.Task t) {
        run(() -> PdaStore.setDone(t, !t.done));
    }

    private void taskMenu(View anchor, PdaStore.Task t) {
        PopMenu m = new PopMenu(act, anchor);
        m.add(0, 1, 0, t.done ? "Mark as not done" : "Mark as done");
        m.add(0, 2, 1, "Edit");
        m.add(0, 3, 2, "Delete");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: tick(t); break;
                case 2: edit(t); break;
                default: run(() -> PdaStore.deleteTask(t));
            }
            return true;
        });
        m.show();
    }

    private void edit(PdaStore.Task t) {
        final EditText box = new EditText(act);
        box.setSingleLine(true);
        box.setText(t.text);
        box.setSelection(box.getText().length());
        LinearLayout pad = new LinearLayout(act);
        pad.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        pad.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(act)
                .setTitle("Edit task")
                .setView(pad)
                .setPositiveButton("OK", (d, w) -> run(() -> PdaStore.editTask(t, box.getText().toString())))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void noteMenu(View anchor, PdaStore.Note n) {
        PopMenu m = new PopMenu(act, anchor);
        m.add(0, 1, 0, "Make it a task");
        m.add(0, 2, 1, "Show on the board");
        m.add(0, 3, 2, "Delete");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: run(() -> PdaStore.toTask(n)); break;
                case 2: Board.show(n.text); show(BOARD); break;
                default: run(() -> PdaStore.deleteNote(n));
            }
            return true;
        });
        m.show();
    }

    private interface Change {
        void run() throws IOException;
    }

    /** A change to a file, then the part read again; a failure is said. */
    private void run(Change c) {
        try {
            c.run();
        } catch (IOException e) {
            Toast.makeText(act, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    // --- the rest -----------------------------------------------------------------------------

    private boolean storageAllowed() {
        return act.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Without storage the files cannot be read: say so where the part would be. */
    private void showNoStorage() {
        if (part == BOARD) return;   // the board needs no file
        todayList.removeAllViews();
        heading("Today keeps its files in " + Config.get("today.folder"), null);
        item("Allow storage", "Tap to let the launcher read and write them", v -> act.requestPermissions(
                new String[] { Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE },
                org.pda.launcher.files.FilesTab.REQUEST_STORAGE));
        if (part != TODAY) {
            for (int i = 0; i < PARTS.length; i++) partViews[i].setVisibility(i == TODAY ? View.VISIBLE : View.GONE);
        }
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void start(android.content.Intent i) {
        try {
            act.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignore) {
        }
    }
}
