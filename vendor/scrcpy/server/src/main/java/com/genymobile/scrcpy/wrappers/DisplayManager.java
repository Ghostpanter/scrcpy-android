package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Command;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.IBinder;
import android.content.AttributionSource;
import android.content.pm.ApplicationInfo;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Handler;
import android.view.Display;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressLint({"PrivateApi", "DiscouragedPrivateApi", "NewApi"})
public final class DisplayManager {

    // android.hardware.display.DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
    public static final long EVENT_FLAG_DISPLAY_CHANGED = 1L << 2;

    public interface DisplayListener {
        /**
         * Called whenever the properties of a logical {@link android.view.Display},
         * such as size and density, have changed.
         *
         * @param displayId The id of the logical display that changed.
         */
        void onDisplayChanged(int displayId);
    }

    public static final class DisplayListenerHandle {
        private final Object displayListenerProxy;
        private DisplayListenerHandle(Object displayListenerProxy) {
            this.displayListenerProxy = displayListenerProxy;
        }
    }

    private final Object manager; // instance of hidden class android.hardware.display.DisplayManagerGlobal
    private Method getDisplayInfoMethod;
    private Method createVirtualDisplayMethod;
    private Method requestDisplayPowerMethod;

    static DisplayManager create() {
        try {
            Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstanceMethod = clazz.getDeclaredMethod("getInstance");
            Object dmg = getInstanceMethod.invoke(null);
            return new DisplayManager(dmg);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private DisplayManager(Object manager) {
        this.manager = manager;
    }

    // public to call it from unit tests
    public static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, density ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher m = regex.matcher(dumpsysDisplayOutput);
        if (!m.find()) {
            return null;
        }
        int flags = parseDisplayFlags(m.group(1));
        int width = Integer.parseInt(m.group(2));
        int height = Integer.parseInt(m.group(3));
        int rotation = Integer.parseInt(m.group(4));
        int density = Integer.parseInt(m.group(5));
        int layerStack = Integer.parseInt(m.group(6));

        return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, density, null);
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Command.execReadOutput("dumpsys", "display");
            return parseDisplayInfo(dumpsysDisplayOutput, displayId);
        } catch (Exception e) {
            Ln.e("Could not get display info from \"dumpsys display\" output", e);
            return null;
        }
    }

    private static int parseDisplayFlags(String text) {
        if (text == null) {
            return 0;
        }

        int flags = 0;
        Pattern regex = Pattern.compile("FLAG_[A-Z_]+");
        Matcher m = regex.matcher(text);
        while (m.find()) {
            String flagString = m.group();
            try {
                Field filed = Display.class.getDeclaredField(flagString);
                flags |= filed.getInt(null);
            } catch (ReflectiveOperationException e) {
                // Silently ignore, some flags reported by "dumpsys display" are @TestApi
            }
        }
        return flags;
    }

    // getDisplayInfo() may be used from both the Controller thread and the video (main) thread
    private synchronized Method getGetDisplayInfoMethod() throws NoSuchMethodException {
        if (getDisplayInfoMethod == null) {
            getDisplayInfoMethod = manager.getClass().getMethod("getDisplayInfo", int.class);
        }
        return getDisplayInfoMethod;
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Method method = getGetDisplayInfoMethod();
            Object displayInfo = method.invoke(manager, displayId);
            if (displayInfo == null) {
                // fallback when displayInfo is null
                return getDisplayInfoFromDumpsysDisplay(displayId);
            }
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            int dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo);
            String uniqueId;
            try {
                uniqueId = (String) cls.getDeclaredField("uniqueId").get(displayInfo);
            } catch (NoSuchFieldException e) {
                // This field might not exist: <https://github.com/Genymobile/scrcpy/issues/6461>
                uniqueId = null;
            }
            return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags, dpi, uniqueId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    public int[] getDisplayIds() {
        try {
            return (int[]) manager.getClass().getMethod("getDisplayIds").invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Method getCreateVirtualDisplayMethod() throws NoSuchMethodException {
        if (createVirtualDisplayMethod == null) {
            createVirtualDisplayMethod = android.hardware.display.DisplayManager.class
                    .getMethod("createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
        }
        return createVirtualDisplayMethod;
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int displayIdToMirror, Surface surface) throws Exception {
        Method method = getCreateVirtualDisplayMethod();
        return (VirtualDisplay) method.invoke(null, name, width, height, displayIdToMirror, surface);
    }

    /**
     * Mirror {@code displayIdToMirror} with {@code VIRTUAL_DISPLAY_FLAG_SECURE}.
     * The public static helper only sets AUTO_MIRROR; secure capture of
     * FLAG_SECURE layers (lock screen, banking, encrypted gallery) requires
     * this flag plus a privileged caller (AID_SYSTEM / root).
     * <p>
     * Caller should temporarily {@code setresuid(1000,1000,0)} so Binder
     * callingUid (ruid on HyperOS) matches package {@code android}; seteuid
     * alone is not enough when callingUid follows real uid.
     */
    @TargetApi(AndroidVersions.API_34_ANDROID_14)
    public VirtualDisplay createSecureMirrorVirtualDisplay(String name, int width, int height, int displayIdToMirror,
            Surface surface, int densityDpi) throws Exception {
        if (densityDpi <= 0) {
            densityDpi = 160;
        }
        // PUBLIC|AUTO_MIRROR|SECURE — MediaProjection-style secure mirror.
        // (TRUSTED / other @SystemApi bits omitted: public SDK @IntDef rejects them.)
        int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                | android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
        VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setFlags(flags)
                .setSurface(surface);
        // @SystemApi — present on Android 12+ framework, missing from public SDK stubs
        Method setMirror = builder.getClass().getMethod("setDisplayIdToMirror", int.class);
        setMirror.invoke(builder, displayIdToMirror);
        VirtualDisplayConfig config = builder.build();
        String pkg = FakeContext.currentPackageName();
        int ownerUid = FakeContext.ownerUidForPackage();
        int ruid;
        int euid;
        try {
            ruid = android.system.Os.getuid();
            euid = android.system.Os.geteuid();
        } catch (Throwable t) {
            ruid = -1;
            euid = FakeContext.binderIdentityUid();
        }
        int attrUid = -1;
        String attrPkg = "?";
        try {
            AttributionSource as = FakeContext.get().getAttributionSource();
            attrUid = as.getUid();
            attrPkg = as.getPackageName();
        } catch (Throwable ignored) {
            // API < 31 or stub
        }
        int appInfoUid = -1;
        try {
            ApplicationInfo ai = FakeContext.get().getApplicationInfo();
            if (ai != null) {
                appInfoUid = ai.uid;
            }
        } catch (Throwable ignored) {
            // ignore
        }
        Ln.i("DisplayManager SECURE create: flags=0x" + Integer.toHexString(flags)
                + " mirrorId=" + displayIdToMirror
                + " pkg=" + pkg
                + " ownerUid=" + ownerUid
                + " ruid=" + ruid
                + " euid=" + euid
                + " attrUid=" + attrUid
                + " attrPkg=" + attrPkg
                + " appInfoUid=" + appInfoUid);

        // Prefer explicit packageName on IDisplayManager (matches DMS validatePackageName).
        // Some OEM paths may also take ownerUid — try both overloads via reflection.
        try {
            return createSecureViaDisplayManagerGlobal(config, pkg, ownerUid);
        } catch (Exception explicit) {
            Ln.d("DisplayManagerGlobal explicit packageName failed: " + explicit.getMessage());
            Constructor<android.hardware.display.DisplayManager> ctor =
                    android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            android.hardware.display.DisplayManager dm = ctor.newInstance(FakeContext.get());
            try {
                return dm.createVirtualDisplay(config);
            } catch (Exception e) {
                // Prefer the explicit-path exception if it was the OEM package check.
                if (isPackageOwnerUidException(explicit) && !isPackageOwnerUidException(e)) {
                    throw explicit;
                }
                throw e;
            }
        }
    }

    private static boolean isPackageOwnerUidException(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("packageName must match")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * Call {@code DisplayManagerGlobal.createVirtualDisplay(Context, MediaProjection,
     * VirtualDisplayConfig, ...)} normally, but also try IDisplayManager binder
     * overloads that take an explicit packageName / ownerUid (HyperOS).
     */
    private VirtualDisplay createSecureViaDisplayManagerGlobal(VirtualDisplayConfig config,
            String packageName, int ownerUid) throws Exception {
        // Path 1: public DisplayManager(Context) → uses FakeContext.getPackageName()
        Constructor<android.hardware.display.DisplayManager> ctor =
                android.hardware.display.DisplayManager.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);
        android.hardware.display.DisplayManager dm = ctor.newInstance(FakeContext.get());

        // Path 2 (preferred when present): invoke IDisplayManager.createVirtualDisplay
        // with explicit packageName so OEM owner-uid checks see the intended package
        // even if ContextWrapper plumbing is wrong.
        try {
            Object dmg = manager; // DisplayManagerGlobal
            Object iDm = null;
            for (String fieldName : new String[] {"mDm", "mDisplayManager"}) {
                try {
                    Field f = dmg.getClass().getDeclaredField(fieldName);
                    f.setAccessible(true);
                    iDm = f.get(dmg);
                    if (iDm != null) {
                        break;
                    }
                } catch (NoSuchFieldException ignored) {
                    // try next
                }
            }
            if (iDm != null) {
                Class<?> callbackClass = Class.forName("android.hardware.display.IVirtualDisplayCallback");
                // Build a no-op callback via DisplayManagerGlobal helper if possible
                VirtualDisplay result = tryIDisplayManagerCreate(iDm, callbackClass, config, packageName, ownerUid);
                if (result != null) {
                    return result;
                }
            }
        } catch (Exception binderPath) {
            Ln.d("IDisplayManager explicit create skipped: " + binderPath.getMessage());
        }

        return dm.createVirtualDisplay(config);
    }

    private VirtualDisplay tryIDisplayManagerCreate(Object iDm, Class<?> callbackClass,
            VirtualDisplayConfig config, String packageName, int ownerUid) throws Exception {
        // Obtain DisplayManagerGlobal.createVirtualDisplayWrapper for the displayId
        Method wrap = null;
        for (Method m : manager.getClass().getDeclaredMethods()) {
            if ("createVirtualDisplayWrapper".equals(m.getName())) {
                wrap = m;
                wrap.setAccessible(true);
                break;
            }
        }
        // Create callback through DisplayManagerGlobal.createVirtualDisplay path:
        // easiest reliable approach — still call dm.createVirtualDisplay after ensuring
        // FakeContext package matches. Direct IDisplayManager needs a Stub callback.
        // Instead: use DisplayManagerGlobal.createVirtualDisplay(Context,...) reflective
        // overload if it accepts packageName explicitly (some OEM forks).
        for (Method m : manager.getClass().getDeclaredMethods()) {
            if (!"createVirtualDisplay".equals(m.getName())) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            // (Context, MediaProjection, VirtualDisplayConfig, Callback, Executor)
            // OEM: may add String packageName and/or int ownerUid
            if (pts.length >= 5 && pts[0] == Context.class && pts[2] == VirtualDisplayConfig.class) {
                m.setAccessible(true);
                Object[] args = new Object[pts.length];
                args[0] = FakeContext.get();
                args[1] = null; // projection
                args[2] = config;
                args[3] = null; // callback
                args[4] = null; // executor
                for (int i = 5; i < pts.length; i++) {
                    if (pts[i] == String.class) {
                        args[i] = packageName;
                    } else if (pts[i] == int.class || pts[i] == Integer.class) {
                        args[i] = ownerUid;
                    } else {
                        args[i] = null;
                    }
                }
                Ln.i("DisplayManager SECURE via DMG reflective arity=" + pts.length
                        + " pkg=" + packageName + " ownerUid=" + ownerUid);
                return (VirtualDisplay) m.invoke(manager, args);
            }
        }

        // Fallback: IDisplayManager.createVirtualDisplay(config, callback, projection, packageName)
        // and optional ownerUid — need a callback Stub. Skip if we cannot construct one.
        for (Method m : iDm.getClass().getMethods()) {
            if (!"createVirtualDisplay".equals(m.getName())) {
                continue;
            }
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length < 4 || pts[0] != VirtualDisplayConfig.class) {
                continue;
            }
            // Need IVirtualDisplayCallback — use java.lang.reflect.Proxy
            Object callback = java.lang.reflect.Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[] {callbackClass},
                    (proxy, method, args) -> {
                        if ("asBinder".equals(method.getName())) {
                            return new android.os.Binder();
                        }
                        return null;
                    });
            Object[] args = new Object[pts.length];
            args[0] = config;
            args[1] = callback;
            args[2] = null; // IMediaProjection
            for (int i = 3; i < pts.length; i++) {
                if (pts[i] == String.class) {
                    args[i] = packageName;
                } else if (pts[i] == int.class || pts[i] == Integer.class) {
                    args[i] = ownerUid;
                } else {
                    args[i] = null;
                }
            }
            Ln.i("DisplayManager SECURE via IDisplayManager arity=" + pts.length
                    + " pkg=" + packageName + " ownerUid=" + ownerUid);
            int displayId = (Integer) m.invoke(iDm, args);
            if (displayId < 0) {
                throw new IOException("IDisplayManager.createVirtualDisplay returned " + displayId);
            }
            if (wrap != null) {
                return (VirtualDisplay) wrap.invoke(manager, config, callback, displayId);
            }
            // Without wrapper, fall through to Context path
            Ln.d("createVirtualDisplayWrapper missing; displayId=" + displayId);
        }
        return null;
    }

    /** Compatibility overload (density defaults to 160). */
    public VirtualDisplay createSecureMirrorVirtualDisplay(String name, int width, int height, int displayIdToMirror,
            Surface surface) throws Exception {
        return createSecureMirrorVirtualDisplay(name, width, height, displayIdToMirror, surface, 160);
    }

    public VirtualDisplay createNewVirtualDisplay(String name, int width, int height, int dpi, Surface surface, int flags) throws Exception {
        Constructor<android.hardware.display.DisplayManager> ctor = android.hardware.display.DisplayManager.class.getDeclaredConstructor(
                Context.class);
        ctor.setAccessible(true);
        android.hardware.display.DisplayManager dm = ctor.newInstance(FakeContext.get());
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
    }

    private Method getRequestDisplayPowerMethod() throws NoSuchMethodException {
        if (requestDisplayPowerMethod == null) {
            requestDisplayPowerMethod = manager.getClass().getMethod("requestDisplayPower", int.class, boolean.class);
        }
        return requestDisplayPowerMethod;
    }

    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    public boolean requestDisplayPower(int displayId, boolean on) {
        try {
            Method method = getRequestDisplayPowerMethod();
            return (boolean) method.invoke(manager, displayId, on);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    public DisplayListenerHandle registerDisplayListener(DisplayListener listener, Handler handler) {
        try {
            Class<?> displayListenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            Object displayListenerProxy = Proxy.newProxyInstance(
                    ClassLoader.getSystemClassLoader(),
                    new Class[] {displayListenerClass},
                    (proxy, method, args) -> {
                        if ("onDisplayChanged".equals(method.getName())) {
                            listener.onDisplayChanged((int) args[0]);
                        }
                        if ("toString".equals(method.getName())) {
                            return "DisplayListener";
                        }
                        return null;
                    });
            try {
                manager.getClass()
                        .getMethod("registerDisplayListener", displayListenerClass, Handler.class, long.class, String.class)
                        .invoke(manager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED, FakeContext.currentPackageName());
            } catch (NoSuchMethodException e) {
                try {
                    manager.getClass()
                            .getMethod("registerDisplayListener", displayListenerClass, Handler.class, long.class)
                            .invoke(manager, displayListenerProxy, handler, EVENT_FLAG_DISPLAY_CHANGED);
                } catch (NoSuchMethodException e2) {
                    manager.getClass()
                            .getMethod("registerDisplayListener", displayListenerClass, Handler.class)
                            .invoke(manager, displayListenerProxy, handler);
                }
            }

            return new DisplayListenerHandle(displayListenerProxy);
        } catch (Exception e) {
            // Rotation and screen size won't be updated, not a fatal error
            Ln.e("Could not register display listener", e);
        }

        return null;
    }

    public void unregisterDisplayListener(DisplayListenerHandle listener) {
        try {
            Class<?> displayListenerClass = Class.forName("android.hardware.display.DisplayManager$DisplayListener");
            manager.getClass().getMethod("unregisterDisplayListener", displayListenerClass).invoke(manager, listener.displayListenerProxy);
        } catch (Exception e) {
            Ln.e("Could not unregister display listener", e);
        }
    }
}
