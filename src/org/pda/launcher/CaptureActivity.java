package org.pda.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.pda.launcher.today.PdaStore;

import java.io.IOException;

/**
 * Capture: a small window over whatever is on screen, to write a line into the
 * Today tab's inbox (or straight into its tasks). It is an app of its own in
 * the app lists, so a key (Samsung's side key, say) or a home screen can open
 * it; the launcher stays where it was.
 */
public class CaptureActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config.prepare(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.outline(this, Ui.LINE));
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, Ui.dp(this, 8));

        TextView title = new TextView(this);
        title.setText("Capture");
        title.setTextColor(Ui.DIM);
        title.setTextSize(13);
        title.setAllCaps(true);
        box.addView(title);

        final EditText text = new EditText(this);
        text.setTextColor(Ui.TEXT);
        text.setHintTextColor(Ui.DIM);
        text.setHint("A thought, a link, a thing to do…");
        text.setTextSize(17);
        text.setMinLines(2);
        text.setBackground(null);
        text.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        box.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.addView(Ui.labeledAction(this, R.drawable.ic_task, "Task", v -> save(text.getText().toString(), true)));
        buttons.addView(Ui.labeledAction(this, R.drawable.ic_inbox, "Inbox", v -> save(text.getText().toString(), false)));
        for (int i = 0; i < buttons.getChildCount(); i++) {
            buttons.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 72),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        box.addView(buttons);

        setContentView(box);
        getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32),
                Ui.dp(this, 480)), ViewGroup.LayoutParams.WRAP_CONTENT);
        text.requestFocus();
    }

    private void save(String text, boolean task) {
        if (text.trim().isEmpty()) {
            finish();
            return;
        }
        try {
            if (task) PdaStore.addTask(text);
            else PdaStore.capture(text);
            Toast.makeText(this, task ? "Added to Tasks" : "Added to Inbox", Toast.LENGTH_SHORT).show();
            finish();
        } catch (IOException e) {
            Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
