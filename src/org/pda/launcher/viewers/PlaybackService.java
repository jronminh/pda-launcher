package org.pda.launcher.viewers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.pda.launcher.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * What plays for the audio player, so it plays on when the player is closed or
 * the launcher's process is in the background: the queue (a folder's audio
 * files, or one link another app handed over), one MediaPlayer, audio focus, a
 * media session (a headset's buttons, the lock screen) and a notification with
 * previous, play/pause, next and close, after AOSP Music's MediaPlaybackService.
 * Playing, it is a foreground service; paused, the notification stays and can
 * be swiped away; closed (✕, or Stop in the player) it goes. Headphones pulled
 * out pause it. A sleep timer pauses it after some minutes, or at the end of
 * the track.
 */
public class PlaybackService extends Service {

    static final String ACTION_TOGGLE = "org.pda.launcher.audio.TOGGLE";
    static final String ACTION_NEXT = "org.pda.launcher.audio.NEXT";
    static final String ACTION_PREV = "org.pda.launcher.audio.PREV";
    static final String ACTION_STOP = "org.pda.launcher.audio.STOP";
    private static final String CHANNEL = "playback";
    private static final int NOTE = 1;

    /** The sleep timer's "at the end of this track". */
    static final long SLEEP_END_OF_TRACK = -2;

    /** What the player shows: called on the main thread after any change. */
    interface Listener {
        void changed();
    }

    final class Local extends Binder {
        PlaybackService service() {
            return PlaybackService.this;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private List<File> files = new ArrayList<>();
    private Uri link;            // a link alone (another app's file): the queue is one entry, named by files
    private int at = -1;
    private MediaPlayer player;
    private boolean prepared;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private MediaSession session;
    private boolean foreground;
    private long sleepAt;        // when the sleep timer pauses (elapsed realtime), 0 off, or SLEEP_END_OF_TRACK

    // The current track's tags.
    String title = "", artist = "";
    Bitmap art;

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (playing()) pause();
        }
    };

    private final Runnable sleep = () -> {
        sleepAt = 0;
        if (playing()) pause();
        else changed();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        audio = getSystemService(AudioManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Playing audio", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        session = new MediaSession(this, "PDA Launcher audio");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onStop() { stop(); }
            @Override public void onSeekTo(long ms) { seekTo((int) ms); }
        });
        Intent open = new Intent(this, AudioPlayerActivity.class).putExtra(AudioPlayerActivity.EXTRA_SHOW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public IBinder onBind(Intent i) {
        return new Local();
    }

    /** The notification's buttons (and a start from the player, with no action). */
    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String a = i != null ? i.getAction() : null;
        if (a != null && at < 0) {   // a button left from a process since killed: nothing to play
            stop();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(a)) toggle();
        else if (ACTION_NEXT.equals(a)) next();
        else if (ACTION_PREV.equals(a)) prev();
        else if (ACTION_STOP.equals(a)) stop();
        return START_NOT_STICKY;   // a queue is not kept across a killed process
    }

    void listen(Listener l) {
        listeners.add(l);
    }

    void unlisten(Listener l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.changed();
    }

    // --- the queue --------------------------------------------------------------------------

    /** Play {@code queue} from {@code index}; {@code uri}: a link alone, the queue then one name. */
    void play(List<File> queue, int index, Uri uri) {
        startService(new Intent(this, PlaybackService.class));   // lives on when the player unbinds
        files = new ArrayList<>(queue);
        link = uri;
        go(index, true);
    }

    /** Whether {@code f} (or {@code uri}) is what is loaded now. */
    boolean holds(File f, Uri uri) {
        if (at < 0) return false;
        if (uri != null) return uri.equals(link);
        return link == null && f != null && f.equals(files.get(at));
    }

    boolean loaded() {
        return at >= 0;
    }

    File current() {
        return at >= 0 ? files.get(at) : null;
    }

    Uri link() {
        return link;
    }

    int index() {
        return at;
    }

    int count() {
        return files.size();
    }

    private void go(int i, boolean start) {
        if (i < 0 || i >= files.size()) return;
        at = i;
        release();
        File f = files.get(i);
        tags(f);
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            if (link == null) player.setDataSource(f.getPath());
            else player.setDataSource(this, link);
            player.prepare();
            prepared = true;
            player.setOnCompletionListener(mp -> {
                if (sleepAt == SLEEP_END_OF_TRACK) {
                    sleepAt = 0;
                    show();
                } else if (at < files.size() - 1) {
                    go(at + 1, true);
                } else {
                    show();
                }
            });
            if (start && takeFocus()) player.start();
        } catch (Exception e) {
            release();
            artist = "Cannot be played";
        }
        show();
    }

    /** Title, artist and cover from the file's tags; the name when it has none. */
    private void tags(File f) {
        String t = null, a = null;
        Bitmap pic = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (link == null) r.setDataSource(f.getPath());
            else r.setDataSource(this, link);
            t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            byte[] b = r.getEmbeddedPicture();
            if (b != null) pic = BitmapFactory.decodeByteArray(b, 0, b.length);
        } catch (RuntimeException | OutOfMemoryError ignore) {
        } finally {
            try { r.release(); } catch (Exception ignore) {}
        }
        title = t != null && !t.isEmpty() ? t : f.getName();
        artist = a != null ? a : "";
        art = pic;
    }

    // --- playing ----------------------------------------------------------------------------

    boolean playing() {
        try {
            return player != null && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    int position() {
        try {
            return prepared ? player.getCurrentPosition() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    int duration() {
        try {
            return prepared ? player.getDuration() : 0;
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    void toggle() {
        if (playing()) pause();
        else play();
    }

    void play() {
        if (!prepared) {
            go(at, true);
            return;
        }
        if (takeFocus()) player.start();
        show();
    }

    void pause() {
        if (prepared && playing()) player.pause();
        show();
    }

    void next() {
        go(at + 1, true);
    }

    /** Back to this one's start after 3 s of it, else the one before. */
    void prev() {
        if (prepared && position() > 3000) seekTo(0);
        else go(at - 1, true);
    }

    void seekTo(int ms) {
        if (prepared) player.seekTo(ms);
        show();
    }

    /** Close: the sound, the notification, the service (once the player lets it go). */
    void stop() {
        release();
        at = -1;
        files.clear();
        link = null;
        setSleep(0);
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        session.setActive(false);
        stopForeground(true);
        foreground = false;
        getSystemService(NotificationManager.class).cancel(NOTE);
        changed();
        stopSelf();
    }

    private boolean takeFocus() {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change < 0 && playing()) pause();
                    }, main)
                    .build();
        }
        return audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void release() {
        prepared = false;
        if (player == null) return;
        try {
            player.release();
        } catch (RuntimeException ignore) {}
        player = null;
    }

    // --- the sleep timer --------------------------------------------------------------------

    /** Pause in {@code ms} (0: off; {@link #SLEEP_END_OF_TRACK}: when this track ends). */
    void setSleep(long ms) {
        main.removeCallbacks(sleep);
        if (ms > 0) {
            sleepAt = SystemClock.elapsedRealtime() + ms;
            main.postDelayed(sleep, ms);
        } else {
            sleepAt = ms == SLEEP_END_OF_TRACK ? SLEEP_END_OF_TRACK : 0;
        }
        changed();
    }

    /** The sleep timer's ms left, 0 off, or {@link #SLEEP_END_OF_TRACK}. */
    long sleepLeft() {
        if (sleepAt <= 0) return sleepAt;
        return Math.max(0, sleepAt - SystemClock.elapsedRealtime());
    }

    // --- the session and the notification ---------------------------------------------------

    /** The session, the notification and the player's screen, after any change. */
    private void show() {
        boolean on = playing();
        session.setActive(at >= 0);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO)
                .setState(on ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position(), on ? 1f : 0f)
                .build());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());

        if (at >= 0) {
            Notification n = notification(on);
            if (on) {
                startForeground(NOTE, n);
                foreground = true;
            } else {
                if (foreground) stopForeground(false);   // paused: it may be swiped away
                foreground = false;
                getSystemService(NotificationManager.class).notify(NOTE, n);
            }
        }
        changed();
    }

    private Notification notification(boolean on) {
        Intent open = new Intent(this, AudioPlayerActivity.class).putExtra(AudioPlayerActivity.EXTRA_SHOW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_audio)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(art)
                .setOngoing(on)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(button(ACTION_STOP))
                .addAction(action(R.drawable.ic_prev, "Previous", ACTION_PREV))
                .addAction(action(on ? R.drawable.ic_pause : R.drawable.ic_play, on ? "Pause" : "Play", ACTION_TOGGLE))
                .addAction(action(R.drawable.ic_next, "Next", ACTION_NEXT))
                .addAction(action(R.drawable.ic_close, "Close", ACTION_STOP))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent button(String action) {
        return PendingIntent.getService(this, action.hashCode(),
                new Intent(this, PlaybackService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action action(int icon, String label, String action) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, button(action)).build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(sleep);
        release();
        if (focus != null) audio.abandonAudioFocusRequest(focus);
        try {
            unregisterReceiver(noisy);
        } catch (RuntimeException ignore) {}
        session.release();
        getSystemService(NotificationManager.class).cancel(NOTE);
    }
}
