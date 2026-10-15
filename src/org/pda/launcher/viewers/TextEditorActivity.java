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
 * font (⋮). Leaving with changes not saved asks first.
 */
public class TextEditorActivity extends Activity {

    private static final long MAX = 2L * 1024 * 1024;

    private File file;
    private EditText text;
    private Header header;
    private String saved = "";
    private boolean dirty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config.prepare(this);
        String path = getIntent().getStringExtra(Viewers.EXTRA_PATH);
        file = path == null ? null : new File(path);
        String content = file == null ? null : read(file);
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
            @Override public void afterTextChanged(Editable s) { setDirty(!s.toString().equals(saved)); }
        });
        root.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        applyLook();
        setContentView(root);
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
        m.add(0, 1, 0, "Wrap lines").setChecked(Config.getBool("editor.wrap"));
        m.add(0, 2, 1, "Monospace").setChecked(Config.getBool("editor.mono"));
        m.add(0, 3, 2, "Revert to saved").setEnabled(dirty);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: Config.set(this, "editor.wrap", String.valueOf(!Config.getBool("editor.wrap"))); break;
                case 2: Config.set(this, "editor.mono", String.valueOf(!Config.getBool("editor.mono"))); break;
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
