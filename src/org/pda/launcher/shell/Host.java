package org.pda.launcher.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Network;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The host ABI server: what only Android can do, asked for from the tree
 * (docs/shell.md).
 *
 * <p>The transport is a Unix socket <b>in the tree</b>, {@code var/host.sock},
 * not an abstract one. It lives in the app's private directory, so only this
 * app's uid can even reach it, and the client is toybox's {@code nc -U}: the
 * tree needs no native binary. Every request also carries a token (per run, in
 * {@code var/host.token}).
 *
 * <p>One line of JSON in, one line out, then the connection closes:
 * <pre>
 * {"v":1,"id":"1","token":"...","method":"battery","args":{}}
 * {"v":1,"id":"1","ok":true,"code":0,"data":{"level":87,"charging":false}}
 * {"v":1,"id":"1","ok":false,"code":3,"error":"bad token"}
 * </pre>
 * A request may add {@code "fmt":"kv"}: the reply is then plain text for a
 * shell, its first line {@code code=N}, then {@code key=value} lines (an array
 * repeats its key, an object nests with dots, newline and backslash are
 * escaped), or {@code error=...}.
 *
 * <p>Codes: 0 ok, 1 failure, 2 unreachable (the client's), 3 denied, 4 timeout,
 * 5 unsupported, 64 usage.
 */
final class Host {

    static final int PROTOCOL = 1;
    private static final int MAX_LINE = 1 << 20;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAIN_TIMEOUT_MS = 4000;

    /** The one list of methods: {@code describe} and {@code status} both read it.
     *  name, what it takes, what it does. */
    private static final String[][] METHODS = {
        { "describe",      "",                    "the methods, what they take, what they do" },
        { "status",        "",                    "the app, the device, the methods" },
        { "battery",       "",                    "level, charging, plugged, temperature" },
        { "net.dns",       "",                    "the device's DNS servers" },
        { "toast",         "text, long?",         "a short message on the screen" },
        { "notify",        "title, body?, id?",   "a notification (denied while the app's notifications are off)" },
        { "clipboard.get", "",                    "the clipboard text (Android 10+: only while the app is on screen)" },
        { "clipboard.set", "text",                "put text on the clipboard" },
        { "open",          "url",                 "open a link or a file in the app that handles it (Android 10+: only while the app is on screen)" },
        { "share",         "text",                "offer text to other apps (Android 10+: only while the app is on screen)" },
    };

    /** A failure with a protocol code. */
    private static final class HostError extends Exception {
        final int code;
        HostError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File sock;
    private final File tokenFile;
    private final String token = UUID.randomUUID().toString();
    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dn-host-client");
            t.setDaemon(true);
            return t;
        }
    });
    /** Cuts off a client that does not finish its request. LocalSocket.setSoTimeout
     *  is not usable: when it fires, the platform's native read throws with a
     *  pending exception and then calls NewStringUTF, which aborts the whole app
     *  under CheckJNI (seen on Android 12). */
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dn-host-timer");
            t.setDaemon(true);
            return t;
        }
    });
    private LocalSocket bound;           // kept: its finalizer closes the descriptor the server uses
    private LocalServerSocket server;
    private volatile boolean stopped;
    private int nextNotification = 1000;

    Host(Context ctx) {
        this.ctx = ctx;
        File var = new File(Tree.root(ctx), "var");
        sock = new File(var, "host.sock");
        tokenFile = new File(var, "host.token");
    }

    String token() { return token; }

    /** Bind the socket and start serving. A stale file from a killed run is replaced. */
    void start() throws IOException {
        try (OutputStream o = new FileOutputStream(tokenFile)) {
            o.write((token + "\n").getBytes("UTF-8"));
        }
        chmod(tokenFile, 0600);

        sock.delete();
        bound = new LocalSocket();
        bound.bind(new LocalSocketAddress(sock.getPath(), LocalSocketAddress.Namespace.FILESYSTEM));
        chmod(sock, 0600);
        // Listen on the bound descriptor: LocalServerSocket only binds abstract names itself.
        server = new LocalServerSocket(bound.getFileDescriptor());

        Thread t = new Thread(new Runnable() {
            @Override public void run() { accept(); }
        }, "dn-host");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        stopped = true;
        try { if (server != null) server.close(); } catch (IOException ignore) {}
        try { if (bound != null) bound.close(); } catch (IOException ignore) {}
        pool.shutdownNow();
        timer.shutdownNow();
        sock.delete();
        tokenFile.delete();
    }

    private static void chmod(File f, int mode) {
        try { Os.chmod(f.getPath(), mode); } catch (Throwable ignore) {}
    }

    // --- serving -------------------------------------------------------------------

    private void accept() {
        while (!stopped) {
            final LocalSocket c;
            try {
                c = server.accept();
            } catch (IOException e) {
                if (!stopped) Config.log("host: accept stopped: " + e);
                return;
            }
            try {
                pool.execute(new Runnable() {
                    @Override public void run() { serve(c); }
                });
            } catch (RuntimeException e) {   // the pool was shut down
                try { c.close(); } catch (IOException ignore) {}
            }
        }
    }

    /** One request, one reply. A client that is slow, silent or too long is dropped. */
    private void serve(final LocalSocket c) {
        ScheduledFuture<?> cut = timer.schedule(new Runnable() {
            @Override public void run() {
                try { c.shutdownInput(); } catch (IOException ignore) {}   // wakes the read: EOF
            }
        }, READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        try {
            String line = readLine(c.getInputStream());
            cut.cancel(false);
            String reply = line == null ? render(reply(null, 64, "no request", null), null) : dispatch(line);
            OutputStream out = c.getOutputStream();
            out.write((reply + "\n").getBytes("UTF-8"));
            out.flush();
        } catch (IOException e) {
            // the client went away or was too slow: nothing to answer
        } finally {
            try { c.close(); } catch (IOException ignore) {}
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') return b.toString("UTF-8");
            if (b.size() >= MAX_LINE) throw new IOException("request too long");
            b.write(ch);
        }
        return b.size() == 0 ? null : b.toString("UTF-8");   // EOF without a newline
    }

    private String dispatch(String line) {
        String id = "", fmt = null;
        try {
            JSONObject req = new JSONObject(line);
            id = req.optString("id", "");
            fmt = req.has("fmt") ? req.optString("fmt") : null;
            if (req.optInt("v", 0) != PROTOCOL) return render(reply(id, 5, "unsupported protocol version", null), fmt);
            if (!token.equals(req.optString("token", ""))) return render(reply(id, 3, "bad token", null), fmt);
            String method = req.optString("method", "");
            JSONObject args = req.optJSONObject("args");
            if (args == null) args = new JSONObject();
            return render(reply(id, 0, null, call(method, args)), fmt);
        } catch (HostError e) {
            return render(reply(id, e.code, e.getMessage(), null), fmt);
        } catch (Throwable t) {
            return render(reply(id, 64, "bad request: " + t.getMessage(), null), fmt);
        }
    }

    private JSONObject call(String method, JSONObject a) throws Exception {
        switch (method) {
            case "describe":      return describe();
            case "status":        return status();
            case "battery":       return battery();
            case "net.dns":       return dns();
            case "toast":         return toast(need(a, "text"), a.optBoolean("long", false));
            case "notify":        return notify(need(a, "title"), a.optString("body", ""), a.optInt("id", 0));
            case "clipboard.get": return clipboardGet();
            case "clipboard.set": return clipboardSet(a.optString("text", ""));
            case "open":          return open(need(a, "url"));
            case "share":         return share(need(a, "text"));
            default:              throw new HostError(5, "unsupported method: " + method);
        }
    }

    private static String need(JSONObject a, String key) throws HostError {
        String v = a.optString(key, "");
        if (v.length() == 0) throw new HostError(64, "missing argument: " + key);
        return v;
    }

    // --- the methods -----------------------------------------------------------------

    private JSONObject describe() throws Exception {
        JSONArray ms = new JSONArray();
        for (String[] m : METHODS) {
            JSONObject o = new JSONObject();
            o.put("name", m[0]);
            o.put("args", m[1]);
            o.put("help", m[2]);
            ms.put(o);
        }
        JSONObject d = new JSONObject();
        d.put("v", PROTOCOL);
        d.put("backend", "android");
        d.put("methods", ms);
        return d;
    }

    private JSONObject status() throws Exception {
        JSONArray names = new JSONArray();
        for (String[] m : METHODS) names.put(m[0]);
        JSONObject d = new JSONObject();
        d.put("protocol", PROTOCOL);
        d.put("backend", "android");
        d.put("inst", Tree.root(ctx).getPath());
        d.put("uid", Process.myUid());
        d.put("sdk", Build.VERSION.SDK_INT);
        d.put("onScreen", ShellTab.isVisible());
        d.put("capabilities", names);
        return d;
    }

    private JSONObject battery() throws Exception {
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) throw new HostError(1, "no battery information");
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        JSONObject d = new JSONObject();
        d.put("level", level < 0 ? -1 : Math.round(level * 100f / scale));
        d.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        d.put("plugged", plugged == 0 ? "no" : plugged == BatteryManager.BATTERY_PLUGGED_AC ? "ac"
                : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "usb" : "wireless");
        d.put("temperature", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);   // degrees C
        return d;
    }

    /** The DNS servers of the active network; if it has none (a VPN may carry none),
     *  those of any network. */
    private JSONObject dns() throws Exception {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Set<String> servers = new LinkedHashSet<>();
        Network active = cm.getActiveNetwork();
        if (active != null) addDns(servers, cm.getLinkProperties(active));
        if (servers.isEmpty()) {
            for (Network n : cm.getAllNetworks()) addDns(servers, cm.getLinkProperties(n));
        }
        JSONObject d = new JSONObject();
        d.put("servers", new JSONArray(servers));
        return d;
    }

    private static void addDns(Set<String> out, LinkProperties lp) {
        if (lp == null) return;
        for (InetAddress a : lp.getDnsServers()) out.add(a.getHostAddress());
    }

    private JSONObject toast(final String text, final boolean longer) throws Exception {
        onMain(new Callable<Void>() {
            @Override public Void call() {
                Toast.makeText(ctx, text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                return null;
            }
        });
        return new JSONObject();
    }

    private JSONObject notify(String title, String body, int id) throws Exception {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!nm.areNotificationsEnabled()) throw new HostError(3, "notifications are off for the app");
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    "dn-host", "Shell commands", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(ctx, "dn-host");
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setContentTitle(title).setContentText(body)
         .setSmallIcon(android.R.drawable.stat_notify_chat).setAutoCancel(true);
        int nid = id != 0 ? id : nextNotification++;
        nm.notify(nid, b.build());
        JSONObject d = new JSONObject();
        d.put("id", nid);
        return d;
    }

    private JSONObject clipboardGet() throws Exception {
        if (Build.VERSION.SDK_INT >= 29 && !ShellTab.isVisible()) {
            throw new HostError(3, "the clipboard can be read only while the app is on screen");
        }
        String text = onMain(new Callable<String>() {
            @Override public String call() {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).coerceToText(ctx);
                return t == null ? "" : t.toString();
            }
        });
        JSONObject d = new JSONObject();
        d.put("text", text);
        return d;
    }

    private JSONObject clipboardSet(final String text) throws Exception {
        onMain(new Callable<Void>() {
            @Override public Void call() {
                ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("shell", text));
                return null;
            }
        });
        return new JSONObject();
    }

    private JSONObject open(final String url) throws Exception {
        screenRequired();
        onMain(new Callable<Void>() {
            @Override public Void call() throws Exception {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.startsWith("www.") ? "http://" + url : url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(i);
                } catch (android.content.ActivityNotFoundException e) {
                    throw new HostError(1, "no app can open " + url);
                }
                return null;
            }
        });
        return new JSONObject();
    }

    private JSONObject share(final String text) throws Exception {
        screenRequired();
        onMain(new Callable<Void>() {
            @Override public Void call() {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                Intent chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(chooser);
                return null;
            }
        });
        return new JSONObject();
    }

    /** From Android 10 an app may start an activity only while one of its own is on
     *  screen; otherwise the system drops the request without a word. Say so. */
    private static void screenRequired() throws HostError {
        if (Build.VERSION.SDK_INT >= 29 && !ShellTab.isVisible()) {
            throw new HostError(3, "the app must be on screen to open another app (Android 10+)");
        }
    }

    /** Run on the main thread and wait, for what Android allows only there. */
    private <T> T onMain(Callable<T> job) throws Exception {
        FutureTask<T> f = new FutureTask<>(job);
        main.post(f);
        try {
            return f.get(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw new HostError(4, "the app did not answer in time");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof HostError) throw (HostError) c;
            throw new HostError(1, String.valueOf(c));
        }
    }

    // --- replies ---------------------------------------------------------------------

    private static JSONObject reply(String id, int code, String error, JSONObject data) {
        try {
            JSONObject r = new JSONObject();
            r.put("v", PROTOCOL);
            r.put("id", id == null ? "" : id);
            r.put("ok", code == 0);
            r.put("code", code);
            if (code == 0) r.put("data", data == null ? new JSONObject() : data);
            else r.put("error", error);
            return r;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The reply line: JSON, or for {@code fmt:"kv"} lines for a shell. */
    private static String render(JSONObject r, String fmt) {
        if (!"kv".equals(fmt)) return r.toString();
        StringBuilder sb = new StringBuilder();
        sb.append("code=").append(r.optInt("code", 1)).append('\n');
        if (r.optBoolean("ok")) flatten(r.optJSONObject("data"), "", sb);
        else sb.append("error=").append(kvValue(r.optString("error"))).append('\n');
        return sb.toString().replaceAll("\n$", "");   // the caller adds the last newline
    }

    private static void flatten(JSONObject o, String prefix, StringBuilder sb) {
        if (o == null) return;
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.opt(k);
            String name = prefix + k;
            if (v instanceof JSONObject) {
                flatten((JSONObject) v, name + ".", sb);
            } else if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    Object e = arr.opt(i);
                    if (e instanceof JSONObject) flatten((JSONObject) e, name + "." + i + ".", sb);
                    else sb.append(name).append('=').append(kvValue(String.valueOf(e))).append('\n');
                }
            } else {
                sb.append(name).append('=').append(kvValue(String.valueOf(v))).append('\n');
            }
        }
    }

    /** One line: a backslash and a newline are escaped. */
    private static String kvValue(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
