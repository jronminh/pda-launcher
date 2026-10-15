package org.pda.launcher.files;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.pda.launcher.Header;
import org.pda.launcher.PagedView;
import org.pda.launcher.Pager;
import org.pda.launcher.PopMenu;
import org.pda.launcher.R;
import org.pda.launcher.Ui;
import org.pda.launcher.Config;

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
 * Share and a menu (Rename, Select all, Show in folder, Add to Briefcase).
 * Copied or cut items wait in a bar until they are pasted into a folder.
 */
public final class FilesTab {

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


    // What was copied or cut: kept across screens until it is pasted or dropped.
    private static List<File> clip;
    private static boolean clipMove;

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final LinearLayout root;
    private final Header header;
    private final View sortBtn, moreBtn, closeBtn;
    private final LinearLayout banner;    // the first screen: how full storage is
    private final HorizontalScrollView crumbScroll;
    private final LinearLayout crumbs;
    private final PagedView list;
    private final LinearLayout overlay;   // over the list: no access, or an empty folder
    private final ImageView viewBtn;
    private final LinearLayout opsBar, pasteBar;
    private final TextView pasteText, status;
    private final View pasteBtn;
    private final Pager pager;

    private final List<Place> history = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final Set<File> selected = new LinkedHashSet<>();
    private Set<File> favs = new java.util.HashSet<>();
    private List<File> listing = new ArrayList<>();   // the folder as read, before the search filter
    private int generation;
    private boolean busy;
    private boolean noAccess;   // no storage permission: the page asks for it

    private final Runnable runSearch = this::load;
    // The order, hidden files, the view: read the place again.
    private final Config.Listener onConfig = keys -> { if (Config.touches(keys, "files.")) load(); };

    public FilesTab(Activity act) {
        this.act = act;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);

        // The header: ‹ (when there is a place to go back to), search, sort, the
        // view, and the menu; a selection shows ✕ instead.
        pager = new Pager(act);
        sortBtn = Header.sort(act, "files.sort", "files.desc", "Name", "Date modified", "Size", "Type");
        moreBtn = Ui.action(act, R.drawable.ic_more, "More", v -> showMenu(v));
        closeBtn = Ui.action(act, R.drawable.ic_close, "End the selection", v -> clearSelection());
        viewBtn = Ui.action(act, PagedView.iconOf(Config.get("files.view")), "View",
                v -> PagedView.chooseMode(act, v, Config.get("files.view"), m -> Config.set(act, "files.view", m)));
        header = new Header(act, "")
                .back(this::onBack)
                .search(q -> {
                    pager.setPage(0);
                    // A folder filters what it already read; elsewhere the media index is asked.
                    main.removeCallbacks(runSearch);
                    if (place().mode == Mode.FOLDER) showItems(filter(listing));
                    else main.postDelayed(runSearch, 300);
                })
                .action(sortBtn).action(viewBtn).action(closeBtn).action(moreBtn);
        root.addView(header.view());
        root.addView(Ui.divider(act));

        banner = new LinearLayout(act);
        banner.setOrientation(LinearLayout.VERTICAL);
        root.addView(banner);

        // The path: Internal storage › Download › ..., each part a way back.
        crumbs = new LinearLayout(act);
        crumbs.setOrientation(LinearLayout.HORIZONTAL);
        crumbs.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), 0);
        crumbScroll = new HorizontalScrollView(act);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll);

        // The current page, as details, a list or a grid; a message can cover it.
        list = new PagedView(act, pager);
        list.setBinder(new PagedView.Binder() {
            @Override public int count() { return items.size(); }
            @Override public Drawable icon(int i) {
                Item it = items.get(i);
                return Ui.icon(act, it.root != null ? it.root.icon : iconOf(it.file));
            }
            @Override public String title(int i) {
                Item it = items.get(i);
                return it.root != null ? it.root.title : it.file.getName();
            }
            @Override public String summary(int i) { return FilesTab.this.summary(items.get(i)); }
            @Override public String mark(int i) {
                File f = items.get(i).file;
                return f == null ? null : selected.contains(f) ? "✓" : favs.contains(f) ? "★" : null;
            }
            @Override public boolean highlight(int i) {
                File f = items.get(i).file;
                return f != null && selected.contains(f);
            }
            @Override public boolean dim(int i) { return false; }
            @Override public void thumb(int i, ImageView icon) {
                File f = items.get(i).file;
                if (f != null && !noAccess) Thumbs.into(act, f, icon);
            }
            @Override public void click(int i, View v) { tap(items.get(i)); }
            @Override public boolean longClick(int i, View v) {
                Roots.Root r = items.get(i).root;
                if (r != null && r.kind == Roots.Kind.LOCATION) { locationMenu(v, r); return true; }
                File f = items.get(i).file;
                if (f == null) return false;
                toggle(f);
                return true;
            }
        });
        overlay = new LinearLayout(act);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(Ui.BG);
        overlay.setVisibility(View.GONE);
        FrameLayout body = new FrameLayout(act);
        body.addView(list);
        body.addView(overlay);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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
        opsBar.addView(op(R.drawable.ic_copy, "Copy", v -> toClip(false)));
        opsBar.addView(op(R.drawable.ic_move, "Move", v -> toClip(true)));
        opsBar.addView(op(R.drawable.ic_delete, "Delete", v -> confirmDelete()));
        opsBar.addView(op(R.drawable.ic_share, "Share", v -> share()));
        opsBar.addView(op(R.drawable.ic_more, "More", v -> showSelectionMenu(v)));
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
        pasteBtn = Ui.labeledAction(act, R.drawable.ic_paste, "Paste here", v -> paste());
        pasteBar.addView(pasteBtn, new LinearLayout.LayoutParams(
                Ui.dp(act, 80), ViewGroup.LayoutParams.WRAP_CONTENT));
        pasteBar.addView(Ui.action(act, R.drawable.ic_close, "Drop", v -> { clip = null; render(); }));
        root.addView(withLine(pasteBar));

        root.addView(pager);
        Config.addListener(onConfig);

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

    public void onDestroy() {
        Config.removeListener(onConfig);
    }

    /** Back on screen: the folder may have changed, or the permission been granted. */
    public void onResume() {
        load();
    }

    /** Back: the selection, the search, then the history. Returns true if it did something. */
    public boolean onBack() {
        if (!selected.isEmpty()) { clearSelection(); return true; }
        if (header.searching()) { header.closeSearch(); return true; }
        if (history.size() > 1) {
            history.remove(history.size() - 1);
            list.slideNext(-1);
            pager.setPage(place().page);
            load();
            return true;
        }
        return false;
    }

    /** Show a folder, from another tab (Briefcase, Today): the first screen, then the folder. */
    public void openFolder(File dir) {
        if (header.searching()) header.closeSearch();
        history.clear();
        history.add(new Place(Mode.HOME, null, null));
        history.add(new Place(Mode.FOLDER, dir, null));
        selected.clear();
        pager.setPage(0);
        load();
    }

    /** The tab's button tapped again: the first screen, no search, no selection. */
    public void toStart() {
        if (header.searching()) header.closeSearch();
        list.slideNext(-1);
        goHome();
    }

    /** Where the tab is, to be kept: "home", "folder:/path" or "media:KIND". */
    public String placeKey() {
        Place p = place();
        if (p.mode == Mode.FOLDER) return "folder:" + p.dir.getPath();
        if (p.mode == Mode.MEDIA) return "media:" + p.root.kind.name();
        return "home";
    }

    /** Back where {@link #placeKey} said (a folder gone, a root unknown: the first screen). */
    public void restore(String key) {
        if (key == null) return;
        if (key.startsWith("folder:")) {
            File dir = new File(key.substring(7));
            if (dir.isDirectory()) openFolder(dir);
        } else if (key.startsWith("media:")) {
            for (Roots.Root r : Roots.all()) {
                if (r.kind.name().equals(key.substring(6)) && r.dir == null) {
                    history.clear();
                    history.add(new Place(Mode.HOME, null, null));
                    history.add(new Place(Mode.MEDIA, null, r));
                    pager.setPage(0);
                    load();
                }
            }
        }
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
        if (header.searching()) header.closeSearch();
        place().page = pager.page();
        history.add(p);
        list.slideNext(1);
        selected.clear();
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
        final String q = header.query();
        final String sort = Config.get("files.sort");
        final boolean desc = Config.getBool("files.desc");
        final boolean hidden = Config.getBool("files.showHidden");
        favs = new java.util.HashSet<>(Favorites.load(act));
        final int gen = ++generation;
        new Thread(() -> {
            List<File> files = new ArrayList<>();
            if (p.mode == Mode.FOLDER) {
                File[] all = p.dir.listFiles();
                if (all != null) for (File f : all) if (hidden || !f.getName().startsWith(".")) files.add(f);
                sort(files, sort, desc);
            } else if (!q.isEmpty()) {
                files = Roots.search(act, q);
                sort(files, sort, desc);
            } else if (p.mode == Mode.MEDIA && p.root.kind == Roots.Kind.TRASH) {
                java.util.Map<File, Trash.Entry> t = new java.util.HashMap<>();
                for (Trash.Entry e : Trash.entries()) {
                    t.put(e.file, e);
                    files.add(e.file);   // the last deleted first
                }
                trashed = t;
            } else if (p.mode == Mode.MEDIA) {
                files = Roots.query(act, p.root.kind);
                if (p.root.kind != Roots.Kind.RECENT) sort(files, sort, desc);
            } else if (p.mode == Mode.HOME) {
                purgeTrash();
            }
            final List<File> result = files;
            act.runOnUiThread(() -> {
                if (gen != generation) return;
                listing = result;
                showItems(p.mode == Mode.FOLDER ? filter(result) : result);
            });
        }).start();
    }

    // --- locations (other apps' folders, through the Storage Access Framework) ----------

    public static final int REQUEST_TREE = 44;

    private void addLocation() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            act.startActivityForResult(pick, REQUEST_TREE);
        } catch (ActivityNotFoundException e) {
            toast("No folder picker on this device");
        }
    }

    /** The answer of the folder picker (HomeActivity passes it on). */
    public void onTreePicked(Intent data) {
        String name = Locations.add(act, data);
        if (name != null) toast("Added: " + name);
        load();
    }

    private void locationMenu(View anchor, Roots.Root r) {
        PopMenu m = new PopMenu(act, anchor);
        m.add(0, 1, 0, "Rename");
        m.add(0, 2, 1, "Remove");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                new org.pda.launcher.Prompt(act, "Rename").field("Name", r.title).ok("Rename", v -> {
                    if (!v[0].isEmpty()) Locations.rename(act, r.tree, v[0]);
                    load();
                }).show(root);
            } else {
                Locations.remove(act, r.tree);
                toast("Removed " + r.title + " (nothing in it was touched)");
                load();
            }
            return true;
        });
        m.show();
    }

    /** The app that provides a location, by its documents provider's authority. */
    private String providerName(android.net.Uri tree) {
        try {
            android.content.pm.ProviderInfo pi = act.getPackageManager().resolveContentProvider(tree.getAuthority(), 0);
            if (pi != null) return String.valueOf(pi.applicationInfo.loadLabel(act.getPackageManager()));
        } catch (RuntimeException ignore) {}
        return tree.getAuthority();
    }

    /** What the trash holds, by the thing's place in it: where it came from, when. */
    private volatile java.util.Map<File, Trash.Entry> trashed = new java.util.HashMap<>();

    private boolean inTrash() {
        Place p = place();
        return p.mode == Mode.MEDIA && p.root.kind == Roots.Kind.TRASH && header.query().isEmpty();
    }

    /** On the files' thread: what was deleted longer ago than files.trashDays goes for good. */
    private void purgeTrash() {
        List<File> old = Trash.expired(Integer.parseInt(Config.get("files.trashDays")));
        if (old.isEmpty()) return;
        FileOps.delete(act, old, new FileOps.Progress() {
            @Override public void onProgress(int done, int total) { }
            @Override public void onDone(String error) { }
        });
    }

    private List<File> filter(List<File> files) {
        if (header.query().isEmpty()) return files;
        String q = header.query().toLowerCase(Locale.ROOT);
        List<File> out = new ArrayList<>();
        for (File f : files) if (f.getName().toLowerCase(Locale.ROOT).contains(q)) out.add(f);
        return out;
    }

    private void showItems(List<File> files) {
        items.clear();
        if (place().mode == Mode.HOME && header.query().isEmpty()) {
            for (Roots.Root r : Roots.all(act)) items.add(new Item(null, r));
        } else {
            for (File f : files) items.add(new Item(f, null));
        }
        selected.retainAll(files);
        render();
    }

    /** What sorting needs of a file, read once: on shared storage every stat goes
     *  through FUSE, and a comparator would ask for it at every comparison. */
    private static final class Key {
        final File file;
        final boolean dir;
        final String name;
        final long date, size;

        Key(File f) {
            file = f;
            dir = f.isDirectory();
            name = f.getName();
            date = f.lastModified();
            size = dir ? 0 : f.length();
        }
    }

    /** Folders first, then the order {@code by} (name, date, size, type); a tie is
     *  broken by name. */
    static void sort(List<File> files, String by, boolean desc) {
        List<Key> keys = new ArrayList<>(files.size());
        for (File f : files) keys.add(new Key(f));
        Comparator<Key> name = (a, b) -> a.name.compareToIgnoreCase(b.name);
        Comparator<Key> order;
        switch (by) {
            case "date": order = (a, b) -> Long.compare(a.date, b.date); break;
            case "size": order = (a, b) -> Long.compare(a.size, b.size); break;
            case "type": order = (a, b) -> ext(a.name).compareToIgnoreCase(ext(b.name)); break;
            default: order = name;
        }
        Comparator<Key> chosen = desc ? order.reversed() : order;
        Collections.sort(keys, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            int c = chosen.compare(a, b);
            return c != 0 ? c : name.compare(a, b);
        });
        files.clear();
        for (Key k : keys) files.add(k.file);
    }

    private static String ext(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot + 1) : "";
    }

    // --- drawing -------------------------------------------------------------------------

    private void render() {
        Place p = place();
        boolean selecting = !selected.isEmpty();
        boolean home = p.mode == Mode.HOME && header.query().isEmpty();

        // The header.
        // The tab bar names the tab and the crumbs a folder: a title only for a
        // media root (it has no crumbs) and for a selection.
        if (selecting) header.setTitle(selected.size() + " selected");
        else if (p.mode == Mode.MEDIA) header.setTitle(p.root.title);
        else header.setTitle("");
        header.showBack(!selecting && history.size() > 1);
        header.setSearchEnabled(!selecting);
        // Recent is newest first by what it is (as DocumentsUI's), so it has no sort.
        boolean recent = p.mode == Mode.MEDIA && p.root.kind == Roots.Kind.RECENT && header.query().isEmpty();
        sortBtn.setVisibility(selecting || home || recent ? View.GONE : View.VISIBLE);
        closeBtn.setVisibility(selecting ? View.VISIBLE : View.GONE);
        moreBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);
        String mode = Config.get("files.view");
        viewBtn.setImageResource(PagedView.iconOf(mode));
        viewBtn.setVisibility(selecting ? View.GONE : View.VISIBLE);

        renderCrumbs(p);

        // The first screen: how full storage is, over the roots.
        banner.removeAllViews();
        if (home && !noAccess) banner.addView(storageBanner());
        banner.setVisibility(banner.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        // The page.
        list.setMode(mode);
        list.setGrid(4, 0);
        list.refresh();
        overlay.removeAllViews();
        if (noAccess) overlay.addView(noAccessPanel());
        else if (items.isEmpty()) overlay.addView(message(p.mode == Mode.FOLDER && header.query().isEmpty()
                ? "This folder is empty" : "Nothing here"));
        overlay.setVisibility(overlay.getChildCount() > 0 ? View.VISIBLE : View.GONE);

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
        // Over a bar (the selection's, the paste one) a single page leaves no empty
        // page bar: the bar sits right under the list.
        pager.setVisibility((selecting || showPaste) && pager.pages() <= 1 ? View.GONE : View.VISIBLE);
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

    // What storage holds by kind (Roots.kinds), counted in the background and kept
    // between visits; recounted at most every half minute.
    private static long[] kinds;
    private static long kindsAt;

    /** The first screen's banner: how full shared storage is, and with what. */
    private View storageBanner() {
        LinearLayout r = Ui.banner(act);
        long total = 0, free = 0;
        try {
            StatFs s = new StatFs(Roots.storage().getPath());
            total = s.getTotalBytes();
            free = s.getAvailableBytes();
        } catch (IllegalArgumentException ignore) {}
        r.addView(Ui.bannerTitle(act, Formatter.formatShortFileSize(act, total - free) + " used of "
                + Formatter.formatShortFileSize(act, total) + " · "
                + Formatter.formatShortFileSize(act, free) + " free"));
        // The bar: a white part for what is used, on a dark line.
        LinearLayout bar = new LinearLayout(act);
        bar.setBackgroundColor(Ui.LINE);
        View used = new View(act);
        used.setBackgroundColor(Ui.TEXT);
        float part = total > 0 ? (float) (total - free) / total : 0f;
        bar.setWeightSum(1f);
        bar.addView(used, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, part));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 3));
        lp.topMargin = Ui.dp(act, 5);
        lp.bottomMargin = Ui.dp(act, 5);
        r.addView(bar, lp);
        // By kind: images, videos, audio, documents (sizes and counts).
        TextView k = Ui.bannerLine(act, kinds == null ? "Counting…" : kindsText());
        r.addView(k);
        if (kinds == null || android.os.SystemClock.uptimeMillis() - kindsAt > 30_000) {
            kindsAt = android.os.SystemClock.uptimeMillis();
            new Thread(() -> {
                long[] counted = Roots.kinds(act);
                act.runOnUiThread(() -> {
                    kinds = counted;
                    if (k.isAttachedToWindow()) k.setText(kindsText());
                });
            }).start();
        }
        return r;
    }

    /** One banner line: each kind with something in it, by size. */
    private String kindsText() {
        String[] names = { "Images", "Videos", "Audio", "Documents" };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (kinds[i * 2 + 1] == 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(names[i]).append(' ').append(Formatter.formatShortFileSize(act, kinds[i * 2]));
        }
        return sb.length() > 0 ? sb.toString() : "No images, videos, audio or documents";
    }


    private String summary(Item it) {
        if (it.root != null) {
            if (it.root.dir != null) return it.root.dir.getPath();
            if (it.root.kind == Roots.Kind.LOCATION) {
                return Locations.reachable(act, it.root.tree) ? "From " + providerName(it.root.tree)
                        : "Not reachable: add it again";
            }
            if (it.root.kind == Roots.Kind.ADD) return "A NAS, a cloud, Termux… through an app that offers it";
            if (it.root.kind == Roots.Kind.TRASH) return "Deleted things, kept " + Config.get("files.trashDays") + " days";
            return it.root.kind == Roots.Kind.RECENT ? "Changed in the last " + Roots.RECENT_DAYS + " days" : "";
        }
        Trash.Entry e = inTrash() ? trashed.get(it.file) : null;
        if (e != null) {
            File from = new File(e.origin).getParentFile();
            return "Deleted " + DateUtils.formatSameDayTime(e.deleted, System.currentTimeMillis(),
                    DateFormat.MEDIUM, DateFormat.SHORT) + " · from " + (from == null ? "/" : from.getName());
        }
        // Outside a folder (media, search) the folder a file is in says where it is.
        return describe(act, it.file, place().mode != Mode.FOLDER);
    }

    /** "date · size · folder" for a file, the date for a folder. */
    static String describe(Context c, File f, boolean withFolder) {
        if (!f.exists()) return "Not found";
        String when = DateUtils.formatSameDayTime(f.lastModified(), System.currentTimeMillis(),
                DateFormat.MEDIUM, DateFormat.SHORT).toString();
        if (f.isDirectory()) return withFolder ? when + " · " + f.getParent() : when;
        String s = when + " · " + Formatter.formatShortFileSize(c, f.length());
        if (withFolder && f.getParentFile() != null) s += " · " + f.getParentFile().getName();
        return s;
    }

    static int iconOf(File f) {
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

    /** One of the selection's actions: an icon over a small label, a fifth of the bar. */
    private View op(int icon, String label, View.OnClickListener click) {
        View b = Ui.labeledAction(act, icon, label, click);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
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
        if (it.root != null && it.root.kind == Roots.Kind.LOCATION) {
            start(new Intent(act, LocationActivity.class)
                    .putExtra(LocationActivity.EXTRA_TREE, it.root.tree.toString())
                    .putExtra(LocationActivity.EXTRA_NAME, it.root.title));
            return;
        }
        if (it.root != null && it.root.kind == Roots.Kind.ADD) {
            addLocation();
            return;
        }
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
        open(act, f);
    }

    /** Open a file: in a built-in viewer if its kind is set so (Prefs › Viewers), else
     *  in the app that handles its type. */
    static void open(Context c, File f) {
        if (org.pda.launcher.viewers.Viewers.open(c, f)) return;
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileShare.linkFor(c, f), FileShare.mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, "No app can open " + f.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    private void share() {
        ArrayList<Uri> uris = new ArrayList<>();
        File first = null;
        for (File f : selected) {
            if (!f.isFile()) continue;
            if (first == null) first = f;
            uris.add(FileShare.linkFor(act, f));
        }
        if (uris.isEmpty()) { toast("Folders cannot be shared"); return; }
        Intent send;
        if (uris.size() == 1) {
            send = new Intent(Intent.ACTION_SEND).setType(FileShare.mimeOf(first))
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

    /** Delete: into the trash at once (Restore brings it back); in the trash, for good, after a question. */
    private void confirmDelete() {
        final List<File> what = new ArrayList<>(selected);
        String name = what.size() == 1 ? "\"" + what.get(0).getName() + "\"" : what.size() + " items";
        if (!inTrash() && !Trash.contains(place().dir == null ? new File("/") : place().dir)) {
            work("Moving to Trash", what, (list, p) -> FileOps.trash(act, list, p),
                    () -> toast(what.size() == 1 ? "Moved to Trash" : what.size() + " items moved to Trash"));
            return;
        }
        // In the trash: the slots go, the thing and where it came from.
        final List<File> slots = new ArrayList<>();
        for (File f : what) slots.add(inTrash() ? f.getParentFile() : f);
        new AlertDialog.Builder(act)
                .setMessage("Delete " + name + " for good? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    final List<File> what2 = slots;
                    busy = true;
                    selected.clear();
                    status.setVisibility(View.VISIBLE);
                    status.setText("Deleting 0 / " + what2.size());
                    render();
                    FileOps.delete(act, what2, new FileOps.Progress() {
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

    private interface Op {
        void start(List<File> items, FileOps.Progress p);
    }

    /** A file operation with its progress in the status line, then the place read again. */
    private void work(String doing, List<File> what, Op op, Runnable after) {
        busy = true;
        selected.clear();
        status.setVisibility(View.VISIBLE);
        status.setText(doing + " 0 / " + what.size());
        render();
        op.start(what, new FileOps.Progress() {
            @Override public void onProgress(int done, int total) {
                status.setText(doing + " " + done + " / " + total);
            }
            @Override public void onDone(String error) {
                busy = false;
                status.setVisibility(View.GONE);
                if (error != null) toast(error);
                else if (after != null) after.run();
                if (clip != null) clip.removeAll(what);
                if (clip != null && clip.isEmpty()) clip = null;
                load();
            }
        });
    }

    private void restore(List<File> what) {
        final List<Trash.Entry> entries = new ArrayList<>();
        for (File f : what) {
            Trash.Entry e = trashed.get(f);
            if (e != null) entries.add(e);
        }
        work("Restoring", what, (list, p) -> FileOps.restore(act, entries, p),
                () -> toast(entries.size() == 1 ? "Restored to " + new File(entries.get(0).origin).getParent()
                        : entries.size() + " items restored"));
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

    private void showMenu(View anchor) {
        final int NEW_FOLDER = 1, HIDDEN = 2, HOME = 4;
        Place p = place();
        PopMenu pm = new PopMenu(act, anchor);
        PopMenu m = pm.getMenu();
        if (p.mode == Mode.FOLDER) {
            m.add(0, NEW_FOLDER, 0, "New folder");
        }
        m.add(0, HIDDEN, 2, Config.getBool("files.showHidden") ? "Hide hidden files" : "Show hidden files");
        if (p.mode != Mode.HOME) m.add(0, HOME, 3, "Storage");
        final int EMPTY = 5, KEEP = 6;
        if (inTrash()) {
            m.add(0, EMPTY, -2, "Empty trash").setEnabled(!items.isEmpty());
            m.add(0, KEEP, -1, "Keep for: " + Config.get("files.trashDays") + " days  ›");
        }
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case EMPTY: emptyTrash(); break;
                case KEEP: PopMenu.choose(act, anchor, "files.trashDays", "7 days", "30 days", "90 days", "365 days"); break;
                case NEW_FOLDER: newFolder(); break;
                case HIDDEN: {
                    String err = Config.set(act, "files.showHidden",
                            Config.getBool("files.showHidden") ? "false" : "true");
                    if (err != null) toast(err);
                    load();
                    break;
                }
                case HOME: goHome(); break;
            }
            return true;
        });
        pm.show();
    }

    private void emptyTrash() {
        final List<File> slots = new ArrayList<>();
        for (Item it : items) if (it.file != null) slots.add(it.file.getParentFile());
        new AlertDialog.Builder(act)
                .setMessage("Delete the " + slots.size() + (slots.size() == 1 ? " thing" : " things")
                        + " in the trash for good? This cannot be undone.")
                .setPositiveButton("Empty", (d, w) -> work("Deleting", slots,
                        (list, p) -> FileOps.delete(act, list, p), () -> toast("The trash is empty")))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSelectionMenu(View anchor) {
        final List<File> sel = new ArrayList<>(selected);
        final File one = sel.size() == 1 ? sel.get(0) : null;
        if (inTrash()) {
            // In the trash: put back, or look; nothing that would change it in place.
            PopMenu pm = new PopMenu(act, anchor);
            pm.getMenu().add(0, 1, 0, "Restore");
            if (one != null) pm.getMenu().add(0, 2, 1, "Details");
            pm.getMenu().add(0, 3, 2, "Select all");
            pm.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: restore(sel); break;
                    case 2: FileDetails.show(act, one, root); break;
                    default: for (Item it : items) if (it.file != null) selected.add(it.file); render();
                }
                return true;
            });
            pm.show();
            return;
        }
        PopMenu pm = new PopMenu(act, anchor);
        PopMenu m = pm.getMenu();
        if (one != null && one.isFile()) m.add(0, 6, -2, "Open with…");
        if (one != null) m.add(0, 7, -1, "Details");
        if (one != null) m.add(0, 1, 0, "Rename");
        m.add(0, 2, 1, "Select all");
        if (one != null && place().mode != Mode.FOLDER) m.add(0, 4, 3, "Show in folder");
        final boolean allFav = favs.containsAll(sel);
        m.add(0, 5, 4, allFav ? "Remove from Briefcase" : "Add to Briefcase");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 6: FileDetails.openWith(act, one); clearSelection(); break;
                case 7: FileDetails.show(act, one, root); break;
                case 1: rename(one); break;
                case 2: for (Item it : items) if (it.file != null) selected.add(it.file); render(); break;
                case 4: go(new Place(Mode.FOLDER, one.getParentFile(), null)); break;
                case 5:
                    if (allFav) Favorites.remove(act, sel);
                    else Favorites.add(act, sel);
                    toast(allFav ? "Removed from Briefcase" : "Added to Briefcase");
                    selected.clear();
                    load();
                    break;
            }
            return true;
        });
        pm.show();
    }

    // --- search and the rest -------------------------------------------------------------

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
