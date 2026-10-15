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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * The Note tab: a whiteboard. Text only, written anywhere, never scrolled, never
 * saved: what is on it lives as long as the launcher's process (kept in a static
 * field, so a screen made again, for DeX or a new font size, keeps it). The board takes
 * text only while it still fits; when it is full, typing stops. It can be
 * locked, so it only shows what is written (no cursor, no keyboard).
 */
final class NoteTab {

    private static final int SMALL = 18, MEDIUM = 26, LARGE = 36;   // sp

    // The board's text and lock outlive the screen, not the process.
    private static CharSequence kept = "";
    private static boolean keptLocked;
    private static NoteTab live;   // the board on screen now, if any

    private final Activity act;
    private final LinearLayout root;
    private final ImageView lockBtn;
    private final EditText board;
    private boolean locked;
    private long fullToastAt;

    private final Config.Listener onConfig = keys -> { if (Config.touches(keys, "note.")) applySize(); };

    NoteTab(Activity act) {
        this.act = act;

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // The button shows the board's state: open while it can be written, shut when locked.
        lockBtn = Ui.action(act, R.drawable.ic_lock_open, "Lock", v -> setLocked(!locked, true));
        root.addView(new Header(act, "Note")
                .action(lockBtn)
                .action(Ui.action(act, R.drawable.ic_more, "More", v -> showMenu(v)))
                .view());
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
        board.setText(kept);   // before the filter: the board is not laid out yet
        board.setFilters(new InputFilter[] { this::fit });
        board.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { kept = s.toString(); }
        });
        root.addView(board, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Config.addListener(onConfig);
        applySize();
        if (keptLocked) setLocked(true, false);
        live = this;
    }

    /**
     * Put {@code text} on the board in place of what is there (Share › Show on
     * Note). It is shown whole even if it does not fit: the fit check is for
     * typing; what runs past the board is simply not seen.
     */
    static void show(String text) {
        kept = text;
        NoteTab n = live;
        if (n == null) return;   // the next board made starts with it
        InputFilter[] filters = n.board.getFilters();
        n.board.setFilters(new InputFilter[0]);
        n.board.setText(text);
        n.board.setFilters(filters);
    }

    View view() {
        return root;
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

    /** Lock or unlock; {@code byUser} raises the keyboard on unlock. */
    private void setLocked(boolean on, boolean byUser) {
        locked = on;
        keptLocked = on;
        lockBtn.setImageResource(on ? R.drawable.ic_lock : R.drawable.ic_lock_open);
        lockBtn.setContentDescription(on ? "Unlock" : "Lock");
        board.setFocusable(!on);
        board.setFocusableInTouchMode(!on);
        board.setCursorVisible(!on);
        board.setLongClickable(!on);
        board.setHint(on ? null : "Write anything");
        if (on) {
            board.clearFocus();
            hideIme();
        } else if (byUser) {
            board.requestFocus();
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(board, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void showMenu(View anchor) {
        PopMenu pm = new PopMenu(act, anchor);
        pm.getMenu().add(0, 1, 0, "Clear").setEnabled(!locked && board.length() > 0);
        pm.getMenu().add(0, 2, 1, "Text size  ›");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) board.setText("");
            else PopMenu.choose(act, anchor, "note.textSize", "Small", "Medium", "Large");
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
