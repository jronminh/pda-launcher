package org.pda.launcher.files;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import org.pda.launcher.HomeActivity;

import java.io.File;

/**
 * "Open with › PDA Launcher" for a folder another app hands over: the folder
 * opens in the Files tab. A folder in shared storage (a path, or the system's
 * documents link to it) opens as a folder; any other app's folder link becomes
 * a location of Files, kept, when the link allows it.
 */
public class OpenFolderActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri u = getIntent().getData();
        File dir = u == null ? null : org.pda.launcher.viewers.Viewers.fileFor(this, u);
        if (dir != null && dir.isDirectory()) {
            startActivity(new Intent(this, HomeActivity.class)
                    .putExtra(HomeActivity.EXTRA_TAB, HomeActivity.FILES)
                    .putExtra(HomeActivity.EXTRA_FOLDER, dir.getAbsolutePath())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (u != null && "content".equals(u.getScheme())
                && android.provider.DocumentsContract.isTreeUri(u)) {
            String name = Locations.add(this, getIntent());
            Toast.makeText(this, name != null ? "Added to Files: " + name : "Cannot open this folder",
                    Toast.LENGTH_SHORT).show();
            if (name != null) {
                startActivity(new Intent(this, HomeActivity.class)
                        .putExtra(HomeActivity.EXTRA_TAB, HomeActivity.FILES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } else {
            Toast.makeText(this, "Cannot open this folder here", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
