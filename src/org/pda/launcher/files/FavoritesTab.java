package org.pda.launcher.files;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.pda.launcher.Config;
import org.pda.launcher.Header;
import org.pda.launcher.PagedView;
import org.pda.launcher.Pager;
import org.pda.launcher.PopMenu;
import org.pda.launcher.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The Briefcase tab: the files and folders kept in {@link Favorites}, one page
 * at a time, as details, a list or a grid, searched by name and sorted (the
 * order they were added, or name, date, size, type). Tap a folder to show it in
 * the Files tab, a file to open it; long-press for Move up and Move down (in
 * the order added), Show in folder and Remove. Items are added from the Files
 * tab (select, then ⋮ › Add to Briefcase).
 */
public final class FavoritesTab {

    /** What the tab asks of the launcher: show a folder in the Files tab. */
    public interface Host {
        void openFolder(File dir);
    }

    private final Activity act;
    private final Host host;
    private final LinearLayout root;
    private final Header header;
    private final ImageView viewBtn;
    private final Pager pager;
    private final PagedView list;
    private final TextView empty;
    private List<File> favs = new ArrayList<>();    // in the order added
    private final List<File> shown = new ArrayList<>();

    private final Config.Listener onConfig = keys -> { if (Config.touches(keys, "favorites.")) onConfig(); };

    public FavoritesTab(Activity act, Host host) {
        this.act = act;
        this.host = host;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        pager = new Pager(act);
        viewBtn = Ui.action(act, PagedView.iconOf(Config.get("favorites.view")), "View",
                v -> PagedView.chooseMode(act, v, Config.get("favorites.view"),
                        m -> Config.set(act, "favorites.view", m)));
        header = new Header(act, "")
                .search(q -> {
                    pager.setPage(0);
                    show();
                })
                .action(Header.sort(act, "favorites.sort", "favorites.desc",
                        "Order added", "Name", "Date modified", "Size", "Type"))
                .action(viewBtn)
                .action(Ui.action(act, org.pda.launcher.R.drawable.ic_more, "More", v -> showMenu(v)));
        root.addView(header.view());
        root.addView(Ui.divider(act));

        list = new PagedView(act, pager);
        list.setBinder(new PagedView.Binder() {
            @Override public int count() { return shown.size(); }
            @Override public Drawable icon(int i) { return act.getDrawable(FilesTab.iconOf(shown.get(i))); }
            @Override public String title(int i) {
                File f = shown.get(i);
                return f.getName().isEmpty() ? f.getPath() : f.getName();
            }
            @Override public String summary(int i) { return FilesTab.describe(act, shown.get(i), true); }
            @Override public String mark(int i) { return null; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return !shown.get(i).exists(); }
            @Override public void thumb(int i, ImageView icon) {
                if (shown.get(i).exists()) Thumbs.into(act, shown.get(i), icon);
            }
            @Override public void click(int i, View v) { tap(shown.get(i)); }
            @Override public boolean longClick(int i, View v) { itemMenu(v, shown.get(i)); return true; }
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

    /** On screen: read the list again (the Files tab may have changed it). */
    public void onShow() {
        favs = Favorites.load(act);
        show();
    }

    /** Back: close the search, then go to the first page. */
    public boolean onBack() {
        if (header.searching()) { header.closeSearch(); return true; }
        if (pager.page() == 0) return false;
        pager.setPage(0);
        list.refresh();
        return true;
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

    /** The favourites that match the search, in the chosen order. */
    private void show() {
        shown.clear();
        String q = header.query().toLowerCase(Locale.ROOT);
        for (File f : favs) if (q.isEmpty() || f.getName().toLowerCase(Locale.ROOT).contains(q)) shown.add(f);
        String by = Config.get("favorites.sort");
        boolean desc = Config.getBool("favorites.desc");
        if (by.equals("added")) {
            if (desc) Collections.reverse(shown);
        } else {
            FilesTab.sort(shown, by, desc);
        }
        empty.setText(favs.isEmpty()
                ? "The Briefcase is empty.\n\nIn Files, long-press a file or a folder,\nthen ⋮ › Add to Briefcase."
                : "Nothing matches.");
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        list.refresh();
    }

    private void tap(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) host.openFolder(f);
        else FilesTab.open(act, f);
    }

    private void itemMenu(View anchor, final File f) {
        final int UP = 1, DOWN = 2, SHOW = 3, REMOVE = 4;
        final int at = favs.indexOf(f);
        // Moving means something only in the order the items were added.
        boolean movable = Config.get("favorites.sort").equals("added") && header.query().isEmpty();
        boolean desc = Config.getBool("favorites.desc");
        PopMenu pm = new PopMenu(act, anchor);
        PopMenu m = pm.getMenu();
        if (movable && at > 0) m.add(0, desc ? DOWN : UP, 0, desc ? "Move down" : "Move up");
        if (movable && at < favs.size() - 1) m.add(0, desc ? UP : DOWN, 1, desc ? "Move up" : "Move down");
        if (f.getParentFile() != null && f.getParentFile().isDirectory()) m.add(0, SHOW, 2, "Show in folder");
        m.add(0, REMOVE, 3, "Remove from Briefcase");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case UP: move(at, desc ? at + 1 : at - 1); break;
                case DOWN: move(at, desc ? at - 1 : at + 1); break;
                case SHOW: host.openFolder(f.getParentFile()); break;
                case REMOVE:
                    Favorites.remove(act, Collections.singletonList(f));
                    onShow();
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void move(int from, int to) {
        Collections.swap(favs, from, to);
        Favorites.save(act, favs);
        show();
    }

    private void showMenu(View anchor) {
        PopMenu pm = new PopMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Remove missing items");
        pm.setOnMenuItemClickListener(item -> {
            List<File> gone = new ArrayList<>();
            for (File f : favs) if (!f.exists()) gone.add(f);
            Favorites.remove(act, gone);
            onShow();
            return true;
        });
        pm.show();
    }
}
