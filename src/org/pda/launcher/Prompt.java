package org.pda.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A few words asked for, in the app's look (a black card with a thin outline,
 * as {@link Card} and {@link PopMenu}), not Android's dialog: a title, one or
 * more lines, Cancel and an action. It sits in the upper part of the screen so
 * the keyboard never covers it; Enter on the last line is the action.
 */
public final class Prompt {

    private final Context c;
    private final LinearLayout box;
    private final List<EditText> fields = new ArrayList<>();
    private String action = "OK";
    private Consumer<String[]> onOk;
    private PopupWindow window;

    public Prompt(Context c, String title) {
        this.c = c;
        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.outline(c, Ui.LINE));
        int p = Ui.dp(c, 20);
        box.setPadding(p, p, p, Ui.dp(c, 8));
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(18);
        t.setPadding(0, 0, 0, Ui.dp(c, 4));
        box.addView(t);
    }

    /** A line to fill: its hint, what it holds already, and its kind (InputType; 0 for text). */
    public Prompt field(String hint, String value, int inputType) {
        EditText e = new EditText(c);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.DIM);
        e.setTextSize(16);
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.DIM));
        if (inputType != 0) e.setInputType(inputType);
        if (value != null) {
            e.setText(value);
            e.setSelection(value.length());
        }
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fields.add(e);
        return this;
    }

    public Prompt field(String hint, String value) {
        return field(hint, value, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    /** The action's name, and what it does with the lines (trimmed). */
    public Prompt ok(String name, Consumer<String[]> then) {
        action = name;
        onOk = then;
        return this;
    }

    public void show(View over) {
        LinearLayout buttons = new LinearLayout(c);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, Ui.dp(c, 8), 0, 0);
        buttons.addView(button("Cancel", Ui.DIM, v -> dismiss()));
        buttons.addView(button(action, Ui.TEXT, v -> done()));
        box.addView(buttons);
        EditText last = fields.isEmpty() ? null : fields.get(fields.size() - 1);
        if (last != null) {
            last.setImeOptions(EditorInfo.IME_ACTION_DONE);
            last.setOnEditorActionListener((v, id, e) -> { done(); return true; });
        }

        int width = Math.min(c.getResources().getDisplayMetrics().widthPixels - Ui.dp(c, 32), Ui.dp(c, 420));
        window = new PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.setOutsideTouchable(true);
        window.setAnimationStyle(0);
        window.setElevation(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        window.showAtLocation(over, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0,
                c.getResources().getDisplayMetrics().heightPixels / 8);
        Anim.popIn(box, width / 2f, 0);
        if (!fields.isEmpty()) {
            EditText first = fields.get(0);
            first.requestFocus();
            first.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(first, InputMethodManager.SHOW_IMPLICIT);
            }, 100);
        }
    }

    private TextView button(String text, int color, View.OnClickListener click) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextColor(color);
        b.setTextSize(15);
        b.setAllCaps(true);
        int h = Ui.dp(c, 16), v = Ui.dp(c, 12);
        b.setPadding(h, v, h, v);
        b.setOnClickListener(click);
        return b;
    }

    private void done() {
        String[] values = new String[fields.size()];
        for (int i = 0; i < values.length; i++) values[i] = fields.get(i).getText().toString().trim();
        dismiss();
        if (onOk != null) onOk.accept(values);
    }

    private void dismiss() {
        if (window == null) return;
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(box.getWindowToken(), 0);
        window.dismiss();
    }
}
