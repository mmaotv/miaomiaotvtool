package com.miaomiao.tv;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

public class ShizukuBackend implements PrivilegedBackend {
    @Override
    public String getName() {
        return "Shizuku";
    }

    @Override
    public boolean isAvailable() {
        return ShizukuManager.get().getState().status != BackendStatus.SHIZUKU_UNAVAILABLE;
    }

    @Override
    public boolean isReady() {
        return ShizukuManager.get().isReady();
    }

    @Override
    public void execShell(String cmd, StringCallback callback) {
        new Thread(() -> callback.onResult(execShellSync(cmd))).start();
    }

    @Override
    public String execShellSync(String cmd) {
        if (!isReady()) {
            return "ERROR: Shizuku 未就绪";
        }
        Process process = null;
        try {
            process = startProcess(new String[]{"sh", "-c", cmd});
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            readFully(process.getInputStream(), baos);
            readFully(process.getErrorStream(), baos);
            process.waitFor();
            return baos.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public void disableApp(String pkg, BooleanCallback callback) {
        execShell("pm disable-user --user 0 " + pkg,
            result -> callback.onResult(result != null && !result.contains("ERROR")));
    }

    @Override
    public void enableApp(String pkg, BooleanCallback callback) {
        execShell("pm enable --user 0 " + pkg,
            result -> callback.onResult(result != null && !result.contains("ERROR")));
    }

    @Override
    public void clearAppData(String pkg, BooleanCallback callback) {
        execShell("pm clear --user 0 " + pkg,
            result -> callback.onResult(result != null && result.contains("Success")));
    }

    @Override
    public void forceStopApp(String pkg, BooleanCallback callback) {
        execShell("am force-stop " + pkg,
            result -> callback.onResult(result != null && !result.contains("ERROR")));
    }

    @Override
    public void listDisabledPackages(ListCallback callback) {
        execShell("pm list packages -d --user 0", result -> {
            List<String> packages = new ArrayList<>();
            if (!TextUtils.isEmpty(result) && !result.startsWith("ERROR")) {
                for (String line : result.split("\\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("package:")) {
                        packages.add(trimmed.substring(8).trim());
                    }
                }
            }
            callback.onResult(packages);
        });
    }

    public void installApk(String apkPath, BooleanCallback callback) {
        execShell("pm install -r -t \"" + apkPath + "\"",
            result -> callback.onResult(result != null && result.contains("Success")));
    }

    private Process startProcess(String[] command) throws Exception {
        Method method = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        method.setAccessible(true);
        return (Process) method.invoke(null, command, null, null);
    }

    private void readFully(InputStream inputStream, ByteArrayOutputStream baos) throws Exception {
        byte[] buffer = new byte[8192];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
            if (inputStream.available() <= 0) {
                Thread.sleep(20);
            }
        }
    }

    public String execShellSyncWithTimeout(String cmd, long timeoutMs) {
        final String[] result = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        execShell(cmd, value -> {
            result[0] = value;
            latch.countDown();
        });
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return "ERROR: timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: interrupted";
        }
        return result[0];
    }
}
