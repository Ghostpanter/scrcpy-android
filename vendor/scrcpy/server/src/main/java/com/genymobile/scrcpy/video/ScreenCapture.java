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
        if (root) {
            firstFailure = trySecureCapture(surface, inputSize);
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
     * Prefer AID_SYSTEM (1000): DisplayManager validates packageName against
     * Binder callingUid, and package "android" is owned by uid 1000 — not
     * uid 0 — so seteuid(0) alone hits "packageName must match the owner uid"
     * on A14+/HyperOS.
     */
    private Exception trySecureCapture(Surface surface, Size inputSize) {
        try {
            runAsEuid(FakeContext.SYSTEM_UID, () -> openSecureDisplay(surface, inputSize));
            Ln.i("Display: secure capture enabled (euid=AID_SYSTEM)");
            return null;
        } catch (Exception systemException) {
            Ln.w("Secure display via AID_SYSTEM failed, trying euid=0", systemException);
            try {
                runAsEuid(0, () -> openSecureDisplay(surface, inputSize));
                Ln.i("Display: secure capture enabled (euid=0)");
                return null;
            } catch (Exception rootException) {
                Ln.w("Secure display creation failed, falling back", rootException);
                return systemException;
            }
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
     */
    private Exception fallBackToShellUid(Surface surface, Size inputSize, Exception firstFailure)
            throws IOException {
        Ln.i("Display: falling back to setuid(2000) after root display create failed");
        try {
            int beforeRuid = Os.getuid();
            int beforeEuid = Os.geteuid();
            Ln.i("Display: before drop ruid=" + beforeRuid + " euid=" + beforeEuid);
            Os.seteuid(0);
            Os.setuid(2000);
            Workarounds.updateFakePackageName(FakeContext.PACKAGE_NAME); // com.android.shell
            Ln.i("Display: after drop ruid=" + Os.getuid() + " euid=" + Os.geteuid());
        } catch (ErrnoException e) {
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

    private static boolean isRealUidRoot() {
        try {
            return Os.getuid() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @FunctionalInterface
    private interface RootAction {
        void run() throws Exception;
    }

    /**
     * Temporarily seteuid({@code targetEuid}) for a privileged binder call,
     * sync FakeContext / ActivityThread package to match, then restore.
     * Requires ruid==0 (Magisk). targetEuid 1000 = AID_SYSTEM.
     */
    private static void runAsEuid(int targetEuid, RootAction action) throws Exception {
        int previous = Os.geteuid();
        String previousPkg = FakeContext.currentPackageName();
        try {
            if (previous != targetEuid) {
                // May need euid 0 first when current euid is 2000 and target is 1000
                // (seteuid to non-ruid from non-root euid can fail without CAP_SETUID).
                if (previous != 0 && targetEuid != 0) {
                    Os.seteuid(0);
                }
                Os.seteuid(targetEuid);
            }
            String pkg = (targetEuid == FakeContext.SYSTEM_UID || targetEuid == 0)
                    ? FakeContext.ROOT_PACKAGE_NAME
                    : FakeContext.PACKAGE_NAME;
            Workarounds.updateFakePackageName(pkg);
            Ln.d("Display: runAsEuid target=" + targetEuid
                    + " now euid=" + Os.geteuid()
                    + " pkg=" + FakeContext.currentPackageName());
            action.run();
        } catch (ErrnoException e) {
            throw new IOException("seteuid(" + targetEuid + ") failed", e);
        } finally {
            try {
                if (Os.geteuid() != previous) {
                    if (Os.geteuid() != 0 && previous != 0) {
                        Os.seteuid(0);
                    }
                    Os.seteuid(previous);
                }
            } catch (ErrnoException e) {
                Ln.w("Failed to restore euid=" + previous, e);
            }
            Workarounds.updateFakePackageName(previousPkg);
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
