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
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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
import org.pda.launcher.remote.RemoteFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A location of Files, browsed in the Files tab's look: another app's folder
 * (Storage Access Framework) or a server (SFTP, FTP, FTPS), all through
 * {@link RemoteFs}. The header (‹ up, the folder's name, + and ⋮), the paged
 * list, the status line while working. Tap a folder to go in; a file opens
 * where Files would open it (a server's is fetched first into the app's cache;
 * another app's goes to it by its link). Long-press: Open with, Download to
 * the device, Rename, Delete, Details. +: New folder, Upload files (Android's
 * picker), Paste what Files copied. Every call to the place runs on one worker
 * thread, one at a time. Nothing goes to a trash here: a delete is the place's,
 * after a question.
 */
public class LocationActivity extends Activity {

    static final String EXTRA_TREE = "tree", EXTRA_REMOTE = "remote", EXTRA_NAME = "name";
    private static final int REQUEST_UPLOAD = 7;

    private RemoteFs fs;
    private Uri tree;          // another app's folder, or null for a server
    private String remoteId;   // a server's id, or null
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String[]> stack = new ArrayList<>();   // { path, name }, the top last
    private final List<RemoteFs.Entry> entries = new ArrayList<>();
    private Header header;
    private Pager pager;
    private PagedView list;
    private TextView message, status;
    private int generation;

    @Override
    protected void onCreate(Bundle b) {
        Ui.applyTheme(this);
        super.onCreate(b);
        Ui.paintWindow(this);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String t = getIntent().getStringExtra(EXTRA_TREE);
        remoteId = getIntent().getStringExtra(EXTRA_REMOTE);
        if (t != null) {
            tree = Uri.parse(t);
            fs = new DocsFs(this, tree);
        } else if (remoteId != null && Remotes.get(this, remoteId) != null) {
            try {
                fs = Remotes.open(this, Remotes.get(this, remoteId));
            } catch (IOException e) {
                toast(e.getMessage());
                finish();
                return;
            }
        } else {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        header = new Header(this, name == null ? "" : name)
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
            @Override public int count() { return entries.size(); }
            @Override public Drawable icon(int i) { return Ui.icon(LocationActivity.this, iconOf(entries.get(i))); }
            @Override public String title(int i) { return entries.get(i).name; }
            @Override public String summary(int i) { return describe(entries.get(i)); }
            @Override public String mark(int i) { return null; }
            @Override public boolean highlight(int i) { return false; }
            @Override public boolean dim(int i) { return false; }
            @Override public void click(int i, View v) { open(entries.get(i)); }
            @Override public boolean longClick(int i, View v) { entryMenu(v, entries.get(i)); return true; }
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
        start(name);
    }

    /** The start: the server's folder (set when added) or its home; the tree's top. */
    private void start(String name) {
        message.setText("Connecting…");
        message.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            try {
                Remotes.Server s = remoteId == null ? null : Remotes.get(this, remoteId);
                String home = s != null && !s.folder.isEmpty() ? s.folder : fs.home();
                runOnUiThread(() -> {
                    stack.clear();
                    stack.add(new String[] { home, name == null ? home : name });
                    load();
                });
            } catch (IOException e) {
                runOnUiThread(() -> failed(e, () -> start(name)));
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        final RemoteFs f = fs;
        worker.execute(() -> {
            try {
                if (f != null) f.close();
            } catch (IOException ignore) {}
        });
        worker.shutdown();
    }

    private String[] top() {
        return stack.get(stack.size() - 1);
    }

    private void load() {
        if (stack.isEmpty()) return;
        header.setTitle(top()[1]);
        message.setText("Reading…");
        message.setVisibility(View.VISIBLE);
        final int gen = ++generation;
        final String dir = top()[0];
        worker.execute(() -> {
            try {
                List<RemoteFs.Entry> found = fs.list(dir);
                // Hidden names (".config") as in Files: shown only with ⋮ › Show hidden files there.
                if (!Config.getBool("files.showHidden")) found.removeIf(x -> x.name.startsWith("."));
                runOnUiThread(() -> {
                    if (gen != generation || isDestroyed()) return;
                    entries.clear();
                    entries.addAll(found);
                    message.setText("Empty");
                    message.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    list.refresh();
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (gen == generation && !isDestroyed()) failed(e, this::load);
                });
            }
        });
    }

    /** A failure said where the list would be; a changed server key asked about, then {@code retry}. */
    private void failed(IOException e, Runnable retry) {
        entries.clear();
        list.refresh();
        Throwable k = e;
        while (k != null && !(k instanceof RemoteFs.KeyChanged)) k = k.getCause();
        if (k != null && remoteId != null) {
            final RemoteFs.KeyChanged kc = (RemoteFs.KeyChanged) k;
            message.setText("The server's key changed.");
            message.setVisibility(View.VISIBLE);
            new AlertDialog.Builder(this)
                    .setTitle("The server's key changed")
                    .setMessage("It was\n" + kc.expected + "\nand is now\n" + kc.actual + "\n\nThe server was set up"
                            + " again, or another machine answers in its place. Trust the new key only if you know why.")
                    .setPositiveButton("Trust the new key", (d, w) -> {
                        Remotes.pinning(this, remoteId).remember(kc.actual);
                        retry.run();
                    })
                    .setNegativeButton("Cancel", (d, w) -> finish())
                    .show();
            return;
        }
        message.setText("Cannot reach this place:\n" + e.getMessage()
                + (tree != null ? "\n\nThe app that provides it may be closed, signed out or gone."
                        : "\n\nCheck the network, the address and the user (in Files, long-press it › Edit)."));
        message.setVisibility(View.VISIBLE);
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

    // --- entries -------------------------------------------------------------------------

    private static int iconOf(RemoteFs.Entry e) {
        return e.dir ? R.drawable.ic_folder : FilesTab.iconOf(new File(e.name));
    }

    private String describe(RemoteFs.Entry e) {
        String when = e.modified > 0 ? DateUtils.formatSameDayTime(e.modified, System.currentTimeMillis(),
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).toString() : "";
        if (e.dir || e.size < 0) return when;
        return (when.isEmpty() ? "" : when + " · ") + Formatter.formatShortFileSize(this, e.size);
    }

    private void open(RemoteFs.Entry e) {
        if (e.dir) {
            stack.add(new String[] { e.path, e.name });
            list.slideNext(1);
            pager.setPage(0);
            load();
            return;
        }
        if (fs instanceof DocsFs) {   // another app's file: to the viewer by its link, no copy
            view(((DocsFs) fs).uriOf(e.path), e.name, false);
            return;
        }
        fetch(e, f -> FilesTab.open(this, f));
    }

    private void view(Uri u, String name, boolean choose) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, FileShare.mimeOf(new File(name)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivity(choose ? Intent.createChooser(i, "Open with") : i);
        } catch (ActivityNotFoundException x) {
            toast("No app can open " + name);
        }
    }

    private interface Then {
        void run(File f);
    }

    /**
     * A server's file into the app's cache under shared storage (so FileShare
     * and the viewers can hand it on), then {@code then}. The copy stays until
     * Android clears the cache; it is not sent back when changed.
     */
    private void fetch(RemoteFs.Entry e, Then then) {
        File dir = new File(getExternalCacheDir(), "remote");
        File to = new File(dir, e.name);
        work("Fetching " + e.name, () -> {
            dir.mkdirs();
            copy(fs.read(e.path), new FileOutputStream(to), e.size);
        }, null, () -> then.run(to));
    }

    private void entryMenu(View anchor, RemoteFs.Entry e) {
        PopMenu m = new PopMenu(this, anchor);
        if (!e.dir) m.add(0, 1, 0, "Open with…");
        if (!e.dir) m.add(0, 2, 1, "Download to the device");
        m.add(0, 3, 2, "Rename");
        m.add(0, 4, 3, "Delete");
        m.add(0, 5, 4, "Details");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    if (fs instanceof DocsFs) view(((DocsFs) fs).uriOf(e.path), e.name, true);
                    else fetch(e, f -> FileDetails.openWith(this, f));
                    break;
                case 2: download(e); break;
                case 3: rename(e); break;
                case 4: delete(e); break;
                default: details(e);
            }
            return true;
        });
        m.show();
    }

    private void details(RemoteFs.Entry e) {
        Card c = new Card(this, e.name);
        c.row("In", top()[1]);
        c.row("Path", e.path);
        c.row("Type", e.dir ? "Folder" : FileShare.mimeOf(new File(e.name)));
        if (e.size >= 0 && !e.dir) c.row("Size", Formatter.formatFileSize(this, e.size));
        if (e.modified > 0) c.row("Modified", DateUtils.formatDateTime(this, e.modified, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME));
        if (remoteId != null) {
            Remotes.Server s = Remotes.get(this, remoteId);
            if (s != null) c.row("Server", s.where());
            String pin = Remotes.pinning(this, remoteId).known();
            if (pin != null) c.row("Server's key", pin);
        }
        c.show(list);
    }

    // --- changes -------------------------------------------------------------------------

    private interface Work {
        void run() throws IOException;
    }

    /** Work on the place, said in the status line; then {@code done} said and {@code after} run, else the folder read again. */
    private void work(String doing, Work w, String done, Runnable after) {
        status.setText(doing + "…");
        status.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            String err = null;
            try {
                w.run();
            } catch (IOException | RuntimeException e) {
                err = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String e = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                status.setVisibility(View.GONE);
                if (e != null) toast(e);
                else if (done != null) toast(done);
                if (e == null && after != null) after.run();
                else load();
            });
        });
    }

    /** All of a stream into another, both closed; the status line follows when the size is known. */
    private void copy(InputStream in, OutputStream out, long size) throws IOException {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[64 * 1024];
            long done = 0, said = 0;
            int n;
            while ((n = i.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                if (size > 0 && done - said > 256 * 1024) {
                    said = done;
                    final int pct = (int) (done * 100 / size);
                    runOnUiThread(() -> {
                        String t = status.getText().toString();
                        int cut = t.lastIndexOf("  ");
                        status.setText((cut > 0 ? t.substring(0, cut) : t) + "  " + pct + "%");
                    });
                }
            }
        }
    }

    private void download(RemoteFs.Entry e) {
        File into = new File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS);
        File to = FileOps.unique(into, e.name);
        work("Downloading " + e.name, () -> {
            into.mkdirs();
            copy(fs.read(e.path), new FileOutputStream(to), e.size);
            android.media.MediaScannerConnection.scanFile(this, new String[] { to.getPath() }, null, null);
        }, "Saved to Download/" + to.getName(), null);
    }

    private void rename(RemoteFs.Entry e) {
        new Prompt(this, "Rename").field("Name", e.name).ok("Rename", v -> {
            if (v[0].isEmpty() || v[0].equals(e.name)) return;
            work("Renaming", () -> fs.rename(e, v[0]), "Renamed", null);
        }).show(list);
    }

    private void delete(RemoteFs.Entry e) {
        new AlertDialog.Builder(this)
                .setMessage("Delete \"" + e.name + "\"" + (e.dir ? " and all it holds" : "") + " from "
                        + stack.get(0)[1] + "? It does not go to the Trash; this cannot be undone.")
                .setPositiveButton("Delete", (x, w) -> work("Deleting", () -> fs.delete(e), "Deleted", null))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addMenu(View anchor) {
        if (stack.isEmpty()) return;
        final List<File> clip = FilesTab.clipboard();
        PopMenu m = new PopMenu(this, anchor);
        m.add(0, 1, 0, "New folder");
        m.add(0, 2, 1, "Upload files here…");
        if (!clip.isEmpty()) m.add(0, 3, 2, "Paste here (" + clip.size() + " from Files)");
        m.setOnMenuItemClickListener(item -> {
            final String dir = top()[0];
            switch (item.getItemId()) {
                case 1:
                    new Prompt(this, "New folder").field("Folder name", null).ok("Create", v -> {
                        if (!v[0].isEmpty()) work("Creating", () -> fs.mkdir(dir, v[0]), "Created " + v[0], null);
                    }).show(list);
                    break;
                case 2: {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    try {
                        startActivityForResult(pick, REQUEST_UPLOAD);
                    } catch (ActivityNotFoundException e) {
                        toast("No picker on this device");
                    }
                    break;
                }
                default:
                    work("Uploading " + clip.size() + (clip.size() == 1 ? " item" : " items"), () -> {
                        for (File f : clip) put(f, dir);
                    }, clip.size() == 1 ? "Pasted" : clip.size() + " items pasted", null);
            }
            return true;
        });
        m.show();
    }

    /** A file or a folder of the device into the folder {@code dir} of the place (on the worker). */
    private void put(File f, String dir) throws IOException {
        if (f.isDirectory()) {
            fs.mkdir(dir, f.getName());
            String sub = null;
            for (RemoteFs.Entry e : fs.list(dir)) if (e.dir && e.name.equals(f.getName())) sub = e.path;
            if (sub == null) throw new IOException("could not make " + f.getName());
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) put(k, sub);
        } else {
            final String name = f.getName();
            runOnUiThread(() -> status.setText("Uploading " + name));
            copy(new FileInputStream(f), fs.write(dir, name), f.length());
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQUEST_UPLOAD || result != RESULT_OK || data == null) return;
        final List<Uri> picked = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) picked.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) picked.add(data.getData());
        if (picked.isEmpty() || stack.isEmpty()) return;
        final String dir = top()[0];
        work("Uploading " + picked.size() + (picked.size() == 1 ? " file" : " files"), () -> {
            for (Uri u : picked) {
                String name = Docs.name(this, u);
                if (name == null) name = u.getLastPathSegment();
                InputStream in = getContentResolver().openInputStream(u);
                if (in == null) throw new IOException("cannot read " + name);
                copy(in, fs.write(dir, name), -1);
            }
        }, picked.size() == 1 ? "Uploaded" : picked.size() + " files uploaded", null);
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
