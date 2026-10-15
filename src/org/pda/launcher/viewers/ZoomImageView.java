package org.pda.launcher.viewers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * An image fitted to the view that pinches to zoom (up to 8×), drags when
 * zoomed, and double-taps between fitted and 2.5×. Not zoomed, a horizontal
 * fling is a page turn ({@link Pages}), as in the launcher's own paged lists;
 * a single tap is passed on (the viewers show or hide their bars with it).
 */
public class ZoomImageView extends ImageView {

    public interface Pages {
        /** -1: the previous, 1: the next. */
        void turn(int dir);
        void tap();
    }

    private static final float MAX = 8f;

    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private float fit = 1f;   // the scale that fits the image
    private Bitmap bmp;
    private Pages pages;
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (pages != null) pages.tap();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoomed()) reset();
                else zoomBy(2.5f, e.getX(), e.getY());
                return true;
            }

            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (!zoomed()) return false;
                m.postTranslate(-dx, -dy);
                clamp();
                return true;
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (zoomed() || pages == null || a == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(dx) > getWidth() / 8f) {
                    pages.turn(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setPages(Pages p) {
        pages = p;
    }

    public void show(Bitmap b) {
        bmp = b;
        setImageBitmap(b);
        reset();
    }

    public boolean zoomed() {
        m.getValues(v);
        return v[Matrix.MSCALE_X] > fit * 1.01f;
    }

    /** Fitted and centred. */
    public void reset() {
        if (bmp == null || getWidth() == 0) return;
        float w = getWidth(), h = getHeight();
        fit = Math.min(w / bmp.getWidth(), h / bmp.getHeight());
        m.reset();
        m.postScale(fit, fit);
        m.postTranslate((w - bmp.getWidth() * fit) / 2f, (h - bmp.getHeight() * fit) / 2f);
        setImageMatrix(m);
    }

    private void zoomBy(float f, float px, float py) {
        m.getValues(v);
        float s = v[Matrix.MSCALE_X];
        f = Math.max(fit / s, Math.min(MAX * fit / s, f));
        m.postScale(f, f, px, py);
        clamp();
    }

    /** Keep the image on screen: centred while smaller than the view, its edges at the view's when larger. */
    private void clamp() {
        if (bmp == null) return;
        RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
        m.mapRect(r);
        float w = getWidth(), h = getHeight(), dx = 0, dy = 0;
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < h) dy = h - r.bottom;
        m.postTranslate(dx, dy);
        setImageMatrix(m);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        reset();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        if (zoomed()) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
}
