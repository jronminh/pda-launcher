package org.pda.launcher.viewers;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.pda.launcher.Header;
import org.pda.launcher.PopMenu;
import org.pda.launcher.R;
import org.pda.launcher.Ui;

import java.io.File;

/**
 * The built-in audio player's screen: the folder's audio files by name, from
 * the one tapped, one after another, played by {@link PlaybackService} so the
 * sound goes on when this screen is closed (Back) or another app is on top;
 * the notification brings it back, and its ✕ (or ⋮ › Stop) ends it. Cover
 * (embedded, else a plain note), title and artist (the tags, else the file
 * name), a bar to seek, previous, play/pause, next; ⋮ also has the sleep timer.
 */
public class AudioPlayerActivity extends Activity implements PlaybackService.Listener {

    /** From the notification or the session: show what plays, open nothing new. */
    static final String EXTRA_SHOW = "show";

    private final Handler main = new Handler(Looper.getMainLooper());
    private PlaybackService svc;
    private Viewers.Source pending;   // what was opened, handed to the service once bound
    private Header header;
    private ImageView cover;
    private TextView title, artist, pos, len, count;
    private SeekBar seek;
    private ImageView play;
    private boolean seeking;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((PlaybackService.Local) b).service();
            svc.listen(AudioPlayerActivity.this);
            take();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            svc = null;
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (svc != null && !seeking) {
                seek.setProgress(svc.position());
                pos.setText(time(svc.position()));
                count.setText(countLine());   // the sleep timer's minutes go down
            }
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        Ui.applyTheme(this);
        super.onCreate(b);
        Ui.paintWindow(this);
        if (!getIntent().getBooleanExtra(EXTRA_SHOW, false)) {
            pending = Viewers.sourceOf(this);
            if (pending == null) return;
        }
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        header = new Header(this, "")
                .back(this::finish)
                .action(Ui.action(this, R.drawable.ic_more, "More", this::menu));
        header.showBack(true);
        root.addView(header.view());
        root.addView(Ui.divider(this));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ui.dp(this, 24);
        body.setPadding(p, p, p, p);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cover.setAdjustViewBounds(true);
        body.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        title = text(20, Ui.TEXT);
        title.setPadding(0, Ui.dp(this, 20), 0, 0);
        body.addView(title);
        artist = text(15, Ui.DIM);
        body.addView(artist);

        seek = new SeekBar(this);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.TEXT));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.TEXT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));
        seek.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { if (user) pos.setText(time(v)); }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });
        body.addView(seek, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout times = new LinearLayout(this);
        pos = text(13, Ui.DIM);
        pos.setGravity(Gravity.START);
        len = text(13, Ui.DIM);
        len.setGravity(Gravity.END);
        times.addView(pos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(len, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(times, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        keys.setPadding(0, Ui.dp(this, 12), 0, 0);
        keys.addView(big(Ui.action(this, R.drawable.ic_prev, "Previous", v -> { if (svc != null) svc.prev(); })));
        play = big(Ui.action(this, R.drawable.ic_pause, "Play or pause", v -> { if (svc != null) svc.toggle(); }));
        keys.addView(play);
        keys.addView(big(Ui.action(this, R.drawable.ic_next, "Next", v -> { if (svc != null) svc.next(); })));
        body.addView(keys);
        count = text(13, Ui.DIM);
        body.addView(count);

        setContentView(root);
        bindService(new Intent(this, PlaybackService.class), conn, BIND_AUTO_CREATE);
        main.post(tick);
    }

    /** Another audio file tapped while this screen is up: that folder, from that file. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SHOW, false)) return;
        Viewers.Source s = Viewers.sourceOf(this);
        if (s == null) return;
        pending = s;
        if (svc != null) take();
    }

    /** What was opened, to the service; the track already playing, tapped again, plays on. */
    private void take() {
        Viewers.Source s = pending;
        pending = null;
        if (s != null && !svc.holds(s.file, s.file == null ? s.uri : null)) {
            java.util.List<File> files = Viewers.siblings(s);
            File f = s.file != null ? s.file : new File(s.name);
            svc.play(files, Math.max(0, files.indexOf(f)), s.file == null ? s.uri : null);
        }
        if (!svc.loaded()) {   // the notification of a queue since closed
            finish();
            return;
        }
        changed();
    }

    /** The service changed: the track, its state, the timer. */
    @Override
    public void changed() {
        if (svc == null || isDestroyed()) return;
        if (!svc.loaded()) {   // stopped (✕ in the notification)
            finish();
            return;
        }
        File f = svc.current();
        header.setTitle(svc.link() != null || f.getParentFile() == null ? "" : f.getParentFile().getName());
        title.setText(svc.title);
        artist.setText(svc.artist);
        if (svc.art != null) {
            cover.setImageBitmap(svc.art);
            cover.setImageTintList(null);
            cover.setPadding(0, 0, 0, 0);
        } else {
            cover.setImageResource(R.drawable.ic_audio);
            cover.setImageTintList(ColorStateList.valueOf(Ui.LINE));
            int p = Ui.dp(this, 48);
            cover.setPadding(p, p, p, p);
        }
        seek.setMax(svc.duration());
        len.setText(time(svc.duration()));
        if (!seeking) {
            seek.setProgress(svc.position());
            pos.setText(time(svc.position()));
        }
        count.setText(countLine());
        play.setImageResource(svc.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /** "3 / 12", and the sleep timer when it is set. */
    private String countLine() {
        String n = svc.count() > 1 ? (svc.index() + 1) + " / " + svc.count() : "";
        long left = svc.sleepLeft();
        String z = left == PlaybackService.SLEEP_END_OF_TRACK ? "Sleep at the end of this track"
                : left > 0 ? "Sleep in " + DateUtils.formatElapsedTime((left + 999) / 1000) : "";
        return n.isEmpty() ? z : z.isEmpty() ? n : n + "  ·  " + z;
    }

    private void menu(View anchor) {
        if (svc == null || !svc.loaded()) return;
        File f = svc.current();
        Viewers.Source s = svc.link() != null ? new Viewers.Source(null, svc.link(), f.getName(), null, null)
                : new Viewers.Source(f, null, f.getName(), null, null);
        Viewers.menu(this, anchor, s, f, new String[] { "Sleep timer", "Stop" }, i -> {
            if (i == 0) sleepMenu(anchor);
            else svc.stop();
        });
    }

    private void sleepMenu(View anchor) {
        final long[] ms = { 0, 15 * 60_000L, 30 * 60_000L, 45 * 60_000L, 60 * 60_000L, PlaybackService.SLEEP_END_OF_TRACK };
        final String[] names = { "Off", "In 15 minutes", "In 30 minutes", "In 45 minutes", "In 1 hour", "At the end of this track" };
        long left = svc.sleepLeft();
        PopMenu m = new PopMenu(this, anchor);
        for (int i = 0; i < ms.length; i++) {
            m.add(0, i, i, names[i]).setChecked(i == 0 ? left == 0 : ms[i] == PlaybackService.SLEEP_END_OF_TRACK
                    && left == PlaybackService.SLEEP_END_OF_TRACK);
        }
        m.setOnMenuItemClickListener(item -> {
            if (svc != null) svc.setSleep(ms[item.getItemId()]);
            return true;
        });
        m.show();
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private ImageView big(ImageView v) {
        int s = Ui.dp(this, 72);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        return v;
    }

    private static String time(int ms) {
        return DateUtils.formatElapsedTime(ms / 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(tick);
        if (svc != null) svc.unlisten(this);
        if (header != null) unbindService(conn);
    }
}
