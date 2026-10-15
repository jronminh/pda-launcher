package org.pda.launcher.viewers;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.pda.launcher.Config;
import org.pda.launcher.Header;
import org.pda.launcher.R;
import org.pda.launcher.Ui;

import java.io.File;
import java.io.IOException;

/**
 * The built-in PDF reader: Android's own PdfRenderer, one page at a time fitted
 * to the screen, turned like the launcher's lists (‹ n / count › at the bottom,
 * or a horizontal swipe); pinch or double-tap to read closer. A page is drawn
 * at twice the screen's width so zooming in stays sharp. The page last read is
 * remembered per file while the launcher lives. Password-protected files go to
 * the system's app.
 */
public class PdfViewerActivity extends Activity {

    private static final java.util.Map<String, Integer> LAST = new java.util.HashMap<>();

    private File file;
    private Viewers.Source src;
    private String key;   // the file's path, or the link: where the last page read is kept
    private ParcelFileDescriptor fd;
    private PdfRenderer pdf;
    private ZoomImageView image;
    private TextView count;
    private View prev, next;
    private LinearLayout top, bottom;
    private int page;

    @Override
    protected void onCreate(Bundle b) {
        org.pda.launcher.Ui.applyTheme(this);
        super.onCreate(b);
        org.pda.launcher.Ui.paintWindow(this);
        src = Viewers.sourceOf(this);
        if (src == null) return;
        file = src.file != null ? src.file : new File(src.name);
        key = src.file != null ? file.getPath() : src.uri.toString();
        try {
            fd = src.file != null ? ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    : getContentResolver().openFileDescriptor(src.uri, "r");
            if (fd == null) throw new IOException("no descriptor");
            pdf = new PdfRenderer(fd);
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            Toast.makeText(this, file.getName() + " cannot be read here (protected, or not a PDF)", Toast.LENGTH_LONG).show();
            if (src.file != null) org.pda.launcher.files.FileDetails.openWith(this, file);
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        image = new ZoomImageView(this);
        image.setPages(new ZoomImageView.Pages() {
            @Override public void turn(int dir) { go(page + dir); }
            @Override public void tap() {
                int vis = top.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
                top.setVisibility(vis);
                bottom.setVisibility(vis);
            }
        });
        root.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Header header = new Header(this, file.getName())
                .back(this::finish)
                .action(Ui.action(this, R.drawable.ic_more, "More", v -> Viewers.menu(this, v, src, file)));
        header.showBack(true);
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.addView(header.view());
        top.addView(Ui.divider(this));
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackgroundColor(Ui.BG);
        prev = Ui.action(this, R.drawable.ic_back, "Previous page", v -> go(page - 1));
        next = Ui.action(this, R.drawable.ic_forward, "Next page", v -> go(page + 1));
        count = new TextView(this);
        count.setTextColor(Ui.DIM);
        count.setTextSize(18);
        count.setGravity(Gravity.CENTER);
        count.setMinWidth(Ui.dp(this, 120));
        bottom.addView(prev);
        bottom.addView(count);
        bottom.addView(next);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        lp.gravity = Gravity.BOTTOM;
        root.addView(bottom, lp);
        setContentView(root);

        Integer last = LAST.get(key);
        go(last == null ? 0 : last);
    }

    private void go(int i) {
        if (pdf == null || i < 0 || i >= pdf.getPageCount()) return;
        page = i;
        LAST.put(key, i);
        count.setText((i + 1) + " / " + pdf.getPageCount());
        prev.setAlpha(i > 0 ? 1f : 0.3f);
        next.setAlpha(i < pdf.getPageCount() - 1 ? 1f : 0.3f);
        try (PdfRenderer.Page p = pdf.openPage(i)) {
            int w = getResources().getDisplayMetrics().widthPixels * 2;
            int h = (int) ((long) w * p.getHeight() / Math.max(1, p.getWidth()));
            if ((long) w * h > 40_000_000L) {   // a very long page: keep the bitmap under ~160 MB
                float k = (float) Math.sqrt(40_000_000.0 / ((long) w * h));
                w = (int) (w * k);
                h = (int) (h * k);
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);   // a page is paper: transparent parts are white
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            image.show(bmp);
        } catch (RuntimeException | OutOfMemoryError e) {
            Toast.makeText(this, "Page " + (i + 1) + " cannot be shown", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (pdf != null) pdf.close();
            if (fd != null) fd.close();
        } catch (IOException | RuntimeException ignore) {}
    }
}
