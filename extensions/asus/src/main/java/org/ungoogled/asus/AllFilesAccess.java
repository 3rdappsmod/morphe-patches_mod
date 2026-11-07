package org.ungoogled.asus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

/**
 * Asks for "All files access", which ASUS Gallery and File Manager need to manage the phone's storage the way they do
 * on an ASUS phone (where they are system apps and hold it already).
 *
 * Android 11 and later grant it through a settings screen, not a runtime permission: the app opens that screen once.
 * When the app already holds it, or the screen cannot be opened, nothing happens.
 */
public final class AllFilesAccess {
    private AllFilesAccess() {
    }

    /** True when the app may manage every file on the phone. */
    public static boolean granted(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        // Before Android 11 the storage permission covers it; the app asks for that itself.
        return true;
    }

    /** Opens the "All files access" screen for this app, once, unless it is already granted. */
    public static void request(Activity activity) {
        if (activity == null || granted(activity)) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
            activity.startActivity(intent);
        } catch (Throwable ignored) {
            // Some ROMs do not have that screen; the app then keeps working with the access it has.
        }
    }
}
