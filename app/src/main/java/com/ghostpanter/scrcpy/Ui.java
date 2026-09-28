package com.ghostpanter.scrcpy;

import android.content.Context;
import android.content.res.Configuration;
import android.annotation.SuppressLint;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import java.util.Locale;

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

    /** Primary tablet threshold; matches {@code layout-sw600dp}. */
    public static final int TABLET_SMALLEST_WIDTH_DP = 600;

    private Ui() {}

    /**
     * Auto-detect tablet vs phone — single source of truth for mode-specific
     * behavior (native {@code max_size} default, Mirror tools expectation, etc.).
     * Resource qualifiers ({@code layout-sw600dp} / {@code layout-w600dp}) still
     * choose which XML inflates; call sites should use this helper rather than
     * re-reading {@link Configuration} ad hoc.
     *
     * <p>Treat as tablet when <b>any</b> of:
     * <ul>
     *   <li>{@code smallestScreenWidthDp >= 600} (primary; matches sw600dp layouts), or</li>
     *   <li>{@link Configuration#SCREENLAYOUT_SIZE_LARGE} / {@code XLARGE}
     *       (via {@link Configuration#SCREENLAYOUT_SIZE_MASK}).</li>
     * </ul>
     *
     * <p>Note: {@code layout-w600dp} may still inflate a tools pane on a phone in
     * landscape (available width ≥ 600) even when this returns false. Mirror binds
     * tools when the view exists, and logs a graceful fallback if tablet=true but
     * {@code tools_pane} is missing.
     */
    public static boolean isTablet(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        if (c.smallestScreenWidthDp >= TABLET_SMALLEST_WIDTH_DP) {
            return true;
        }
        int size = c.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        return size == Configuration.SCREENLAYOUT_SIZE_LARGE
                || size == Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    /** Available width ≥ 600dp (w600dp), e.g. tablet or phone landscape. */
    public static boolean isWide(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        return c.screenWidthDp >= TABLET_SMALLEST_WIDTH_DP;
    }

    /**
     * Compact summary for logs: mode plus the Configuration fields that drove
     * {@link #isTablet(Context)}.
     */
    public static String deviceModeSummary(Context ctx) {
        Configuration c = ctx.getResources().getConfiguration();
        int size = c.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK;
        return String.format(Locale.US,
                "mode=%s sw=%ddp w=%ddp h=%ddp screenLayoutSize=%d",
                isTablet(ctx) ? "tablet" : "phone",
                c.smallestScreenWidthDp, c.screenWidthDp, c.screenHeightDp, size);
    }

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
