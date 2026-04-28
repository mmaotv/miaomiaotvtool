package com.miaomiao.tv;

import java.util.List;

public interface PrivilegedBackend {
    interface BooleanCallback {
        void onResult(boolean success);
    }

    interface StringCallback {
        void onResult(String result);
    }

    interface ListCallback {
        void onResult(List<String> result);
    }

    String getName();
    boolean isAvailable();
    boolean isReady();
    void execShell(String cmd, StringCallback callback);
    String execShellSync(String cmd);
    void disableApp(String pkg, BooleanCallback callback);
    void enableApp(String pkg, BooleanCallback callback);
    void clearAppData(String pkg, BooleanCallback callback);
    void forceStopApp(String pkg, BooleanCallback callback);
    void listDisabledPackages(ListCallback callback);
}
