package org.pda.launcher.viewers;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import org.pda.launcher.Header;
import org.pda.launcher.R;
import org.pda.launcher.Ui;

import java.io.File;
import java.util.List;

/**
 * The built-in video player: one video fitted to the screen on black (VideoView,
 * so whatever the device's codecs play), the folder's videos by name with a
 * horizontal swipe or at the end of one. A tap shows or hides the bars: the
 * name, n/count and ⋮ (Share, Open with…, Open as…, Details) above; play/pause,
 * the time and a bar to seek below. The screen stays on while it plays; leaving
 * it pauses, and it comes back where it was.
 */
public class VideoPlayerActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private List<File> files;
    private Viewers.Source src;   // a link alone when src.file is null: one video, played through it
    private int at;
    private VideoView video;
    private Header header;
    private LinearLayout top, bottom;
    private ImageView play;
    private SeekBar seek;
    private TextView pos, len;
    private boolean seeking, prepared;
    private boolean autoplay;    // start once prepared: a video opened, not one come back to
    private int resumeAt = -1;   // where it was left: VideoView opens it again from the start

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (prepared && !seeking) {
                seek.setProgress(video.getCurrentPosition());
                pos.setText(time(video.getCurrentPosition()));
            }
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        Ui.applyTheme(this);
        super.onCreate(b);
        Ui.paintWindow(this);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        src = Viewers.sourceOf(this);
        if (src == null) return;
        File f = src.file != null ? src.file : new File(src.name);
        files = Viewers.siblings(src);
        at = files.indexOf(f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(video, vlp);
        video.setOnPreparedListener(mp -> {
            prepared = true;
            seek.setMax(video.getDuration());
            len.setText(time(video.getDuration()));
            if (resumeAt >= 0) {
                video.seekTo(resumeAt);
                resumeAt = -1;
            }
            if (autoplay) video.start();
            showPlaying();
        });
        video.setOnCompletionListener(mp -> {
            if (at < files.size() - 1) go(at + 1);
            else showPlaying();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            prepared = false;
            header.setTitle(files.get(at).getName() + " — cannot be played");
            showPlaying();
            return true;   // said in the title: no system dialog
        });

        // A tap shows or hides the bars; a swipe across goes to the next or the previous video.
        GestureDetector g = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                int vis = top.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
                top.setVisibility(vis);
                bottom.setVisibility(vis);
                return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vx) < Math.abs(vy) * 2 || Math.abs(b.getX() - a.getX()) < Ui.dp(VideoPlayerActivity.this, 64)) return false;
                go(at + (vx < 0 ? 1 : -1));
                return true;
            }
        });
        View touch = new View(this);
        touch.setOnTouchListener((v, e) -> g.onTouchEvent(e));
        root.addView(touch, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        header = new Header(this, "")
                .back(this::finish)
                .action(Ui.action(this, R.drawable.ic_more, "More", v ->
                        Viewers.menu(this, v, src, files.get(at), new String[] { "Share" },
                                i -> Viewers.share(this, src, files.get(at)))));
        header.showBack(true);
        top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackgroundColor(Ui.BG);
        top.addView(header.view());
        top.addView(Ui.divider(this));
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackgroundColor(Ui.BG);
        bottom.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 12), 0);
        play = Ui.action(this, R.drawable.ic_pause, "Play or pause", v -> toggle());
        bottom.addView(play);
        pos = clock(13);
        bottom.addView(pos);
        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.TEXT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) pos.setText(time(v)); }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (prepared) video.seekTo(s.getProgress());
            }
        });
        bottom.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        len = clock(13);
        bottom.addView(len);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56));
        blp.gravity = Gravity.BOTTOM;
        root.addView(bottom, blp);
        setContentView(root);
        go(at);
        main.post(tick);
    }

    private TextView clock(int sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Ui.DIM);
        t.setText(time(0));
        return t;
    }

    private static String time(int ms) {
        return DateUtils.formatElapsedTime(ms / 1000);
    }

    private void go(int i) {
        if (i < 0 || i >= files.size()) return;
        at = i;
        prepared = false;
        autoplay = true;
        resumeAt = -1;
        File f = files.get(i);
        header.setTitle(f.getName() + (files.size() > 1 ? "  " + (i + 1) + "/" + files.size() : ""));
        seek.setProgress(0);
        pos.setText(time(0));
        len.setText(time(0));
        if (src.file != null) video.setVideoPath(f.getPath());
        else video.setVideoURI(src.uri);
    }

    private void toggle() {
        if (!prepared) {
            go(at);
            return;
        }
        if (video.isPlaying()) video.pause();
        else video.start();
        showPlaying();
    }

    private void showPlaying() {
        boolean on = prepared && video.isPlaying();
        play.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
        video.setKeepScreenOn(on);
    }

    /** Left (Home, another app): paused where it is; it comes back there, paused. */
    @Override
    protected void onPause() {
        super.onPause();
        if (prepared) {
            resumeAt = video.getCurrentPosition();
            autoplay = false;
            video.pause();
            showPlaying();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        prepared = false;   // the surface goes, and VideoView's player with it
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(tick);
        if (video != null) video.stopPlayback();
    }
}
