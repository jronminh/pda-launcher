package org.pda.launcher.shell;

import android.content.Context;
import android.view.MotionEvent;

import jackpal.androidterm.emulatorview.EmulatorView;

/**
 * The terminal view with the app's gesture layer on top of {@link EmulatorView}:
 *
 * <ul>
 *   <li>two-finger drag -> always scroll the local scrollback (escape hatch,
 *       even while a program has mouse reporting on)</li>
 *   <li>two-finger tap -> toggle the soft keyboard</li>
 *   <li>long-press -> select the word under the finger (ActionMode)</li>
 *   <li>single-finger tap / drag / fling / long-press are handled by
 *       {@link EmulatorView} (tap = focus, drag = scroll or mouse wheel)</li>
 * </ul>
 *
 * Font size is set from the ⋮ menu / Settings — there is no pinch-to-zoom.
 */
public class TermView extends EmulatorView {

    /** Callbacks the Activity reacts to. */
    public interface Listener {
        void onSingleTap(float x, float y);
        void onTwoFingerTap();
        void onLongPress(float x, float y);
    }

    private static final int TWO_FINGER_SLOP = 20; // px

    private Listener listener;

    private boolean twoActive;
    private boolean twoMoved;
    private long twoDownTime;
    private float twoStartY;
    private int twoAppliedRows;

    public TermView(Context context) {
        super(context);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    @Override public boolean onSingleTapUp(MotionEvent e) {
        if (listener != null) listener.onSingleTap(e.getX(), e.getY());
        return super.onSingleTapUp(e);
    }

    @Override public void onLongPress(MotionEvent e) {
        if (listener != null) listener.onLongPress(e.getX(), e.getY());
    }

    // --- touch routing -------------------------------------------------------

    @Override public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                twoActive = false;
                twoMoved = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                twoActive = true;
                twoMoved = false;
                twoDownTime = ev.getEventTime();
                twoStartY = avgY(ev);
                twoAppliedRows = 0;
                // A second finger means this is not a single-touch gesture:
                // cancel the in-flight single-touch state so tap/long-press/
                // scroll do not fire.
                cancelSingleTouch(ev);
                break;
            case MotionEvent.ACTION_MOVE:
                if (twoActive) {
                    float y = avgY(ev);
                    float total = twoStartY - y;
                    if (Math.abs(total) > TWO_FINGER_SLOP) twoMoved = true;
                    int h = getCharacterHeight();
                    if (h > 0) {
                        int target = (int) (total / h);
                        int delta = target - twoAppliedRows;
                        if (delta != 0) {
                            scrollTranscriptBy(delta);
                            twoAppliedRows = target;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (twoActive && !twoMoved
                        && ev.getEventTime() - twoDownTime < 250) {
                    if (listener != null) listener.onTwoFingerTap();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                twoActive = false;
                break;
            default:
                break;
        }

        if (twoActive || ev.getPointerCount() >= 2) {
            return true;
        }
        return super.onTouchEvent(ev);
    }

    /** Reset the in-flight single-touch gesture (tap/scroll/long-press). */
    private void cancelSingleTouch(MotionEvent ev) {
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.onTouchEvent(cancel);
        cancel.recycle();
    }

    private static float avgY(MotionEvent e) {
        int n = e.getPointerCount();
        float s = 0;
        for (int i = 0; i < n; i++) s += e.getY(i);
        return n > 0 ? s / n : 0f;
    }
}
