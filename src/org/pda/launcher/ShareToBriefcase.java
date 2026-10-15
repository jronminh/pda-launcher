package org.pda.launcher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Patterns;
import android.widget.Toast;

import org.pda.launcher.files.Favorites;

import java.util.regex.Matcher;

/**
 * "Add to Briefcase" in other apps' Share menus: the first web link in the
 * shared text becomes a link in the Briefcase, named by the shared subject
 * (a page's title, usually) or else by its host. The app one came from stays
 * on screen.
 */
public class ShareToBriefcase extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        CharSequence text = in != null ? in.getCharSequenceExtra(Intent.EXTRA_TEXT) : null;
        CharSequence subject = in != null ? in.getCharSequenceExtra(Intent.EXTRA_SUBJECT) : null;
        String url = null;
        if (text != null) {
            Matcher m = Patterns.WEB_URL.matcher(text);
            if (m.find()) url = m.group();
        }
        String said;
        if (url == null) {
            said = "No link to add";
        } else {
            if (!url.contains("://")) url = "https://" + url;
            String host = Uri.parse(url).getHost();
            String title = subject != null && subject.length() > 0 ? subject.toString()
                    : host != null ? host : url;
            said = Favorites.addItem(this, Favorites.Item.url(title, url))
                    ? "Added to Briefcase: " + title : "In the Briefcase already";
        }
        Toast.makeText(this, said, Toast.LENGTH_SHORT).show();
        finish();
    }
}
