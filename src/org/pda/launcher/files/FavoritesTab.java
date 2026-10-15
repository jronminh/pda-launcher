package org.pda.launcher.files;

import android.app.Activity;
import android.view.Gravity;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import org.pda.launcher.Pager;
import org.pda.launcher.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Favorites tab: the files and folders kept in {@link Favorites}, in the
 * order they were added, one page at a time. Tap a folder to show it in the
 * Files tab, a file to open it; long-press for Move up, Move down, Show in
 * folder, Open shell here and Remove. Items are added from the Files tab
 * (select, then ⋮ › Add to favorites), or by editing {@code etc/favorites}.
 */
public final class FavoritesTab {

    /** What the tab asks of the launcher. */
    public interface Host {
        void openFolder(File dir);

        void openShellAt(File dir);
    }

    private final Activity act;
    private final Host host;
    private final LinearLayout root;
    private final LinearLayout list;
    private final Pager pager;
    private List<File> favs = new ArrayList<>();
    private int rowsPerPage = 8;

    public FavoritesTab(Activity act, Host host) {
        this.act = act;
        this.host = host;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.addView(Ui.header(act, Ui.title(act, "Favorites"), Ui.textAction(act, "⋮", v -> showMenu(v))));
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

    public View view() {
        return root;
    }

    /** On screen: read the list again (the Files tab or the shell may have changed it). */
    public void onShow() {
        favs = Favorites.load(act);
        render();
    }

    /** Back goes to the first page first. */
    public boolean onBack() {
        if (pager.page() == 0) return false;
        pager.setPage(0);
        render();
        return true;
    }

    private void render() {
        pager.setPages((favs.size() + rowsPerPage - 1) / rowsPerPage);
        list.removeAllViews();
        if (favs.isEmpty()) {
            TextView t = new TextView(act);
            t.setText("No favorites yet.\n\nIn Files, long-press a file or a folder,\nthen ⋮ › Add to favorites.");
            t.setTextColor(Ui.DIM);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(act, 48), 0, 0);
            list.addView(t);
            return;
        }
        int from = pager.page() * rowsPerPage;
        for (int i = from; i < Math.min(favs.size(), from + rowsPerPage); i++) {
            final File f = favs.get(i);
            View r = Ui.row(act, FilesTab.iconOf(f),
                    f.getName().isEmpty() ? f.getPath() : f.getName(),
                    FilesTab.describe(act, f, true), null, false);
            if (!f.exists()) r.setAlpha(0.4f);
            r.setOnClickListener(v -> tap(f));
            r.setOnLongClickListener(v -> { itemMenu(v, f); return true; });
            list.addView(r);
        }
    }

    private void tap(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) host.openFolder(f);
        else FilesTab.open(act, f);
    }

    private void itemMenu(View anchor, final File f) {
        final int UP = 1, DOWN = 2, SHOW = 3, SHELL = 4, REMOVE = 5;
        final int at = favs.indexOf(f);
        PopupMenu pm = new PopupMenu(act, anchor);
        Menu m = pm.getMenu();
        if (at > 0) m.add(0, UP, 0, "Move up");
        if (at < favs.size() - 1) m.add(0, DOWN, 1, "Move down");
        if (f.getParentFile() != null && f.getParentFile().isDirectory()) m.add(0, SHOW, 2, "Show in folder");
        if (f.isDirectory()) m.add(0, SHELL, 3, "Open shell here");
        m.add(0, REMOVE, 4, "Remove from favorites");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case UP: move(at, at - 1); break;
                case DOWN: move(at, at + 1); break;
                case SHOW: host.openFolder(f.getParentFile()); break;
                case SHELL: host.openShellAt(f); break;
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
        // Follow the item if it crossed a page.
        pager.setPages((favs.size() + rowsPerPage - 1) / rowsPerPage);
        pager.setPage(to / rowsPerPage);
        render();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
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
