package org.pda.launcher.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.pda.launcher.HomeActivity;

import com.google.ase.Exec;

import jackpal.androidterm.emulatorview.UpdateCallback;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The shell service. It keeps the sessions alive while the home screen comes and
 * goes, and is a foreground service while at least one session is open. A
 * session is a PTY running a shell and the {@link PtySession} that reads and
 * writes it (docs/shell.md). The Shell tab binds to the service and attaches its
 * one view to the active session.
 *
 * <p>What a session runs is the line in {@code etc/shell/command}, after the
 * profile; the system shell is Android's {@code sh} with no profile. A shell
 * starts when its view first reports a size (see {@link PtySession}). When the
 * last session ends the service leaves the foreground and the tab shows a panel
 * (with the error, if it ended at once with one). No session survives the process.
 */
public class ShellService extends Service {

    private static final String TAG = "pdash";

    /** The screen side: the Shell tab. Called on the main thread. */
    interface Ui {
        /** The sessions or a tab's name changed. */
        void sessionsChanged();

        /** Something failed that must be seen. */
        void showError(String message);
    }

    static volatile Ui ui;

    private static void sessionsChanged() {
        Ui u = ui;
        if (u != null) u.sessionsChanged();
    }
    private static final String CHANNEL = "shell";
    private static final int NOTIF_ID = 1;

    /** One session: a shell on a PTY. Touched on the main thread only, except exitCode. */
    static final class Session {
        final int id;
        final boolean system;        // Android's own sh, no profile, a coloured tab
        final String command;        // what it runs; null for the system shell
        String dir;                  // where it starts; null for home/
        PtySession term;
        FileDescriptor fd;
        int pid;
        String title = "";
        long startedAt;
        boolean ended;
        volatile int exitCode = -1;
        final CountDownLatch reaped = new CountDownLatch(1);

        Session(int id, boolean system, String command) {
            this.id = id;
            this.system = system;
            this.command = command;
        }

        /** The name for a tab: the shell's own title, else what it runs. */
        String label() {
            if (title.length() > 0) return title;
            String c = command == null ? "sh" : command.trim();
            int sp = c.indexOf(' ');
            return sp > 0 ? c.substring(0, sp) : c;
        }
    }

    /** Why the last session ended at once: shown instead of exiting. */
    static final class Failure {
        final String command;
        final boolean system;
        final int code;
        final String lastLines;

        Failure(String command, boolean system, int code, String lastLines) {
            this.command = command;
            this.system = system;
            this.code = code;
            this.lastLines = lastLines;
        }
    }

    /** Hands the Activity the service itself (same process). */
    final class LocalBinder extends Binder {
        ShellService service() { return ShellService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Session> sessions = new ArrayList<>();
    private Host host;
    private Session active;
    private Failure failure;
    private int nextId = 1;
    private String error;

    // --- what the Activity uses ---------------------------------------------------

    /** Why the service could not start, or null. */
    String error() { return error; }

    List<Session> sessions() { return new ArrayList<>(sessions); }

    Session active() { return active; }

    Failure failure() { return failure; }

    void activate(Session s) {
        if (sessions.contains(s)) active = s;
    }

    /** A tab that runs the command of {@code etc/shell/command}; null if too many. */
    Session newTab() {
        return open(Config.get("shell.command"), false);
    }

    /** A new tab that starts in {@code dir} (the Files tab's "Open shell here"). */
    Session newTabAt(String dir) {
        Session s = newTab();
        if (s != null) s.dir = dir;   // read when the shell starts, at its first size
        return s;
    }

    /** The system shell: the one that exists, else a new one. */
    Session systemTab() {
        for (Session s : sessions) if (s.system) { active = s; return s; }
        return open(null, true);
    }

    /** Start again what failed. A tab reads the command again, so a fix made in
     *  the meantime to {@code etc/shell/command} is taken. */
    Session retry() {
        Failure f = failure;
        return f != null && f.system ? open(null, true) : newTab();
    }

    /** No session is left: nothing needs the foreground any more. */
    private void idle() {
        stopForeground(true);
        sessionsChanged();
    }

    // --- lifecycle ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Tree.prepare(this);
            Config.seed(this);
            Config.init(this);
            clearViews();
            startHost();
        } catch (Throwable t) {
            fail("Could not prepare the tree: " + t);
            stopSelf();   // a bound Activity keeps it alive long enough to read error()
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;   // no persistence: a killed app starts afresh
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        for (Session s : sessions) {
            if (s.pid > 0) {
                try { Exec.kill(s.pid, 1); } catch (Throwable ignore) {}   // SIGHUP, as a closing terminal does
            }
        }
        if (host != null) host.stop();
        clearViews();
        super.onDestroy();
    }

    /** The socket the tree reaches Android through. A failure costs the capabilities, not the shell. */
    private void startHost() {
        try {
            host = new Host(this);
            host.start();
        } catch (Throwable t) {
            host = null;
            Config.log("host: could not start: " + t);
        }
    }

    private void fail(String why) {
        Log.e(TAG, why);
        error = why;
    }

    // --- sessions -------------------------------------------------------------------

    private Session open(String command, boolean system) {
        if (error != null || sessions.size() >= Config.getInt("shell.maxSessions")) return null;
        final Session s = new Session(nextId++, system, command);
        s.term = new PtySession(new SessionHost(s));
        s.term.setTitleChangedListener(new UpdateCallback() {
            @Override public void onUpdate() {
                s.title = s.term.getTitle() == null ? "" : s.term.getTitle();
                writeView(s);
                sessionsChanged();
            }
        });
        if (sessions.isEmpty()) promote();
        sessions.add(s);
        active = s;
        failure = null;
        sessionsChanged();
        return s;
    }

    private final class SessionHost implements PtySession.Host {
        private final Session s;

        SessionHost(Session s) { this.s = s; }

        @Override public boolean start(PtySession t, int cols, int rows) {
            try {
                spawn(s, cols, rows);
                return true;
            } catch (Throwable e) {
                String why = "Could not start the shell: " + e;
                Log.e(TAG, why);
                sessions.remove(s);
                if (active == s) active = sessions.isEmpty() ? null : sessions.get(0);
                if (sessions.isEmpty()) stopForeground(true);
                Ui u = ui;
                if (u != null) u.showError(why);   // the screen must show it
                return false;
            }
        }

        @Override public void resize(int cols, int rows) {
            if (s.fd != null) Exec.setPtyWindowSize(s.fd, rows, cols, 0, 0);
        }

        @Override public void onExit() {
            ended(s);
        }
    }

    /** The reader saw the end of the shell. Wait for the exit code (the reaper's), then decide. */
    private void ended(final Session s) {
        if (s.ended) return;
        s.ended = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try { s.reaped.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignore) {}
                main.post(new Runnable() {
                    @Override public void run() { finished(s); }
                });
            }
        }, "dn-ended").start();
    }

    /** A tab other than the last closes and its neighbour shows. The last one leaves
     *  the panel, with the error if it ended at once with one (docs/shell.md). */
    private void finished(Session s) {
        int at = sessions.indexOf(s);
        if (at < 0) return;
        sessions.remove(s);
        removeView(s);
        if (!sessions.isEmpty()) {
            if (active == s) active = sessions.get(Math.min(at, sessions.size() - 1));
            sessionsChanged();
            return;
        }
        active = null;
        int secs = Config.getInt("shell.failSeconds");
        long lived = System.currentTimeMillis() - s.startedAt;
        if (s.exitCode != 0 && secs > 0 && lived < secs * 1000L) {
            failure = new Failure(s.command, s.system, s.exitCode, lastLines(s.term.finalText(), 8));
        }
        idle();
    }

    private static String lastLines(String text, int n) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int found = 0;
        for (int i = lines.length - 1; i >= 0 && found < n; i--) {
            String l = lines[i].replaceAll("\\s+$", "");
            if (l.length() == 0) continue;
            sb.insert(0, l + "\n");
            found++;
        }
        return sb.toString().trim();
    }

    /** Start the shell on a PTY of the given size and give the session its ends.
     *  The environment is the minimum the app sets (docs/shell.md); the
     *  rest is the profile's, which the system shell does not read. */
    private void spawn(final Session s, int cols, int rows) throws java.io.IOException {
        File root = Tree.root(this);
        String r = root.getAbsolutePath();
        StringBuilder b = new StringBuilder();
        b.append("export HOME=").append(q(r + "/home"))
         .append(" DN_INSTDIR=").append(q(r))
         .append(" TMPDIR=").append(q(r + "/tmp"))
         .append(" ENV=").append(q(r + "/etc/mkshrc"))
         .append(" DN_HOST_SOCK=").append(q(r + "/var/host.sock"))
         .append(" DN_HOST_TOKEN=").append(q(host == null ? "" : host.token()))
         .append(" SHELL=/system/bin/sh TERM=xterm-256color")
         .append(" PATH=").append(q(r + "/usr/local/bin:" + r + "/bin:/system/bin:/system/xbin"))
         .append("; cd ").append(s.dir != null ? q(s.dir) : "\"$HOME\"");
        if (s.system) {
            b.append("; exec /system/bin/sh -i");
        } else {
            b.append("; [ -r \"$DN_INSTDIR/etc/profile\" ] && . \"$DN_INSTDIR/etc/profile\"")
             .append("; exec ").append(s.command == null || s.command.trim().length() == 0
                     ? "/system/bin/sh -i" : s.command);
        }

        int[] pids = new int[1];
        FileDescriptor fd = Exec.createSubprocess("/system/bin/sh", "-c", b.toString(), pids);
        if (fd == null || pids[0] <= 0) throw new java.io.IOException("createSubprocess failed");
        s.fd = fd;
        s.pid = pids[0];
        s.startedAt = System.currentTimeMillis();
        // The shell reads its size when it first draws a prompt, so set it now.
        Exec.setPtyWindowSize(fd, rows, cols, 0, 0);

        // Reap the shell when it ends (else it stays a zombie) and keep its exit code.
        final int child = s.pid;
        Thread reaper = new Thread(new Runnable() {
            @Override public void run() {
                s.exitCode = Exec.waitFor(child);
                s.reaped.countDown();
            }
        }, "dn-reap");
        reaper.setDaemon(true);
        reaper.start();

        s.term.setDefaultUTF8Mode(true);
        s.term.setTranscriptRows(Config.getInt("terminal.scrollback"));   // a new session only
        s.term.setTermIn(new FileInputStream(fd));
        s.term.setTermOut(new FileOutputStream(fd));
        writeView(s);
    }

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // --- var/sessions: what the tree may read about the sessions -------------------

    private File viewFile(Session s) {
        return new File(Tree.root(this), "var/sessions/" + s.id);
    }

    private void writeView(Session s) {
        if (s.pid <= 0) return;
        String cwd = "";
        try { cwd = new File("/proc/" + s.pid + "/cwd").getCanonicalPath(); } catch (Throwable ignore) {}
        String text = "pid=" + s.pid + "\ntitle=" + s.title.replace('\n', ' ')
                + "\ncwd=" + cwd + "\ncommand=" + (s.command == null ? "system" : s.command) + "\n";
        try (FileOutputStream o = new FileOutputStream(viewFile(s))) {
            o.write(text.getBytes("UTF-8"));
        } catch (java.io.IOException ignore) {}
    }

    private void removeView(Session s) {
        viewFile(s).delete();
    }

    /** A killed process leaves its files behind: the next start clears them. */
    private void clearViews() {
        File[] old = new File(Tree.root(this), "var/sessions").listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    // --- foreground ---------------------------------------------------------------

    private void promote() {
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Shell", NotificationManager.IMPORTANCE_LOW));
            nb = new Notification.Builder(this, CHANNEL);
        } else {
            nb = new Notification.Builder(this);
        }
        Intent open = new Intent(this, HomeActivity.class).putExtra(HomeActivity.EXTRA_TAB, HomeActivity.SHELL);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);
        nb.setContentTitle("Shell")
          .setContentText("Tap to open the Shell tab")
          .setSmallIcon(android.R.drawable.stat_notify_sync)
          .setContentIntent(pi)
          .setOngoing(true);
        startForeground(NOTIF_ID, nb.build());
    }
}
