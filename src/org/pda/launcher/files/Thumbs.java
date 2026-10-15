package org.pda.launcher.files;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small thumbnails, enough to tell files apart, not to look at: an image
 * itself, a frame of a video, the cover of an audio file, the icon of an APK,
 * and for a folder the first image in it. Made on two background threads at
 * about {@link #SIZE} pixels and kept in memory only (an LRU of a few MB); a
 * file without one keeps its line icon.
 */
final class Thumbs {

    static final int SIZE = 128;   // px, the longer side of what is decoded

    private static final Drawable NONE = new BitmapDrawable();   // "looked, there is none"
    private static final LruCache<String, Drawable> CACHE = new LruCache<String, Drawable>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Drawable d) {
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return ((BitmapDrawable) d).getBitmap().getByteCount();
            }
            return 1024;
        }
    };
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "files-thumbs");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Thumbs() {}

    /**
     * Put the thumbnail of {@code f} in {@code v} when there is one. The view
     * keeps its icon until then; a view reused for another file meanwhile is
     * left alone (its tag names the file it shows).
     */
    static void into(Context c, File f, ImageView v) {
        String key = f.getPath() + ":" + f.lastModified();
        v.setTag(key);
        Drawable d = CACHE.get(key);
        if (d == NONE) return;
        final boolean crop = !FileShare.mimeOf(f).equals("application/vnd.android.package-archive");
        if (d != null) { show(v, d, crop); return; }
        if (!worth(f)) return;
        final Context app = c.getApplicationContext();
        WORKERS.execute(() -> {
            if (!key.equals(v.getTag())) return;   // scrolled away before its turn
            Drawable made = make(app, f);
            CACHE.put(key, made != null ? made : NONE);
            if (made != null) MAIN.post(() -> { if (key.equals(v.getTag())) show(v, made, crop); });
        });
    }

    /** A picture fills its square (cropped); an app's icon is shown whole. */
    private static void show(ImageView v, Drawable d, boolean crop) {
        v.clearColorFilter();
        v.setScaleType(crop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        v.setImageDrawable(d);
    }

    /** Only the kinds that can have a thumbnail are looked at. */
    private static boolean worth(File f) {
        if (f.isDirectory()) return true;
        String m = FileShare.mimeOf(f);
        return m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/")
                || m.equals("application/vnd.android.package-archive");
    }

    private static Drawable make(Context c, File f) {
        try {
            if (f.isDirectory()) {
                File img = firstImage(f);
                return img == null ? null : bitmap(c, image(img));
            }
            String m = FileShare.mimeOf(f);
            if (m.startsWith("image/")) return bitmap(c, image(f));
            if (m.startsWith("video/")) {
                @SuppressWarnings("deprecation")
                Bitmap b = ThumbnailUtils.createVideoThumbnail(f.getPath(), MediaStore.Video.Thumbnails.MICRO_KIND);
                return bitmap(c, b);
            }
            if (m.startsWith("audio/")) return bitmap(c, cover(f));
            if (m.equals("application/vnd.android.package-archive")) return apkIcon(c, f);
        } catch (RuntimeException | OutOfMemoryError e) {
            // a broken file: no thumbnail
        }
        return null;
    }

    /** The first image directly in a folder (the first 200 entries are looked at). */
    private static File firstImage(File dir) {
        String[] names = dir.list();
        if (names == null) return null;
        for (int i = 0; i < names.length && i < 200; i++) {
            if (names[i].startsWith(".")) continue;
            File f = new File(dir, names[i]);
            if (FileShare.mimeOf(f).startsWith("image/") && f.isFile()) return f;
        }
        return null;
    }

    /** An image decoded at about SIZE px: the bounds first, then a power-of-two sample. */
    private static Bitmap image(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static Bitmap cover(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            byte[] art = r.getEmbeddedPicture();
            if (art == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(art, 0, art.length, o);
            o.inSampleSize = sample(o.outWidth, o.outHeight);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(art, 0, art.length, o);
        } finally {
            try { r.release(); } catch (Exception ignore) {}
        }
    }

    static Drawable apkIcon(Context c, File f) {
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(f.getPath(), 0);
        if (pi == null || pi.applicationInfo == null) return null;
        ApplicationInfo ai = pi.applicationInfo;
        ai.sourceDir = f.getPath();          // so the icon is read from the archive
        ai.publicSourceDir = f.getPath();
        return ai.loadIcon(pm);
    }

    private static int sample(int w, int h) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= SIZE) s *= 2;
        return s;
    }

    private static Drawable bitmap(Context c, Bitmap b) {
        if (b == null) return null;
        if (Math.max(b.getWidth(), b.getHeight()) > SIZE * 2) {
            float k = (float) SIZE / Math.max(b.getWidth(), b.getHeight());
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)),
                    Math.max(1, Math.round(b.getHeight() * k)), true);
            if (small != b) b.recycle();
            b = small;
        }
        return new BitmapDrawable(c.getResources(), b);
    }
}
