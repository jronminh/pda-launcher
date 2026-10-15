package org.pda.launcher.remote;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * FTP, and FTPS with explicit TLS (AUTH TLS), written on the platform's
 * sockets: no library (RFC 959, 2228, 2428, 3659). Passive mode only (EPSV,
 * else PASV, always to the control host, as NAT needs), binary, UTF-8 names.
 * Lists with MLSD where the server has it, else LIST in the Unix form. An
 * empty user is anonymous. FTPS trusts the server's certificate on first use
 * and pins it ({@link RemoteFs.Pinning}), as self-signed ones are the rule on
 * a NAS. A dropped control connection is opened again once, transparently.
 */
public final class FtpFs implements RemoteFs {

    private static final int TIMEOUT = 15_000;

    private final String host, user, password;
    private final int port;
    private final boolean tls;
    private final Pinning pin;
    private final Semaphore busy = new Semaphore(1);   // one command, or one stream, at a time
    private Socket control;
    private BufferedReader in;
    private OutputStream out;
    private SSLContext ssl;
    private boolean mlsd;
    private String lastReply = "";

    public FtpFs(String host, int port, String user, String password, boolean tls, Pinning pin) {
        this.host = host;
        this.port = port <= 0 ? 21 : port;
        this.user = user == null || user.isEmpty() ? "anonymous" : user;
        this.password = password == null || password.isEmpty() && this.user.equals("anonymous") ? "pda@" : password;
        this.tls = tls;
        this.pin = pin;
    }

    // --- the control connection ------------------------------------------------------

    private void connect() throws IOException {
        closeQuietly();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), TIMEOUT);
        s.setSoTimeout(TIMEOUT * 2);
        use(s);
        expect(reply(), 2);
        if (tls) {
            expect(command("AUTH TLS"), 2);
            ssl = context();
            SSLSocket t = (SSLSocket) ssl.getSocketFactory().createSocket(s, host, port, true);
            t.startHandshake();
            use(t);
            expect(command("PBSZ 0"), 2);
            expect(command("PROT P"), 2);
        }
        int r = command("USER " + user);
        if (r / 100 == 3) r = command("PASS " + password);
        if (r / 100 != 2) throw new IOException(r == 530 ? "the user or password was refused" : "login: " + lastReply);
        command("OPTS UTF8 ON");   // most servers; harmless where not
        expect(command("TYPE I"), 2);
        int f = command("FEAT");
        mlsd = f / 100 == 2 && lastReply.toUpperCase(Locale.ROOT).contains("MLST");   // MLST means MLSD too (RFC 3659)
    }

    private void use(Socket s) throws IOException {
        control = s;
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out = s.getOutputStream();
    }

    /** A connection that answers, opened again if it was dropped. */
    private void ready() throws IOException {
        if (control == null || control.isClosed()) {
            connect();
            return;
        }
        try {
            if (command("NOOP") / 100 == 2) return;
        } catch (IOException ignore) {
            // dropped by the server while idle
        }
        connect();
    }

    private int command(String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        return reply();
    }

    /** A reply's code; its text, all its lines, in lastReply. */
    private int reply() throws IOException {
        String first = in.readLine();
        if (first == null) throw new IOException("the server closed the connection");
        StringBuilder all = new StringBuilder(first);
        if (first.length() >= 4 && first.charAt(3) == '-') {
            String end = first.substring(0, 3) + " ";
            String l;
            while ((l = in.readLine()) != null) {
                all.append('\n').append(l);
                if (l.startsWith(end)) break;
            }
        }
        lastReply = all.toString();
        try {
            return Integer.parseInt(first.substring(0, 3));
        } catch (RuntimeException e) {
            throw new IOException("not an FTP server: " + first);
        }
    }

    private void expect(int code, int klass) throws IOException {
        if (code / 100 != klass) throw new IOException(lastReply.length() > 4 ? lastReply.substring(4) : lastReply);
    }

    /** TLS that pins the server's certificate the first time it is met. */
    private SSLContext context() throws IOException {
        try {
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, new TrustManager[] { new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    String fp = fingerprint(chain[0]);
                    String known = pin == null ? null : pin.known();
                    if (known != null && !known.equals(fp)) throw new CertificateException(new KeyChanged(known, fp));
                    if (known == null && pin != null) pin.remember(fp);
                }
            } }, null);
            return c;
        } catch (Exception e) {
            throw new IOException("TLS: " + e.getMessage(), e);
        }
    }

    static String fingerprint(X509Certificate c) throws CertificateException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder("SHA256:");
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CertificateException(e);
        }
    }

    // --- data connections --------------------------------------------------------------

    private static final Pattern EPSV = Pattern.compile("\\(\\|\\|\\|(\\d+)\\|\\)");
    private static final Pattern PASV = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)");

    /** A data connection for {@code cmd} (RETR, STOR, MLSD, LIST), the transfer begun. */
    private Socket data(String cmd) throws IOException {
        int dport;
        int r = command("EPSV");
        Matcher m = EPSV.matcher(lastReply);
        if (r == 229 && m.find()) {
            dport = Integer.parseInt(m.group(1));
        } else {
            expect(command("PASV"), 2);
            Matcher p = PASV.matcher(lastReply);
            if (!p.find()) throw new IOException("passive mode: " + lastReply);
            dport = Integer.parseInt(p.group(5)) * 256 + Integer.parseInt(p.group(6));
        }
        Socket d = new Socket();
        d.connect(new InetSocketAddress(host, dport), TIMEOUT);   // the control host, not the one PASV names
        d.setSoTimeout(TIMEOUT * 2);
        if (tls) {
            SSLSocket t = (SSLSocket) ssl.getSocketFactory().createSocket(d, host, port, true);
            t.setUseClientMode(true);
            d = t;
        }
        int c = command(cmd);
        if (c / 100 != 1) {
            d.close();
            throw new IOException(c == 550 ? "not found, or not allowed (" + lastReply.substring(Math.min(4, lastReply.length())) + ")"
                    : lastReply.substring(Math.min(4, lastReply.length())));
        }
        if (d instanceof SSLSocket) ((SSLSocket) d).startHandshake();
        return d;
    }

    /** The transfer's end: the data socket closed, the server's 226 read. */
    private void finish(Socket d) throws IOException {
        try {
            d.close();
            expect(reply(), 2);
        } finally {
            busy.release();
        }
    }

    private void acquire() throws IOException {
        try {
            busy.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    // --- the operations ------------------------------------------------------------------

    @Override public String home() throws IOException {
        acquire();
        try {
            ready();
            expect(command("PWD"), 2);
            int a = lastReply.indexOf('"'), b = lastReply.lastIndexOf('"');
            return a >= 0 && b > a ? lastReply.substring(a + 1, b).replace("\"\"", "\"") : "/";
        } finally {
            busy.release();
        }
    }

    @Override public List<Entry> list(String dir) throws IOException {
        acquire();
        List<String> lines = new ArrayList<>();
        try {
            ready();
            Socket d = data(mlsd ? "MLSD " + dir : "LIST " + dir);   // no flags: some servers take them for a name
            try (BufferedReader r = new BufferedReader(new InputStreamReader(d.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) lines.add(l);
            }
            d.close();
            expect(reply(), 2);
        } finally {
            busy.release();
        }
        List<Entry> out = new ArrayList<>();
        for (String l : lines) {
            Entry e = mlsd ? parseMlsd(dir, l) : parseList(dir, l);
            if (e != null && !e.name.equals(".") && !e.name.equals("..")) out.add(e);
        }
        RemoteFs.sort(out);
        return out;
    }

    /** "type=dir;size=0;modify=20261015120000; name" (RFC 3659). */
    static Entry parseMlsd(String dir, String line) {
        int sp = line.indexOf(' ');
        if (sp < 0) return null;
        String name = line.substring(sp + 1);
        String type = "", modify = null;
        long size = -1;
        for (String fact : line.substring(0, sp).split(";")) {
            int eq = fact.indexOf('=');
            if (eq < 0) continue;
            String k = fact.substring(0, eq).toLowerCase(Locale.ROOT), v = fact.substring(eq + 1);
            if (k.equals("type")) type = v.toLowerCase(Locale.ROOT);
            else if (k.equals("size")) size = parseLong(v);
            else if (k.equals("modify")) modify = v;
        }
        if (type.equals("cdir") || type.equals("pdir")) return null;
        boolean d = type.equals("dir");
        long when = 0;
        if (modify != null && modify.length() >= 14) {
            try {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                when = f.parse(modify.substring(0, 14)).getTime();
            } catch (java.text.ParseException ignore) {}
        }
        return new Entry(RemoteFs.join(dir, name), name, d, d ? -1 : size, when);
    }

    private static final Pattern UNIX = Pattern.compile(
            "^([dlbcps-])[rwxsStT-]{9}\\S*\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\w{3})\\s+(\\d{1,2})\\s+(\\d{1,2}:\\d{2}|\\d{4})\\s(.+)$");
    private static final String MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec";

    /** "drwxr-xr-x 2 user group 4096 Oct 15 12:00 name" (the Unix ls form most servers give). */
    static Entry parseList(String dir, String line) {
        Matcher m = UNIX.matcher(line);
        if (!m.matches()) return null;
        boolean d = m.group(1).equals("d");
        String name = m.group(6);
        if (m.group(1).equals("l")) {   // "name -> target": the link's own name
            int arrow = name.indexOf(" -> ");
            if (arrow > 0) name = name.substring(0, arrow);
        }
        long when = 0;
        int mon = MONTHS.indexOf(m.group(3).toLowerCase(Locale.ROOT)) / 3;
        if (mon >= 0) {
            Calendar c = Calendar.getInstance();
            int year = c.get(Calendar.YEAR), h = 0, min = 0;
            if (m.group(5).contains(":")) {
                h = Integer.parseInt(m.group(5).substring(0, m.group(5).indexOf(':')));
                min = Integer.parseInt(m.group(5).substring(m.group(5).indexOf(':') + 1));
                if (mon > c.get(Calendar.MONTH)) year--;   // a time is this year's, unless in the future
            } else {
                year = Integer.parseInt(m.group(5));
            }
            c.clear();
            c.set(year, mon, Integer.parseInt(m.group(4)), h, min);
            when = c.getTimeInMillis();
        }
        return new Entry(RemoteFs.join(dir, name), name, d, d ? -1 : parseLong(m.group(2)), when);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override public InputStream read(String path) throws IOException {
        acquire();
        final Socket d;
        try {
            ready();
            d = data("RETR " + path);
        } catch (IOException | RuntimeException e) {
            busy.release();
            throw e;
        }
        return new FilterInputStream(d.getInputStream()) {
            private boolean closed;

            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                finish(d);
            }
        };
    }

    @Override public OutputStream write(String dir, String name) throws IOException {
        String path = RemoteFs.join(dir, name);
        acquire();
        final Socket d;
        try {
            ready();
            d = data("STOR " + path);
        } catch (IOException | RuntimeException e) {
            busy.release();
            throw e;
        }
        return new FilterOutputStream(d.getOutputStream()) {
            private boolean closed;

            @Override public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                out.flush();
                finish(d);
            }
        };
    }

    @Override public void delete(Entry e) throws IOException {
        if (e.dir) {
            for (Entry k : list(e.path)) delete(k);
        }
        simple((e.dir ? "RMD " : "DELE ") + e.path);
    }

    @Override public void rename(Entry e, String name) throws IOException {
        String from = e.path, to = RemoteFs.join(RemoteFs.parent(e.path), name);
        acquire();
        try {
            ready();
            expect(command("RNFR " + from), 3);
            expect(command("RNTO " + to), 2);
        } finally {
            busy.release();
        }
    }

    @Override public void mkdir(String dir, String name) throws IOException {
        String path = RemoteFs.join(dir, name);
        simple("MKD " + path);
    }

    private void simple(String cmd) throws IOException {
        acquire();
        try {
            ready();
            expect(command(cmd), 2);
        } finally {
            busy.release();
        }
    }

    @Override public void close() {
        try {
            if (control != null && !control.isClosed()) command("QUIT");
        } catch (IOException ignore) {}
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (control != null) control.close();
        } catch (IOException ignore) {}
        control = null;
    }
}
