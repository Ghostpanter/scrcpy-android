package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.display.DisplayMonitor;
import com.genymobile.scrcpy.display.DisplayProperties;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Orientation;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.AffineMatrix;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.system.Os;
import android.system.ErrnoException;
import android.os.IBinder;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

public class ScreenCapture extends SurfaceCapture {

    private final VirtualDisplayListener vdListener;
    private final int displayId;
    private final Rect crop;
    private Orientation.Lock captureOrientationLock;
    private Orientation captureOrientation;
    private final float angle;

    private VideoConstraints videoConstraints;

    private DisplayInfo displayInfo;
    private Size videoSize;

    private final DisplayMonitor displayMonitor = new DisplayMonitor();

    private IBinder display;
    private VirtualDisplay virtualDisplay;

    private AffineMatrix transform;
    private OpenGLRunner glRunner;

    public ScreenCapture(VirtualDisplayListener vdListener, Options options) {
        this.vdListener = vdListener;
        this.displayId = options.getDisplayId();
        assert displayId != Device.DISPLAY_ID_NONE;
        this.crop = options.getCrop();
        this.captureOrientationLock = options.getCaptureOrientationLock();
        this.captureOrientation = options.getCaptureOrientation();
        assert captureOrientationLock != null;
        assert captureOrientation != null;
        this.angle = options.getAngle();
    }

    @Override
    public void init(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        displayMonitor.start(displayId, (props) -> getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED));
    }

    @Override
    public void prepare() throws ConfigurationException {
        displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (displayInfo == null) {
            Ln.e("Display " + displayId + " not found\n" + LogUtils.buildDisplayListMessage());
            throw new ConfigurationException("Unknown display id: " + displayId);
        }

        if ((displayInfo.getFlags() & DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) == 0) {
            Ln.w("Display doesn't have FLAG_SUPPORTS_PROTECTED_BUFFERS flag, mirroring can be restricted");
        }

        Size displaySize = displayInfo.getSize();
        int displayRotation = displayInfo.getRotation();
        displayMonitor.setSessionDisplayProperties(new DisplayProperties(displaySize, displayRotation));

        if (captureOrientationLock == Orientation.Lock.LockedInitial) {
            // The user requested to lock the video orientation to the current orientation
            captureOrientationLock = Orientation.Lock.LockedValue;
            captureOrientation = Orientation.fromRotation(displayRotation);
        }

        VideoFilter filter = new VideoFilter(displaySize);

        if (crop != null) {
            boolean transposed = (displayRotation % 2) != 0;
            filter.addCrop(crop, transposed);
        }

        boolean locked = captureOrientationLock != Orientation.Lock.Unlocked;
        filter.addOrientation(displayRotation, locked, captureOrientation);
        filter.addAngle(angle);

        transform = filter.getInverseTransform();
        videoSize = filter.getOutputSize().constrain(videoConstraints);
    }

    @Override
    public void start(Surface surface) throws IOException {
        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        Size inputSize;
        if (transform != null) {
            // If there is a filter, it must receive the full display content
            inputSize = displayInfo.getSize();
            assert glRunner == null;
            OpenGLFilter glFilter = new AffineOpenGLFilter(transform);
            glRunner = new OpenGLRunner(glFilter);
            surface = glRunner.start(inputSize, videoSize, surface);
        } else {
            // If there is no filter, the display must be rendered at target video size directly
            inputSize = videoSize;
        }

        Exception firstFailure = tryOpenDisplay(surface, inputSize);

        if (virtualDisplay == null && display == null) {
            throw new IOException("Could not create display", firstFailure);
        }

        if (vdListener != null) {
            int virtualDisplayId;
            PositionMapper positionMapper;
            if (virtualDisplay == null || displayId == 0) {
                // Surface control or main display: send all events to the original display, relative to the device size
                Size deviceSize = displayInfo.getSize();
                positionMapper = PositionMapper.create(videoSize, transform, deviceSize);
                virtualDisplayId = displayId;
            } else {
                // The positions are relative to the virtual display, not the original display (so use inputSize, not deviceSize!)
                positionMapper = PositionMapper.create(videoSize, transform, inputSize);
                virtualDisplayId = virtualDisplay.getDisplay().getDisplayId();
            }
            vdListener.onNewVirtualDisplay(virtualDisplayId, positionMapper);
        }
    }

    /**
     * Try secure (AID_SYSTEM / root), then non-secure DM/SC, then setuid(2000).
     * Returns the first failure for diagnostics (may be null on success).
     */
    private Exception tryOpenDisplay(Surface surface, Size inputSize) throws IOException {
        Exception firstFailure = null;
        boolean root = isRealUidRoot();
        boolean systemUid = isRealUidSystem();
        if (root) {
            firstFailure = trySecureCapture(surface, inputSize);
        } else if (systemUid) {
            // Born as AID_SYSTEM (client su 1000) — no mid-flight setresuid.
            firstFailure = trySecureAsCurrentSystem(surface, inputSize);
        }

        // On A16, SurfaceControl.createDisplay is gone. If secure already failed
        // under ruid=0, HyperOS OEM DisplayManager still rejects packageName vs
        // uid — skip futile non-secure DM/SC attempts and go to setuid sooner.
        boolean skipNonSecureFallbacks = root
                && virtualDisplay == null
                && display == null
                && firstFailure != null
                && !SurfaceControl.hasCreateDisplayMethod();
        if (skipNonSecureFallbacks) {
            Ln.i("Display: SurfaceControl unavailable and secure failed; skipping non-secure fallbacks");
        }

        if (!skipNonSecureFallbacks && virtualDisplay == null && display == null) {
            firstFailure = tryNonSecureDisplay(surface, inputSize, firstFailure);
        }

        if (virtualDisplay == null && display == null && isRealUidRoot()) {
            firstFailure = fallBackToShellUid(surface, inputSize, firstFailure);
        }
        return firstFailure;
    }

    /**
     * Privileged secure capture (Genymobile/scrcpy#4947 / #3049).
     * <p>
     * HyperOS / Android 16: {@code Binder.getCallingUid()} follows <b>real</b>
     * uid, not euid. {@code seteuid(1000)} alone still presents callingUid=0,
     * and package {@code android} is owned by uid 1000 →
     * {@code packageName must match the owner uid}. Fix: temporarily
     * {@code setresuid(1000,1000,0)} (or setreuid dance) so ruid=euid=1000
     * while saved-uid stays 0 for restore. Then create SECURE VD. On failure
     * restore root and let the setuid(2000) safety net run.
     */
    private Exception trySecureCapture(Surface surface, Size inputSize) {
        // Primary: ruid=euid=1000 (AID_SYSTEM), saved uid 0
        try {
            runAsSystemUid(() -> openSecureDisplay(surface, inputSize));
            Ln.i("Display: secure capture enabled (ruid=euid=1000)");
            return null;
        } catch (Exception systemException) {
            logSecureRejectHint(systemException);
            Ln.w("Secure display via setresuid(AID_SYSTEM) failed, trying root identity variants",
                    systemException);

            // Secondary: callingUid==ROOT — AOSP exempts root from package checks;
            // HyperOS may not. Try android / shell package under full root euid.
            // FakeContext.setPackageOverride ensures create logs/uses the requested pkg.
            for (String pkg : new String[] {
                    FakeContext.ROOT_PACKAGE_NAME,
                    FakeContext.PACKAGE_NAME,
            }) {
                try {
                    runAsRootWithPackage(pkg, () -> openSecureDisplay(surface, inputSize));
                    Ln.i("Display: secure capture enabled (ruid=0 euid=0 pkg=" + pkg + ")");
                    return null;
                } catch (Exception e) {
                    logSecureRejectHint(e);
                    Ln.w("Secure display as root pkg=" + pkg + " failed", e);
                }
            }
            Ln.w("Secure display creation failed, falling back", systemException);
            return systemException;
        }
    }

    private Exception tryNonSecureDisplay(Surface surface, Size inputSize, Exception firstFailure) {
        try {
            virtualDisplay = ServiceManager.getDisplayManager()
                    .createVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
            Ln.d("Display: using DisplayManager API");
            return firstFailure;
        } catch (Exception displayManagerException) {
            if (firstFailure == null) {
                firstFailure = displayManagerException;
            }
            if (Build.BRAND.equalsIgnoreCase("oculus") && Build.MODEL.toLowerCase(Locale.ROOT).startsWith("quest")) {
                // Workaround for buggy createVirtualDisplay on Quest
                try {
                    virtualDisplay = (VirtualDisplay) VirtualDisplay.class.getDeclaredConstructors()[0]
                            .newInstance(null, null, null, surface);
                } catch (ReflectiveOperationException e) {
                    Ln.e("Could not create VirtualDisplay", e);
                }
                return firstFailure;
            }
            try {
                display = createDisplay(/* secure */ false);
                Size deviceSize = displayInfo.getSize();
                int layerStack = displayInfo.getLayerStack();
                setDisplaySurface(display, surface, deviceSize.toRect(), inputSize.toRect(), layerStack);
                Ln.d("Display: using SurfaceControl API");
            } catch (Exception surfaceControlException) {
                Ln.e("Could not create display using DisplayManager", displayManagerException);
                Ln.e("Could not create display using SurfaceControl", surfaceControlException);
                if (firstFailure != null) {
                    Ln.e("Earlier secure attempt also failed", firstFailure);
                }
                // Do not AssertionError here — setuid(2000) safety net below.
            }
            return firstFailure;
        }
    }

    /**
     * Safety net (HyperOS / Android 16): if still no display while retaining
     * ruid=0, permanently drop to shell like stock scrcpy and retry once.
     * Server.dropRootPrivileges() only did seteuid(2000), so restore euid
     * before setuid (Linux requires euid==0 / CAP_SETUID).
     * Only reached when secure setresuid path failed — do not call if secure
     * succeeded.
     */
    private Exception fallBackToShellUid(Surface surface, Size inputSize, Exception firstFailure)
            throws IOException {
        Ln.i("Display: falling back to setuid(2000) after root display create failed");
        try {
            // Ensure full root before irreversible setuid (may be euid=2000).
            ensureRootEuid();
            // If a failed setresuid attempt left ruid!=0, restore via saved uid.
            restoreRootUids();
            int beforeRuid = Os.getuid();
            int beforeEuid = Os.geteuid();
            Ln.i("Display: before drop ruid=" + beforeRuid + " euid=" + beforeEuid);
            Os.seteuid(0);
            Os.setuid(2000);
            Workarounds.updateFakePackageName(FakeContext.PACKAGE_NAME); // com.android.shell
            Ln.i("Display: after drop ruid=" + Os.getuid() + " euid=" + Os.geteuid());
        } catch (Exception e) {
            throw new IOException("seteuid(0)/setuid(2000) failed after display create failure", e);
        }
        try {
            virtualDisplay = ServiceManager.getDisplayManager()
                    .createVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
            Ln.i("Display: using DisplayManager API after setuid(2000)");
            return firstFailure;
        } catch (Exception afterSetuidException) {
            Ln.e("Could not create display after setuid(2000)", afterSetuidException);
            if (firstFailure != null) {
                Ln.e("Earlier secure attempt also failed", firstFailure);
            }
            throw new IOException("Could not create display", afterSetuidException);
        }
    }

    @Override
    public void stop() {
        if (glRunner != null) {
            glRunner.stopAndRelease();
            glRunner = null;
        }
    }

    @Override
    public void release() {
        displayMonitor.stopAndRelease();

        if (display != null) {
            SurfaceControl.destroyDisplay(display);
            display = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }

    @Override
    public Size getSize() {
        return videoSize;
    }

    @Override
    protected boolean applyNewVideoConstraints(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        return true;
    }

    private void openSecureDisplay(Surface surface, Size inputSize) throws Exception {
        // Prefer DisplayManager + VIRTUAL_DISPLAY_FLAG_SECURE (required on A14+;
        // SurfaceControl.createDisplay is deprecated/removed on A16). Fall back to
        // SurfaceControl secure display only when the method still exists.
        int dpi = displayInfo != null ? displayInfo.getDpi() : 160;
        try {
            virtualDisplay = ServiceManager.getDisplayManager().createSecureMirrorVirtualDisplay(
                    "scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface, dpi);
            Ln.d("Display: using DisplayManager API (SECURE)");
            return;
        } catch (Exception displayManagerException) {
            Ln.d("DisplayManager SECURE failed: " + displayManagerException.getMessage());
            if (!SurfaceControl.hasCreateDisplayMethod()) {
                // Do not mask the DM failure behind a missing SC method on A16.
                throw displayManagerException;
            }
            display = createDisplay(/* secure */ true);
            Size deviceSize = displayInfo.getSize();
            int layerStack = displayInfo.getLayerStack();
            setDisplaySurface(display, surface, deviceSize.toRect(), inputSize.toRect(), layerStack);
            Ln.d("Display: using SurfaceControl API (SECURE)");
        }
    }

    /**
     * Already running as AID_SYSTEM (process started via Magisk {@code su 1000}).
     * Sync FakeContext to package {@code android} / uid 1000 and create SECURE VD
     * without setresuid (Binder identity is correct from process birth).
     */
    private Exception trySecureAsCurrentSystem(Surface surface, Size inputSize) {
        String previousPkg = FakeContext.getPackageOverride();
        try {
            Workarounds.updateFakePackageName(FakeContext.ROOT_PACKAGE_NAME);
            Ln.i("Display: already AID_SYSTEM ruid=" + Os.getuid()
                    + " euid=" + Os.geteuid()
                    + " pkg=" + FakeContext.currentPackageName());
            openSecureDisplay(surface, inputSize);
            Ln.i("Display: secure capture enabled (born as AID_SYSTEM)");
            return null;
        } catch (Exception e) {
            logSecureRejectHint(e);
            Ln.w("Secure display as born-AID_SYSTEM failed", e);
            return e;
        } finally {
            if (previousPkg != null) {
                Workarounds.updateFakePackageName(previousPkg);
            } else {
                Workarounds.clearFakePackageOverride();
            }
        }
    }

    private static void logSecureRejectHint(Throwable t) {
        if (isPackageOwnerMismatch(t)) {
            Ln.w("SECURE_VD_OEM_REJECT: DisplayManager refused packageName vs owner/calling uid "
                    + "even with AID_SYSTEM identity. On this HyperOS/Android 16 build Magisk "
                    + "alone cannot unlock FLAG_SECURE layers — need LSPosed + Disable "
                    + "FLAG_SECURE (or equivalent). Marker for client UI.");
        }
    }

    static boolean isPackageOwnerMismatch(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("packageName must match")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static boolean isRealUidRoot() {
        try {
            return Os.getuid() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isRealUidSystem() {
        try {
            return Os.getuid() == FakeContext.SYSTEM_UID;
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface RootAction {
        void run() throws Exception;
    }

    /**
     * Temporarily become AID_SYSTEM for Binder ({@code ruid=euid=1000}), keep
     * saved-uid 0, sync FakeContext to package {@code android}, run action,
     * then restore {@code ruid=0} + previous euid (normally 2000). Does
     * <b>not</b> permanently {@code setuid(2000)}.
     */
    private static void runAsSystemUid(RootAction action) throws Exception {
        int previousEuid = Os.geteuid();
        String previousOverride = FakeContext.getPackageOverride();
        boolean elevated = false;
        try {
            ensureRootEuid();
            setResUid(FakeContext.SYSTEM_UID, FakeContext.SYSTEM_UID, /* suid */ 0);
            elevated = true;
            Workarounds.updateFakePackageName(FakeContext.ROOT_PACKAGE_NAME);
            Ln.i("Display: runAsSystemUid ruid=" + Os.getuid()
                    + " euid=" + Os.geteuid()
                    + " pkg=" + FakeContext.currentPackageName()
                    + " ownerUid=" + FakeContext.ownerUidForPackage());
            action.run();
        } catch (ErrnoException e) {
            throw new IOException("setresuid(1000,1000,0) failed", e);
        } finally {
            try {
                if (elevated || Os.getuid() != 0 || Os.geteuid() != previousEuid) {
                    restoreRootUids();
                    if (previousEuid != 0) {
                        Os.seteuid(previousEuid);
                    }
                }
            } catch (Exception e) {
                Ln.w("Failed to restore uids after AID_SYSTEM attempt (ruid="
                        + safeGetuid() + " euid=" + safeGeteuid() + ")", e);
            }
            restorePackageOverride(previousOverride);
        }
    }

    /**
     * Full root identity (ruid=0,euid=0) with a chosen FakeContext package —
     * probe HyperOS root packageName policy.
     */
    private static void runAsRootWithPackage(String packageName, RootAction action) throws Exception {
        int previousEuid = Os.geteuid();
        String previousOverride = FakeContext.getPackageOverride();
        try {
            ensureRootEuid();
            restoreRootUids(); // ruid=0 euid=0 suid=0
            Workarounds.updateFakePackageName(packageName);
            Ln.i("Display: runAsRootWithPackage ruid=" + Os.getuid()
                    + " euid=" + Os.geteuid()
                    + " pkg=" + FakeContext.currentPackageName()
                    + " (requested=" + packageName + ")"
                    + " ownerUid=" + FakeContext.ownerUidForPackage());
            action.run();
        } catch (ErrnoException e) {
            throw new IOException("restore root for pkg=" + packageName + " failed", e);
        } finally {
            try {
                if (Os.geteuid() != previousEuid) {
                    if (Os.geteuid() != 0 && previousEuid != 0) {
                        Os.seteuid(0);
                    }
                    Os.seteuid(previousEuid);
                }
            } catch (ErrnoException e) {
                Ln.w("Failed to restore euid=" + previousEuid, e);
            }
            restorePackageOverride(previousOverride);
        }
    }

    private static void restorePackageOverride(String previousOverride) {
        if (previousOverride != null) {
            Workarounds.updateFakePackageName(previousOverride);
        } else {
            Workarounds.clearFakePackageOverride();
            Workarounds.syncApplicationInfoFromFakeContext();
        }
    }

    private static void ensureRootEuid() throws ErrnoException {
        if (Os.geteuid() != 0) {
            Os.seteuid(0);
        }
    }

    /**
     * Restore ruid=euid=suid=0 using saved-uid 0 (after a temporary AID_SYSTEM
     * setresuid). Safe no-op if already root.
     */
    private static void restoreRootUids() throws Exception {
        if (Os.getuid() == 0 && Os.geteuid() == 0) {
            return;
        }
        // Need euid 0 (or suid 0) to change ruid back.
        if (Os.geteuid() != 0) {
            Os.seteuid(0);
        }
        setResUid(0, 0, 0);
    }

    /**
     * {@code setresuid(ruid,euid,suid)} via libcore if present; otherwise a
     * {@code setreuid}/{@code seteuid} dance that keeps saved-uid 0 when
     * {@code suid==0}.
     */
    private static void setResUid(int ruid, int euid, int suid) throws Exception {
        Object os = getLibcoreOs();
        Method setresuid = findInstanceMethod(os, "setresuid", int.class, int.class, int.class);
        if (setresuid != null) {
            invokeUidMethod(setresuid, os, ruid, euid, suid);
            return;
        }
        if (suid != 0) {
            throw new IOException("libcore setresuid unavailable; cannot set suid=" + suid);
        }
        // Dance (Linux): setreuid(ruid, 0) sets suid to new euid (0), then
        // seteuid(euid) yields ruid / euid / suid=0.
        ensureRootEuid();
        Method setreuid = findInstanceMethod(os, "setreuid", int.class, int.class);
        if (setreuid == null) {
            throw new IOException("libcore setreuid/setresuid unavailable");
        }
        if (ruid == 0 && euid == 0) {
            invokeUidMethod(setreuid, os, 0, 0);
            return;
        }
        // First park euid at 0 with new ruid so suid becomes 0.
        invokeUidMethod(setreuid, os, ruid, 0);
        if (euid != 0) {
            Os.seteuid(euid);
        }
    }

    private static void invokeUidMethod(Method method, Object os, Object... args) throws Exception {
        try {
            method.invoke(os, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static Object getLibcoreOs() throws Exception {
        Class<?> libcore = Class.forName("libcore.io.Libcore");
        try {
            // Prefer rawOs (Linux) over BlockGuardOs wrapper when present.
            return libcore.getField("rawOs").get(null);
        } catch (NoSuchFieldException ignored) {
            return libcore.getField("os").get(null);
        }
    }

    private static Method findInstanceMethod(Object target, String name, Class<?>... params) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // continue
            }
        }
        for (Class<?> iface : target.getClass().getInterfaces()) {
            try {
                Method m = iface.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // continue
            }
        }
        return null;
    }

    private static int safeGetuid() {
        try {
            return Os.getuid();
        } catch (Exception e) {
            return -1;
        }
    }

    private static int safeGeteuid() {
        try {
            return Os.geteuid();
        } catch (Exception e) {
            return -1;
        }
    }

    private static IBinder createDisplay(boolean secure) throws Exception {
        // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
        // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
        // When running as real root we request secure=true so FLAG_SECURE layers are mirrored.
        if (!secure) {
            secure = Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11
                    || (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11
                    && !"S".equals(Build.VERSION.CODENAME));
        }
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }
}
