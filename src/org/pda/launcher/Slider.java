package org.pda.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * A level picked on a bar, in a card over the screen in the app's look (as
 * {@link Card}): a title, the value in words, the bar. Each move is applied at
 * once (a volume is heard, a brightness seen); a tap outside or Back closes it.
 * Prefs' brightness and volumes use it, as Android's own sliders do.
 */
public final class Slider {

    private Slider() {}

    /**
     * @param max the bar's top (its bottom is 0)
     * @param words the value as shown ("40%", "7 of 15")
     * @param change each new value, as the finger moves
     * @param closed after the card closes (the screen behind shows the new value)
     */
    public static void show(Context c, View over, String title, int value, int max,
                            IntFunction<String> words, IntConsumer change, Runnable closed) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.outline(c, Ui.LINE));
        int p = Ui.dp(c, 20);
        box.setPadding(p, p, p, Ui.dp(c, 16));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(18);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(c);
        v.setText(words.apply(value));
        v.setTextColor(Ui.DIM);
        v.setTextSize(15);
        top.addView(v);
        box.addView(top);

        SeekBar bar = new SeekBar(c);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.TEXT));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        bar.setPadding(0, Ui.dp(c, 20), 0, Ui.dp(c, 8));
        bar.setContentDescription(title);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int n, boolean user) {
                if (!user) return;
                v.setText(words.apply(n));
                change.accept(n);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 32), Ui.dp(c, 420));
        PopupWindow w = new PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // a tap outside closes it
        w.setOutsideTouchable(true);
        w.setAnimationStyle(0);
        w.setElevation(0);
        w.setOnDismissListener(closed::run);
        w.showAtLocation(over, Gravity.CENTER, 0, 0);
        Anim.popIn(box, width / 2f, 0);
    }
}
