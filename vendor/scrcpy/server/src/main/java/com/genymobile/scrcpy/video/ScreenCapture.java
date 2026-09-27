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

        Exception firstFailure = null;
        boolean root = isRealUidRoot();
        if (root) {
            // Privileged secure capture (Genymobile/scrcpy#4947 / #3049).
            // Main thread already seteuid(2000); restore euid 0 for this call.
            // surface may have been reassigned by the OpenGL filter above — capture final.
            final Surface captureSurface = surface;
            final Size captureInputSize = inputSize;
            try {
                runAsRootEuid(() -> openSecureDisplay(captureSurface, captureInputSize));
                Ln.i("Display: secure capture enabled (ruid=0)");
            } catch (Exception secureException) {
                firstFailure = secureException;
                Ln.w("Secure display creation failed, falling back", secureException);
            }
        }

        if (virtualDisplay == null && display == null) {
            try {
                virtualDisplay = ServiceManager.getDisplayManager()
                        .createVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
                Ln.d("Display: using DisplayManager API");
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
                } else {
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
                }
            }
        }

        // Safety net (HyperOS / Android 16): if still no display while retaining
        // ruid=0, permanently drop to shell like stock scrcpy and retry once.
        // Guarantees vc20-like connectivity when FakeContext/package fixes are
        // incomplete on some OEMs. Secure capture is abandoned after this.
        if (virtualDisplay == null && display == null && isRealUidRoot()) {
            Ln.i("Display: falling back to setuid(2000) after root display create failed");
            try {
                Os.setuid(2000);
                Workarounds.updateFakePackageName(FakeContext.PACKAGE_NAME);
            } catch (ErrnoException e) {
                throw new IOException("setuid(2000) failed after display create failure", e);
            }
            try {
                virtualDisplay = ServiceManager.getDisplayManager()
                        .createVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
                Ln.i("Display: using DisplayManager API after setuid(2000)");
            } catch (Exception afterSetuidException) {
                Ln.e("Could not create display after setuid(2000)", afterSetuidException);
                if (firstFailure != null) {
                    Ln.e("Earlier secure attempt also failed", firstFailure);
                }
                throw new IOException("Could not create display", afterSetuidException);
            }
        }

        if (virtualDisplay == null && display == null) {
            throw new IOException("Could not create display");
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
        try {
            virtualDisplay = ServiceManager.getDisplayManager().createSecureMirrorVirtualDisplay(
                    "scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
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

    private static void runAsRootEuid(RootAction action) throws Exception {
        int previous = Os.geteuid();
        try {
            if (previous != 0) {
                Os.seteuid(0);
            }
            action.run();
        } catch (ErrnoException e) {
            throw new IOException("seteuid(0) failed", e);
        } finally {
            if (previous != 0) {
                try {
                    Os.seteuid(previous);
                } catch (ErrnoException e) {
                    Ln.w("Failed to restore euid=" + previous, e);
                }
            }
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
