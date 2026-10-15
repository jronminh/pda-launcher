package org.pda.launcher.remote;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * SFTP, over SSH with JSch (BSD; third_party/jsch). A password, a private key
 * (OpenSSH or PEM, with its passphrase), or both. The server's host key is
 * pinned on first use ({@link RemoteFs.Pinning}): a different key later stops
 * the connection with {@link RemoteFs.KeyChanged}. One session; each stream
 * has its own channel, closed with it.
 */
public final class SftpFs implements RemoteFs {

    private final String host, user, password;
    private final int port;
    private final byte[] key, passphrase;
    private final Pinning pin;
    private Session session;
    private ChannelSftp sftp;

    public SftpFs(String host, int port, String user, String password, byte[] key, byte[] passphrase, Pinning pin) {
        this.host = host;
        this.port = port <= 0 ? 22 : port;
        this.user = user;
        this.password = password;
        this.key = key;
        this.passphrase = passphrase;
        this.pin = pin;
    }

    private synchronized ChannelSftp sftp() throws IOException {
        if (sftp != null && sftp.isConnected() && session.isConnected()) return sftp;
        close();
        try {
            JSch jsch = new JSch();
            if (key != null) jsch.addIdentity("key", key, null, passphrase);
            Session s = jsch.getSession(user, host, port);
            if (password != null && !password.isEmpty()) s.setPassword(password);
            // The key is checked here, against the pin, not against a known_hosts file.
            s.setConfig("StrictHostKeyChecking", "no");
            s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
            s.setServerAliveInterval(30_000);
            s.connect(15_000);
            String fp = s.getHostKey().getFingerPrint(jsch);
            String known = pin == null ? null : pin.known();
            if (known != null && !known.equals(fp)) {
                s.disconnect();
                throw new KeyChanged(known, fp);
            }
            if (known == null && pin != null) pin.remember(fp);
            ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
            c.connect(15_000);
            session = s;
            sftp = c;
            return c;
        } catch (JSchException e) {
            throw new IOException(say(e), e);
        }
    }

    private static String say(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (m.contains("Auth fail") || m.contains("Auth cancel")) return "the user, password or key was refused";
        if (m.contains("UnknownHost")) return "no such host";
        if (m.contains("timeout") || m.contains("timed out")) return "the server does not answer";
        if (m.contains("Connection refused")) return "nothing listens on that port";
        return m;
    }

    private static IOException io(SftpException e) {
        switch (e.id) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE: return new IOException("not found");
            case ChannelSftp.SSH_FX_PERMISSION_DENIED: return new IOException("not allowed");
            default: return new IOException(e.getMessage() == null ? "sftp error " + e.id : e.getMessage(), e);
        }
    }

    @Override public synchronized String home() throws IOException {
        try {
            return sftp().pwd();
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override public synchronized List<Entry> list(String dir) throws IOException {
        List<Entry> out = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> v = sftp().ls(dir);
            for (ChannelSftp.LsEntry e : v) {
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                SftpATTRS a = e.getAttrs();
                boolean d = a.isDir();
                if (a.isLink()) {   // a link to a folder is browsed as one
                    try {
                        d = sftp.stat(RemoteFs.join(dir, n)).isDir();
                    } catch (SftpException ignore) {}
                }
                out.add(new Entry(RemoteFs.join(dir, n), n, d, d ? -1 : a.getSize(), a.getMTime() * 1000L));
            }
        } catch (SftpException e) {
            throw io(e);
        }
        RemoteFs.sort(out);
        return out;
    }

    /** A channel of its own for a stream, so browsing goes on meanwhile. */
    private ChannelSftp channel() throws IOException {
        sftp();
        try {
            ChannelSftp c = (ChannelSftp) session.openChannel("sftp");
            c.connect(15_000);
            return c;
        } catch (JSchException e) {
            throw new IOException(say(e), e);
        }
    }

    @Override public InputStream read(String path) throws IOException {
        final ChannelSftp c = channel();
        try {
            return new FilterInputStream(c.get(path)) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { c.disconnect(); }
                }
            };
        } catch (SftpException e) {
            c.disconnect();
            throw io(e);
        }
    }

    @Override public OutputStream write(String dir, String name) throws IOException {
        String path = RemoteFs.join(dir, name);
        final ChannelSftp c = channel();
        try {
            return new FilterOutputStream(c.put(path, ChannelSftp.OVERWRITE)) {
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override public void close() throws IOException {
                    try { super.close(); } finally { c.disconnect(); }
                }
            };
        } catch (SftpException e) {
            c.disconnect();
            throw io(e);
        }
    }

    @Override public synchronized void delete(Entry e) throws IOException {
        try {
            if (e.dir) {
                for (Entry k : list(e.path)) delete(k);
                sftp().rmdir(e.path);
            } else {
                sftp().rm(e.path);
            }
        } catch (SftpException x) {
            throw io(x);
        }
    }

    @Override public synchronized void rename(Entry e, String name) throws IOException {
        String from = e.path, to = RemoteFs.join(RemoteFs.parent(e.path), name);
        try {
            sftp().rename(from, to);
        } catch (SftpException x) {
            throw io(x);
        }
    }

    @Override public synchronized void mkdir(String dir, String name) throws IOException {
        String path = RemoteFs.join(dir, name);
        try {
            sftp().mkdir(path);
        } catch (SftpException e) {
            throw io(e);
        }
    }

    @Override public synchronized void close() {
        if (sftp != null) sftp.disconnect();
        if (session != null) session.disconnect();
        sftp = null;
        session = null;
    }
}
