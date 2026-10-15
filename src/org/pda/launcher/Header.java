package org.pda.launcher;

import android.content.Context;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * The header every tab starts with: ‹ (when there is somewhere to go back to),
 * the title, which the search box replaces while searching, then the actions.
 * Search and sort are the same in every tab: {@link #search} adds the search
 * button, {@link #sort} a button whose menu picks the order (a Config key) and,
 * picked again, turns it round.
 */
public final class Header {

    private final Context c;
    private final LinearLayout view;
    private final TextView back, title;
    private final EditText search;
    private final LinearLayout actions;
    private ImageView searchBtn;
    private Consumer<String> onQuery;
    private String query = "";

    public Header(Context c, String titleText) {
        this.c = c;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinimumHeight(Ui.dp(c, 52));
        view.setBackgroundColor(Ui.BG);

        back = Ui.textAction(c, "‹", null);
        back.setTextSize(28);
        back.setVisibility(View.GONE);
        view.addView(back);

        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.title(c, titleText);
        title.setEllipsize(TextUtils.TruncateAt.START);
        box.addView(title);
        search = new EditText(c);
        search.setSingleLine(true);
        search.setHint("Search");
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.DIM);
        search.setBackground(null);
        search.setPadding(0, 0, 0, 0);
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().replace("\n", "").trim();
                if (q.equals(query)) return;
                query = q;
                if (onQuery != null) onQuery.accept(q);
            }
        });
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        view.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        actions = new LinearLayout(c);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, 0, Ui.dp(c, 4), 0);
        view.addView(actions);
        showBack(false);
    }

    public View view() {
        return view;
    }

    public void setTitle(String text) {
        title.setText(text);
    }

    /** What ‹ does; it shows only after {@link #showBack}(true). */
    public Header back(Runnable r) {
        back.setOnClickListener(v -> r.run());
        return this;
    }

    public void showBack(boolean on) {
        back.setVisibility(on ? View.VISIBLE : View.GONE);
        title.setPadding(on ? 0 : Ui.dp(c, 16), 0, 0, 0);
        search.setPadding(on ? 0 : Ui.dp(c, 16), 0, 0, 0);
    }

    public Header action(View v) {
        actions.addView(v);
        return this;
    }

    // --- search --------------------------------------------------------------------------

    /** Add the search button; {@code onQuery} gets the text as it is typed ("" when closed). */
    public Header search(Consumer<String> onQuery) {
        this.onQuery = onQuery;
        searchBtn = Ui.action(c, android.R.drawable.ic_menu_search, "Search", v -> toggleSearch());
        actions.addView(searchBtn, 0);
        return this;
    }

    public String query() {
        return query;
    }

    public boolean searching() {
        return search.getVisibility() == View.VISIBLE;
    }

    /** Hide or show search (a selection hides it, so its title shows); the query is kept. */
    public void setSearchEnabled(boolean on) {
        if (searchBtn != null) searchBtn.setVisibility(on ? View.VISIBLE : View.GONE);
        boolean box = on && !query.isEmpty() || on && searching();
        search.setVisibility(box ? View.VISIBLE : View.GONE);
        title.setVisibility(box ? View.GONE : View.VISIBLE);
    }

    public void toggleSearch() {
        if (searching()) { closeSearch(); return; }
        title.setVisibility(View.GONE);
        search.setVisibility(View.VISIBLE);
        search.requestFocus();
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    /** Close the search box; a query that was typed is cleared (and reported). */
    public void closeSearch() {
        hideIme();
        search.setVisibility(View.GONE);
        title.setVisibility(View.VISIBLE);
        search.setText("");
    }

    public void hideIme() {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    // --- sort ----------------------------------------------------------------------------

    /**
     * A sort button. {@code key} is a Config enum (its values are the orders),
     * {@code descKey} a Config bool, {@code labels} the names shown for the values.
     * Picking the current order turns it round; a new order starts newest or
     * largest first for dates and sizes, A to Z otherwise.
     */
    public static ImageView sort(Context c, String key, String descKey, String... labels) {
        return Ui.action(c, android.R.drawable.ic_menu_sort_by_size, "Sort", v -> {
            String[] values = Config.schema(key)[2].split("\\|");
            String cur = Config.get(key);
            boolean desc = Config.getBool(descKey);
            PopupMenu pm = new PopupMenu(c, v);
            for (int i = 0; i < values.length; i++) {
                String arrow = values[i].equals(cur) ? (desc ? "  ↓" : "  ↑") : "";
                pm.getMenu().add(0, i, i, (i < labels.length ? labels[i] : values[i]) + arrow);
            }
            pm.setOnMenuItemClickListener(item -> {
                String pick = values[item.getItemId()];
                boolean d = pick.equals(cur) ? !desc : newestFirst(pick);
                Config.set(c, descKey, String.valueOf(d));
                Config.set(c, key, pick);
                return true;
            });
            pm.show();
        });
    }

    private static boolean newestFirst(String order) {
        return order.equals("date") || order.equals("size") || order.equals("installed") || order.equals("updated");
    }
}
