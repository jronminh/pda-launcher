package org.pda.launcher.files;

import android.content.Context;
import android.content.SharedPreferences;

import org.pda.launcher.remote.FtpFs;
import org.pda.launcher.remote.RemoteFs;
import org.pda.launcher.remote.SftpFs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The servers added to Files (SFTP, FTP, FTPS), each a location of its own.
 * What they are is a line in the app's preferences; the secrets (a password,
 * a private key, the server's pinned key) stay in the app's private storage,
 * never in a log or a backup (allowBackup is off).
 */
final class Remotes {

    static final String SFTP = "sftp", FTP = "ftp", FTPS = "ftps";

    static final class Server {
        final String id, kind, name, host, user, folder;
        final int port;

        Server(String id, String kind, String name, String host, int port, String user, String folder) {
            this.id = id;
            this.kind = kind;
            this.name = name;
            this.host = host;
            this.port = port;
            this.user = user;
            this.folder = folder;
        }

        int port() {
            return port > 0 ? port : kind.equals(SFTP) ? 22 : 21;
        }

        /** "SFTP · user@host:port" */
        String where() {
            return kind.toUpperCase(java.util.Locale.ROOT) + " · " + (user.isEmpty() ? "" : user + "@") + host
                    + (port > 0 ? ":" + port : "");
        }

        String line() {
            return String.join("\t", id, kind, clean(name), clean(host), String.valueOf(port), clean(user), clean(folder));
        }

        private static String clean(String s) {
            return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
        }
    }

    private static final String PREFS = "remotes", SECRETS = "remote-secrets";

    private Remotes() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences secrets(Context c) {
        return c.getSharedPreferences(SECRETS, Context.MODE_PRIVATE);
    }

    static List<Server> all(Context c) {
        List<Server> out = new ArrayList<>();
        for (String line : prefs(c).getString("list", "").split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            int port;
            try {
                port = Integer.parseInt(f[4]);
            } catch (NumberFormatException e) {
                port = 0;
            }
            out.add(new Server(f[0], f[1], f[2], f[3], port, f[5], f[6]));
        }
        return out;
    }

    static Server get(Context c, String id) {
        for (Server s : all(c)) if (s.id.equals(id)) return s;
        return null;
    }

    private static void save(Context c, List<Server> list) {
        StringBuilder sb = new StringBuilder();
        for (Server s : list) sb.append(s.line()).append('\n');
        prefs(c).edit().putString("list", sb.toString()).apply();
    }

    /** Add a server, or replace the one with the same id; its password kept unless a new one is given. */
    static void put(Context c, Server s, String password) {
        List<Server> list = all(c);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(s.id)) {
                list.set(i, s);
                found = true;
            }
        }
        if (!found) list.add(s);
        save(c, list);
        if (password != null) secrets(c).edit().putString(s.id + ".password", password).apply();
    }

    static void remove(Context c, String id) {
        List<Server> list = all(c);
        list.removeIf(s -> s.id.equals(id));
        save(c, list);
        secrets(c).edit().remove(id + ".password").remove(id + ".pin").remove(id + ".passphrase").apply();
        keyFile(c, id).delete();
    }

    static String password(Context c, String id) {
        return secrets(c).getString(id + ".password", "");
    }

    static File keyFile(Context c, String id) {
        return new File(new File(c.getFilesDir(), "keys"), id);
    }

    /** A private key for an SFTP server (a copy in the app's private storage) and its passphrase. */
    static void setKey(Context c, String id, byte[] key, String passphrase) throws IOException {
        File f = keyFile(c, id);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), key);
        secrets(c).edit().putString(id + ".passphrase", passphrase == null ? "" : passphrase).apply();
    }

    static void forgetKey(Context c, String id) {
        keyFile(c, id).delete();
        secrets(c).edit().remove(id + ".passphrase").apply();
    }

    /** Forget the server's pinned key: the next connection pins the one it meets. */
    static void forgetPin(Context c, String id) {
        secrets(c).edit().remove(id + ".pin").apply();
    }

    static RemoteFs.Pinning pinning(Context c, String id) {
        final Context app = c.getApplicationContext();
        return new RemoteFs.Pinning() {
            @Override public String known() {
                return secrets(app).getString(id + ".pin", null);
            }

            @Override public void remember(String fingerprint) {
                secrets(app).edit().putString(id + ".pin", fingerprint).apply();
            }
        };
    }

    /** The connection for a server (not opened yet: the first operation opens it). */
    static RemoteFs open(Context c, Server s) throws IOException {
        String pw = password(c, s.id);
        if (s.kind.equals(SFTP)) {
            File k = keyFile(c, s.id);
            byte[] key = k.isFile() ? Files.readAllBytes(k.toPath()) : null;
            String pp = secrets(c).getString(s.id + ".passphrase", "");
            return new SftpFs(s.host, s.port(), s.user, pw, key, pp.isEmpty() ? null : pp.getBytes(), pinning(c, s.id));
        }
        return new FtpFs(s.host, s.port(), s.user, pw, s.kind.equals(FTPS), pinning(c, s.id));
    }
}
