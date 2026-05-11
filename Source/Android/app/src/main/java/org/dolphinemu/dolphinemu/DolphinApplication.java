package org.dolphinemu.dolphinemu;

import android.app.Application;
import android.content.Context;

public class DolphinApplication extends Application {
    private static Context sContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sContext = getApplicationContext();
        UserDirectoryBootstrap.ensureLayout(this);
    }

    public static Context getAppContext() {
        return sContext;
    }
}
