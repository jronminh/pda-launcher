package org.pda.launcher;

import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The few motions of the app, all short and all off with one setting
 * (launcher.animations): a tab fades in, a page or a folder slides in from the
 * side it comes from, a menu grows from where it was asked for, a settings
 * screen rises. They run on the view's animator, so the system's animator
 * duration scale (Developer options, or the PDA panel) applies too: at 0 they
 * are instant.
 */
public final class Anim {

    private static final int SHORT = 150, MEDIUM = 200;   // ms

    private Anim() {}

    public static boolean on() {
        return Config.getBool("launcher.animations");
    }

    /** A view that just became visible: from transparent. */
    public static void fadeIn(View v) {
        if (!on()) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(SHORT).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** New content from the side it comes from: {@code dir} > 0 from the right, < 0 from the left. */
    public static void slideIn(View v, int dir) {
        if (!on() || dir == 0) return;
        v.animate().cancel();
        v.setTranslationX(Integer.signum(dir) * Ui.dp(v.getContext(), 48));
        v.setAlpha(0f);
        v.animate().translationX(0f).alpha(1f).setDuration(MEDIUM)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** A screen laid over the tabs: up from a little below. */
    public static void riseIn(View v) {
        if (!on()) return;
        v.animate().cancel();
        v.setTranslationY(Ui.dp(v.getContext(), 32));
        v.setAlpha(0f);
        v.animate().translationY(0f).alpha(1f).setDuration(MEDIUM)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** A menu: grows from the point ({@code px}, {@code py}) within it. */
    public static void popIn(View v, float px, float py) {
        if (!on()) return;
        v.setPivotX(px);
        v.setPivotY(py);
        v.setScaleX(0.9f);
        v.setScaleY(0.9f);
        v.setAlpha(0f);
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(SHORT)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
}
