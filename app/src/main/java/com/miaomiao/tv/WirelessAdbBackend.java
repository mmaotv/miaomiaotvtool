package com.miaomiao.tv;

import java.util.List;

public class WirelessAdbBackend implements PrivilegedBackend {
    @Override
    public String getName() {
        return "Wireless ADB";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isReady() {
        return AdbHelper.get().isConnected();
    }

    @Override
    public void execShell(String cmd, StringCallback callback) {
        AdbHelper.get().execShell(cmd, callback::onResult);
    }

    @Override
    public String execShellSync(String cmd) {
        return AdbHelper.get().executeCommandSync(cmd);
    }

    @Override
    public void disableApp(String pkg, BooleanCallback callback) {
        AdbHelper.get().disableApp(pkg, callback::onResult);
    }

    @Override
    public void enableApp(String pkg, BooleanCallback callback) {
        AdbHelper.get().enableApp(pkg, callback::onResult);
    }

    @Override
    public void clearAppData(String pkg, BooleanCallback callback) {
        AdbHelper.get().clearAppData(pkg, callback::onResult);
    }

    @Override
    public void forceStopApp(String pkg, BooleanCallback callback) {
        AdbHelper.get().forceStopApp(pkg, callback::onResult);
    }

    @Override
    public void listDisabledPackages(ListCallback callback) {
        AdbHelper.get().listDisabledPackages(callback::onResult);
    }
}
