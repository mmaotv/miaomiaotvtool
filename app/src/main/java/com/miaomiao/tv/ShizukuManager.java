package com.miaomiao.tv;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import rikka.shizuku.Shizuku;

public class ShizukuManager {
    public interface StateListener {
        void onStateChanged(BackendState state);
    }

    public interface PermissionResultCallback {
        void onResult(boolean granted);
    }

    private static final int REQUEST_CODE = 23051;

    private static ShizukuManager instance;

    public static synchronized ShizukuManager get() {
        if (instance == null) {
            instance = new ShizukuManager();
        }
        return instance;
    }

    private Context appContext;
    private boolean initialized;
    private PermissionResultCallback pendingPermissionCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::notifyStateChanged;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::notifyStateChanged;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        (requestCode, grantResult) -> {
            if (requestCode != REQUEST_CODE) return;
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            PermissionResultCallback callback = pendingPermissionCallback;
            pendingPermissionCallback = null;
            if (callback != null) {
                mainHandler.post(() -> callback.onResult(granted));
            }
            notifyStateChanged();
        };

    private ShizukuManager() {}

    public synchronized void init(Context context) {
        if (initialized) return;
        appContext = context.getApplicationContext();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
        initialized = true;
    }

    public void addListener(StateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    public BackendState getState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new BackendState(
                BackendStatus.SHIZUKU_UNAVAILABLE,
                "Shizuku 不支持",
                "当前系统版本低于 Android 6，无法使用 Shizuku。",
                "",
                false,
                true
            );
        }

        boolean installed = isShizukuInstalled();
        if (!installed) {
            return new BackendState(
                BackendStatus.SHIZUKU_UNAVAILABLE,
                "Shizuku 未安装",
                "安装并启动 Shizuku 后，可直接执行本机特权命令。",
                "",
                false,
                true
            );
        }

        if (!Shizuku.pingBinder()) {
            return new BackendState(
                BackendStatus.SHIZUKU_UNAVAILABLE,
                "Shizuku 未启动",
                "请先在设备上启动 Shizuku 服务。",
                "",
                false,
                true
            );
        }

        if (Shizuku.isPreV11()) {
            return new BackendState(
                BackendStatus.SHIZUKU_READY,
                "Shizuku 已连接",
                "Shizuku 服务已就绪。",
                "",
                true,
                true
            );
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return new BackendState(
                BackendStatus.SHIZUKU_READY,
                "Shizuku 已授权",
                "Shizuku 可直接执行本机特权命令。",
                "",
                true,
                true
            );
        }

        return new BackendState(
            BackendStatus.SHIZUKU_PERMISSION_REQUIRED,
            "Shizuku 待授权",
            Shizuku.shouldShowRequestPermissionRationale()
                ? "请在授权提示中允许本应用使用 Shizuku。"
                : "需要先授予 Shizuku 权限后才能执行本机特权命令。",
            "",
            false,
            true
        );
    }

    public boolean isReady() {
        return getState().status == BackendStatus.SHIZUKU_READY;
    }

    public void requestPermission(Activity activity, PermissionResultCallback callback) {
        init(activity);
        BackendState state = getState();
        if (state.status == BackendStatus.SHIZUKU_READY) {
            if (callback != null) callback.onResult(true);
            return;
        }
        if (state.status == BackendStatus.SHIZUKU_UNAVAILABLE || !Shizuku.pingBinder()) {
            if (callback != null) callback.onResult(false);
            return;
        }
        pendingPermissionCallback = callback;
        Shizuku.requestPermission(REQUEST_CODE);
    }

    private boolean isShizukuInstalled() {
        if (appContext == null) return false;
        try {
            appContext.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyStateChanged() {
        BackendState state = getState();
        for (StateListener listener : listeners) {
            mainHandler.post(() -> listener.onStateChanged(state));
        }
    }
}
