package org.pda.launcher;

import android.app.Activity;
import android.content.Context;
import android.text.InputFilter;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

/**
 * The Today tab's board: a whiteboard. Text only, written anywhere, never
 * scrolled, never saved: what is on it lives as long as the launcher's process
 * (kept in a static field, so a screen made again, for DeX or a new font size,
 * keeps it). It takes text only while it still fits; when it is full, typing
 * stops. It has no bar of its own: Today's bar is the one on top, and its menu
 * has Clear and Text size while the board shows.
 */
final class Board {

    private static final int SMALL = 18, MEDIUM = 26, LARGE = 36;   // sp

    // The board's text outlives the screen, not the process.
    private static CharSequence kept = "";
    private static Board live;   // the board on screen now, if any

    private final Activity act;
    private final EditText board;
    private long fullToastAt;

    private final Config.Listener onConfig = keys -> { if (Config.touches(keys, "note.")) applySize(); };

    Board(Activity act) {
        this.act = act;
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
        board.setText(kept);   // before the filter: the board is not laid out yet
        board.setFilters(new InputFilter[] { this::fit });
        board.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { kept = s.toString(); }
        });
        Config.addListener(onConfig);
        applySize();
        live = this;
    }

    /**
     * Put {@code text} on the board in place of what is there (Share › Show on
     * Note). It is shown whole even if it does not fit: the fit check is for
     * typing; what runs past the board is simply not seen.
     */
    static void show(String text) {
        kept = text;
        Board n = live;
        if (n == null) return;   // the next board made starts with it
        InputFilter[] filters = n.board.getFilters();
        n.board.setFilters(new InputFilter[0]);
        n.board.setText(text);
        n.board.setFilters(filters);
    }

    View view() {
        return board;
    }

    boolean isEmpty() {
        return board.length() == 0;
    }

    void clear() {
        board.setText("");
    }

    void onHide() {
        hideIme();
    }

    void onDestroy() {
        if (live == this) live = null;
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
