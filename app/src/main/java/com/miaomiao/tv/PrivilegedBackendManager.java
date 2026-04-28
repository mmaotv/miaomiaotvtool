package com.miaomiao.tv;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;

public class PrivilegedBackendManager {
    public interface StateListener {
        void onStateChanged(BackendState state);
    }

    public interface StateCallback {
        void onResult(BackendState state);
    }

    private static PrivilegedBackendManager instance;

    public static synchronized PrivilegedBackendManager get() {
        if (instance == null) {
            instance = new PrivilegedBackendManager();
        }
        return instance;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final ShizukuBackend shizukuBackend = new ShizukuBackend();
    private final WirelessAdbBackend wirelessBackend = new WirelessAdbBackend();
    private Context appContext;
    private volatile BackendState currentState = new BackendState(
        BackendStatus.DISCONNECTED, "未连接", "暂无可用的本机特权通道。", "", false, false);

    private PrivilegedBackendManager() {}

    public void init(Context context) {
        appContext = context.getApplicationContext();
        AdbHelper.get().init(appContext);
        ShizukuManager.get().init(appContext);
        ShizukuManager.get().addListener(state -> {
            if (state.status == BackendStatus.SHIZUKU_READY || state.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED) {
                setCurrentState(state);
            } else if (!wirelessBackend.isReady()) {
                setCurrentState(buildDisconnectedState());
            }
        });
        AdbHelper.get().addStatusListener(status -> {
            if (!shizukuBackend.isReady()) {
                setCurrentState(fromAdbStatus(status));
            }
        });
        refreshState(null);
    }

    public void addListener(StateListener listener) {
        listeners.add(listener);
    }

    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    public BackendState getCurrentState() {
        return currentState;
    }

    public boolean isReady() {
        return getBackend().isReady();
    }

    public boolean isAdbConnected() {
        return AdbHelper.get().isConnected();
    }

    public PrivilegedBackend getBackend() {
        return shizukuBackend.isReady() ? shizukuBackend : wirelessBackend;
    }

    public boolean isUsingShizuku() {
        return getBackend() instanceof ShizukuBackend;
    }

    public void requestShizukuPermission(Activity activity, ShizukuManager.PermissionResultCallback callback) {
        ShizukuManager.get().requestPermission(activity, granted -> {
            refreshState(null);
            if (callback != null) callback.onResult(granted);
        });
    }

    public void execShell(String cmd, PrivilegedBackend.StringCallback callback) {
        getBackend().execShell(cmd, callback);
    }

    public String execShellSync(String cmd) {
        return getBackend().execShellSync(cmd);
    }

    public void disableApp(String pkg, PrivilegedBackend.BooleanCallback callback) {
        getBackend().disableApp(pkg, callback);
    }

    public void enableApp(String pkg, PrivilegedBackend.BooleanCallback callback) {
        getBackend().enableApp(pkg, callback);
    }

    public void clearAppData(String pkg, PrivilegedBackend.BooleanCallback callback) {
        getBackend().clearAppData(pkg, callback);
    }

    public void forceStopApp(String pkg, PrivilegedBackend.BooleanCallback callback) {
        getBackend().forceStopApp(pkg, callback);
    }

    public void listDisabledPackages(PrivilegedBackend.ListCallback callback) {
        getBackend().listDisabledPackages(callback);
    }

    public void installApkViaShizuku(String apkPath, PrivilegedBackend.BooleanCallback callback) {
        if (shizukuBackend.isReady()) {
            shizukuBackend.installApk(apkPath, callback);
        } else {
            callback.onResult(false);
        }
    }

    public void refreshState(StateCallback callback) {
        BackendState shizukuState = ShizukuManager.get().getState();
        if (shizukuState.status == BackendStatus.SHIZUKU_READY
                || shizukuState.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED) {
            setCurrentState(shizukuState);
            if (callback != null) callback.onResult(shizukuState);
            return;
        }

        if (AdbHelper.get().isConnected()) {
            BackendState state = fromAdbStatus(AdbHelper.get().getStatus());
            setCurrentState(state);
            if (callback != null) callback.onResult(state);
            return;
        }

        AdbHelper.get().autoDetectLocalServices(state -> {
            setCurrentState(state);
            if (callback != null) callback.onResult(state);
        });
    }

    public String getDisplayLabel() {
        BackendState state = currentState;
        String prefix = state.usesShizuku ? "Shizuku" : "ADB";
        return prefix + "：" + state.summary;
    }

    public String getGuidanceText() {
        return currentState.detail;
    }

    public boolean shouldPromptForAuthorization() {
        return currentState.status == BackendStatus.AUTH_REQUIRED
            || currentState.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED;
    }

    public BackendState fromThrowable(Throwable throwable) {
        if (throwable instanceof AdbAuthenticationFailedException) {
            return new BackendState(
                BackendStatus.AUTH_REQUIRED,
                "等待调试授权",
                "请留意系统弹出的“允许调试”授权框并确认。",
                "",
                false,
                false
            );
        }
        if (throwable instanceof AdbPairingRequiredException) {
            return new BackendState(
                BackendStatus.PAIRED,
                "需要先配对",
                "当前设备需要先完成无线调试配对。",
                "",
                false,
                false
            );
        }
        return buildDisconnectedState();
    }

    private BackendState fromAdbStatus(AdbHelper.AdbStatus status) {
        switch (status) {
            case CONNECTED:
                return new BackendState(
                    BackendStatus.CONNECTED,
                    "无线 ADB 已连接",
                    "当前通过无线 ADB 执行命令。",
                    AdbHelper.get().getDeviceSerial(),
                    true,
                    false
                );
            case LOCAL_ADB:
                return new BackendState(
                    BackendStatus.CONNECTED,
                    "本机无线调试可用",
                    "检测到可连接的本机无线调试端口。",
                    AdbHelper.get().getDeviceSerial(),
                    true,
                    false
                );
            case PAIRED:
                return new BackendState(
                    BackendStatus.PAIRED,
                    "无线调试已配对",
                    "请使用无线调试页面显示的连接端口继续连接。",
                    "",
                    false,
                    false
                );
            default:
                return buildDisconnectedState();
        }
    }

    private BackendState buildDisconnectedState() {
        return new BackendState(
            BackendStatus.DISCONNECTED,
            "未检测到可用后端",
            "请启动 Shizuku，或开启无线调试后重新检测。",
            "",
            false,
            false
        );
    }

    private void setCurrentState(BackendState state) {
        currentState = state;
        for (StateListener listener : listeners) {
            mainHandler.post(() -> listener.onStateChanged(state));
        }
    }
}
