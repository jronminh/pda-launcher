package org.pda.launcher.shell;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import jackpal.androidterm.emulatorview.ColorScheme;

import org.pda.launcher.Ui;

import java.util.List;

/**
 * The Shell tab: a bar (the name of the tab, a list of the tabs, the system
 * shell), the emulator view over the active session, and the key bar. When no
 * session is open a panel takes the terminal's place. Its look follows the
 * settings files (docs/shell.md): a change in {@code etc/} reaches
 * {@link #applyConfig} at once. The tab keeps no state of its own: the sessions
 * are the service's, the settings are files.
 *
 * <p>The service is started the first time the tab is shown, so a launcher
 * that never opens it runs no shell and holds no foreground service.
 */
public final class ShellTab implements ShellService.Ui {

    private static final int NEW_TAB = 1, SYSTEM = 3, TAB = 100;

    private static volatile boolean visible;   // the home screen is on screen

    private final Activity act;
    private final LinearLayout root;     // bar, then the body
    private final FrameLayout body;      // the terminal or the panel
    private final LinearLayout term;     // the view and the key bar
    private final TermView view;
    private final TextView barTitle, barSub;
    private final LinearLayout bar;
    private KeyBar keyBar;
    private String keyBarSpec;           // what the current key bar was built from
    private ShellService svc;
    private boolean bound, showing;
    private ShellService.Session shown;  // the session the view is attached to
    private String error;                // a start that failed, until the next refresh
    private String pendingDir;           // "Open shell here" asked before the service was bound

    private final Config.Listener onConfig = new Config.Listener() {
        @Override public void onChange() { applyConfig(); }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            svc = ((ShellService.LocalBinder) b).service();
            if (svc.error() == null) {
                Config.init(act);
                Config.setListener(onConfig);
                if (pendingDir != null) svc.newTabAt(pendingDir);
                else if (svc.sessions().isEmpty() && svc.failure() == null) svc.newTab();
                pendingDir = null;
            }
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName n) { svc = null; }
    };

    public ShellTab(Activity act) {
        this.act = act;

        view = new TermView(act);
        view.setDensity(act.getResources().getDisplayMetrics());
        // The IME stays uncooked (TYPE_NULL): no autocorrect or composing is
        // injected into the terminal, yet the soft keyboard still shows.
        view.setUseCookedIME(false);
        // A swipe or tap acts as wheel or click only when a program asks for
        // mouse reporting; a plain shell keeps local scrollback.
        view.setMouseTracking(true);
        // Android 9+ lays a pale highlight over a focused view once a key event
        // arrives (hardware keys, adb input); the terminal draws its own cursor.
        if (Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
        view.setListener(new TermView.Listener() {
            @Override public void onSingleTap(float x, float y) { showIme(); }
            @Override public void onTwoFingerTap() { toggleIme(); }
            @Override public void onLongPress(float x, float y) {}
        });

        term = new LinearLayout(act);
        term.setOrientation(LinearLayout.VERTICAL);
        term.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));   // the key bar is added by applyConfig

        body = new FrameLayout(act);
        body.setBackgroundColor(0xFF000000);

        // The bar: the name of the tab (touch it for the list of tabs), then ⋮.
        bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.BG);
        bar.setMinimumHeight(dp(52));
        LinearLayout names = new LinearLayout(act);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(16), dp(6), dp(8), dp(6));
        barTitle = new TextView(act);
        barTitle.setTextColor(Ui.TEXT);
        barTitle.setTextSize(20);
        barTitle.setSingleLine(true);
        barTitle.setText("Shell ▾");
        barSub = new TextView(act);
        barSub.setTextColor(Ui.DIM);
        barSub.setTextSize(12);
        barSub.setVisibility(View.GONE);
        names.addView(barTitle);
        names.addView(barSub);
        names.setOnClickListener(v -> showTabs(v));
        bar.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.textAction(act, "⋮", v -> showMenu(v)));

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.divider(act));
        root.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public View view() {
        return root;
    }

    // --- the launcher's calls ------------------------------------------------------------

    /** The tab came on screen: start the service the first time. */
    public void onShow() {
        showing = true;
        ShellService.ui = this;
        if (!bound) {
            Intent i = new Intent(act, ShellService.class);
            act.startService(i);   // outlives the binding
            bound = act.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } else {
            refresh();
        }
        if (shown != null && Config.getBool("terminal.showIme")) showIme();
    }

    /** A new tab that starts in {@code dir}. Call before {@link #onShow}. */
    public void openAt(String dir) {
        if (svc == null) { pendingDir = dir; return; }
        if (svc.newTabAt(dir) == null) Toast.makeText(act, "Too many tabs", Toast.LENGTH_SHORT).show();
        refresh();
    }

    public void onHide() {
        showing = false;
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    public void onResume() {
        view.onResume();
        if (showing) onShow();
    }

    public void onPause() {
        view.onPause();
    }

    public void onDestroy() {
        if (ShellService.ui == this) ShellService.ui = null;
        Config.setListener(null);
        if (bound) act.unbindService(conn);
        bound = false;
    }

    /** Whether the home screen is on screen: Android 10+ lets only then start another
     *  app's activity or read the clipboard. */
    public static void setVisible(boolean v) {
        visible = v;
    }

    static boolean isVisible() {
        return visible;
    }

    // --- following the service ---------------------------------------------------------

    @Override public void sessionsChanged() {
        act.runOnUiThread(this::refresh);
    }

    @Override public void showError(final String message) {
        act.runOnUiThread(() -> { error = message; refresh(); });
    }

    /** Show what the service holds: the active session, or why there is none. */
    private void refresh() {
        if (svc == null) return;
        String why = svc.error() != null ? svc.error() : error;
        error = null;
        ShellService.Session a = svc.active();
        if (a == null || why != null) {
            if (shown != null) shown.term.setUpdateCallback(null);
            shown = null;
            showPanel(why, svc.failure());
            bar(null);
            return;
        }
        if (term.getParent() == null) { body.removeAllViews(); body.addView(term); }
        if (a != shown) attach(a);
        bar(a);
    }

    /** Point the view at a session. The first size it reports starts a new shell. */
    private void attach(ShellService.Session a) {
        if (shown != null) shown.term.setUpdateCallback(null);   // the old one draws no more
        view.attachSession(act, a.term);
        applyConfig();           // the font decides the size the shell starts on
        view.updateSize(true);
        shown = a;
    }

    private void bar(ShellService.Session a) {
        List<ShellService.Session> all = svc.sessions();
        barTitle.setText((a == null ? "Shell" : a.label()) + " ▾");
        barSub.setText("tab " + (all.indexOf(a) + 1) + " of " + all.size());
        barSub.setVisibility(a != null && all.size() > 1 ? View.VISIBLE : View.GONE);
        // The system shell is told apart by colour: amber, not a lit bar (OLED).
        barTitle.setTextColor(a != null && a.system ? Ui.ACCENT : Ui.TEXT);
    }

    /** Make the look match the settings: font, colours, key bar, screen. */
    private void applyConfig() {
        // The view draws nothing until a session is attached (attach() calls this again).
        if (view.getTermSession() != null) {
            view.setTextSize(Config.getInt("terminal.fontSize"));
            view.setColorScheme(new ColorScheme(
                    Config.getColor("terminal.foreground"), Config.getColor("terminal.background")));
            view.setKeepScreenOn(Config.getBool("ui.keepScreenOn"));
        }

        String spec = Config.get("terminal.keybar") + "\n" + Config.get("terminal.keybarHeight");
        if (!spec.equals(keyBarSpec)) {
            keyBarSpec = spec;
            if (keyBar != null) term.removeView(keyBar);
            keyBar = new KeyBar(act, view, new KeyBar.Sink() {
                @Override public void sendText(String s) {
                    if (view.getTermSession() != null) view.getTermSession().write(s);
                }
            }, KeyBar.parse(Config.get("terminal.keybar")), Config.getInt("terminal.keybarHeight"));
            term.addView(keyBar);
        }
    }

    // --- menus ----------------------------------------------------------------------------

    /** The list of tabs under the title, and a way to open one more. */
    private void showTabs(View anchor) {
        if (svc == null) return;
        PopupMenu pm = new PopupMenu(act, anchor);
        Menu m = pm.getMenu();
        List<ShellService.Session> all = svc.sessions();
        for (int i = 0; i < all.size(); i++) {
            ShellService.Session s = all.get(i);
            MenuItem it = m.add(1, TAB + i, i, (i + 1) + "  " + (s.system ? "system: " : "") + s.label());
            it.setCheckable(true);
            it.setChecked(s == svc.active());
        }
        m.setGroupCheckable(1, true, true);
        m.add(2, NEW_TAB, all.size(), "New tab")
                .setEnabled(all.size() < Config.getInt("shell.maxSessions"));
        pm.setOnMenuItemClickListener(this::onItem);
        pm.show();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(act, anchor);
        pm.getMenu().add(0, SYSTEM, 0, "System shell");
        pm.setOnMenuItemClickListener(this::onItem);
        pm.show();
    }

    private boolean onItem(MenuItem item) {
        if (svc == null) return false;
        int id = item.getItemId();
        if (id >= TAB) {
            List<ShellService.Session> all = svc.sessions();
            if (id - TAB < all.size()) { svc.activate(all.get(id - TAB)); refresh(); }
            return true;
        }
        ShellService.Session s;
        switch (id) {
            case NEW_TAB: s = svc.newTab(); break;
            case SYSTEM: s = svc.systemTab(); break;
            default: return false;
        }
        if (s == null) Toast.makeText(act, "Too many tabs", Toast.LENGTH_SHORT).show();
        refresh();
        return true;
    }

    // --- the panel ------------------------------------------------------------------------

    /** No session: why (a start that failed, or a last session that ended at once
     *  with an error), and the ways to open one. */
    private void showPanel(String why, ShellService.Failure f) {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(24), dp(24), dp(24), dp(24));

        String head = why != null ? why
                : f != null ? (f.system ? "The system shell" : "\"" + f.command + "\"")
                        + " ended with exit code " + f.code + " right after it started."
                : "No session is open.";
        TextView t = new TextView(act);
        t.setText(head);
        t.setTextColor(why != null || f != null ? Ui.ACCENT : Ui.TEXT);
        t.setTextSize(16);
        p.addView(t);

        if (why == null && f != null && f.lastLines.length() > 0) {
            TextView out = new TextView(act);
            out.setText(f.lastLines);
            out.setTypeface(Typeface.MONOSPACE);
            out.setTextColor(Ui.DIM);
            out.setTextSize(13);
            out.setPadding(0, dp(16), 0, dp(16));
            p.addView(out);
        }

        if (svc.error() == null) {
            Button open = button("");
            open.setText(f != null ? "Try again" : "New session");
            open.setOnClickListener(v -> { svc.retry(); refresh(); });
            p.addView(open);

            Button sys = button("");
            sys.setText("Open system shell");
            sys.setOnClickListener(v -> { svc.systemTab(); refresh(); });
            p.addView(sys);
        }

        body.removeAllViews();
        body.addView(p);
    }

    // --- the rest -------------------------------------------------------------------------

    private void showIme() {
        view.requestFocus();
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
    }

    private void toggleIme() {
        InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.toggleSoftInput(0, 0);
    }

    private Button button(String text) {
        Button b = new Button(act);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setBackground(Ui.outline(act, Ui.LINE));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int n) {
        return Math.round(n * act.getResources().getDisplayMetrics().density);
    }
}
