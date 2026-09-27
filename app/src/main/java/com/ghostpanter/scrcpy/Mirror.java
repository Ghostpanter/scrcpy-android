package com.ghostpanter.scrcpy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

// Full-screen mirror activity. Pulls target host/port from intent
// extras, starts a Sessions foreground service to keep the process
// alive during brief backgrounding, and owns the Session itself.
//
// Every build uses the same SurfaceView layout. Tests must exercise the
// renderer users receive, not a debug-only TextureView substitute.
//
// Surface lifetime is decoupled from session lifetime: when the surface
// goes away (rotation, background) we swap the session's video surface
// to null and let the wire keep draining. When the surface comes back
// we swap the new one in. Audio and control streams are unaffected.
//
// Session state vs activity lifetime: a fatal session error does NOT
// finish() the activity any more - instead the status bar transitions
// to DISCONNECTED and exposes a Reconnect button.
public final class Mirror extends Activity {

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";

    // Arbitrary request code for POST_NOTIFICATIONS - we don't react to
    // the result; the system caches the choice for next launch.
    private static final int RQ_POST_NOTIFICATIONS = 1001;

    private enum State { CONNECTING, CONNECTED, DISCONNECTED }

    private volatile Adb   adb;
    private Devices.Device target;
    private Session        session;
    private Surface        currentSurface;
    private volatile boolean destroyed;
    private long           sessionGeneration;
    private boolean        stoppingSession;
    private State          state = State.CONNECTING;
    private int            connectedW, connectedH;
    private long           connectedGeometryVersion;
    private int            gestureBottomInset;
    private int            systemTopInset;
    private boolean        immersiveOk;

    private SurfaceView surfaceView;

    // Always present (declared in both layouts).
    private View     root;
    private View     statusBar;
    private TextView statusText;
    private Button   reconnectBtn;
    private Button   disconnectBtn;

    // Blind unlock assist (Android 12+ FLAG_SECURE black screen).
    private Button unlockFab;
    private View   unlockPanel;
    private boolean unlockPanelOpen;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.mirror);
        // Start CONNECTING with system bars visible so the status pill can
        // clear status/cutout. Immersive hide applies once CONNECTED.
        prepareEdgeToEdge();
        showSystemBarsForStatus();
        // Hold the source screen awake for as long as Mirror is in
        // front. Cleared automatically when the activity is destroyed.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String host = getIntent().getStringExtra(EXTRA_HOST);
        int port = getIntent().getIntExtra(EXTRA_PORT, -1);
        if (host == null || port <= 0 || port > 65535) {
            Log.e("mirror: bad extras host=%s port=%d", host, port);
            finish();
            return;
        }
        target = resolveTarget(host, port);
        if (target == null) {
            Log.e("mirror: refusing unsaved target %s:%d", host, port);
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        View video = findViewById(R.id.surface);
        if (!(video instanceof SurfaceView)) {
            Log.e("mirror: layout has no SurfaceView at R.id.surface");
            finish();
            return;
        }
        surfaceView = (SurfaceView) video;
        surfaceView.getHolder().addCallback(holderCallback);

        root = findViewById(R.id.root);
        // Rotation and insets change the container without touching the
        // video surface, so re-fit from here as well.
        root.addOnLayoutChangeListener(
                (view, l, t, r, b, ol, ot, or, ob) -> applyLetterbox());
        // Keep the target's bottom edge above the source's mandatory Home
        // gesture area. A target gesture can then start on the mirrored
        // handle instead of being claimed by the source system.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.mandatorySystemGestures()).bottom;
            Insets bars = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            boolean changed = false;
            if (gestureBottomInset != bottom) {
                gestureBottomInset = bottom;
                changed = true;
            }
            if (systemTopInset != bars.top) {
                systemTopInset = bars.top;
                changed = true;
            }
            if (changed) {
                applyLetterbox();
                insetUnlockControls();
            }
            return insets;
        });
        root.requestApplyInsets();

        statusBar     = findViewById(R.id.status_bar);
        statusText    = findViewById(R.id.status_text);
        reconnectBtn  = findViewById(R.id.reconnect);
        disconnectBtn = findViewById(R.id.disconnect);
        insetStatusBar();
        reconnectBtn.setOnClickListener(view -> reconnect());
        if (disconnectBtn != null) {
            disconnectBtn.setOnClickListener(view -> disconnectAndLeave());
        }
        setupUnlockAssist();
        updateStatusBar();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackRequested);
        }

        requestNotificationsIfNeeded();
        startKeepalive();

        if (!Settings.hintBackShown(this)) {
            Toast.makeText(this, R.string.hint_back, Toast.LENGTH_LONG).show();
            Settings.setHintBackShown(this, true);
        }

        new Thread(() -> {
            try {
                Adb a = Adb.getInstance(getApplicationContext());
                runOnUiThread(() -> {
                    if (destroyed) return;
                    adb = a;
                    if (session == null && currentSurface != null) {
                        startSession(currentSurface);
                    }
                });
            } catch (Exception e) {
                Log.e(e, "mirror: adb init");
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Toast.makeText(this, getString(R.string.adb_init_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }, "adb-init").start();
    }

    // ---- surface lifecycle ----

    private final SurfaceHolder.Callback holderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.i("mirror: surface created");
            attachSurface(holder.getSurface());
        }
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            Log.i("mirror: surface changed %dx%d", w, h);
            applyLetterbox();
        }
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.i("mirror: surface destroyed");
            detachSurface();
        }
    };

    // ---- session driver ----

    private void attachSurface(Surface s) {
        if (session != null && currentSurface != null) session.swapSurface(null);
        currentSurface = s;
        if (session != null) {
            session.swapSurface(s);
            applyLetterbox();
            return;
        }
        if (adb == null) return; // adb-init thread will start the session
        if (stoppingSession) return; // stop worker starts the latest target
        if (state == State.DISCONNECTED) return; // wait for user to tap reconnect
        startSession(s);
        applyLetterbox();
    }

    // Size the video view to the target's aspect ratio inside the root
    // frame and centre it, so the black root shows through as letterbox
    // bars instead of the picture being stretched to the source's screen
    // shape. Also tells the session where the picture ended up, because
    // touches arrive in window coordinates and must be offset by the bars.
    //
    // No-ops until both the container and the target geometry are known;
    // every caller is a point where one of them may have just changed.
    private void applyLetterbox() {
        View v = surfaceView;
        if (v == null || root == null || session == null) return;
        int cw = root.getWidth(), ch = root.getHeight();
        // Full-window letterbox only when immersive hide succeeded; otherwise
        // keep the picture clear of the (visible) top system bar / cutout.
        int topInset = (state == State.CONNECTED && immersiveOk) ? 0 : systemTopInset;
        int availableH = ch - gestureBottomInset - topInset;
        int tw = connectedW, th = connectedH;
        if (cw <= 0 || availableH <= 0 || tw <= 0 || th <= 0) return;

        float scale = Math.min(cw / (float) tw, availableH / (float) th);
        int w = Math.min(cw, Math.max(1, Math.round(tw * scale)));
        int h = Math.min(availableH, Math.max(1, Math.round(th * scale)));
        int x = (cw - w) / 2;
        int y = topInset + (availableH - h) / 2;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (lp.width != w || lp.height != h || lp.leftMargin != x
                || lp.topMargin != y || lp.gravity != (Gravity.TOP | Gravity.START)) {
            lp.width = w;
            lp.height = h;
            lp.leftMargin = x;
            lp.topMargin = y;
            lp.gravity = Gravity.TOP | Gravity.START;
            v.setLayoutParams(lp);   // re-layout re-enters here, then converges
            Log.i("mirror: letterbox %dx%d -> %dx%d in %dx%d top=%d gesture_bottom=%d immersive=%s",
                    tw, th, w, h, cw, ch, topInset, gestureBottomInset, immersiveOk);
        }
        session.setViewport(connectedGeometryVersion, x, y, w, h);
    }

    private void detachSurface() {
        if (session != null) session.swapSurface(null);
        currentSurface = null;
    }

    private void startSession(Surface s) {
        if (destroyed) return;
        // Re-read the row so forgetting a device invalidates stale tasks and
        // notifications before they open a new session.
        Devices.Device current = target;
        Devices.Device resolved = resolveTarget(current.host, current.port);
        if (resolved == null) {
            state = State.DISCONNECTED;
            updateStatusBar();
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            return;
        }
        target = resolved;
        state = State.CONNECTING;
        updateStatusBar();
        long generation = ++sessionGeneration;
        // Do not show Magisk-waiting UI until the server reports a pending grant.
        // Already-authorized devices skip the long wait and stay on「正在连接…」.
        session = new Session(this, adb, target, s, new Session.Listener() {
            @Override public void onConnected(long geometryVersion, int w, int h) {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.CONNECTED;
                    connectedGeometryVersion = geometryVersion;
                    connectedW = w; connectedH = h;
                    syncTargetFromSession();
                    updateStatusBar();
                    applyLetterbox();
                    if (session != null) session.syncClipboard();
                });
            }
            @Override public void onReconnecting() {
                Log.i("mirror: link lost, reconnecting");
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.CONNECTING;
                    updateStatusBar();
                });
            }
            @Override public void onElevateWaitingForGrant() {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    if (statusText != null && state == State.CONNECTING) {
                        statusText.setText(getString(R.string.elevate_waiting));
                    }
                    Toast.makeText(Mirror.this, R.string.elevate_waiting, Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onElevateStatus(Server.ElevateStatus status) {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    showElevateStatus(status);
                });
            }
            @Override public void onSecureOemRejected() {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    showElevateStatus(Server.ElevateStatus.SECURE_OEM_REJECTED);
                });
            }
            @Override public void onError(Throwable t) {
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    Toast.makeText(Mirror.this, describe(t), Toast.LENGTH_LONG).show();
                });
            }
            @Override public void onStopped() {
                Log.i("mirror: session stopped");
                runOnUiThread(() -> {
                    if (destroyed || generation != sessionGeneration) return;
                    state = State.DISCONNECTED;
                    updateStatusBar();
                });
            }
        });
        session.start();
    }

    private void disconnectAndLeave() {
        Log.i("mirror: disconnect tapped — stop session and return to device list");
        // finish() → onDestroy stops the Session and Sessions service.
        finish();
    }

    private void showElevateStatus(Server.ElevateStatus status) {
        if (status == null || status == Server.ElevateStatus.DISABLED) return;
        int res;
        boolean offerLogs = false;
        switch (status) {
            case ROOT:
                res = R.string.elevate_root_ok;
                break;
            case DENIED:
                res = R.string.elevate_su_denied;
                offerLogs = true;
                break;
            case UNAVAILABLE:
                res = R.string.elevate_su_unavailable;
                offerLogs = true;
                break;
            case FALLBACK:
                res = R.string.elevate_fallback;
                offerLogs = true;
                break;
            case SECURE_OEM_REJECTED:
                res = R.string.elevate_secure_oem_rejected;
                offerLogs = true;
                break;
            default:
                return;
        }
        String msg = getString(res);
        // Long toast still truncates on many OEMs — also show a dialog with
        // an explicit「查看日志」action so the ring-buffer transcript is reachable.
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        if (statusText != null
                && status != Server.ElevateStatus.ROOT
                && (state == State.CONNECTING
                    || status == Server.ElevateStatus.SECURE_OEM_REJECTED)) {
            statusText.setText(msg);
            if (offerLogs) {
                statusText.setOnClickListener(v -> openLogViewer());
            }
        }
        if (offerLogs) {
            new AlertDialog.Builder(this)
                    .setMessage(msg + "\n\n" + getString(R.string.elevate_open_logs_hint))
                    .setPositiveButton(R.string.elevate_fallback_view_logs,
                            (d, w) -> openLogViewer())
                    .setNegativeButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void openLogViewer() {
        startActivity(new android.content.Intent(this, LogViewerActivity.class));
    }

    private void reconnect() {
        Log.i("mirror: reconnect tapped");
        state = State.CONNECTING;
        updateStatusBar();
        if (stoppingSession) return;
        stoppingSession = true;
        Session old = session;
        session = null;
        sessionGeneration++;
        connectedW = connectedH = 0;
        connectedGeometryVersion = 0;
        // Stop the old session off the UI thread (teardown closes
        // sockets and joins the server's log pump), THEN start the new
        // one. Sequencing matters: both sessions share the singleton
        // Adb, so the old teardown's disconnect must finish before the
        // new bring-up connects.
        new Thread(() -> {
            boolean stopped = old == null || old.stop();
            runOnUiThread(() -> {
                if (destroyed) return;
                stoppingSession = false;
                if (!stopped) {
                    state = State.DISCONNECTED;
                    updateStatusBar();
                    Toast.makeText(this, R.string.session_stop_timeout,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (session != null) return;  // another path already started one
                if (adb == null || currentSurface == null) return;
                startSession(currentSurface);
            });
        }, "session-stop").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        if (host == null || port <= 0 || port > 65535) {
            Log.w("mirror: ignoring bad replacement target host=%s port=%d", host, port);
            return;
        }
        if (target.host.equals(host) && target.port == port) return;

        Devices.Device replacement = resolveTarget(host, port);
        if (replacement == null) {
            Log.w("mirror: refusing unsaved replacement target %s:%d", host, port);
            Toast.makeText(this, R.string.device_not_paired, Toast.LENGTH_LONG).show();
            return;
        }
        setIntent(intent);
        target = replacement;
        connectedW = connectedH = 0;
        connectedGeometryVersion = 0;
        startKeepalive();   // repoint the notification at the new target
        reconnect();
    }

    private void updateStatusBar() {
        if (statusText == null) return;
        String s;
        switch (state) {
            case CONNECTED:
                s = String.format(Locale.ROOT, "%s:%d  %dx%d  %s",
                        target.host, target.port, connectedW, connectedH,
                        Settings.videoCodec(this));
                break;
            case DISCONNECTED:
                s = String.format(Locale.ROOT, "%s:%d  %s",
                        target.host, target.port, getString(R.string.disconnected));
                break;
            default:
                s = String.format(Locale.ROOT, "%s:%d  %s",
                        target.host, target.port, getString(R.string.connecting));
        }
        statusText.setText(s);
        reconnectBtn.setVisibility(state == State.DISCONNECTED ? View.VISIBLE : View.GONE);
        // Disconnect stays available while mirroring / connecting / after drop.
        if (disconnectBtn != null) disconnectBtn.setVisibility(View.VISIBLE);

        // Keep the status pill visible (including while CONNECTED) so
        // 「断开连接」 is always easy to tap — not only buried behind Back×2.
        if (statusBar != null) {
            statusBar.setVisibility(View.VISIBLE);
        }
        if (unlockFab != null) {
            unlockFab.setVisibility(state == State.CONNECTED && !unlockPanelOpen
                    ? View.VISIBLE : View.GONE);
        }
        if (state != State.CONNECTED) {
            hideUnlockPanel();
        }
        if (state == State.CONNECTED) {
            immersive();
        } else {
            showSystemBarsForStatus();
        }
    }

    // ---- input ----

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // While the blind-unlock panel is open, swallow surface touches so
        // they are not injected as remote taps on a black lock/secure screen.
        if (unlockPanelOpen) return true;
        if (session != null) {
            session.onTouch(ev);
            return true;
        }
        return super.onTouchEvent(ev);
    }

    // Back goes to the target; Back twice in quick succession leaves the
    // mirror.
    //
    // It used to be long-press-Back to reach the target and short-press
    // to do nothing. That stopped working twice over: gesture navigation
    // has no Back key to hold, and at targetSdk 35 and later predictive back is on
    // by default, so the framework routes Back through
    // OnBackInvokedDispatcher and onKeyDown/onKeyLongPress are never
    // called for it at all. The advertised feature was unreachable on
    // every current device.
    private static final long DOUBLE_BACK_MS = 600L;
    private long lastBackAtMs;

    private void onBackRequested() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBackAtMs < DOUBLE_BACK_MS) {
            finish();
            return;
        }
        lastBackAtMs = now;
        if (session != null) session.onBack();
    }

    // Pre-33 devices (minSdk is 31) still deliver Back this way. API 33+
    // uses the OnBackInvokedDispatcher callback registered in onCreate;
    // lint does not follow that version split.
    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        onBackRequested();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (session != null && shouldForward(ev)) {
            session.onKey(ev);
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // ---- lifecycle ----

    @Override
    protected void onDestroy() {
        destroyed = true;
        sessionGeneration++;
        ui.removeCallbacksAndMessages(null);
        currentSurface = null;
        Session s = session;
        session = null;
        if (s != null) {
            // Teardown blocks on socket closes and a thread join; keep
            // it off the UI thread.
            new Thread(() -> s.stop(), "session-stop").start();
        }
        stopService(new Intent(this, Sessions.class));
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            if (state == State.CONNECTED) immersive();
            if (session != null) session.syncClipboard();
        }
    }

    // Android 13+ requires runtime grant for POST_NOTIFICATIONS. The
    // foreground service still starts without the grant, but granting it
    // keeps the active session visible in the notification drawer.
    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                RQ_POST_NOTIFICATIONS);
    }

    // Most of what reaches here has a null message - a bare IOException
    // from the socket, an SSLHandshakeException - and "session error:
    // null" is what the user was being shown for the commonest failure
    // there is.
    private String describe(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && !m.isEmpty()) return getString(R.string.session_error, m);
        }
        return getString(R.string.session_error,
                t == null ? getString(R.string.error_unknown) : t.getClass().getSimpleName());
    }

    // Missing rows fail closed. A stale notification, restored task, or
    // malformed internal intent must not create a session for an unsaved row.
    private Devices.Device resolveTarget(String host, int port) {
        try {
            Devices.Device exact = Devices.find(this, host, port);
            if (exact != null) return exact;
            // Port may have been rediscovered/upserted (e.g. reboot → new TLS port).
            for (Devices.Device d : Devices.load(this)) {
                if (d.host.equals(host)) return d;
            }
            return null;
        } catch (java.io.IOException e) {
            Log.e(e, "mirror: cannot read paired devices");
            return null;
        }
    }

    private void syncTargetFromSession() {
        if (session == null) return;
        Devices.Device ep = session.getTarget();
        if (ep == null) return;
        if (target != null && target.host.equals(ep.host) && target.port == ep.port) return;
        target = ep;
        startKeepalive();
    }

    // The foreground service exists only to keep this process alive while
    // mirroring. It carries the target so its notification can lead back
    // here rather than somewhere that would tear the session down.
    private void startKeepalive() {
        Intent i = new Intent(this, Sessions.class);
        i.putExtra(EXTRA_HOST, target.host);
        i.putExtra(EXTRA_PORT, target.port);
        startForegroundService(i);
    }

    @SuppressWarnings("deprecation")
    private void prepareEdgeToEdge() {
        // Without this the window stops at the cutout's safe area and the
        // system letterboxes it, which shows up as black bands down the
        // sides of what is supposed to be a full-screen mirror. Only the
        // overlay controls are moved around a display cutout.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        getWindow().setDecorFitsSystemWindows(false);
    }

    private void immersive() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            immersiveOk = true;
        } else {
            immersiveOk = false;
        }
        applyLetterbox();
    }

    private void showSystemBarsForStatus() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.show(WindowInsets.Type.systemBars());
        }
        immersiveOk = false;
        applyLetterbox();
    }

    private void insetStatusBar() {
        int base = getResources().getDimensionPixelSize(R.dimen.space_sm);
        statusBar.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            // While CONNECTING/DISCONNECTED the pill must clear both the
            // status bar and any display cutout — cutout-only left it under
            // the system status area on many devices.
            Insets bars = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
            int left = base + bars.left;
            int top = base + bars.top;
            int right = base + bars.right;
            if (lp.leftMargin != left || lp.topMargin != top
                    || lp.rightMargin != right || lp.bottomMargin != base) {
                lp.setMargins(left, top, right, base);
                view.setLayoutParams(lp);
            }
            if (systemTopInset != bars.top) {
                systemTopInset = bars.top;
                applyLetterbox();
            }
            return windowInsets;
        });
        statusBar.requestApplyInsets();
    }

    // ---- blind unlock assist ----

    private void setupUnlockAssist() {
        unlockFab = findViewById(R.id.unlock_fab);
        unlockPanel = findViewById(R.id.unlock_panel);
        if (unlockFab == null || unlockPanel == null) {
            Log.w("mirror: unlock assist views missing");
            return;
        }
        unlockFab.setOnClickListener(v -> showUnlockPanel());
        findViewById(R.id.unlock_close).setOnClickListener(v -> hideUnlockPanel());
        findViewById(R.id.unlock_wake).setOnClickListener(v -> {
            if (session != null) session.wakeOrBack();
        });
        findViewById(R.id.unlock_power).setOnClickListener(v ->
                injectUnlockKey(KeyEvent.KEYCODE_POWER));

        int[] digitIds = {
                R.id.unlock_key_0, R.id.unlock_key_1, R.id.unlock_key_2,
                R.id.unlock_key_3, R.id.unlock_key_4, R.id.unlock_key_5,
                R.id.unlock_key_6, R.id.unlock_key_7, R.id.unlock_key_8,
                R.id.unlock_key_9,
        };
        int[] digitCodes = {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2,
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5,
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8,
                KeyEvent.KEYCODE_9,
        };
        for (int i = 0; i < digitIds.length; i++) {
            final int code = digitCodes[i];
            findViewById(digitIds[i]).setOnClickListener(v -> injectUnlockKey(code));
        }
        findViewById(R.id.unlock_key_del).setOnClickListener(v ->
                injectUnlockKey(KeyEvent.KEYCODE_DEL));
        findViewById(R.id.unlock_key_enter).setOnClickListener(v ->
                injectUnlockKey(KeyEvent.KEYCODE_ENTER));
        insetUnlockControls();
    }

    private void injectUnlockKey(int keycode) {
        if (session == null) return;
        session.injectKeycode(keycode);
    }

    private void showUnlockPanel() {
        if (unlockPanel == null) return;
        unlockPanelOpen = true;
        unlockPanel.setVisibility(View.VISIBLE);
        if (unlockFab != null) unlockFab.setVisibility(View.GONE);
        insetUnlockControls();
    }

    private void hideUnlockPanel() {
        unlockPanelOpen = false;
        if (unlockPanel != null) unlockPanel.setVisibility(View.GONE);
        if (unlockFab != null) {
            unlockFab.setVisibility(state == State.CONNECTED ? View.VISIBLE : View.GONE);
        }
    }

    private void insetUnlockControls() {
        int base = getResources().getDimensionPixelSize(R.dimen.space_lg);
        int bottom = base + gestureBottomInset;
        if (unlockFab != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) unlockFab.getLayoutParams();
            if (lp.bottomMargin != bottom || lp.rightMargin != base) {
                lp.bottomMargin = bottom;
                lp.rightMargin = base;
                unlockFab.setLayoutParams(lp);
            }
        }
        if (unlockPanel != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) unlockPanel.getLayoutParams();
            int side = getResources().getDimensionPixelSize(R.dimen.space_md);
            if (lp.bottomMargin != bottom
                    || lp.leftMargin != side || lp.rightMargin != side) {
                lp.bottomMargin = bottom;
                lp.leftMargin = side;
                lp.rightMargin = side;
                unlockPanel.setLayoutParams(lp);
            }
        }
    }

    private static boolean shouldForward(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_CENTER) return true;
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return true;
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return true;
        switch (code) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_MOVE_HOME:
            case KeyEvent.KEYCODE_MOVE_END:
            case KeyEvent.KEYCODE_INSERT:
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT:
            case KeyEvent.KEYCODE_CAPS_LOCK:
            case KeyEvent.KEYCODE_NUM_LOCK:
            case KeyEvent.KEYCODE_SCROLL_LOCK:
            case KeyEvent.KEYCODE_COMMA:
            case KeyEvent.KEYCODE_PERIOD:
            case KeyEvent.KEYCODE_SLASH:
            case KeyEvent.KEYCODE_BACKSLASH:
            case KeyEvent.KEYCODE_SEMICOLON:
            case KeyEvent.KEYCODE_APOSTROPHE:
            case KeyEvent.KEYCODE_GRAVE:
            case KeyEvent.KEYCODE_LEFT_BRACKET:
            case KeyEvent.KEYCODE_RIGHT_BRACKET:
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_EQUALS:
                return true;
            default:
                return false;
        }
    }
}
