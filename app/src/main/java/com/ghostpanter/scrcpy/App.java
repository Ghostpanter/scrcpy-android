package com.ghostpanter.scrcpy;

import android.app.Application;
import android.content.Context;

// Process-wide bootstrap. Only job today is to install the crash
// logger before any of our code runs. Add other process-scoped init
// here if it appears, but resist filling this with statics.
public final class App extends Application {

    // True when this process runs inside 卓易通 (HarmonyOS NEXT Android
    // container). Decided before any ContentProvider or other init runs;
    // Main / Mirror then show the notice and exit. See ZhuoyitongGuard.
    static volatile boolean zhuoyitongBlocked;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            zhuoyitongBlocked = ZhuoyitongGuard.checkLocal(base).detected();
        } catch (Throwable t) {
            zhuoyitongBlocked = false;
        }
    }

    @Override
    public void onCreate() {
        if (zhuoyitongBlocked) {
            // Skip all optional init (Shizuku, crash logger): the launcher
            // activity only shows "当前环境为卓易通，不支持运行" and exits.
            super.onCreate();
            Log.w("app: Zhuoyitong environment — init skipped, will exit");
            return;
        }
        ShizukuHelper.ensureListener();
        super.onCreate();
        Crashlog.install(this);
    }
}
