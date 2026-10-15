package org.pda.launcher;

import android.app.Activity;
import android.content.Context;
import android.text.InputFilter;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The Note tab: a whiteboard. Text only, written anywhere, never scrolled, never
 * saved: what is on it lives as long as the launcher's process. The board takes
 * text only while it still fits; when it is full, typing stops. It can be
 * locked, so it only shows what is written (no cursor, no keyboard).
 */
final class NoteTab {

    private static final int SMALL = 18, MEDIUM = 26, LARGE = 36;   // sp

    private final Activity act;
    private final LinearLayout root;
    private final TextView lockBtn;
    private final EditText board;
    private boolean locked;
    private long fullToastAt;

    private final Config.Listener onConfig = this::applySize;

    NoteTab(Activity act) {
        this.act = act;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        lockBtn = Ui.textAction(act, "Lock", v -> setLocked(!locked));
        lockBtn.setTextSize(15);
        lockBtn.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 12), 0);
        root.addView(Ui.header(act, Ui.title(act, "Note"), lockBtn, Ui.textAction(act, "⋮", v -> showMenu(v))));
        root.addView(Ui.divider(act));

        board = new EditText(act);
        board.setBackground(null);
        board.setTextColor(Ui.TEXT);
        board.setHintTextColor(Ui.DIM);
        board.setHint("Write anything");
        board.setGravity(Gravity.TOP | Gravity.START);
        int p = Ui.dp(act, 16);
        board.setPadding(p, p, p, p);
        board.setVerticalScrollBarEnabled(false);
        board.setOverScrollMode(View.OVER_SCROLL_NEVER);
        board.setHorizontallyScrolling(false);
        board.setFilters(new InputFilter[] { this::fit });
        root.addView(board, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Config.addListener(onConfig);
        applySize();
    }

    View view() {
        return root;
    }

    void onHide() {
        hideIme();
    }

    void onDestroy() {
        Config.removeListener(onConfig);
    }

    /**
     * The board's filter: an edit is taken only if the text still fits the board
     * after it. Deleting is always taken.
     */
    private CharSequence fit(CharSequence source, int start, int end,
                             android.text.Spanned dest, int dstart, int dend) {
        if (end - start <= 0) return null;   // a deletion
        int w = board.getWidth() - board.getPaddingLeft() - board.getPaddingRight();
        int h = board.getHeight() - board.getPaddingTop() - board.getPaddingBottom();
        if (w <= 0 || h <= 0) return null;   // not laid out yet
        SpannableStringBuilder next = new SpannableStringBuilder(dest);
        next.replace(dstart, dend, source, start, end);
        StaticLayout l = StaticLayout.Builder.obtain(next, 0, next.length(), board.getPaint(), w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(board.getLineSpacingExtra(), board.getLineSpacingMultiplier())
                .setIncludePad(board.getIncludeFontPadding())
                .build();
        if (l.getHeight() <= h) return null;
        long now = System.currentTimeMillis();
        if (now - fullToastAt > 2000) {
            fullToastAt = now;
            Toast.makeText(act, "The board is full", Toast.LENGTH_SHORT).show();
        }
        return "";
    }

    private void setLocked(boolean on) {
        locked = on;
        lockBtn.setText(on ? "Unlock" : "Lock");
        board.setFocusable(!on);
        board.setFocusableInTouchMode(!on);
        board.setCursorVisible(!on);
        board.setLongClickable(!on);
        board.setHint(on ? null : "Write anything");
        if (on) {
            board.clearFocus();
            hideIme();
        } else {
            board.requestFocus();
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(board, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Clear").setEnabled(!locked && board.length() > 0);
        pm.setOnMenuItemClickListener(item -> {
            board.setText("");
            return true;
        });
        pm.show();
    }

    private void applySize() {
        switch (Config.get("note.textSize")) {
            case "small": board.setTextSize(SMALL); break;
            case "large": board.setTextSize(LARGE); break;
            default: board.setTextSize(MEDIUM);
        }
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(board.getWindowToken(), 0);
    }
}
