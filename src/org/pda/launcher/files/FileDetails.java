package org.pda.launcher.files;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Toast;

import org.pda.launcher.Card;
import org.pda.launcher.PopMenu;
import org.pda.launcher.viewers.Viewers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Details of a file or a folder, "Open with…" and "Open as…": what the Files
 * tab and the Briefcase offer for one item. The details are a {@link Card}:
 * name, path, type, size, modified, hidden, a link's target; for an image its
 * size in pixels, for audio and video their length (and title, artist), for an
 * APK its icon, package and version and whether it is installed. A folder's
 * size and what needs a file read come in the background.
 */
public final class FileDetails {

    private FileDetails() {}

    /** Android's chooser for the file's type: another app than the usual, once. */
    public static void openWith(Context c, File f) {
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileShare.linkFor(c, f), FileShare.mimeOf(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            c.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, "No app can open " + f.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * "Open as…": a file whose name says the wrong kind, or none, taken as text,
     * an image, audio, video or anything: in the built-in viewer when that kind
     * is set to built-in, else Android's chooser for the kind (as DocumentsUI's
     * type is the file's, the chooser's here is the one picked).
     */
    public static void openAs(Context c, View anchor, File f) {
        final String[] kinds = { "text", "image", "audio", "video", null };
        final String[] names = { "Text", "Image", "Audio", "Video", "Any app" };
        final String[] mimes = { "text/plain", "image/*", "audio/*", "video/*", "*/*" };
        PopMenu m = new PopMenu(c, anchor);
        for (int i = 0; i < names.length; i++) m.add(0, i, i, names[i]);
        m.setOnMenuItemClickListener(item -> {
            int i = item.getItemId();
            if (kinds[i] != null && Viewers.open(c, f, kinds[i])) return true;
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(FileShare.linkFor(c, f), mimes[i])
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                c.startActivity(Intent.createChooser(view, "Open as " + names[i].toLowerCase(java.util.Locale.ROOT))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(c, "No app opens " + names[i].toLowerCase(java.util.Locale.ROOT), Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        m.show();
    }

    public static void show(Context c, File f, View over) {
        Card card = new Card(c, f.getName().isEmpty() ? f.getPath() : f.getName());
        boolean dir = f.isDirectory();
        card.row("Path", f.getAbsolutePath());
        String mime = dir ? null : FileShare.mimeOf(f);
        card.row("Type", dir ? "Folder" : mime);
        final int size = card.row("Size", dir ? "Counting…" : sizeOf(c, f.length()));
        card.row("Modified", DateUtils.formatDateTime(c, f.lastModified(), DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_WEEKDAY));
        if (f.getName().startsWith(".")) card.row("Hidden", "Yes (the name starts with a dot)");
        try {
            if (Files.isSymbolicLink(f.toPath())) card.row("Link to", Files.readSymbolicLink(f.toPath()).toString());
        } catch (IOException | RuntimeException ignore) {}

        // What only the kind tells: a row filled in the background.
        final int extra;
        if (mime != null && (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
                || mime.equals("application/vnd.android.package-archive"))) {
            extra = card.row(mime.startsWith("image/") ? "Dimensions" : mime.startsWith("application/")
                    ? "App" : "Length", "…");
        } else {
            extra = -1;
        }
        card.show(over);

        final Context app = c.getApplicationContext();
        new Thread(() -> {
            if (dir) {
                long[] t = new long[3];   // bytes, files, folders
                walk(f, t, 0);
                card.set(size, sizeOf(app, t[0]) + " · " + t[1] + (t[1] == 1 ? " file" : " files")
                        + (t[2] > 0 ? ", " + t[2] + (t[2] == 1 ? " folder" : " folders") : ""));
            }
            if (extra >= 0) card.set(extra, kindDetails(app, f, mime));
            if ("application/vnd.android.package-archive".equals(mime)) {
                try {
                    card.icon(Thumbs.apkIcon(app, f));
                } catch (RuntimeException | OutOfMemoryError ignore) {}
            }
        }, "files-details").start();
    }

    private static String sizeOf(Context c, long bytes) {
        return Formatter.formatFileSize(c, bytes) + (bytes >= 1024 ? " (" + bytes + " bytes)" : "");
    }

    /** Count a folder (links not followed, at most 64 levels deep). */
    private static void walk(File dir, long[] t, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null || depth > 64) return;
        for (File k : kids) {
            boolean link = Files.isSymbolicLink(k.toPath());
            if (k.isDirectory() && !link) {
                t[2]++;
                walk(k, t, depth + 1);
            } else {
                t[0] += k.length();
                t[1]++;
            }
        }
    }

    private static String kindDetails(Context c, File f, String mime) {
        try {
            if (mime.startsWith("image/")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                return o.outWidth > 0 ? o.outWidth + " × " + o.outHeight + " pixels" : "unknown";
            }
            if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(f.getPath());
                    String ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    String title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                    String artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                    String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    StringBuilder sb = new StringBuilder(ms == null ? "unknown"
                            : DateUtils.formatElapsedTime(Long.parseLong(ms) / 1000));
                    if (w != null && h != null) sb.append(" · ").append(w).append(" × ").append(h);
                    if (title != null) sb.append(" · ").append(title);
                    if (artist != null) sb.append(" · ").append(artist);
                    return sb.toString();
                } finally {
                    try { r.release(); } catch (Exception ignore) {}
                }
            }
            PackageManager pm = c.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(f.getPath(), 0);
            if (pi == null) return "not a readable APK";
            String installed;
            try {
                installed = "installed: " + pm.getPackageInfo(pi.packageName, 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                installed = "not installed";
            }
            return pi.packageName + " " + pi.versionName + " · " + installed;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}
