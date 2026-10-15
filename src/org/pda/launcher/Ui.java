package org.pda.launcher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look shared by the tabs: pure black for an OLED screen, flat, no animation.
 * Every tab starts with the same {@link Header}.
 */
public final class Ui {

    public static final int BG = 0xFF000000;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int DIM = 0xFF8A8A8A;
    public static final int LINE = 0xFF262626;   // dividers and outlines

    private Ui() {}

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(20);
        t.setSingleLine(true);
        return t;
    }

    /** A header action: one of the app's line icons (tools/icons.py), white. */
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

    /** The height of a list row; lists turn pages of whole rows. */
    public static final int ROW_DP = 64;

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
