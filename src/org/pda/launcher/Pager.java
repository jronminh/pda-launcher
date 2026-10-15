package org.pda.launcher;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The page bar shared by the tabs: "‹ 1 / 3 ›". Lists turn pages instead of
 * scrolling. With one page the bar keeps its height but shows nothing, so the
 * rows above it do not move.
 */
public final class Pager extends LinearLayout {

    public interface Listener {
        void onPage(int page);
    }

    private final TextView text;
    private final ImageView prev, next;
    private Listener listener;
    private int page, pages = 1;

    public Pager(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        prev = Ui.action(c, R.drawable.ic_back, "Previous page", v -> turn(-1));
        next = Ui.action(c, R.drawable.ic_forward, "Next page", v -> turn(1));
        text = new TextView(c);
        text.setTextColor(Ui.DIM);
        text.setTextSize(14);
        text.setGravity(Gravity.CENTER);
        text.setMinWidth(Ui.dp(c, 72));
        addView(prev);
        addView(text);
        addView(next);
        render();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public int page() {
        return page;
    }

    /** How many pages there are; the current page is kept if it still exists. */
    public void setPages(int n) {
        pages = Math.max(1, n);
        page = Math.min(page, pages - 1);
        render();
    }

    public void setPage(int p) {
        page = Math.max(0, Math.min(pages - 1, p));
        render();
    }

    /** Move by {@code by} pages; tells the listener if the page changed. */
    public boolean turn(int by) {
        int to = Math.max(0, Math.min(pages - 1, page + by));
        if (to == page) return false;
        page = to;
        render();
        if (listener != null) listener.onPage(page);
        return true;
    }

    private void render() {
        int show = pages > 1 ? View.VISIBLE : View.INVISIBLE;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setVisibility(show);
        text.setText((page + 1) + " / " + pages);
        prev.setAlpha(page > 0 ? 1f : 0.3f);
        next.setAlpha(page < pages - 1 ? 1f : 0.3f);
    }
}
