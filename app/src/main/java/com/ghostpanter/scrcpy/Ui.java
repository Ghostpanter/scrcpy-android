package com.ghostpanter.scrcpy;

import android.annotation.SuppressLint;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

// Window inset plumbing for the non-fullscreen activities.
//
// targetSdk 35 and later make every window edge-to-edge on Android 15: the system
// no longer insets the content view, so an unhandled layout draws under
// the status and navigation bars. Mirror wants that and opts in itself;
// Main and Settings do not, so they pad their root by the bar sizes.
//
// WindowInsets.Type / getInsets are API 30+. On API 28–29 we fall back to the
// deprecated system-window inset getters so the controller still runs on
// Android 9–10.
public final class Ui {

    private Ui() {}

    /** Pad root for status + navigation bars. */
    public static void padForSystemBars(View root) {
        padForInsets(root, /*ime=*/ false);
    }

    /** Pad root for system bars and the IME (keyboard). */
    public static void padForSystemBarsAndIme(View root) {
        padForInsets(root, /*ime=*/ true);
    }

    // Add the insets to whatever padding the layout already declares.
    // Applied on top of the XML padding, not instead of it.
    @SuppressLint("NewApi")
    @SuppressWarnings("deprecation")
    private static void padForInsets(View root, boolean ime) {
        int left   = root.getPaddingLeft();
        int top    = root.getPaddingTop();
        int right  = root.getPaddingRight();
        int bottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int inL;
            int inT;
            int inR;
            int inB;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int types = WindowInsets.Type.systemBars();
                if (ime) types |= WindowInsets.Type.ime();
                Insets in = insets.getInsets(types);
                inL = in.left;
                inT = in.top;
                inR = in.right;
                inB = in.bottom;
            } else {
                inL = insets.getSystemWindowInsetLeft();
                inT = insets.getSystemWindowInsetTop();
                inR = insets.getSystemWindowInsetRight();
                inB = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left + inL, top + inT,
                         right + inR, bottom + inB);
            return insets;
        });
        root.requestApplyInsets();
    }
}
