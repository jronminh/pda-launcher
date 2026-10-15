package org.pda.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The app's own pop-up menu, in place of the platform's PopupMenu (which takes
 * the system's look: grey, rounded, a shadow). Black with a thin outline, white
 * text, no animation, no shadow; a checked item has a check mark, a disabled
 * one is dim. It opens where the finger was: at the point of a long press on
 * a row or a cell, under a header button for a tap on one; below that point
 * when there is room, else above, and always inside the window's visible
 * frame (clear of the status and navigation bars) with a small margin. The calls follow PopupMenu's
 * ({@code getMenu().add(...)}, {@code setOnMenuItemClickListener}, {@code show}),
 * so a call site only changes its type.
 */
public final class PopMenu {

    /** One item: an id, a title, whether it can be picked, whether it is checked. */
    public static final class Item {
        final int id, order;
        final String title;
        boolean enabled = true, checked;

        Item(int id, int order, String title) {
            this.id = id;
            this.order = order;
            this.title = title;
        }

        public int getItemId() {
            return id;
        }

        public Item setEnabled(boolean on) {
            enabled = on;
            return this;
        }

        public Item setCheckable(boolean on) {
            return this;   // any item can show a check
        }

        public Item setChecked(boolean on) {
            checked = on;
            return this;
        }
    }

    /** What a pick does; the result is ignored (PopupMenu's has one). */
    public interface OnPick {
        boolean onMenuItemClick(Item item);
    }

    // Where the last touch went down, on screen (the launcher's activity reports it).
    private static float touchX = -1, touchY = -1;
    private static long touchAt;

    /** Called for every touch that goes down in the launcher's window. */
    public static void touchedAt(float rawX, float rawY) {
        touchX = rawX;
        touchY = rawY;
        touchAt = android.os.SystemClock.uptimeMillis();
    }

    private final Context c;
    private final View anchor;
    private final List<Item> items = new ArrayList<>();
    private OnPick onPick;

    public PopMenu(Context c, View anchor) {
        this.c = c;
        this.anchor = anchor;
    }

    /** The menu itself, as PopupMenu's getMenu() gives one. */
    public PopMenu getMenu() {
        return this;
    }

    public Item add(int group, int id, int order, String title) {
        Item it = new Item(id, order, title);
        int at = items.size();
        while (at > 0 && items.get(at - 1).order > order) at--;   // kept in order
        items.add(at, it);
        return it;
    }

    public Item add(String title) {
        return add(0, items.size(), items.size(), title);
    }

    public void setGroupCheckable(int group, boolean checkable, boolean exclusive) {
        // nothing to do: checks are drawn as set
    }

    public void setOnMenuItemClickListener(OnPick l) {
        onPick = l;
    }

    public void show() {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.outline(c, Ui.LINE));
        int pad = Ui.dp(c, 4);
        box.setPadding(0, pad, 0, pad);
        box.setMinimumWidth(Ui.dp(c, 200));

        final PopupWindow w = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        for (Item it : items) box.addView(row(it, w));

        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // a tap outside closes it
        w.setOutsideTouchable(true);
        w.setAnimationStyle(0);
        w.setElevation(0);
        w.setClippingEnabled(false);   // the position below is already inside the frame

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int mw = box.getMeasuredWidth(), mh = box.getMeasuredHeight();
        android.graphics.Rect frame = new android.graphics.Rect();
        anchor.getRootView().getWindowVisibleDisplayFrame(frame);
        int margin = Ui.dp(c, 8);

        int[] a = new int[2];
        anchor.getLocationOnScreen(a);
        int ax = a[0], ay = a[1], aw = anchor.getWidth(), ah = anchor.getHeight();
        boolean recent = android.os.SystemClock.uptimeMillis() - touchAt < 3000;
        boolean inside = recent && touchX >= ax && touchX <= ax + aw && touchY >= ay && touchY <= ay + ah;
        boolean button = aw <= Ui.dp(c, 64) && ah <= Ui.dp(c, 64);

        int x, y, flipTo, fromX, fromY;   // fromX/Y: where the menu grows from, on screen
        if (inside && !button) {
            // A long press on a row or a cell: open at the finger.
            x = Math.round(touchX);
            y = Math.round(touchY);
            if (x + mw > frame.right - margin) x -= mw;   // the right half opens to the left
            flipTo = y - mh;
            fromX = Math.round(touchX);
            fromY = Math.round(touchY);
        } else {
            // A header button: under it, its edge on the side of the screen it is on.
            boolean right = ax + aw / 2 > (frame.left + frame.right) / 2;
            x = right ? ax + aw - mw : ax;
            y = ay + ah;
            flipTo = ay - mh;
            fromX = ax + aw / 2;
            fromY = ay + ah / 2;
        }
        if (y + mh > frame.bottom - margin) y = flipTo;   // no room below: above
        x = Math.max(frame.left + margin, Math.min(x, frame.right - margin - mw));
        y = Math.max(frame.top + margin, Math.min(y, frame.bottom - margin - mh));
        w.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
        Anim.popIn(box, Math.max(0, Math.min(mw, fromX - x)), Math.max(0, Math.min(mh, fromY - y)));
    }

    private View row(final Item it, final PopupWindow w) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(c, 48));
        r.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 12), 0);

        TextView t = new TextView(c);
        t.setText(it.title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(16);
        t.setSingleLine(true);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView check = new ImageView(c);
        check.setImageResource(R.drawable.ic_check);
        check.setVisibility(it.checked ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20));
        cp.leftMargin = Ui.dp(c, 16);
        r.addView(check, cp);

        if (it.enabled) {
            r.setOnClickListener(v -> {
                w.dismiss();
                if (onPick != null) onPick.onMenuItemClick(it);
            });
        } else {
            r.setAlpha(0.35f);
        }
        return r;
    }
}
