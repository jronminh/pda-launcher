package org.pda.launcher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A list that turns pages instead of scrolling, shown one of three ways:
 * {@link #DETAILS} (icon, title, a dim summary), {@link #LIST} (a small icon and
 * the title) or {@link #GRID} (a big icon over the title). It fills its height
 * with whole rows; how many items make a page follows from the mode and the
 * height, and the {@link Pager} is told. A horizontal fling anywhere in it, on an
 * item or on the empty space under the last one, turns the page.
 * The items come from a {@link Binder}; the view is rebuilt on {@link #refresh}.
 */
public final class PagedView extends LinearLayout {

    public static final String DETAILS = "details", LIST = "list", GRID = "grid";

    /** The items: what each one shows and does. */
    public interface Binder {
        int count();

        Drawable icon(int i);

        String title(int i);

        /** The dim line in DETAILS; null for none. */
        String summary(int i);

        /** A mark such as "✓" or "★"; null for none. */
        String mark(int i);

        /** Drawn with an outline (selected). */
        boolean highlight(int i);

        /** Drawn dim (missing). */
        boolean dim(int i);

        void click(int i, View v);

        /** @return true if the long press was used. */
        boolean longClick(int i, View v);

        /** A chance to put a thumbnail in the icon's place later (default: none). */
        default void thumb(int i, ImageView icon) {}
    }

    private static final int LIST_DP = 48;
    private static final int GRID_CELL_DP = 104;   // a grid cell's height when the rows are not fixed

    private final Pager pager;
    private Binder binder;
    private String mode = DETAILS;
    private int columns = 4;
    private int gridRows;         // 0: as many rows of GRID_CELL_DP as fit
    private int perPage = 1;
    private int height;
    private final GestureDetector flip;

    public PagedView(Context c, Pager pager) {
        super(c);
        this.pager = pager;
        setOrientation(VERTICAL);
        pager.setListener(p -> rebuild());
        flip = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vx) < Math.abs(vy) * 1.5f) return false;
                return PagedView.this.pager.turn(vx < 0 ? 1 : -1);
            }
        });
    }

    public void setBinder(Binder b) {
        binder = b;
    }

    /** DETAILS, LIST or GRID. */
    public void setMode(String m) {
        mode = GRID.equals(m) || LIST.equals(m) ? m : DETAILS;
    }

    /** The grid's columns, and its rows (0: as many as fit). */
    public void setGrid(int columns, int rows) {
        this.columns = Math.max(1, columns);
        this.gridRows = Math.max(0, rows);
    }

    /** The items or the mode changed: count the pages again and draw. */
    public void refresh() {
        measurePage();
        rebuild();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        height = h;
        post(this::refresh);
    }

    /**
     * Every touch is seen by the fling detector first. The gesture is always
     * taken (true), even where no item is under the finger: else Android sends
     * the rest of a gesture that starts on empty space elsewhere, and a swipe
     * there would do nothing. A fling cancels what the items saw of it.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (flip.onTouchEvent(e)) {
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            return true;
        }
        super.dispatchTouchEvent(e);
        return true;
    }

    // --- layout --------------------------------------------------------------------------

    private int rowHeight() {
        if (GRID.equals(mode)) {
            return gridRows > 0 && height > 0 ? height / gridRows : Ui.dp(getContext(), GRID_CELL_DP);
        }
        return Ui.dp(getContext(), LIST.equals(mode) ? LIST_DP : Ui.ROW_DP);
    }

    private int rows() {
        if (GRID.equals(mode) && gridRows > 0) return gridRows;
        return height > 0 ? Math.max(1, height / rowHeight()) : 1;
    }

    private void measurePage() {
        int n = binder == null ? 0 : binder.count();
        perPage = Math.max(1, rows() * (GRID.equals(mode) ? columns : 1));
        pager.setPages((n + perPage - 1) / perPage);
    }

    private void rebuild() {
        removeAllViews();
        if (binder == null || height == 0) return;
        int n = binder.count();
        int from = pager.page() * perPage;
        int to = Math.min(n, from + perPage);
        if (GRID.equals(mode)) {
            int h = rowHeight();
            for (int r = from; r < to; r += columns) {
                LinearLayout row = new LinearLayout(getContext());
                row.setOrientation(HORIZONTAL);
                for (int c = 0; c < columns; c++) {
                    View cell = r + c < to ? cell(r + c) : new View(getContext());
                    row.addView(cell, new LayoutParams(0, h, 1f));
                }
                addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, h));
            }
        } else {
            for (int i = from; i < to; i++) addView(row(i), new LayoutParams(LayoutParams.MATCH_PARENT, rowHeight()));
        }
    }

    /** DETAILS and LIST: an icon, the title, the summary (DETAILS), the mark. */
    private View row(int i) {
        Context c = getContext();
        boolean details = DETAILS.equals(mode);
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);

        Drawable d = binder.icon(i);
        if (d != null) {   // a row without an icon (a setting) starts with its title
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(d);
            binder.thumb(i, icon);
            int s = Ui.dp(c, details ? 36 : 24);
            LayoutParams ip = new LayoutParams(s, s);
            ip.rightMargin = Ui.dp(c, 16);
            r.addView(icon, ip);
        }

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        texts.addView(text(binder.title(i), Ui.TEXT, details ? 16 : 15, TextUtils.TruncateAt.MIDDLE));
        String sum = details ? binder.summary(i) : null;
        if (sum != null && !sum.isEmpty()) texts.addView(text(sum, Ui.DIM, 12, TextUtils.TruncateAt.END));
        r.addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String mark = binder.mark(i);
        if (mark != null) {
            TextView m = text(mark, Ui.TEXT, 15, null);
            m.setPadding(Ui.dp(c, 12), 0, 0, 0);
            r.addView(m);
        }
        return decorate(r, i);
    }

    /** GRID: a big icon over a title of up to two lines; the mark before the title. */
    private View cell(int i) {
        Context c = getContext();
        LinearLayout cell = new LinearLayout(c);
        cell.setOrientation(VERTICAL);
        cell.setGravity(Gravity.CENTER);
        int p = Ui.dp(c, 4);
        cell.setPadding(p, p, p, p);

        ImageView icon = new ImageView(c);
        icon.setImageDrawable(binder.icon(i));
        binder.thumb(i, icon);
        int s = Ui.dp(c, 48);
        cell.addView(icon, new LayoutParams(s, s));

        String mark = binder.mark(i);
        TextView label = text((mark != null ? mark + " " : "") + binder.title(i), Ui.TEXT, 12, TextUtils.TruncateAt.END);
        label.setSingleLine(false);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 4);
        cell.addView(label, lp);
        return decorate(cell, i);
    }

    private View decorate(View v, int i) {
        if (binder.highlight(i)) v.setBackground(Ui.outline(getContext(), Ui.TEXT));
        if (binder.dim(i)) v.setAlpha(0.4f);
        v.setOnClickListener(x -> binder.click(i, x));
        v.setOnLongClickListener(x -> binder.longClick(i, x));
        return v;
    }

    private TextView text(String s, int color, int sp, TextUtils.TruncateAt cut) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setSingleLine(true);
        if (cut != null) t.setEllipsize(cut);
        return t;
    }

    // --- the mode button -----------------------------------------------------------------

    /** The icon for a mode, for the header's view button. */
    public static int iconOf(String mode) {
        if (GRID.equals(mode)) return R.drawable.ic_view_grid;
        if (LIST.equals(mode)) return R.drawable.ic_view_list;
        return R.drawable.ic_view_details;
    }

    /** A menu to choose the mode; {@code onPick} gets details, list or grid. */
    public static void chooseMode(Context c, View anchor, String current, java.util.function.Consumer<String> onPick) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(c, anchor);
        String[] modes = { DETAILS, LIST, GRID };
        String[] names = { "Details", "List", "Grid" };
        for (int i = 0; i < modes.length; i++) {
            pm.getMenu().add(1, i, i, names[i]).setCheckable(true).setChecked(modes[i].equals(current));
        }
        pm.getMenu().setGroupCheckable(1, true, true);
        pm.setOnMenuItemClickListener(item -> {
            onPick.accept(modes[item.getItemId()]);
            return true;
        });
        pm.show();
    }
}
