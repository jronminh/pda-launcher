package org.pda.launcher.viewers;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.pda.launcher.Config;
import org.pda.launcher.Header;
import org.pda.launcher.PopMenu;
import org.pda.launcher.R;
import org.pda.launcher.Ui;
import org.pda.launcher.files.FileShare;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The built-in text editor: one plain text file, UTF-8, edited and saved in
 * place (a temporary file, then a rename, so a crash never leaves half a file).
 * Too large (over 2 MB) or binary (a NUL in its first 8 KB), the file goes to
 * the system's app instead. Lines wrap or not, in a proportional or monospace
 * font (⋮). Find (⋮) marks every match and walks them with ‹ ›. Leaving with
 * changes not saved asks first.
 */
public class TextEditorActivity extends Activity {

    private static final long MAX = 2L * 1024 * 1024;
    private static final int MARKED = 2000;   // matches underlined at most; all are counted

    private File file;
    private Viewers.Source src;   // a link alone when src.file is null: read and saved through it
    private EditText text;
    private Header header;
    private String saved = "";
    private boolean dirty;
    private LinearLayout findBar;
    private EditText query;
    private android.widget.TextView found;
    private final java.util.List<int[]> matches = new java.util.ArrayList<>();
    private int match = -1;

    @Override
    protected void onCreate(Bundle b) {
        org.pda.launcher.Ui.applyTheme(this);
        super.onCreate(b);
        org.pda.launcher.Ui.paintWindow(this);
        src = Viewers.sourceOf(this);
        if (src == null) return;
        file = src.file != null ? src.file : new File(src.name);
        String content = src.file != null ? read(file) : readLink();
        if (content == null) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        header = new Header(this, file.getName())
                .back(this::onBackPressed)
                .action(Ui.action(this, R.drawable.ic_check, "Save", v -> save()))
                .action(Ui.action(this, R.drawable.ic_more, "More", this::menu));
        header.showBack(true);
        root.addView(header.view());
        root.addView(Ui.divider(this));
        root.addView(findBar());

        text = new EditText(this);
        text.setBackground(null);
        text.setTextColor(Ui.TEXT);
        text.setTextSize(15);
        text.setGravity(Gravity.TOP | Gravity.START);
        int p = Ui.dp(this, 12);
        text.setPadding(p, p, p, p);
        text.setText(content);
        saved = content;
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) {
                setDirty(!s.toString().equals(saved));
                if (findBar != null && findBar.getVisibility() == View.VISIBLE) find(false);
            }
        });
        root.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        applyLook();
        setContentView(root);
    }

    /** Find's bar under the header: the words, n/count, ‹ ›, close. Hidden until ⋮ › Find. */
    private View findBar() {
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.VERTICAL);
        findBar.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12), 0, 0, 0);
        query = new EditText(this);
        query.setBackground(null);
        query.setTextColor(Ui.TEXT);
        query.setHintTextColor(Ui.DIM);
        query.setHint("Find");
        query.setTextSize(16);
        query.setSingleLine(true);
        query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, ev) -> { step(1); return true; });
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { find(true); }
        });
        row.addView(query, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        found = new android.widget.TextView(this);
        found.setTextColor(Ui.DIM);
        found.setTextSize(13);
        found.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);
        row.addView(found);
        row.addView(Ui.action(this, R.drawable.ic_back, "Previous match", v -> step(-1)));
        row.addView(Ui.action(this, R.drawable.ic_forward, "Next match", v -> step(1)));
        row.addView(Ui.action(this, R.drawable.ic_close, "Close find", v -> closeFind()));
        findBar.addView(row);
        findBar.addView(Ui.divider(this));
        return findBar;
    }

    private void openFind() {
        findBar.setVisibility(View.VISIBLE);
        query.requestFocus();
        query.selectAll();
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.showSoftInput(query, 0);
        find(true);
    }

    private void closeFind() {
        findBar.setVisibility(View.GONE);
        matches.clear();
        match = -1;
        unmark();
        text.requestFocus();
    }

    /**
     * Every match of the words, case aside, underlined; the current one shown
     * inverted. {@code fromCursor}: the current is the first at or after the
     * cursor (a new search); else the one nearest the old current (the text changed).
     */
    private void find(boolean fromCursor) {
        int near = match >= 0 && match < matches.size() ? matches.get(match)[0] : text.getSelectionStart();
        matches.clear();
        match = -1;
        unmark();
        String q = query.getText().toString();
        if (q.isEmpty()) {
            found.setText("");
            return;
        }
        Editable e = text.getText();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(q),
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE).matcher(e);
        int from = fromCursor ? Math.max(0, text.getSelectionStart()) : Math.max(0, near);
        while (m.find()) {
            if (match < 0 && m.start() >= from) match = matches.size();
            matches.add(new int[] { m.start(), m.end() });
            if (matches.size() <= MARKED) {
                e.setSpan(new Mark(), m.start(), m.end(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (matches.isEmpty()) {
            found.setText("None");
            return;
        }
        if (match < 0) match = 0;   // none after the cursor: from the top again
        show();
    }

    /** The next ({@code dir} 1) or previous (-1) match, round the end. */
    private void step(int dir) {
        if (matches.isEmpty()) return;
        match = (match + dir + matches.size()) % matches.size();
        show();
    }

    private void show() {
        Editable e = text.getText();
        for (Current c : e.getSpans(0, e.length(), Current.class)) e.removeSpan(c);
        int[] at = matches.get(match);
        e.setSpan(new Current(), at[0], at[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        found.setText((match + 1) + "/" + matches.size());
        text.setSelection(at[0]);
        text.post(() -> text.bringPointIntoView(at[0]));
    }

    private void unmark() {
        Editable e = text.getText();
        for (Mark m : e.getSpans(0, e.length(), Mark.class)) e.removeSpan(m);
        for (Current c : e.getSpans(0, e.length(), Current.class)) e.removeSpan(c);
    }

    /** A match: underlined, so it shows in every theme (E-ink has no grey). */
    private static final class Mark extends android.text.style.UnderlineSpan { }

    /** The current match: the theme's colours swapped. */
    private static final class Current extends android.text.style.CharacterStyle {
        @Override public void updateDrawState(android.text.TextPaint p) {
            p.bgColor = Ui.TEXT;
            p.setColor(Ui.BG);
        }
    }

    /** The file as text, or null (said) if the editor should not take it. */
    private String read(File f) {
        if (f.length() > MAX) return fallback("too large for the editor");
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bytes.write(buf, 0, n);
            byte[] all = bytes.toByteArray();
            for (int i = 0; i < Math.min(all.length, 8192); i++) {
                if (all[i] == 0) return fallback("not a text file");
            }
            return new String(all, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Toast.makeText(this, "Cannot read " + f.getName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    /** A link's text (another app's file): the same limits as a file's. */
    private String readLink() {
        try (InputStream in = getContentResolver().openInputStream(src.uri)) {
            if (in == null) throw new IOException("cannot be opened");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
                if (bytes.size() > MAX) {
                    Toast.makeText(this, file.getName() + " is too large for the editor", Toast.LENGTH_LONG).show();
                    return null;
                }
            }
            byte[] all = bytes.toByteArray();
            for (int i = 0; i < Math.min(all.length, 8192); i++) {
                if (all[i] == 0) {
                    Toast.makeText(this, file.getName() + " is not a text file", Toast.LENGTH_LONG).show();
                    return null;
                }
            }
            return new String(all, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            Toast.makeText(this, "Cannot read " + file.getName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    /** Hand the file to the system's app, saying why. */
    private String fallback(String why) {
        Toast.makeText(this, file.getName() + " is " + why + "; opening it elsewhere", Toast.LENGTH_SHORT).show();
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileShare.linkFor(this, file), "text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (RuntimeException ignore) {}
        return null;
    }

    private void setDirty(boolean d) {
        if (d == dirty) return;
        dirty = d;
        header.setTitle((d ? "• " : "") + file.getName());
    }

    private void save() {
        String s = text.getText().toString();
        if (src.file == null) {   // another app's file: written back through its link, if it lets us
            try (OutputStream o = getContentResolver().openOutputStream(src.uri, "wt")) {
                if (o == null) throw new IOException("cannot be opened for writing");
                o.write(s.getBytes(StandardCharsets.UTF_8));
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                Toast.makeText(this, "Cannot save here (the app that gave it allows reading only)", Toast.LENGTH_LONG).show();
                return;
            }
            saved = s;
            setDirty(false);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            Toast.makeText(this, "Cannot save: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            Toast.makeText(this, "Cannot save " + file.getName(), Toast.LENGTH_LONG).show();
            return;
        }
        saved = s;
        setDirty(false);
        MediaScannerConnection.scanFile(this, new String[] { file.getPath() }, null, null);
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private void menu(View anchor) {
        PopMenu m = new PopMenu(this, anchor);
        m.add(0, 4, -1, "Find");
        m.add(0, 1, 0, "Wrap lines").setChecked(Config.getBool("editor.wrap"));
        m.add(0, 2, 1, "Monospace").setChecked(Config.getBool("editor.mono"));
        m.add(0, 3, 2, "Revert to saved").setEnabled(dirty);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: Config.set(this, "editor.wrap", String.valueOf(!Config.getBool("editor.wrap"))); break;
                case 2: Config.set(this, "editor.mono", String.valueOf(!Config.getBool("editor.mono"))); break;
                case 4: openFind(); return true;
                default: text.setText(saved);
            }
            applyLook();
            return true;
        });
        m.show();
    }

    private void applyLook() {
        text.setHorizontallyScrolling(!Config.getBool("editor.wrap"));
        text.setTypeface(Config.getBool("editor.mono") ? Typeface.MONOSPACE : Typeface.DEFAULT);
    }

    @Override
    public void onBackPressed() {
        if (findBar != null && findBar.getVisibility() == View.VISIBLE) {
            closeFind();
            return;
        }
        if (!dirty) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Save the changes to " + file.getName() + "?")
                .setPositiveButton("Save", (d, w) -> { save(); if (!dirty) finish(); })
                .setNegativeButton("Discard", (d, w) -> finish())
                .setNeutralButton("Cancel", null)
                .show();
    }
}
