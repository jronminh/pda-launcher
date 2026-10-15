package org.pda.launcher.viewers;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.pda.launcher.Config;
import org.pda.launcher.Header;
import org.pda.launcher.R;
import org.pda.launcher.Ui;

import java.io.File;
import java.util.List;

/**
 * The built-in audio player: the folder's audio files by name, from the one
 * tapped, one after another. Cover (embedded, else a plain note), title and
 * artist (the tags, else the file name), a bar to seek, previous, play/pause,
 * next. It plays on while another app is on screen and stops when it is closed
 * (Back); another app taking the sound pauses it. No notification: a player of
 * one sitting, not a music app.
 */
public class AudioPlayerActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private List<File> files;
    private Viewers.Source src;   // a link alone when src.file is null: one track, played through it
    private int at;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private Header header;
    private ImageView cover;
    private TextView title, artist, pos, len, count;
    private SeekBar seek;
    private ImageView play;
    private boolean seeking;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null && !seeking) {
                try {
                    seek.setProgress(player.getCurrentPosition());
                    pos.setText(time(player.getCurrentPosition()));
                } catch (IllegalStateException ignore) {}
            }
            main.postDelayed(this, 500);
        }
    };

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
        audio = getSystemService(AudioManager.class);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        header = new Header(this, src.file == null || f.getParentFile() == null ? "" : f.getParentFile().getName())
                .back(this::finish)
                .action(Ui.action(this, R.drawable.ic_more, "More", v -> Viewers.menu(this, v, src, files.get(at))));
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
                if (player != null) player.seekTo(s.getProgress());
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
        keys.addView(big(Ui.action(this, R.drawable.ic_prev, "Previous", v -> prev())));
        play = big(Ui.action(this, R.drawable.ic_pause, "Play or pause", v -> toggle()));
        keys.addView(play);
        keys.addView(big(Ui.action(this, R.drawable.ic_next, "Next", v -> go(at + 1, true))));
        body.addView(keys);
        count = text(13, Ui.DIM);
        body.addView(count);

        setContentView(root);
        go(at, true);
        main.post(tick);
    }

    /** Another audio file tapped while this one plays: that folder, from that file. */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Viewers.Source s = Viewers.sourceOf(this);
        if (s == null) return;
        src = s;
        File f = s.file != null ? s.file : new File(s.name);
        files = s.file != null ? Viewers.siblings(f) : new java.util.ArrayList<>(java.util.Collections.singletonList(f));
        header.setTitle(s.file == null || f.getParentFile() == null ? "" : f.getParentFile().getName());
        go(files.indexOf(f), true);
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

    /** Previous: back to this one's start after 3 s of it, else the one before. */
    private void prev() {
        if (player != null && player.getCurrentPosition() > 3000) player.seekTo(0);
        else go(at - 1, true);
    }

    private void go(int i, boolean start) {
        if (i < 0 || i >= files.size()) return;
        at = i;
        File f = files.get(i);
        release();
        tags(f);
        count.setText(files.size() > 1 ? (i + 1) + " / " + files.size() : "");
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            if (src.file != null) player.setDataSource(f.getPath());
            else player.setDataSource(this, src.uri);
            player.prepare();
            player.setOnCompletionListener(mp -> {
                if (at < files.size() - 1) go(at + 1, true);
                else showPlaying();
            });
            seek.setMax(player.getDuration());
            seek.setProgress(0);
            len.setText(time(player.getDuration()));
            pos.setText(time(0));
            if (start && takeFocus()) player.start();
        } catch (Exception e) {
            release();
            artist.setText("Cannot be played");
        }
        showPlaying();
    }

    /** Cover, title and artist from the file's tags. */
    private void tags(File f) {
        String t = null, a = null;
        Bitmap art = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (src.file != null) r.setDataSource(f.getPath());
            else r.setDataSource(this, src.uri);
            t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) art = BitmapFactory.decodeByteArray(pic, 0, pic.length);
        } catch (RuntimeException ignore) {
        } finally {
            try { r.release(); } catch (Exception ignore) {}
        }
        title.setText(t != null && !t.isEmpty() ? t : f.getName());
        artist.setText(a != null ? a : "");
        if (art != null) {
            cover.setImageBitmap(art);
            cover.setImageTintList(null);
            cover.setPadding(0, 0, 0, 0);
        } else {
            cover.setImageResource(R.drawable.ic_audio);
            cover.setImageTintList(ColorStateList.valueOf(Ui.LINE));
            int p = Ui.dp(this, 48);
            cover.setPadding(p, p, p, p);
        }
    }

    private void toggle() {
        if (player == null) {
            go(at, true);
            return;
        }
        if (player.isPlaying()) player.pause();
        else if (takeFocus()) player.start();
        showPlaying();
    }

    private void showPlaying() {
        boolean on = player != null && player.isPlaying();
        play.setImageResource(on ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private boolean takeFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0 && player != null && player.isPlaying()) {
                            player.pause();
                            showPlaying();
                        }
                    }, main)
                    .build();
        }
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void release() {
        if (player == null) return;
        try {
            player.release();
        } catch (RuntimeException ignore) {}
        player = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(tick);
        release();
        if (focus != null) audio.abandonAudioFocusRequest(focus);
    }
}
