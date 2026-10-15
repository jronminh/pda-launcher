package org.pda.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A card over the screen, in the app's look (black, a thin outline, no shadow):
 * a title and rows of a dim label over a white value, which can be selected and
 * copied. A row's value may come later ({@link #set}), as a folder's size does.
 * A tap outside or Back closes it.
 */
public final class Card {

    private final Context c;
    private final LinearLayout box;
    private final List<TextView> values = new ArrayList<>();
    private PopupWindow window;

    public Card(Context c, String title) {
        this.c = c;
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.outline(c, Ui.LINE));
        int p = Ui.dp(c, 20);
        box.setPadding(p, p, p, Ui.dp(c, 12));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(18);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        t.setPadding(0, 0, 0, Ui.dp(c, 8));
        box.addView(t);
    }

    /** A row; returns its index for {@link #set}. */
    public int row(String label, String value) {
        TextView l = new TextView(c);
        l.setText(label);
        l.setTextColor(Ui.DIM);
        l.setTextSize(12);
        l.setPadding(0, Ui.dp(c, 8), 0, 0);
        box.addView(l);
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextColor(Ui.TEXT);
        v.setTextSize(15);
        v.setTextIsSelectable(true);
        box.addView(v);
        values.add(v);
        return values.size() - 1;
    }

    /** A row's value, now known (from any thread). */
    public void set(int row, String value) {
        TextView v = values.get(row);
        v.post(() -> v.setText(value));
    }

    public void show(View over) {
        int width = Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 32), Ui.dp(c, 420));
        window = new PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // a tap outside closes it
        window.setOutsideTouchable(true);
        window.setAnimationStyle(0);
        window.setElevation(0);
        window.showAtLocation(over, Gravity.CENTER, 0, 0);
        Anim.popIn(box, width / 2f, 0);
    }
}
