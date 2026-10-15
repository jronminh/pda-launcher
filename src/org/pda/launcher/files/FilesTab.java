package org.pda.launcher.files;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.pda.launcher.Pager;
import org.pda.launcher.R;
import org.pda.launcher.Ui;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Files tab, after AOSP DocumentsUI and the Onyx Boox Storage screen.
 *
 * <p>The first screen is the storage (how full it is) and the roots: Recent,
 * Internal storage, Download, Images, Videos, Audio, Documents ({@link Roots}).
 * A folder is listed with folders first and a chosen sort (name, date, size,
 * type), one page at a time. Navigation is a history, as in DocumentsUI: Back
 * returns to the screen before, on the page it was on.
 *
 * <p>Tap opens a folder or a file (in the app that handles it, through
 * {@link FileShare}); long-press starts a selection with Copy, Move, Delete,
 * Share and a menu (Rename, Select all, Open shell here, Show in folder).
 * Copied or cut items wait in a bar until they are pasted into a folder.
 */
public final class FilesTab {

    /** What the tab asks of the launcher. */
    public interface Host {
        void openShellAt(File dir);
    }

    public static final int REQUEST_STORAGE = 41;

    private enum Mode { HOME, FOLDER, MEDIA }

    /** One place in the history. */
    private static final class Place {
        final Mode mode;
        final File dir;          // FOLDER
        final Roots.Root root;   // MEDIA
        int page;

        Place(Mode mode, File dir, Roots.Root root) {
            this.mode = mode;
            this.dir = dir;
            this.root = root;
        }
    }

    /** A row: a file, or on the first screen a root. */
    private static final class Item {
        final File file;
        final Roots.Root root;

        Item(File file, Roots.Root root) {
            this.file = file;
            this.root = root;
        }
    }

    private static final String PREFS = "files";
    private static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2, SORT_TYPE = 3;
    private static final String[] SORTS = { "Name", "Date modified", "Size", "Type" };
    private static final int ROW_DP = 64;

    // What was copied or cut: kept across screens until it is pasted or dropped.
    private static List<File> clip;
    private static boolean clipMove;

    private final Activity act;
    private final Host host;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LinearLayout root;
    private final TextView title;
    private final EditText search;
    private final View searchBtn, sortBtn, moreBtn, closeBtn;
    private final HorizontalScrollView crumbScroll;
    private final LinearLayout crumbs;
    private final LinearLayout list;
    private final LinearLayout opsBar, pasteBar;
    private final TextView pasteText, status;
    private final Button pasteBtn;
    private final Pager pager;

    private final List<Place> history = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final Set<File> selected = new LinkedHashSet<>();
    private List<File> listing = new ArrayList<>();   // the folder as read, before the search filter
    private String query = "";
    private int rowsPerPage = 8;
    private int generation;
    private boolean busy;
    private boolean noAccess;   // no storage permission: the page asks for it

    private final Runnable runSearch = this::load;

    public FilesTab(Activity act, Host host) {
        this.act = act;
        this.host = host;
        prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // The header: the title (or the search box), then the actions.
        LinearLayout titleBox = new LinearLayout(act);
        title = Ui.title(act, "Files");
        title.setEllipsize(TextUtils.TruncateAt.START);
        search = new EditText(act);
        search.setSingleLine(true);
        search.setHint("Search");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setBackground(null);
        search.setPadding(0, 0, 0, 0);
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                pager.setPage(0);
                // A folder filters what it already read; elsewhere the media index is asked.
                main.removeCallbacks(runSearch);
                if (place().mode == Mode.FOLDER) showItems(filter(listing));
                else main.postDelayed(runSearch, 300);
            }
        });
        titleBox.addView(title);
        titleBox.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        searchBtn = Ui.action(act, android.R.drawable.ic_menu_search, "Search", v -> toggleSearch());
        sortBtn = Ui.action(act, android.R.drawable.ic_menu_sort_by_size, "Sort", v -> showSort(v));
        moreBtn = Ui.textAction(act, "⋮", v -> showMenu(v));
        closeBtn = Ui.textAction(act, "✕", v -> clearSelection());
        root.addView(Ui.header(act, titleBox, searchBtn, sortBtn, closeBtn, moreBtn));
        root.addView(Ui.divider(act));

        // The path: Internal storage › Download › ..., each part a way back.
        crumbs = new LinearLayout(act);
        crumbs.setOrientation(LinearLayout.HORIZONTAL);
        crumbs.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), 0);
        crumbScroll = new HorizontalScrollView(act);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll);

        // The rows of the current page. A horizontal fling turns the page.
        GestureDetector flip = new GestureDetector(act, new GestureDetector.SimpleOnGestureListener() {
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
            int rows = Math.max(1, (b - t) / Ui.dp(act, ROW_DP));
            if (b - t > 0 && rows != rowsPerPage) {
                rowsPerPage = rows;
                main.post(this::render);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(act);
        status.setTextColor(Ui.DIM);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
        status.setVisibility(View.GONE);
        root.addView(status);

        // The selection's actions.
        opsBar = new LinearLayout(act);
        opsBar.setOrientation(LinearLayout.HORIZONTAL);
        opsBar.addView(barButton("Copy", v -> toClip(false)));
        opsBar.addView(barButton("Move", v -> toClip(true)));
        opsBar.addView(barButton("Delete", v -> confirmDelete()));
        opsBar.addView(barButton("Share", v -> share()));
        opsBar.addView(barButton("⋮", v -> showSelectionMenu(v)));
        root.addView(withLine(opsBar));

        // What waits to be pasted.
        pasteBar = new LinearLayout(act);
        pasteBar.setOrientation(LinearLayout.HORIZONTAL);
        pasteBar.setGravity(Gravity.CENTER_VERTICAL);
        pasteBar.setPadding(Ui.dp(act, 16), 0, 0, 0);
        pasteText = new TextView(act);
        pasteText.setTextColor(Ui.TEXT);
        pasteText.setTextSize(14);
        pasteBar.addView(pasteText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pasteBtn = barButton("Paste here", v -> paste());
        pasteBar.addView(pasteBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pasteBar.addView(Ui.textAction(act, "✕", v -> { clip = null; render(); }));
        root.addView(withLine(pasteBar));

        pager = new Pager(act);
        pager.setListener(p -> render());
        root.addView(pager);

        history.add(new Place(Mode.HOME, null, null));
    }

    public View view() {
        return root;
    }

    // --- the launcher's calls ------------------------------------------------------------

    public void onShow() {
        load();
    }

    public void onHide() {
        hideIme();
    }

    /** Back on screen: the folder may have changed, or the permission been granted. */
    public void onResume() {
        load();
    }

    /** Back: the selection, the search, then the history. Returns true if it did something. */
    public boolean onBack() {
        if (!selected.isEmpty()) { clearSelection(); return true; }
        if (search.getVisibility() == View.VISIBLE) { closeSearch(); return true; }
        if (history.size() > 1) {
            history.remove(history.size() - 1);
            pager.setPage(place().page);
            load();
            return true;
        }
        return false;
    }

    /** The answer to the storage permission request. */
    public void onPermissionResult() {
        load();
    }

    // --- navigation ----------------------------------------------------------------------

    private Place place() {
        return history.get(history.size() - 1);
    }

    private void go(Place p) {
        place().page = pager.page();
        history.add(p);
        selected.clear();
        if (search.getVisibility() == View.VISIBLE) closeSearch();
        pager.setPage(0);
        load();
    }

    /** Back to the first screen, as a fresh history. */
    private void goHome() {
        history.clear();
        history.add(new Place(Mode.HOME, null, null));
        selected.clear();
        pager.setPage(0);
        load();
    }

    private boolean granted() {
        return act.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Read the current place on a background thread, then show it. */
    private void load() {
        noAccess = !granted();
        if (noAccess) { items.clear(); render(); return; }
        final Place p = place();
        final String q = query;
        final int sort = prefs.getInt("sort", SORT_NAME);
        final boolean desc = prefs.getBoolean("desc", false);
        final boolean hidden = prefs.getBoolean("hidden", false);
        final int gen = ++generation;
        new Thread(() -> {
            List<File> files = new ArrayList<>();
            if (p.mode == Mode.FOLDER) {
                File[] all = p.dir.listFiles();
                if (all != null) for (File f : all) if (hidden || !f.getName().startsWith(".")) files.add(f);
                sort(files, sort, desc);
            } else if (!q.isEmpty()) {
                files = Roots.search(act, q);
            } else if (p.mode == Mode.MEDIA) {
                files = Roots.query(act, p.root.kind);
                if (p.root.kind != Roots.Kind.RECENT) sort(files, sort, desc);
            }
            final List<File> result = files;
            act.runOnUiThread(() -> {
                if (gen != generation) return;
                listing = result;
                showItems(p.mode == Mode.FOLDER ? filter(result) : result);
            });
        }).start();
    }

    private List<File> filter(List<File> files) {
        if (query.isEmpty()) return files;
        String q = query.toLowerCase(Locale.ROOT);
        List<File> out = new ArrayList<>();
        for (File f : files) if (f.getName().toLowerCase(Locale.ROOT).contains(q)) out.add(f);
        return out;
    }

    private void showItems(List<File> files) {
        items.clear();
        if (place().mode == Mode.HOME && query.isEmpty()) {
            for (Roots.Root r : Roots.all()) items.add(new Item(null, r));
        } else {
            for (File f : files) items.add(new Item(f, null));
        }
        selected.retainAll(files);
        render();
    }

    /** Folders first, then the chosen order; a tie is broken by name. */
    private static void sort(List<File> files, int by, boolean desc) {
        Comparator<File> name = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Comparator<File> order;
        switch (by) {
            case SORT_DATE: order = (a, b) -> Long.compare(a.lastModified(), b.lastModified()); break;
            case SORT_SIZE: order = (a, b) -> Long.compare(a.isDirectory() ? 0 : a.length(),
                                                           b.isDirectory() ? 0 : b.length()); break;
            case SORT_TYPE: order = (a, b) -> ext(a).compareToIgnoreCase(ext(b)); break;
            default: order = name;
        }
        Comparator<File> chosen = desc ? order.reversed() : order;
        Collections.sort(files, (a, b) -> {
            boolean da = a.isDirectory(), db = b.isDirectory();
            if (da != db) return da ? -1 : 1;
            int c = chosen.compare(a, b);
            return c != 0 ? c : name.compare(a, b);
        });
    }

    private static String ext(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1) : "";
    }

    // --- drawing -------------------------------------------------------------------------

    private void render() {
        Place p = place();
        boolean selecting = !selected.isEmpty();
        boolean searching = search.getVisibility() == View.VISIBLE;

        // The header.
        if (selecting) title.setText(selected.size() + " selected");
        else if (p.mode == Mode.FOLDER) title.setText(p.dir.equals(Roots.storage()) ? "Internal storage" : p.dir.getName());
        else if (p.mode == Mode.MEDIA) title.setText(p.root.title);
        else title.setText("Files");
        title.setVisibility(searching && !selecting ? View.GONE : View.VISIBLE);
        search.setVisibility(searching && !selecting ? View.VISIBLE : View.GONE);
        searchBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);
        sortBtn.setVisibility(selecting || p.mode == Mode.HOME ? View.GONE : View.VISIBLE);
        closeBtn.setVisibility(selecting ? View.VISIBLE : View.GONE);
        moreBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);

        renderCrumbs(p);

        // The page.
        int first = (p.mode == Mode.HOME && query.isEmpty()) ? 1 : 0;   // the storage bar takes a row
        int per = Math.max(1, rowsPerPage - first);
        pager.setPages((items.size() + per - 1) / per);
        list.removeAllViews();
        if (noAccess) {
            list.addView(noAccessPanel());
            pager.setPages(1);
        } else if (first == 1 && pager.page() == 0) list.addView(storageRow());
        int from = pager.page() * per;
        for (int i = from; i < Math.min(items.size(), from + per); i++) list.addView(row(items.get(i)));
        if (items.isEmpty() && !noAccess) list.addView(message(p.mode == Mode.FOLDER && query.isEmpty() ? "This folder is empty"
                : "Nothing here"));

        // The bars.
        opsBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        ((View) opsBar.getParent()).setVisibility(selecting ? View.VISIBLE : View.GONE);
        boolean showPaste = clip != null && !selecting;
        ((View) pasteBar.getParent()).setVisibility(showPaste ? View.VISIBLE : View.GONE);
        if (showPaste) {
            pasteText.setText(clip.size() + (clip.size() == 1 ? " item" : " items") + " to " + (clipMove ? "move" : "copy"));
            boolean here = p.mode == Mode.FOLDER && !busy;
            pasteBtn.setEnabled(here);
            pasteBtn.setAlpha(here ? 1f : 0.3f);
        }
    }

    private void renderCrumbs(Place p) {
        crumbs.removeAllViews();
        if (p.mode != Mode.FOLDER) { crumbScroll.setVisibility(View.GONE); return; }
        crumbScroll.setVisibility(View.VISIBLE);
        List<File> chain = new ArrayList<>();
        File storage = Roots.storage();
        for (File f = p.dir; f != null; f = f.getParentFile()) {
            chain.add(0, f);
            if (f.equals(storage)) break;
        }
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            if (i > 0) crumbs.addView(crumbText("›", Ui.DIM, null));
            boolean last = i == chain.size() - 1;
            crumbs.addView(crumbText(f.equals(storage) ? "Internal storage" : f.getName(),
                    last ? Ui.TEXT : Ui.DIM,
                    last ? null : v -> go(new Place(Mode.FOLDER, f, null))));
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private TextView crumbText(String text, int color, View.OnClickListener click) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(13);
        t.setSingleLine(true);
        int p = Ui.dp(act, 4);
        t.setPadding(p, Ui.dp(act, 10), p, Ui.dp(act, 10));
        if (click != null) t.setOnClickListener(click);
        return t;
    }

    /** The first row of the first screen: how full shared storage is. */
    private View storageRow() {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), 0);
        long total = 0, free = 0;
        try {
            StatFs s = new StatFs(Roots.storage().getPath());
            total = s.getTotalBytes();
            free = s.getAvailableBytes();
        } catch (IllegalArgumentException ignore) {}
        TextView t = new TextView(act);
        t.setTextColor(Ui.DIM);
        t.setTextSize(13);
        t.setText(Formatter.formatShortFileSize(act, total - free) + " used of "
                + Formatter.formatShortFileSize(act, total) + " · "
                + Formatter.formatShortFileSize(act, free) + " free");
        r.addView(t);
        // The bar: a white part for what is used, on a dark line.
        LinearLayout bar = new LinearLayout(act);
        bar.setBackgroundColor(Ui.LINE);
        View used = new View(act);
        used.setBackgroundColor(Ui.TEXT);
        float part = total > 0 ? (float) (total - free) / total : 0f;
        bar.setWeightSum(1f);
        bar.addView(used, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, part));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 4));
        lp.topMargin = Ui.dp(act, 8);
        r.addView(bar, lp);
        r.setLayoutParams(rowParams());
        return r;
    }

    private View row(final Item it) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(act, 16), 0, Ui.dp(act, 16), 0);
        boolean on = it.file != null && selected.contains(it.file);
        if (on) r.setBackground(Ui.outline(act, Ui.TEXT));

        ImageView icon = new ImageView(act);
        icon.setImageResource(it.root != null ? it.root.icon : iconOf(it.file));
        icon.setColorFilter(Ui.TEXT);
        r.addView(icon, new LinearLayout.LayoutParams(Ui.dp(act, 28), Ui.dp(act, 28)));

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(Ui.dp(act, 16), 0, 0, 0);
        TextView name = new TextView(act);
        name.setText(it.root != null ? it.root.title : it.file.getName());
        name.setTextColor(Ui.TEXT);
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        String sum = summary(it);
        if (!sum.isEmpty()) {
            TextView s = new TextView(act);
            s.setText(sum);
            s.setTextColor(Ui.DIM);
            s.setTextSize(12);
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(s);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (on) {
            TextView tick = new TextView(act);
            tick.setText("✓");
            tick.setTextColor(Ui.TEXT);
            tick.setTextSize(18);
            r.addView(tick);
        }

        r.setOnClickListener(v -> tap(it));
        if (it.file != null) r.setOnLongClickListener(v -> { toggle(it.file); return true; });
        r.setLayoutParams(rowParams());
        return r;
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, ROW_DP));
    }

    private String summary(Item it) {
        if (it.root != null) {
            if (it.root.dir != null) return it.root.dir.getPath();
            return it.root.kind == Roots.Kind.RECENT ? "Changed in the last " + Roots.RECENT_DAYS + " days" : "";
        }
        File f = it.file;
        String when = DateUtils.formatSameDayTime(f.lastModified(), System.currentTimeMillis(),
                DateFormat.MEDIUM, DateFormat.SHORT).toString();
        if (f.isDirectory()) return when;
        String s = when + " · " + Formatter.formatShortFileSize(act, f.length());
        // Outside a folder (media, search) the folder a file is in says where it is.
        if (place().mode != Mode.FOLDER && f.getParentFile() != null) s += " · " + f.getParentFile().getName();
        return s;
    }

    private static int iconOf(File f) {
        if (f.isDirectory()) return R.drawable.ic_folder;
        String m = FileShare.mimeOf(f);
        if (m.startsWith("image/")) return R.drawable.ic_image;
        if (m.startsWith("video/")) return R.drawable.ic_video;
        if (m.startsWith("audio/")) return R.drawable.ic_audio;
        if (m.startsWith("text/") || m.equals("application/pdf") || m.contains("document")
                || m.contains("epub")) return R.drawable.ic_doc;
        return R.drawable.ic_file;
    }

    private View message(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextColor(Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Ui.dp(act, 32), 0, 0);
        return t;
    }

    /** Without the storage permission there is nothing to list: ask for it. */
    private View noAccessPanel() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Ui.dp(act, 24), Ui.dp(act, 24), Ui.dp(act, 24), 0);
        TextView t = new TextView(act);
        t.setText("The Files tab needs access to the phone's storage.");
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        p.addView(t);
        Button ask = barButton("Allow access", v -> act.requestPermissions(new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE },
                REQUEST_STORAGE));
        Button settings = barButton("Open app settings", v -> start(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName()))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 16);
        p.addView(ask, lp);
        p.addView(settings, new LinearLayout.LayoutParams(lp));
        return p;
    }

    private Button barButton(String text, View.OnClickListener click) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setBackground(null);
        b.setStateListAnimator(null);
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    /** A bar with a line over it. */
    private View withLine(View bar) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(Ui.divider(act));
        box.addView(bar);
        box.setVisibility(View.GONE);
        return box;
    }

    // --- acting --------------------------------------------------------------------------

    private void tap(Item it) {
        if (it.root != null) {
            go(it.root.dir != null ? new Place(Mode.FOLDER, it.root.dir, null) : new Place(Mode.MEDIA, null, it.root));
            return;
        }
        if (!selected.isEmpty()) { toggle(it.file); return; }
        if (it.file.isDirectory()) go(new Place(Mode.FOLDER, it.file, null));
        else open(it.file);
    }

    private void toggle(File f) {
        if (!selected.remove(f)) selected.add(f);
        if (!selected.isEmpty()) hideIme();
        render();
    }

    private void clearSelection() {
        selected.clear();
        render();
    }

    private void open(File f) {
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileShare.uriFor(f), FileShare.mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No app can open " + f.getName());
        }
    }

    private void share() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : selected) if (f.isFile()) uris.add(FileShare.uriFor(f));
        if (uris.isEmpty()) { toast("Folders cannot be shared"); return; }
        Intent send;
        if (uris.size() == 1) {
            File f = new File(uris.get(0).getPath());
            send = new Intent(Intent.ACTION_SEND).setType(FileShare.mimeOf(f))
                    .putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            send = new Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        // The grant travels with the clip data, for every item.
        ClipData cd = ClipData.newRawUri("", uris.get(0));
        for (int i = 1; i < uris.size(); i++) cd.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(cd);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        start(Intent.createChooser(send, null));
    }

    private void toClip(boolean move) {
        clip = new ArrayList<>(selected);
        clipMove = move;
        selected.clear();
        render();
    }

    private void paste() {
        Place p = place();
        if (clip == null || p.mode != Mode.FOLDER || busy) return;
        final List<File> what = clip;
        final boolean move = clipMove;
        busy = true;
        status.setVisibility(View.VISIBLE);
        status.setText((move ? "Moving" : "Copying") + " 0 / " + what.size());
        render();
        FileOps.copy(act, what, p.dir, move, new FileOps.Progress() {
            @Override public void onProgress(int done, int total) {
                status.setText((move ? "Moving " : "Copying ") + done + " / " + total);
            }
            @Override public void onDone(String error) {
                busy = false;
                status.setVisibility(View.GONE);
                if (error != null) toast(error);
                else if (move) clip = null;   // a copy can be pasted again elsewhere
                load();
            }
        });
    }

    private void confirmDelete() {
        final List<File> what = new ArrayList<>(selected);
        String name = what.size() == 1 ? "\"" + what.get(0).getName() + "\"" : what.size() + " items";
        new AlertDialog.Builder(act)
                .setMessage("Delete " + name + "? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    busy = true;
                    selected.clear();
                    status.setVisibility(View.VISIBLE);
                    status.setText("Deleting 0 / " + what.size());
                    render();
                    FileOps.delete(act, what, new FileOps.Progress() {
                        @Override public void onProgress(int done, int total) {
                            status.setText("Deleting " + done + " / " + total);
                        }
                        @Override public void onDone(String error) {
                            busy = false;
                            status.setVisibility(View.GONE);
                            if (error != null) toast(error);
                            if (clip != null) clip.removeAll(what);
                            if (clip != null && clip.isEmpty()) clip = null;
                            load();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void rename(final File f) {
        final EditText name = new EditText(act);
        name.setSingleLine(true);
        name.setText(f.getName());
        int dot = f.isDirectory() ? -1 : f.getName().lastIndexOf('.');
        name.setSelection(0, dot > 0 ? dot : f.getName().length());   // the name, not the extension
        ask("Rename", name, () -> {
            String err = FileOps.rename(act, f, name.getText().toString().trim());
            if (err != null) toast(err);
            selected.clear();
            load();
        });
    }

    private void newFolder() {
        final File in = place().dir;
        final EditText name = new EditText(act);
        name.setSingleLine(true);
        name.setHint("Folder name");
        ask("New folder", name, () -> {
            String err = FileOps.mkdir(in, name.getText().toString().trim());
            if (err != null) toast(err);
            load();
        });
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
        field.requestFocus();
    }

    // --- menus ---------------------------------------------------------------------------

    private void showSort(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        int sort = prefs.getInt("sort", SORT_NAME);
        boolean desc = prefs.getBoolean("desc", false);
        for (int i = 0; i < SORTS.length; i++) {
            String arrow = i == sort ? (desc ? "  ↓" : "  ↑") : "";
            pm.getMenu().add(0, i, i, SORTS[i] + arrow);
        }
        pm.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            // The same choice again turns the order round, as DocumentsUI's column headers do.
            boolean d = id == sort ? !desc : (id == SORT_DATE || id == SORT_SIZE);
            prefs.edit().putInt("sort", id).putBoolean("desc", d).apply();
            load();
            return true;
        });
        pm.show();
    }

    private void showMenu(View anchor) {
        final int NEW_FOLDER = 1, HIDDEN = 2, SHELL = 3, HOME = 4;
        Place p = place();
        PopupMenu pm = new PopupMenu(act, anchor);
        Menu m = pm.getMenu();
        if (p.mode == Mode.FOLDER) {
            m.add(0, NEW_FOLDER, 0, "New folder");
            m.add(0, SHELL, 1, "Open shell here");
        }
        m.add(0, HIDDEN, 2, prefs.getBoolean("hidden", false) ? "Hide hidden files" : "Show hidden files");
        if (p.mode != Mode.HOME) m.add(0, HOME, 3, "Storage");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case NEW_FOLDER: newFolder(); break;
                case HIDDEN: prefs.edit().putBoolean("hidden", !prefs.getBoolean("hidden", false)).apply(); load(); break;
                case SHELL: host.openShellAt(p.dir); break;
                case HOME: goHome(); break;
            }
            return true;
        });
        pm.show();
    }

    private void showSelectionMenu(View anchor) {
        final List<File> sel = new ArrayList<>(selected);
        final File one = sel.size() == 1 ? sel.get(0) : null;
        PopupMenu pm = new PopupMenu(act, anchor);
        Menu m = pm.getMenu();
        if (one != null) m.add(0, 1, 0, "Rename");
        m.add(0, 2, 1, "Select all");
        if (one != null && one.isDirectory()) m.add(0, 3, 2, "Open shell here");
        if (one != null && place().mode != Mode.FOLDER) m.add(0, 4, 3, "Show in folder");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: rename(one); break;
                case 2: for (Item it : items) if (it.file != null) selected.add(it.file); render(); break;
                case 3: host.openShellAt(one); clearSelection(); break;
                case 4: go(new Place(Mode.FOLDER, one.getParentFile(), null)); break;
            }
            return true;
        });
        pm.show();
    }

    // --- search and the rest -------------------------------------------------------------

    private void toggleSearch() {
        if (search.getVisibility() == View.VISIBLE) { closeSearch(); return; }
        search.setVisibility(View.VISIBLE);
        render();
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        hideIme();
        main.removeCallbacks(runSearch);
        search.setVisibility(View.GONE);
        if (!query.isEmpty()) {
            search.setText("");   // clears query and shows the place again
            load();
        }
        render();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    private void start(Intent i) {
        try {
            act.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("No app can do that");
        }
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
