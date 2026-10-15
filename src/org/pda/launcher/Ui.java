package org.pda.launcher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look shared by the tabs: pure black for an OLED screen, flat, no animation.
 * Every tab starts with the same header: a title on the left, actions on the right.
 */
public final class Ui {

    public static final int BG = 0xFF000000;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int DIM = 0xFF8A8A8A;
    public static final int LINE = 0xFF262626;   // dividers and outlines
    public static final int ACCENT = 0xFFFFB300; // the system shell, warnings

    private Ui() {}

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** A header row: {@code title} takes the free width, then the actions. */
    public static LinearLayout header(Context c, View title, View... actions) {
        LinearLayout h = new LinearLayout(c);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(dp(c, 52));
        h.setPadding(dp(c, 16), 0, dp(c, 4), 0);
        h.setBackgroundColor(BG);
        h.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (View a : actions) h.addView(a);
        return h;
    }

    public static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(20);
        t.setSingleLine(true);
        return t;
    }

    /** A header action: a platform icon, tinted white. */
    public static ImageView action(Context c, int drawable, String description, View.OnClickListener click) {
        ImageView i = new ImageView(c);
        i.setImageResource(drawable);
        i.setColorFilter(TEXT);
        i.setContentDescription(description);
        int p = dp(c, 12);
        i.setPadding(p, p, p, p);
        i.setOnClickListener(click);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return i;
    }

    /** A text action, for what has no platform icon (the ⋮ of the shell, page arrows). */
    public static TextView textAction(Context c, String text, View.OnClickListener click) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(c, 48));
        t.setMinHeight(dp(c, 48));
        t.setOnClickListener(click);
        return t;
    }

    /** A one-pixel line. */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** A black button with an outline: no fill to light up the panel. */
    public static GradientDrawable outline(Context c, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG);
        d.setStroke(1, stroke);
        d.setCornerRadius(dp(c, 6));
        return d;
    }
}
