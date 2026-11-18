package com.cleanyourtv.home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Keeps Wireless debugging on, so the helper's remote, trackpad and Now playing keep working.
 *
 * Android turns Wireless debugging off after a restart and whenever the Wi-Fi drops. The helper grants this app
 * WRITE_SECURE_SETTINGS over adb (once, while it is connected); with it, the app switches Wireless debugging back on
 * when the pointer service starts (at boot), when Home opens and a few seconds after the Wi-Fi comes back.
 * The pairing stays, so the helper finds the new port over mDNS and connects again by itself.
 * Without the permission this does nothing.
 */
final class AdbKeeper {
    static final String PERM = "android.permission.WRITE_SECURE_SETTINGS";
    static final Handler ui = new Handler(Looper.getMainLooper());
    static boolean watching;

    private AdbKeeper() { }

    static boolean allowed(Context c) { return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED; }

    static void ensure(Context c) {
        if (!allowed(c)) return;
        try {
            ContentResolver r = c.getContentResolver();
            if (Settings.Global.getInt(r, Settings.Global.ADB_ENABLED, 0) != 1) Settings.Global.putInt(r, Settings.Global.ADB_ENABLED, 1);
            if (Settings.Global.getInt(r, "adb_wifi_enabled", 0) != 1) Settings.Global.putInt(r, "adb_wifi_enabled", 1);
        } catch (Exception ignored) { }
    }

    /** Switch it on now, and again each time a network comes back. */
    static synchronized void watch(Context context) {
        final Context c = context.getApplicationContext();
        ensure(c);
        if (watching) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { ui.postDelayed(() -> ensure(c), 4000); }
            });
            watching = true;
        } catch (Exception ignored) { }
    }
}
