package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.os.Binder;
import android.os.Process;
import android.system.Os;

import java.lang.reflect.Field;

public final class FakeContext extends ContextWrapper {

    public static final String PACKAGE_NAME = "com.android.shell";
    public static final String ROOT_PACKAGE_NAME = "android";
    public static final int ROOT_UID = 0; // Like android.os.Process.ROOT_UID, but before API 29
    public static final int SYSTEM_UID = 1000; // Process.SYSTEM_UID / AID_SYSTEM

    private static final FakeContext INSTANCE = new FakeContext();

    /**
     * Explicit package override for DisplayManager / Workarounds. When non-null,
     * {@link #currentPackageName()} returns this instead of deriving solely from
     * uid — fixes the bug where {@code runAsRootWithPackage(com.android.shell)}
     * still logged/used {@code android} because ruid=0 always mapped to android.
     */
    private static volatile String packageOverride;

    public static FakeContext get() {
        return INSTANCE;
    }

    /**
     * Real uid is still 0 after Magisk elevate + seteuid drop. Used by
     * ScreenCapture to decide whether a privileged secure-VD attempt is possible.
     */
    public static boolean isRootUid() {
        try {
            if (Os.getuid() == 0) {
                return true;
            }
        } catch (Throwable ignored) {
            // fall through to Process.myUid()
        }
        return Process.myUid() == 0;
    }

    /** True when process ruid (or myUid) is already AID_SYSTEM. */
    public static boolean isSystemUid() {
        try {
            if (Os.getuid() == SYSTEM_UID) {
                return true;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return Process.myUid() == SYSTEM_UID;
    }

    /**
     * Set / clear the package name used by FakeContext and AttributionSource.
     * Pass {@code null} to resume uid-derived defaults.
     */
    public static void setPackageOverride(String packageName) {
        packageOverride = packageName;
    }

    public static String getPackageOverride() {
        return packageOverride;
    }

    /**
     * Identity for FakeContext package / AttributionSource.
     * <p>
     * On HyperOS A16, {@code Binder.getCallingUid()} follows <b>real</b> uid.
     * ScreenCapture temporarily sets {@code ruid=euid=1000} via setresuid so
     * both match AID_SYSTEM. Prefer ruid when it is SYSTEM/ROOT; else euid
     * (shell drop keeps ruid=0 euid=2000 — still report shell package).
     * <ul>
     *   <li>ruid/euid 1000 → package {@code android}</li>
     *   <li>euid 0 (root) → package {@code android}</li>
     *   <li>euid 2000 → {@code com.android.shell}</li>
     * </ul>
     */
    public static int binderIdentityUid() {
        try {
            int ruid = Os.getuid();
            int euid = Os.geteuid();
            if (ruid == SYSTEM_UID || euid == SYSTEM_UID) {
                return SYSTEM_UID;
            }
            if (euid == ROOT_UID) {
                return ROOT_UID;
            }
            return euid;
        } catch (Throwable ignored) {
            return Process.myUid();
        }
    }

    public static String currentPackageName() {
        String override = packageOverride;
        if (override != null && !override.isEmpty()) {
            return override;
        }
        int uid = binderIdentityUid();
        if (uid == SYSTEM_UID || uid == ROOT_UID) {
            return ROOT_PACKAGE_NAME;
        }
        return PACKAGE_NAME;
    }

    /**
     * Uid that should own {@link #currentPackageName()} for OEM checks that
     * compare package → getPackageUid (HyperOS "owner uid") rather than only
     * Binder callingUid. Prefer override-driven mapping; else binder identity.
     */
    public static int ownerUidForPackage() {
        String pkg = currentPackageName();
        if (ROOT_PACKAGE_NAME.equals(pkg)) {
            // AOSP validatePackageName exempts ROOT_UID, but HyperOS "owner uid"
            // checks often require android ↔ 1000. When we are already SYSTEM or
            // temporarily setresuid'd there, report 1000; when full root without
            // override to shell, still report 1000 so package android matches.
            int id = binderIdentityUid();
            if (id == SYSTEM_UID || id == ROOT_UID) {
                return SYSTEM_UID;
            }
            return SYSTEM_UID;
        }
        if (PACKAGE_NAME.equals(pkg)) {
            return Process.SHELL_UID;
        }
        return binderIdentityUid();
    }

    private final ContentResolver contentResolver = new ContentResolver(this) {
        @SuppressWarnings({"unused", "ProtectedMemberInFinalClass"})
        // @Override (but super-class method not visible)
        protected IContentProvider acquireProvider(Context c, String name) {
            return ServiceManager.getActivityManager().getContentProviderExternal(name, new Binder());
        }

        @SuppressWarnings("unused")
        // @Override (but super-class method not visible)
        public boolean releaseProvider(IContentProvider icp) {
            return false;
        }

        @SuppressWarnings({"unused", "ProtectedMemberInFinalClass"})
        // @Override (but super-class method not visible)
        protected IContentProvider acquireUnstableProvider(Context c, String name) {
            return null;
        }

        @SuppressWarnings("unused")
        // @Override (but super-class method not visible)
        public boolean releaseUnstableProvider(IContentProvider icp) {
            return false;
        }

        @SuppressWarnings("unused")
        // @Override (but super-class method not visible)
        public void unstableProviderDied(IContentProvider icp) {
            // ignore
        }
    };

    private FakeContext() {
        super(Workarounds.getSystemContext());
    }

    @Override
    public String getPackageName() {
        return currentPackageName();
    }

    @Override
    public String getOpPackageName() {
        return currentPackageName();
    }

    @TargetApi(AndroidVersions.API_31_ANDROID_12)
    @Override
    public AttributionSource getAttributionSource() {
        int attrUid = ownerUidForPackage();
        // When override forces shell under root ruid, AttributionSource must
        // carry SHELL_UID even though binderIdentityUid is 0/1000.
        String pkg = currentPackageName();
        if (PACKAGE_NAME.equals(pkg)) {
            attrUid = Process.SHELL_UID;
        } else if (ROOT_PACKAGE_NAME.equals(pkg)) {
            attrUid = SYSTEM_UID;
        }
        AttributionSource.Builder builder = new AttributionSource.Builder(attrUid);
        builder.setPackageName(pkg);
        return builder.build();
    }

    // @Override to be added on SDK upgrade for Android 14
    @SuppressWarnings("unused")
    public int getDeviceId() {
        return 0;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }

    @Override
    public ContentResolver getContentResolver() {
        return contentResolver;
    }

    @SuppressLint("SoonBlockedPrivateApi")
    @Override
    public Object getSystemService(String name) {
        Object service = super.getSystemService(name);
        if (service == null) {
            return null;
        }

        // "semclipboard" is a Samsung-internal service
        // See:
        //  - <https://github.com/Genymobile/scrcpy/issues/6224>
        //  - <https://github.com/Genymobile/scrcpy/issues/6523>
        if (Context.CLIPBOARD_SERVICE.equals(name) || "semclipboard".equals(name) || Context.ACTIVITY_SERVICE.equals(name)) {
            try {
                Field field = service.getClass().getDeclaredField("mContext");
                field.setAccessible(true);
                field.set(service, this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        return service;
    }
}
