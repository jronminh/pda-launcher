package org.pda.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A tab's own tabs, across the top under the header (Today's parts, the
 * Briefcase's kinds), after Palm OS's: words only, the open one bold in the
 * text colour over a bar, the others dim. They take the width they need;
 * when they no longer fit on one line they go on to more lines, each line
 * spread over the whole width, so none is ever cut or scrolled out of sight.
 * A thin line closes the strip.
 */
public final class TabStrip extends ViewGroup {

    /** A tab picked: its index. */
    public interface OnPick {
        void pick(int i);
    }

    private final List<TextView> tabs = new ArrayList<>();
    private final List<int[]> rows = new ArrayList<>();   // each line: first tab, tab after the last
    private final Paint bar = new Paint();
    private final Paint line = new Paint();
    private final int rowH, padX, barH, hair;
    private int selected = -1;
    private OnPick onPick;

    public TabStrip(Context c) {
        super(c);
        rowH = Ui.dp(c, 48);
        padX = Ui.dp(c, 14);
        barH = Ui.EINK ? Ui.dp(c, 4) : Ui.dp(c, 3);
        hair = Ui.hair(c);
        bar.setColor(Ui.TEXT);
        line.setColor(Ui.LINE);
        setWillNotDraw(false);
    }

    public TabStrip onPick(OnPick p) {
        onPick = p;
        return this;
    }

    /** The tabs' names, in order; the open one is kept when it is still there. */
    public void setTabs(String... names) {
        boolean same = names.length == tabs.size();
        for (int i = 0; same && i < names.length; i++) same = tabs.get(i).getText().toString().equals(names[i]);
        if (same) return;
        removeAllViews();
        tabs.clear();
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            TextView t = new TextView(getContext());
            t.setText(names[i]);
            t.setTextSize(15);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER);
            t.setPadding(padX, 0, padX, 0);
            t.setOnClickListener(v -> {
                if (onPick != null) onPick.pick(which);
            });
            tabs.add(t);
            addView(t);
        }
        if (selected >= tabs.size()) selected = -1;
        paint();
    }

    /** Rename one tab (a count changed) without building the strip again. */
    public void setName(int i, String name) {
        if (i < 0 || i >= tabs.size() || tabs.get(i).getText().toString().equals(name)) return;
        tabs.get(i).setText(name);
        requestLayout();
    }

    public void setSelected(int i) {
        selected = i;
        paint();
    }

    public int selected() {
        return selected;
    }

    private void paint() {
        for (int i = 0; i < tabs.size(); i++) {
            TextView t = tabs.get(i);
            boolean on = i == selected;
            t.setTextColor(on ? Ui.TEXT : Ui.DIM);
            t.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            t.setContentDescription(t.getText() + (on ? ", open" : ""));
            t.setSelected(on);
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        int exact = MeasureSpec.makeMeasureSpec(rowH, MeasureSpec.EXACTLY);
        // Lines, greedily: as many tabs as fit at their own width.
        rows.clear();
        int start = 0, used = 0;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).measure(unbounded, exact);
            int w = Math.min(width, tabs.get(i).getMeasuredWidth());
            if (i > start && used + w > width) {
                rows.add(new int[] { start, i });
                start = i;
                used = 0;
            }
            used += w;
        }
        if (start < tabs.size()) rows.add(new int[] { start, tabs.size() });
        // Each line spread over the width: the room left shared out evenly.
        for (int[] r : rows) {
            int sum = 0;
            for (int i = r[0]; i < r[1]; i++) sum += Math.min(width, tabs.get(i).getMeasuredWidth());
            int extra = Math.max(0, width - sum), n = r[1] - r[0];
            for (int i = r[0]; i < r[1]; i++) {
                int w = Math.min(width, tabs.get(i).getMeasuredWidth()) + extra / n + (i - r[0] < extra % n ? 1 : 0);
                tabs.get(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), exact);
            }
        }
        setMeasuredDimension(width, rows.size() * rowH + hair);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int width = r - l;
        for (int row = 0; row < rows.size(); row++) {
            int x = 0, y = row * rowH;
            for (int i = rows.get(row)[0]; i < rows.get(row)[1]; i++) {
                View v = tabs.get(i);
                int w = v.getMeasuredWidth();
                int left = rtl ? width - x - w : x;
                v.layout(left, y, left + w, y + rowH);
                x += w;
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (selected >= 0 && selected < tabs.size()) {
            View v = tabs.get(selected);
            int inset = Ui.dp(getContext(), 10);
            c.drawRect(v.getLeft() + inset, v.getBottom() - barH, v.getRight() - inset, v.getBottom(), bar);
        }
        c.drawRect(0, getHeight() - hair, getWidth(), getHeight(), line);
    }
}
