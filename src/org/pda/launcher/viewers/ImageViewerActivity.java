package org.pda.launcher.viewers;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.pda.launcher.Config;
import org.pda.launcher.Header;
import org.pda.launcher.R;
import org.pda.launcher.Ui;

import java.io.File;
import java.util.List;

/**
 * The built-in image viewer: one image fitted to the screen, black around it;
 * pinch or double-tap to zoom, a horizontal swipe for the next or the previous
 * image of the folder (by name). A tap shows or hides the bar (name, n/count,
 * ⋮ with Open with… and Details). Decoded in the background at most about the
 * screen's size, with the photo's own rotation (EXIF) applied.
 */
public class ImageViewerActivity extends Activity {

    private List<File> files;
    private Viewers.Source src;   // a link alone when src.file is null: one image, read through it
    private int at;
    private ZoomImageView image;
    private Header header;
    private LinearLayout bar;
    private int loading;   // which load is current, so a slow decode never shows late

    @Override
    protected void onCreate(Bundle b) {
        org.pda.launcher.Ui.applyTheme(this);
        super.onCreate(b);
        org.pda.launcher.Ui.paintWindow(this);
        src = Viewers.sourceOf(this);
        if (src == null) return;
        File f = src.file != null ? src.file : new File(src.name);
        files = src.file != null ? Viewers.siblings(f) : new java.util.ArrayList<>(java.util.Collections.singletonList(f));
        at = files.indexOf(f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        image = new ZoomImageView(this);
        image.setPages(new ZoomImageView.Pages() {
            @Override public void turn(int dir) { go(at + dir); }
            @Override public void tap() { bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
        });
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        header = new Header(this, "")
                .back(this::finish)
                .action(Ui.action(this, R.drawable.ic_more, "More", v -> Viewers.menu(this, v, src, files.get(at))));
        header.showBack(true);
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.addView(header.view());
        bar.addView(Ui.divider(this));
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        go(at);
    }

    private void go(int i) {
        if (i < 0 || i >= files.size()) return;
        at = i;
        final File f = files.get(i);
        header.setTitle(f.getName() + (files.size() > 1 ? "  " + (i + 1) + "/" + files.size() : ""));
        final int ticket = ++loading;
        final int max = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 2;
        new Thread(() -> {
            Bitmap bmp = src.file != null ? decode(f, max) : decodeLink(max);
            runOnUiThread(() -> {
                if (ticket != loading || isDestroyed()) return;
                if (bmp == null) {
                    image.show(null);
                    header.setTitle(f.getName() + " — cannot be shown");
                } else {
                    image.show(bmp);
                }
            });
        }, "image-viewer").start();
    }

    private Bitmap decodeLink(int max) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), src.uri), (d, info, s) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    int longer = Math.max(w, h);
                    if (longer > max) d.setTargetSize(w * max / longer, h * max / longer);
                });
            }
            try (java.io.InputStream in = getContentResolver().openInputStream(src.uri)) {
                return android.graphics.BitmapFactory.decodeStream(in);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** The image at most {@code max} pixels on its longer side, rotated as the photo says. */
    private static Bitmap decode(File f, int max) {
        if (android.os.Build.VERSION.SDK_INT < 28) {   // no ImageDecoder: sampled, not rotated
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            o.inJustDecodeBounds = false;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize == 0 ? 1 : o.inSampleSize) > max) {
                o.inSampleSize = o.inSampleSize == 0 ? 2 : o.inSampleSize * 2;
            }
            try {
                return android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            } catch (OutOfMemoryError e) {
                return null;
            }
        }
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (d, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int longer = Math.max(w, h);
                if (longer > max) d.setTargetSize(w * max / longer, h * max / longer);
            });
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
