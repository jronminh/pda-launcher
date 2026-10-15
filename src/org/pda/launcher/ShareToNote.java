package org.pda.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * "Show on Note" in other apps' Share menus: the shared text goes on the Note
 * tab's board (in place of what was there) and the launcher opens on that tab.
 * It has no screen of its own.
 */
public class ShareToNote extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        CharSequence text = in != null ? in.getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        if (text == null && in != null) text = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        if (text != null) NoteTab.show(text.toString());
        startActivity(new Intent(this, HomeActivity.class)
                .putExtra(HomeActivity.EXTRA_TAB, HomeActivity.NOTE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
