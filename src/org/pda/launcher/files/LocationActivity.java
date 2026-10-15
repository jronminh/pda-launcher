package org.pda.launcher.files;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.pda.launcher.Card;
import org.pda.launcher.Config;
import org.pda.launcher.Header;
import org.pda.launcher.PagedView;
import org.pda.launcher.Pager;
import org.pda.launcher.PopMenu;
import org.pda.launcher.Prompt;
import org.pda.launcher.R;
import org.pda.launcher.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A location added to Files ({@link Locations}), browsed in the Files tab's
 * look: the header (‹ up, the folder's name, + and ⋮), the paged list in the
 * Files view, the status line while copying. Tap a folder to go in, a file to
 * open it in the app the system picks; long-press for Open with, Download to
 * the device, Rename, Delete, Details. + makes a folder or uploads files picked
 * with Android's own picker. What it can do follows the providing app's flags.
 * Nothing goes to a trash here: a delete is the provider's, after a question.
 */
public class LocationActivity extends Activity {

    static final String EXTRA_TREE = "tree", EXTRA_NAME = "name";
    private static final int REQUEST_UPLOAD = 7;

    private Uri tree;
    private final List<String[]> stack = new ArrayList<>();   // { document id, name }, the top last
    private final List<Docs.Doc> docs = new ArrayList<>();
    private Header header;
    private Pager pager;
    private PagedView list;
    private TextView message, status;
    private int generation;

    @Override
    protected void onCreate(Bundle b) {
        org.pda.launcher.Ui.applyTheme(this);
        super.onCreate(b);
        org.pda.launcher.Ui.paintWindow(this);
        String t = getIntent().getStringExtra(EXTRA_TREE);
        if (t == null) { finish(); return; }
        tree = Uri.parse(t);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        stack.add(new String[] { DocumentsContract.getTreeDocumentId(tree), name == null ? "Location" : name });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        header = new Header(this, "")
                .back(this::onBackPressed)
                .action(Ui.action(this, R.drawable.ic_add, "Add", this::addMenu))
                .action(Ui.action(this, R.drawable.ic_more, "More", this::menu));
        header.showBack(true);
        root.addView(header.view());
        root.addView(Ui.divider(this));

        pager = new Pager(this);
        list = new PagedView(this, pager);
        list.setMode(Config.get("files.view"));
        list.setGrid(4, 0);
        list.setBinder(new PagedView.Binder() {
            @Override public int count() { return docs.size(); }
            @Override public Drawable icon(int i) { return Ui.icon(LocationActivity.this, iconOf(docs.get(i))); }
            @Override public String title(int i) { return docs.get(i).name; }
            @Override public String summary(int i) { return describe(docs.get(i)); }
            @Override public String mark(int i) { return null; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return false; }
            @Override public void click(int i, View v) { open(docs.get(i)); }
            @Override public boolean longClick(int i, View v) { docMenu(v, docs.get(i)); return true; }
        });
        FrameLayout body = new FrameLayout(this);
        body.addView(list);
        message = new TextView(this);
        message.setTextColor(Ui.DIM);
        message.setGravity(Gravity.CENTER_HORIZONTAL);
        message.setPadding(Ui.dp(this, 24), Ui.dp(this, 48), Ui.dp(this, 24), 0);
        body.addView(message);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        status = new TextView(this);
        status.setTextColor(Ui.DIM);
        status.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        status.setVisibility(View.GONE);
        root.addView(status);
        root.addView(pager);
        setContentView(root);
        load();
    }

    private String[] top() {
        return stack.get(stack.size() - 1);
    }

    private Uri folderUri() {
        return DocumentsContract.buildDocumentUriUsingTree(tree, top()[0]);
    }

    private void load() {
        header.setTitle(top()[1]);
        message.setText("Reading…");
        message.setVisibility(View.VISIBLE);
        final int gen = ++generation;
        final String id = top()[0];
        new Thread(() -> {
            List<Docs.Doc> found = null;
            String error = null;
            try {
                found = Docs.children(this, tree, id);
            } catch (SecurityException e) {
                error = "the launcher is not allowed in (any more). Remove it from Files and add it again.";
            } catch (RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final List<Docs.Doc> f = found;
            final String err = error;
            runOnUiThread(() -> {
                if (gen != generation || isDestroyed()) return;
                docs.clear();
                if (f != null) docs.addAll(f);
                message.setText(err != null ? "Cannot read this place:\n" + err
                        + "\n\nThe app that provides it may be closed, signed out or gone." : "Empty");
                message.setVisibility(err != null || docs.isEmpty() ? View.VISIBLE : View.GONE);
                list.refresh();
            });
        }, "location").start();
    }

    @Override
    public void onBackPressed() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            list.slideNext(-1);
            pager.setPage(0);
            load();
        } else {
            finish();
        }
    }

    // --- items ---------------------------------------------------------------------------

    private static int iconOf(Docs.Doc d) {
        if (d.isDir()) return R.drawable.ic_folder;
        String m = d.mime == null ? "" : d.mime;
        if (m.startsWith("image/")) return R.drawable.ic_image;
        if (m.startsWith("video/")) return R.drawable.ic_video;
        if (m.startsWith("audio/")) return R.drawable.ic_audio;
        if (m.startsWith("text/") || m.equals("application/pdf")) return R.drawable.ic_doc;
        return R.drawable.ic_file;
    }

    private String describe(Docs.Doc d) {
        String when = d.modified > 0 ? DateUtils.formatSameDayTime(d.modified, System.currentTimeMillis(),
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).toString() : "";
        if (d.isDir() || d.size < 0) return when;
        return (when.isEmpty() ? "" : when + " · ") + Formatter.formatShortFileSize(this, d.size);
    }

    private void open(Docs.Doc d) {
        if (d.isDir()) {
            stack.add(new String[] { d.id, d.name });
            list.slideNext(1);
            pager.setPage(0);
            load();
            return;
        }
        view(d, false);
    }

    private void view(Docs.Doc d, boolean choose) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(d.uri, d.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivity(choose ? Intent.createChooser(i, "Open with") : i);
        } catch (ActivityNotFoundException e) {
            toast("No app can open " + d.name);
        }
    }

    private void docMenu(View anchor, Docs.Doc d) {
        PopMenu m = new PopMenu(this, anchor);
        if (!d.isDir()) m.add(0, 1, 0, "Open with…");
        if (!d.isDir()) m.add(0, 2, 1, "Download to the device");
        if (d.can(Document.FLAG_SUPPORTS_RENAME)) m.add(0, 3, 2, "Rename");
        if (d.can(Document.FLAG_SUPPORTS_DELETE)) m.add(0, 4, 3, "Delete");
        m.add(0, 5, 4, "Details");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: view(d, true); break;
                case 2: download(d); break;
                case 3: rename(d); break;
                case 4: delete(d); break;
                default: details(d);
            }
            return true;
        });
        m.show();
    }

    private void details(Docs.Doc d) {
        Card c = new Card(this, d.name);
        c.row("In", top()[1]);
        c.row("Type", d.isDir() ? "Folder" : d.mime);
        if (d.size >= 0 && !d.isDir()) c.row("Size", Formatter.formatFileSize(this, d.size));
        if (d.modified > 0) c.row("Modified", DateUtils.formatDateTime(this, d.modified, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME));
        c.row("Link", d.uri.toString());
        c.show(list);
    }

    // --- changes -------------------------------------------------------------------------

    private interface Work {
        void run() throws Exception;
    }

    /** Work on the provider off the main thread, said in the status line; then read again. */
    private void work(String doing, Work w, String done) {
        status.setText(doing + "…");
        status.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String err = null;
            try {
                w.run();
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String e = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                status.setVisibility(View.GONE);
                toast(e != null ? e : done);
                load();
            });
        }, "location-work").start();
    }

    private void download(Docs.Doc d) {
        File into = new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
        File to = FileOps.unique(into, d.name);
        work("Downloading " + d.name, () -> {
            into.mkdirs();
            Docs.copy(getContentResolver().openInputStream(d.uri), new FileOutputStream(to));
            android.media.MediaScannerConnection.scanFile(this, new String[] { to.getPath() }, null, null);
        }, "Saved to Download/" + to.getName());
    }

    private void rename(Docs.Doc d) {
        new Prompt(this, "Rename").field("Name", d.name).ok("Rename", v -> {
            if (v[0].isEmpty() || v[0].equals(d.name)) return;
            work("Renaming", () -> {
                if (DocumentsContract.renameDocument(getContentResolver(), d.uri, v[0]) == null) {
                    throw new IllegalStateException("could not rename " + d.name);
                }
            }, "Renamed");
        }).show(list);
    }

    private void delete(Docs.Doc d) {
        new AlertDialog.Builder(this)
                .setMessage("Delete \"" + d.name + "\" from " + stack.get(0)[1]
                        + "? It does not go to the Trash; this cannot be undone.")
                .setPositiveButton("Delete", (x, w) -> work("Deleting", () -> {
                    if (!DocumentsContract.deleteDocument(getContentResolver(), d.uri)) {
                        throw new IllegalStateException("could not delete " + d.name);
                    }
                }, "Deleted"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addMenu(View anchor) {
        PopMenu m = new PopMenu(this, anchor);
        m.add(0, 1, 0, "New folder");
        m.add(0, 2, 1, "Upload files here…");
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                new Prompt(this, "New folder").field("Folder name", null).ok("Create", v -> {
                    if (v[0].isEmpty()) return;
                    work("Creating", () -> {
                        if (DocumentsContract.createDocument(getContentResolver(), folderUri(),
                                Document.MIME_TYPE_DIR, v[0]) == null) {
                            throw new IllegalStateException("could not create " + v[0]);
                        }
                    }, "Created " + v[0]);
                }).show(list);
            } else {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(pick, REQUEST_UPLOAD);
                } catch (ActivityNotFoundException e) {
                    toast("No picker on this device");
                }
            }
            return true;
        });
        m.show();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQUEST_UPLOAD || result != RESULT_OK || data == null) return;
        final List<Uri> picked = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) picked.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) picked.add(data.getData());
        if (picked.isEmpty()) return;
        final Uri folder = folderUri();
        work("Uploading " + picked.size() + (picked.size() == 1 ? " file" : " files"), () -> {
            for (Uri u : picked) Docs.upload(this, u, folder);
        }, picked.size() == 1 ? "Uploaded" : picked.size() + " files uploaded");
    }

    private void menu(View anchor) {
        PopMenu m = new PopMenu(this, anchor);
        m.add(0, 1, 0, "Refresh");
        if (stack.size() > 1) m.add(0, 2, 1, "Back to " + stack.get(0)[1]);
        m.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 2) {
                while (stack.size() > 1) stack.remove(stack.size() - 1);
                pager.setPage(0);
            }
            load();
            return true;
        });
        m.show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
