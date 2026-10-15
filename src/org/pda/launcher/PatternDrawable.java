package org.pda.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * The home screen's background (launcher.pattern): the theme's colour and,
 * drawn over it in a faint line colour, a paper-like pattern; never a picture.
 * Dots (a dot grid), Grid (squared paper), Lines (a ruled notebook), Diagonal
 * (thin slants) or Palm (the fine cells of an old PDA's LCD). On OLED the lines
 * are a near-black grey, so the screen stays almost all off.
 */
public final class PatternDrawable extends Drawable {

    private final String kind;
    private final int bg;
    private final float step;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** {@code density}: pixels per dp, so the pattern has one size on every screen. */
    public PatternDrawable(String kind, int bg, int line, float density) {
        this.kind = kind;
        this.bg = bg;
        this.step = (kind.equals("palm") ? 6 : kind.equals("lines") ? 32 : 24) * density;
        p.setColor(line);
        p.setStrokeWidth(Math.max(1f, density * (Ui.EINK ? 1f : 0.6f)));
        p.setStyle(Paint.Style.STROKE);
    }

    @Override
    public void draw(Canvas c) {
        Rect r = getBounds();
        c.drawColor(bg);
        switch (kind) {
            case "dots":
                p.setStyle(Paint.Style.FILL);
                for (float y = r.top + step / 2; y < r.bottom; y += step) {
                    for (float x = r.left + step / 2; x < r.right; x += step) c.drawCircle(x, y, p.getStrokeWidth() * 1.2f, p);
                }
                p.setStyle(Paint.Style.STROKE);
                break;
            case "grid":
            case "palm":
                for (float x = r.left; x < r.right; x += step) c.drawLine(x, r.top, x, r.bottom, p);
                for (float y = r.top; y < r.bottom; y += step) c.drawLine(r.left, y, r.right, y, p);
                break;
            case "lines":
                for (float y = r.top + step; y < r.bottom; y += step) c.drawLine(r.left, y, r.right, y, p);
                break;
            case "diagonal":
                float h = r.height();
                for (float x = r.left - h; x < r.right; x += step) c.drawLine(x, r.bottom, x + h, r.top, p);
                break;
            default:
                break;
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
