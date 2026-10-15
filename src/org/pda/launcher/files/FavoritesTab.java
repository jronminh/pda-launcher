package org.pda.launcher.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.pda.launcher.Config;
import org.pda.launcher.Header;
import org.pda.launcher.PagedView;
import org.pda.launcher.Pager;
import org.pda.launcher.PopMenu;
import org.pda.launcher.R;
import org.pda.launcher.Ui;
import org.pda.launcher.files.Favorites.Item;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The Briefcase tab, in the look of Applications and Prefs: one page of tiles,
 * each a thing the user keeps (a file, a folder, a link, an app, an app's
 * shortcut) drawn with the launcher's line icon, in the user's order; when
 * they are more than a page, the last tile, More, lists them all (paged,
 * sorted). Tap to open (a file in its viewer, a folder in Files); long-press to
 * rename, move, remove, and a file's Open with, Open as, Details, Show in
 * folder. Added with + (a link, an app or its shortcut), from Files (⋮ › Add
 * to Briefcase), from Applications (long-press › Add to Briefcase) and Share ›
 * Add to Briefcase.
 */
public final class FavoritesTab {

    /** What the tab asks of the launcher: show a folder in the Files tab. */
    public interface Host {
        void openFolder(File dir);
    }

    private final Activity act;
    private final Host host;
    private final LauncherApps launcherApps;
    private final LinearLayout root;
    private final Header header;
    private final ImageView viewBtn, sortBtn;
    private final Pager pager;
    private final PagedView list;
    private final TextView empty;
    private final LinearLayout banner;   // what the Briefcase holds, at a glance (as Prefs' banner)
    private List<Item> items = new ArrayList<>();    // in the user's order
    private final List<Item> shown = new ArrayList<>();
    private boolean more;   // in More (every item, sorted), not on the tiles

    private final Config.Listener onConfig = keys -> { if (Config.touches(keys, "favorites.")) onConfig(); };

    public FavoritesTab(Activity act, Host host) {
        this.act = act;
        this.host = host;
        launcherApps = (LauncherApps) act.getSystemService(Context.LAUNCHER_APPS_SERVICE);

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        pager = new Pager(act);
        viewBtn = Ui.action(act, PagedView.iconOf(Config.get("favorites.view")), "View",
                v -> PagedView.chooseMode(act, v, Config.get("favorites.view"),
                        m -> Config.set(act, "favorites.view", m)));
        sortBtn = Header.sort(act, "favorites.sort", "favorites.desc",
                "Order added", "Name", "Date modified", "Size", "Type");
        header = new Header(act, "")
                .back(() -> showMore(false))
                .search(q -> {
                    pager.setPage(0);
                    show();
                })
                .action(Ui.action(act, R.drawable.ic_add, "Add", this::addMenu))
                .action(sortBtn)
                .action(viewBtn)
                .action(Ui.action(act, R.drawable.ic_more, "More", v -> showMenu(v)));
        root.addView(header.view());
        root.addView(Ui.divider(act));

        banner = Ui.banner(act);
        root.addView(banner);

        list = new PagedView(act, pager);
        list.setBinder(new PagedView.Binder() {
            @Override public int count() { return onTiles() && overflow() ? list.perPage() : shown.size(); }
            @Override public Drawable icon(int i) {
                return Ui.icon(act, isMore(i) ? R.drawable.ic_more : iconOf(shown.get(i)));
            }
            @Override public String title(int i) { return isMore(i) ? "More" : shown.get(i).title; }
            @Override public String summary(int i) {
                return isMore(i) ? items.size() + " items" : describe(shown.get(i));
            }
            @Override public String mark(int i) { return null; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return !isMore(i) && missing(shown.get(i)); }
            @Override public void click(int i, View v) {
                if (isMore(i)) showMore(true);
                else open(shown.get(i));
            }
            @Override public boolean longClick(int i, View v) {
                if (isMore(i)) return false;
                itemMenu(v, shown.get(i));
                return true;
            }
        });

        // The empty message sits over the list when there is nothing to show.
        FrameLayout body = new FrameLayout(act);
        body.addView(list);
        empty = new TextView(act);
        empty.setTextColor(Ui.DIM);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(0, Ui.dp(act, 48), 0, 0);
        body.addView(empty);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(pager);

        Config.addListener(onConfig);
        applyView();
    }

    public View view() {
        return root;
    }

    /** On screen: read the list again (Files, Applications or Share may have changed it). */
    public void onShow() {
        items = Favorites.items(act);
        show();
    }

    /** The tab's button tapped again: the tiles, no search. */
    public void toStart() {
        if (header.searching()) header.closeSearch();
        more = false;
        pager.setPage(0);
        show();
    }

    /** Back: close the search, then from More to the tiles. */
    public boolean onBack() {
        if (header.searching()) { header.closeSearch(); return true; }
        if (more) { showMore(false); return true; }
        return false;
    }

    public void onHide() {
        header.hideIme();
    }

    public void onDestroy() {
        Config.removeListener(onConfig);
    }

    private void onConfig() {
        applyView();
        show();
    }

    private void applyView() {
        String mode = Config.get("favorites.view");
        viewBtn.setImageResource(PagedView.iconOf(mode));
        list.setMode(mode);
        list.setGrid(4, 0);
    }

    // --- the tiles and More ----------------------------------------------------------------

    /** The tiles are on screen: not in More, not searching. */
    private boolean onTiles() {
        return !more && header.query().isEmpty();
    }

    /** More items than a page: the last tile is More. */
    private boolean overflow() {
        return items.size() > list.perPage();
    }

    private boolean isMore(int i) {
        return onTiles() && overflow() && i == list.perPage() - 1;
    }

    private void showMore(boolean on) {
        if (header.searching()) header.closeSearch();
        more = on;
        list.slideNext(on ? 1 : -1);
        pager.setPage(0);
        show();
    }

    /** The tiles in the user's order; in More or a search, those that match, sorted as chosen. */
    private void show() {
        shown.clear();
        String q = header.query().toLowerCase(Locale.ROOT);
        for (Item it : items) if (q.isEmpty() || it.title.toLowerCase(Locale.ROOT).contains(q)) shown.add(it);
        if (!onTiles()) sort(shown);
        header.setTitle(more && q.isEmpty() ? "All items" : "");
        header.showBack(more);
        sortBtn.setVisibility(onTiles() ? View.GONE : View.VISIBLE);
        empty.setText(items.isEmpty()
                ? "The Briefcase is empty.\n\nAdd with +: a link, an app or its shortcut;\n"
                    + "or in Files, long-press a file or a folder,\nthen ⋮ › Add to Briefcase."
                : "Nothing matches.");
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        fillBanner();
        list.refresh();
    }

    /** "Briefcase", then how many of each kind: on the tiles only, as Prefs shows its banner. */
    private void fillBanner() {
        banner.removeAllViews();
        banner.setVisibility(onTiles() && !items.isEmpty() ? View.VISIBLE : View.GONE);
        int files = 0, folders = 0, links = 0, apps = 0;
        for (Item it : items) {
            if (it.file() != null) {
                if (it.file().isDirectory()) folders++;
                else files++;
            } else if (Item.URL.equals(it.kind)) {
                links++;
            } else {
                apps++;
            }
        }
        banner.addView(Ui.bannerTitle(act, items.size() + (items.size() == 1 ? " item" : " items")));
        StringBuilder sb = new StringBuilder();
        count(sb, files, "file");
        count(sb, folders, "folder");
        count(sb, links, "link");
        count(sb, apps, "app or shortcut", "apps or shortcuts");
        banner.addView(Ui.bannerLine(act, sb));
    }

    private static void count(StringBuilder sb, int n, String one) {
        count(sb, n, one, one + "s");
    }

    private static void count(StringBuilder sb, int n, String one, String many) {
        if (n == 0) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(n).append(' ').append(n == 1 ? one : many);
    }

    /** favorites.sort: the order added, or name, date, size, type (things not files: by name). */
    private static void sort(List<Item> list) {
        String by = Config.get("favorites.sort");
        java.util.Comparator<Item> byName = (a, b) -> a.title.compareToIgnoreCase(b.title);
        java.util.Comparator<Item> order;
        switch (by) {
            case "name": order = byName; break;
            case "date": order = (a, b) -> Long.compare(modified(a), modified(b)); break;
            case "size": order = (a, b) -> Long.compare(size(a), size(b)); break;
            case "type": order = (a, b) -> typeOf(a).compareTo(typeOf(b)); break;
            default: order = null;   // as added
        }
        if (order != null) {
            java.util.Comparator<Item> o = order;
            Collections.sort(list, (a, b) -> {
                int c = o.compare(a, b);
                return c != 0 ? c : byName.compare(a, b);
            });
        }
        if (Config.getBool("favorites.desc")) Collections.reverse(list);
    }

    private static long modified(Item it) {
        return it.file() != null ? it.file().lastModified() : 0;
    }

    private static long size(Item it) {
        return it.file() != null && it.file().isFile() ? it.file().length() : 0;
    }

    private static String typeOf(Item it) {
        if (it.file() == null) return "~" + it.kind;   // after the files
        String n = it.file().getName();
        int dot = n.lastIndexOf('.');
        return it.file().isDirectory() ? "" : dot >= 0 ? n.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    // --- an item -------------------------------------------------------------------------

    private static int iconOf(Item it) {
        switch (it.kind) {
            case Item.FILE: return FilesTab.iconOf(it.file());
            case Item.URL: return R.drawable.ic_globe;
            default: return R.drawable.ic_launch;
        }
    }

    private String describe(Item it) {
        switch (it.kind) {
            case Item.FILE: return FilesTab.describe(act, it.file(), true);
            case Item.URL: {
                String host = Uri.parse(it.args[0]).getHost();
                return host != null ? host : it.args[0];
            }
            case Item.APP: return "App · " + appName(it.args[0]);
            default: return "Shortcut · " + appName(it.args[0]);
        }
    }

    private String appName(String pkg) {
        try {
            return String.valueOf(act.getPackageManager().getApplicationLabel(
                    act.getPackageManager().getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "not installed";
        }
    }

    private boolean missing(Item it) {
        switch (it.kind) {
            case Item.FILE: return !it.file().exists();
            case Item.URL: return false;
            default: return act.getPackageManager().getLaunchIntentForPackage(it.args[0]) == null;
        }
    }

    private void open(Item it) {
        try {
            switch (it.kind) {
                case Item.FILE: {
                    File f = it.file();
                    if (!f.exists()) return;
                    if (f.isDirectory()) host.openFolder(f);
                    else FilesTab.open(act, f);
                    break;
                }
                case Item.URL:
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.args[0])));
                    break;
                case Item.APP: {
                    Intent i = act.getPackageManager().getLaunchIntentForPackage(it.args[0]);
                    if (i != null) act.startActivity(i);
                    break;
                }
                default:
                    launcherApps.startShortcut(it.args[0], it.args[1], null, null, Process.myUserHandle());
            }
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException e) {
            Toast.makeText(act, "Could not open " + it.title, Toast.LENGTH_SHORT).show();
        }
    }

    private void itemMenu(View anchor, final Item it) {
        final int UP = 1, DOWN = 2, SHOW = 3, REMOVE = 4, OPEN_WITH = 5, DETAILS = 6, RENAME = 7, OPEN_AS = 8;
        final File f = it.file();
        // Moving is in the user's order: on the tiles, or in More sorted as added.
        boolean movable = header.query().isEmpty()
                && (onTiles() || (Config.get("favorites.sort").equals("added") && !Config.getBool("favorites.desc")));
        final int pos = shown.indexOf(it);
        PopMenu pm = new PopMenu(act, anchor);
        PopMenu m = pm.getMenu();
        if (f != null && f.isFile()) m.add(0, OPEN_WITH, -3, "Open with…");
        if (f != null && f.isFile()) m.add(0, OPEN_AS, -2, "Open as…");
        if (f != null && f.exists()) m.add(0, DETAILS, -1, "Details");
        if (f == null) m.add(0, RENAME, -1, "Rename");
        if (movable && pos > 0) m.add(0, UP, 0, "Move up");
        if (movable && pos >= 0 && pos < shown.size() - 1 && !isMore(pos + 1)) m.add(0, DOWN, 1, "Move down");
        if (f != null && f.getParentFile() != null && f.getParentFile().isDirectory()) m.add(0, SHOW, 2, "Show in folder");
        m.add(0, REMOVE, 3, "Remove from Briefcase");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case UP: swap(it, shown.get(pos - 1)); break;
                case DOWN: swap(it, shown.get(pos + 1)); break;
                case SHOW: host.openFolder(f.getParentFile()); break;
                case OPEN_WITH: FileDetails.openWith(act, f); break;
                case OPEN_AS: FileDetails.openAs(act, anchor, f); break;
                case DETAILS: FileDetails.show(act, f, root); break;
                case RENAME: rename(it); break;
                case REMOVE:
                    items.remove(it);
                    save();
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void swap(Item a, Item b) {
        Collections.swap(items, items.indexOf(a), items.indexOf(b));
        save();
    }

    private void save() {
        Favorites.saveItems(act, items);
        pinShortcuts();
        show();
    }

    private void rename(Item it) {
        new org.pda.launcher.Prompt(act, "Rename")
                .field("Name", it.title)
                .ok("Rename", v -> {
                    if (v[0].isEmpty() || !items.contains(it)) return;
                    items.set(items.indexOf(it), it.renamed(v[0]));
                    save();
                })
                .show(root);
    }

    // --- adding --------------------------------------------------------------------------

    private void addMenu(View anchor) {
        PopMenu pm = new PopMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Link…");
        pm.getMenu().add(0, 2, 1, "App or its shortcut…");
        pm.getMenu().add(0, 3, 2, "File or folder…");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: addLink(); break;
                case 2: addApp(); break;
                default:
                    Toast.makeText(act, "Long-press a file or a folder, then ⋮ › Add to Briefcase",
                            Toast.LENGTH_LONG).show();
                    host.openFolder(android.os.Environment.getExternalStorageDirectory());
            }
            return true;
        });
        pm.show();
    }

    private void addLink() {
        new org.pda.launcher.Prompt(act, "Add a link")
                .field("https://…", null, android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_URI)
                .field("Name (optional)", null)
                .ok("Add", v -> {
                    String u = v[0];
                    if (u.isEmpty()) return;
                    if (!u.contains("://")) u = "https://" + u;
                    String t = v[1];
                    if (t.isEmpty()) t = Uri.parse(u).getHost() != null ? Uri.parse(u).getHost() : u;
                    added(Favorites.addItem(act, Item.url(t, u)), t);
                })
                .show(root);
    }

    /** + › App or its shortcut: the app, then the app itself or one of its shortcuts. */
    private void addApp() {
        final List<android.content.pm.LauncherActivityInfo> apps = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.LauncherActivityInfo li : launcherApps.getActivityList(null, Process.myUserHandle())) {
            if (seen.add(li.getApplicationInfo().packageName)) apps.add(li);
        }
        Collections.sort(apps, (a, b) -> String.valueOf(a.getLabel()).compareToIgnoreCase(String.valueOf(b.getLabel())));
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = String.valueOf(apps.get(i).getLabel());
        new AlertDialog.Builder(act)
                .setTitle("Add an app")
                .setItems(names, (d, which) -> addFromApp(act, apps.get(which).getApplicationInfo().packageName,
                        names[which], this::onShow))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Offer the app itself and its shortcuts for the Briefcase (Applications
     * calls this too). {@code done} runs after one is added.
     */
    public static void addFromApp(Activity act, String pkg, String appName, Runnable done) {
        LauncherApps la = (LauncherApps) act.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        final List<ShortcutInfo> shortcuts = new ArrayList<>();
        try {
            if (la.hasShortcutHostPermission()) {
                List<ShortcutInfo> found = la.getShortcuts(new LauncherApps.ShortcutQuery().setPackage(pkg)
                        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                                | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                                | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle());
                if (found != null) for (ShortcutInfo si : found) if (si.isEnabled()) shortcuts.add(si);
            }
        } catch (RuntimeException ignore) {}
        String[] names = new String[shortcuts.size() + 1];
        names[0] = appName + " (the app)";
        for (int i = 0; i < shortcuts.size(); i++) {
            ShortcutInfo si = shortcuts.get(i);
            names[i + 1] = String.valueOf(si.getShortLabel() != null ? si.getShortLabel() : si.getLongLabel());
        }
        Runnable pick0 = () -> {
            boolean ok = Favorites.addItem(act, Item.app(appName, pkg));
            toastAdded(act, ok, appName);
            if (done != null) done.run();
        };
        if (shortcuts.isEmpty()) {
            pick0.run();
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("Add to Briefcase")
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        pick0.run();
                        return;
                    }
                    ShortcutInfo si = shortcuts.get(which - 1);
                    boolean ok = Favorites.addItem(act, Item.shortcut(names[which], pkg, si.getId()));
                    pinShortcuts(act);
                    toastAdded(act, ok, names[which]);
                    if (done != null) done.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void added(boolean ok, String what) {
        toastAdded(act, ok, what);
        onShow();
    }

    private static void toastAdded(Context c, boolean ok, String what) {
        Toast.makeText(c, ok ? "Added to Briefcase: " + what : what + " is in the Briefcase already",
                Toast.LENGTH_SHORT).show();
    }

    private void pinShortcuts() {
        pinShortcuts(act);
    }

    /**
     * Pin the Briefcase's shortcuts with the system, so an app cannot drop them
     * (only the default home may; otherwise they still open while the app keeps
     * them). Pinning sets a package's whole list, so one gone is unpinned too.
     */
    static void pinShortcuts(Context c) {
        LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        try {
            if (!la.hasShortcutHostPermission()) return;
            java.util.Map<String, List<String>> byPkg = new java.util.HashMap<>();
            for (Item it : Favorites.items(c)) {
                if (Item.SHORTCUT.equals(it.kind)) {
                    byPkg.computeIfAbsent(it.args[0], k -> new ArrayList<>()).add(it.args[1]);
                }
            }
            // Packages pinned before but with none now: unpin them too.
            List<ShortcutInfo> pinned = la.getShortcuts(new LauncherApps.ShortcutQuery()
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle());
            if (pinned != null) for (ShortcutInfo si : pinned) byPkg.putIfAbsent(si.getPackage(), new ArrayList<>());
            for (java.util.Map.Entry<String, List<String>> e : byPkg.entrySet()) {
                try {
                    la.pinShortcuts(e.getKey(), e.getValue(), Process.myUserHandle());
                } catch (RuntimeException ignore) {}
            }
        } catch (RuntimeException ignore) {}
    }

    private void showMenu(View anchor) {
        PopMenu pm = new PopMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Remove missing items");
        pm.setOnMenuItemClickListener(item -> {
            items.removeIf(this::missing);
            save();
            return true;
        });
        pm.show();
    }
}
