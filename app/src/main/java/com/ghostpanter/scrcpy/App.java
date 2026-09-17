package com.ghostpanter.scrcpy;

import android.app.Application;

// Process-wide bootstrap. Only job today is to install the crash
// logger before any of our code runs. Add other process-scoped init
// here if it appears, but resist filling this with statics.
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Crashlog.install(this);
    }
}
