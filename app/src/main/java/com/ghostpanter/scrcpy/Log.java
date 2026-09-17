package com.ghostpanter.scrcpy;

import java.util.Locale;

// Single static wrapper around android.util.Log so every line in this app
// uses the same tag. Filter the whole app with `adb logcat -s scrcpy-android`.
//
// String.format always uses Locale.ROOT so logs are unaffected by device
// locales that use comma decimals or other surprises.
public final class Log {
    public static final String TAG = "scrcpy-android";

    private Log() {}

    public static void i(String fmt, Object... a) { android.util.Log.i(TAG, fmt(fmt, a)); }
    public static void w(String fmt, Object... a) { android.util.Log.w(TAG, fmt(fmt, a)); }
    public static void e(String fmt, Object... a) { android.util.Log.e(TAG, fmt(fmt, a)); }

    public static void e(Throwable t, String fmt, Object... a) {
        android.util.Log.e(TAG, fmt(fmt, a), t);
    }

    private static String fmt(String fmt, Object... a) {
        return a.length == 0 ? fmt : String.format(Locale.ROOT, fmt, a);
    }
}
