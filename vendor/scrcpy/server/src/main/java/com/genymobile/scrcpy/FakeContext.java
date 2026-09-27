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

    private static final FakeContext INSTANCE = new FakeContext();

    public static FakeContext get() {
        return INSTANCE;
    }

    /**
     * DisplayManager on Android 16+ checks that the calling package matches the
     * owner of the calling uid. With ruid retained as 0 (seteuid-only drop),
     * {@code com.android.shell} mismatches uid 0; use {@code android}/ROOT_UID.
     * When running as shell (uid 2000), keep the stock shell identity.
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

    public static String currentPackageName() {
        return isRootUid() ? ROOT_PACKAGE_NAME : PACKAGE_NAME;
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
        boolean root = isRootUid();
        AttributionSource.Builder builder = new AttributionSource.Builder(root ? ROOT_UID : Process.SHELL_UID);
        builder.setPackageName(root ? ROOT_PACKAGE_NAME : PACKAGE_NAME);
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
