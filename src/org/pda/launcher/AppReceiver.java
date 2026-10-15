package org.pda.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reloads the grid when an app is installed or removed. */
public class AppReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getData() != null) {
            HomeActivity.ICONS.remove(intent.getData().getSchemeSpecificPart());
        }
        HomeActivity home = HomeActivity.current;
        if (home != null) home.reload();
    }
}
