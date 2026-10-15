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
 * The look shared by the tabs, flat, in one of four themes (launcher.theme):
 * OLED (pure black, the default), Dark (charcoal, for an LCD), Light, and
 * E-ink (pure white and pure black, no grey to dither, no animation, thicker
 * lines). Every tab starts with the same {@link Header}. The colours are read
 * once per activity ({@link #applyTheme}); a change of theme recreates it.
 */
public final class Ui {

    public static int BG = 0xFF000000;
    public static int TEXT = 0xFFFFFFFF;
    public static int DIM = 0xFF8A8A8A;
    public static int LINE = 0xFF262626;      // dividers and outlines
    public static int PATTERN = 0xFF1C1C1C;   // the background's pattern (launcher.pattern)
    public static boolean LIGHT, EINK;

    private Ui() {}

    /** The theme's colours, and the activity's theme for Android's own dialogs: before super.onCreate. */
    public static void applyTheme(android.app.Activity a) {
        applyColors(a);
        a.setTheme(LIGHT ? R.style.HomeLight : R.style.Home);
    }

    /** The theme's colours only (Capture keeps its own small window). */
    public static void applyColors(Context a) {
        Config.prepare(a);
        String t = Config.get("launcher.theme");
        LIGHT = t.equals("light") || t.equals("eink");
        EINK = t.equals("eink");
        switch (t) {
            case "dark":  BG = 0xFF121212; TEXT = 0xFFEDEDED; DIM = 0xFF9A9A9A; LINE = 0xFF2E2E2E; PATTERN = 0xFF242424; break;
            case "light": BG = 0xFFFFFFFF; TEXT = 0xFF111111; DIM = 0xFF6B6B6B; LINE = 0xFFDDDDDD; PATTERN = 0xFFE6E6E6; break;
            case "eink":  BG = 0xFFFFFFFF; TEXT = 0xFF000000; DIM = 0xFF000000; LINE = 0xFF000000; PATTERN = 0xFFD0D0D0; break;
            default:      BG = 0xFF000000; TEXT = 0xFFFFFFFF; DIM = 0xFF8A8A8A; LINE = 0xFF262626; PATTERN = 0xFF1C1C1C;
        }
    }

    /** The window in the theme's colours: background, status and navigation bars (after super.onCreate). */
    public static void paintWindow(android.app.Activity a) {
        android.view.Window w = a.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        d.setSystemUiVisibility(LIGHT ? f | light : f & ~light);
    }

    /** One of the app's line icons (white in res/drawable) in the theme's text colour. */
    public static android.graphics.drawable.Drawable icon(Context c, int res) {
        android.graphics.drawable.Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(TEXT);
        return d;
    }

    /** A line's thickness: one pixel, two on e-ink (a hair line fades there). */
    public static int hair(Context c) {
        return EINK ? Math.max(2, dp(c, 1)) : 1;
    }

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    // --- the banner: one size in every tab ---------------------------------------------

    /** Every tab's banner is this high, whatever it holds. */
    public static final int BANNER_DP = 80;

    /** A banner: under the header, BANNER_DP high, a line of 18 sp over lines of 13 sp. */
    public static LinearLayout banner(Context c) {
        final int h = dp(c, BANNER_DP);
        LinearLayout b = new LinearLayout(c) {
            @Override protected void onMeasure(int w, int hs) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            }
        };
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(android.view.Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setBackgroundColor(BG);   // plain over the pattern, for the words
        return b;
    }

    /** A banner's first line: white, 18 sp, one line. */
    public static TextView bannerTitle(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(18);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** A banner's other line: dim, 13 sp, one line. */
    public static TextView bannerLine(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(13);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
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

    /**
     * A bar button: a line icon over a small label (the tab bar, the selection's
     * actions). Its first child is the icon, its second the label, so a caller
     * can show either or both.
     */
    public static LinearLayout labeledAction(Context c, int icon, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(android.view.Gravity.CENTER);
        int p = dp(c, 6);
        b.setPadding(0, p, 0, p);
        b.setMinimumHeight(dp(c, 52));
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setColorFilter(TEXT);
        b.addView(i, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextColor(TEXT);
        t.setTextSize(11);
        t.setSingleLine(true);
        t.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 3);
        b.addView(t, lp);
        b.setContentDescription(label);
        b.setOnClickListener(click);
        return b;
    }

    /** The height of a list row; lists turn pages of whole rows. */
    public static final int ROW_DP = 64;

    /** A one-pixel line. */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hair(c)));
        return v;
    }

    /** A black button with an outline: no fill to light up the panel. */
    public static GradientDrawable outline(Context c, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG);
        d.setStroke(hair(c), stroke);
        d.setCornerRadius(dp(c, 6));
        return d;
    }
}
