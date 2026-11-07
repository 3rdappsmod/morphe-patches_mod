package org.ungoogled.xiaomiremote;

/**
 * The HyperOS stand-ins' runtime: Android's own answers where the app registered a broadcast receiver at run time
 * without saying whether other apps may send to it.
 *
 * Mi Remote is a system app on a Xiaomi phone, so it was written before Android 14 required that flag; installed as an
 * ordinary app targeting Android 14, every such call throws a SecurityException. These methods register the receiver
 * as exported, which is what Android did before Android 14 and what the app expects.
 */
public final class Xiaomi {
    private Xiaomi() {
    }

    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter) {
        return context.registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED);
    }

    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, String permission,
            android.os.Handler handler) {
        return context.registerReceiver(receiver, filter, permission, handler, android.content.Context.RECEIVER_EXPORTED);
    }

    /** The same with flags: kept when they say exported or not, else exported. */
    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, int flags) {
        return context.registerReceiver(receiver, filter, exported(flags));
    }

    public static android.content.Intent registerReceiver(android.content.Context context,
            android.content.BroadcastReceiver receiver, android.content.IntentFilter filter, String permission,
            android.os.Handler handler, int flags) {
        return context.registerReceiver(receiver, filter, permission, handler, exported(flags));
    }

    private static int exported(int flags) {
        int either = android.content.Context.RECEIVER_EXPORTED | android.content.Context.RECEIVER_NOT_EXPORTED;
        return (flags & either) == 0 ? flags | android.content.Context.RECEIVER_EXPORTED : flags;
    }
}
