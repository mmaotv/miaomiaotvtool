package com.miaomiao.tv;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbConnection;

/**
 * ADB 连接帮助类 - 提供简化的 ADB 连接接口
 *
 * 注意：实际 ADB 操作通过 AdbHelper 类实现
 * 此类提供向后兼容性和辅助功能
 */
public class AdbConnectionHelper {
    private static final String TAG = "AdbConnectionHelper";

    private Context mContext;
    private Handler mMainHandler;
    private ExecutorService mExecutor;

    private AdbConnection mConnection;
    private boolean mIsConnected = false;
    private String mDeviceAddress;

    public AdbConnectionHelper(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return mIsConnected && mConnection != null;
    }

    /**
     * 获取设备地址
     */
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        mExecutor.execute(() -> {
            try {
                if (mConnection != null) {
                    mConnection.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "disconnect error", e);
            } finally {
                mConnection = null;
                mIsConnected = false;
                mDeviceAddress = null;
            }
        });
    }

    /**
     * 释放资源
     */
    public void release() {
        disconnect();
        mExecutor.shutdown();
    }

    public interface ShellCallback {
        void onResult(boolean success, String output);
    }

    /**
     * 获取设备信息
     */
    public void getDeviceInfo(ShellCallback callback) {
        mMainHandler.post(() -> {
            if (callback != null) {
                callback.onResult(true, "使用 AdbHelper.execShell() 执行命令");
            }
        });
    }

    /**
     * 获取 ADB 版本信息
     */
    public String getAdbVersionInfo() {
        return "ADB Version Compatible: Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")";
    }
}
