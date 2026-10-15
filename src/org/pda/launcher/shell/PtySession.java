package org.pda.launcher.shell;

import jackpal.androidterm.emulatorview.TermSession;

/**
 * A {@link TermSession} bound to the app's PTY.
 *
 * <p>{@code TermSession} owns the reader/writer threads and takes an
 * {@code InputStream}/{@code OutputStream}; point them at the PTY and it does
 * the rest.
 *
 * <p>The shell is started at the <b>first real size</b> the view reports, not
 * before: mksh redraws its prompt when the number of columns changes after the
 * prompt is up, which showed as a doubled prompt at start. After the first
 * size {@link #updateSize} also tells the PTY ({@code TIOCSWINSZ}), which the
 * base class does not.
 */
public class PtySession extends TermSession {

    /** The service side. */
    public interface Host {
        /** The first size is known: start the shell on it, then hand the
         *  session its streams with {@link #setTermIn}/{@link #setTermOut}.
         *  @return false if the shell could not be started. */
        boolean start(PtySession session, int cols, int rows);

        void resize(int cols, int rows);

        /** The shell process exited (EOF). */
        void onExit();
    }

    private final Host host;
    private boolean started;
    private String finalText = "";   // the screen when the shell ended (finish() clears it)

    public PtySession(Host host) {
        // exitOnEOF: when the shell exits the reader thread raises EOF and the
        // handler calls onProcessExit() (docs/shell.md).
        super(true);
        this.host = host;
    }

    /** What the terminal showed when the shell ended. */
    public String finalText() {
        return finalText;
    }

    @Override
    protected void onProcessExit() {
        finalText = getTranscriptText();
        if (host != null) host.onExit();
        super.onProcessExit();
    }

    @Override
    public void updateSize(int columns, int rows) {
        if (!started) {
            started = true;
            if (!host.start(this, columns, rows)) return;
        } else {
            host.resize(columns, rows);
        }
        super.updateSize(columns, rows);
    }
}
