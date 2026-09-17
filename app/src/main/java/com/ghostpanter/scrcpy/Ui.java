package com.ghostpanter.scrcpy;

import android.graphics.Insets;
import android.view.View;

// Window inset plumbing for the non-fullscreen activities.
//
// targetSdk 35 and later make every window edge-to-edge on Android 15: the system
// no longer insets the content view, so an unhandled layout draws under
// the status and navigation bars. Mirror wants that and opts in itself;
// Main and Settings do not, so they pad their root by the bar sizes.
public final class Ui {

    private Ui() {}

    // Add the insets of the given WindowInsets.Type mask to whatever
// padding the layout already declares. Applied on top of the XML
// padding, not instead of it, so the layout stays the source of the
// base spacing.
    public static void padForInsets(View root, int types) {
        int left   = root.getPaddingLeft();
        int top    = root.getPaddingTop();
        int right  = root.getPaddingRight();
        int bottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets in = insets.getInsets(types);
            v.setPadding(left + in.left, top + in.top,
                         right + in.right, bottom + in.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
}
