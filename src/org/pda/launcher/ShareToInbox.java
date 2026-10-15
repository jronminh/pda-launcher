package org.pda.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import org.pda.launcher.today.PdaStore;

import java.io.IOException;

/**
 * "Add to Inbox" in other apps' Share menus: the shared text (or link) becomes a
 * capture in the Today tab's inbox.md, and the app one came from stays on screen.
 */
public class ShareToInbox extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        CharSequence text = in != null ? in.getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        CharSequence subject = in != null ? in.getCharSequenceExtra(Intent.EXTRA_SUBJECT) : null;
        String line = text == null ? (subject == null ? null : subject.toString())
                : subject == null || text.toString().contains(subject) ? text.toString() : subject + " " + text;
        String said;
        if (line == null) {
            said = "Nothing to add";
        } else {
            try {
                PdaStore.capture(line);
                said = "Added to Inbox";
            } catch (IOException e) {
                said = "Could not add to Inbox: " + e.getMessage();
            }
        }
        Toast.makeText(this, said, Toast.LENGTH_SHORT).show();
        finish();
    }
}
