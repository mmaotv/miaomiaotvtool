package com.miaomiao.tv;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbConnection;
import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

/**
 * ADB 无线调试工具类
 *
 * 基于 libadb-android (io.github.muntashirakon) 实现，纯 Java ADB 协议，无需 adb 二进制。
 *
 * 版本支持：
 * - Android 5-10 (API 21-29): 无线连接（adb tcpip 5555 后直连）
 * - Android 11+   (API 30+):  无线配对码（6位数字）
 *
 * 密钥生成：
 * - RSA-2048 标准 KeyPairGenerator 生成，不使用任何 android.sun.security.* 内部 API
 * - 公钥编码使用 AndroidPubkeyCodec（纯标准 Java 实现）
 *
 * 持久化：
 * - 私钥：PKCS8 PEM → 文件
 * - 公钥：AndroidPubkey 二进制格式 → 文件
 */
public class AdbHelper {

    private static final String TAG = "AdbHelper";
    private static final int DEFAULT_ADB_PORT = 5555;

    // ===================== 状态枚举 =====================

    public enum AdbStatus {
        DISCONNECTED,
        LOCAL_ADB,
        PAIRED,
        CONNECTED,
    }

    public interface AdbStatusListener {
        void onStatusChanged(AdbStatus status);
    }

    public interface AdbCallback<T> {
        void onResult(T result);
    }

    // ===================== 单例 =====================

    private static AdbHelper instance;

    public static synchronized AdbHelper get() {
        if (instance == null) instance = new AdbHelper();
        return instance;
    }

    // ===================== 成员变量 =====================

    private Context mContext;
    private final MyConnectionManager connectionManager;
    private AdbStatus status = AdbStatus.DISCONNECTED;
    private String deviceSerial = "";
    private String lastHost = "";
    private int lastPort = DEFAULT_ADB_PORT;
    private String lastStatusHint = "未连接";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<AdbStatusListener> listeners = new CopyOnWriteArrayList<>();
    private final List<String> logBuffer = new java.util.ArrayList<>();
    private final List<String> commandHistory = new java.util.ArrayList<>();

    // ===================== 密钥路径 =====================

    private static final String KEY_DIR = "adb_keys";
    private static final String PRIVATE_KEY_FILE = "adb_rsa";
    private static final String PUBKEY_FILE = "adb_rsa.pub";

    // ===================== 构造函数 =====================

    private AdbHelper() {
        connectionManager = new MyConnectionManager();
    }

    /** 初始化（建议在 Application 或 Activity onCreate 中调用） */
    public void init(Context context) {
        this.mContext = context.getApplicationContext();
        addLog("🔧 AdbHelper 初始化");
        loadOrCreateKeys();
    }

    // ===================== 版本信息 =====================

    public String getAndroidVersionInfo() {
        return "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }

    public String getCompatibilityHint() {
        if (AndroidVersionCompat.isAndroid11OrAbove()) {
            return "当前设备支持 ADB 配对码功能，首次连接需要配对。";
        } else {
            return "当前设备为 Android " + Build.VERSION.RELEASE + "，无需配对码。";
        }
    }

    // ===================== 状态查询 =====================

    public AdbStatus getStatus() { return status; }
    public String getDeviceSerial() { return deviceSerial; }
    public boolean isConnected() { return status == AdbStatus.CONNECTED; }
    public String getLastStatusHint() { return lastStatusHint; }
    public List<String> getCommandHistory() { return new java.util.ArrayList<>(commandHistory); }
    public List<String> getLogBuffer() { return new java.util.ArrayList<>(logBuffer); }

    public void addStatusListener(AdbStatusListener l) { listeners.add(l); }
    public void removeStatusListener(AdbStatusListener l) { listeners.remove(l); }

    private void notifyStatus(AdbStatus s) {
        this.status = s;
        for (AdbStatusListener l : listeners) l.onStatusChanged(s);
    }

    private void updateStatusHint(String hint) {
        lastStatusHint = hint;
    }

    private void addLog(String log) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
        logBuffer.add("[" + ts + "] " + log);
        if (logBuffer.size() > 500) logBuffer.remove(0);
    }

    // ===================== 配对 =====================

    /**
     * 无线配对（Android 11+ 需要 6 位配对码）
     */
    public void pair(String host, int pairPort, String code, AdbCallback<Boolean> callback) {
        if (host == null || host.isEmpty() || code == null || !code.matches("\\d{6}")) {
            addLog("❌ 配对参数无效，请输入6位数字配对码");
            mainHandler.post(() -> callback.onResult(false));
            return;
        }

        addLog("🔑 开始配对 " + host + ":" + pairPort + " ...");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            try {
                boolean ok = connectionManager.pair(host, pairPort, code);
                if (ok) {
                    saveKeysToFiles();
                    mainHandler.post(() -> {
                        notifyStatus(AdbStatus.PAIRED);
                        addLog("✅ 配对成功");
                    });
                    mainHandler.post(() -> callback.onResult(true));
                } else {
                    mainHandler.post(() -> {
                        addLog("❌ 配对失败");
                        callback.onResult(false);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "pair error", e);
                mainHandler.post(() -> {
                    addLog("❌ 配对异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    callback.onResult(false);
                });
            } finally {
                executor.shutdown();
            }
        });
    }

    // ===================== 连接 =====================

    /**
     * 连接 ADB 设备
     */
    public void connect(String host, int port, AdbCallback<Boolean> callback) {
        if (host == null || host.isEmpty()) {
            mainHandler.post(() -> callback.onResult(false));
            return;
        }

        lastHost = host;
        lastPort = port > 0 ? port : DEFAULT_ADB_PORT;

        addLog("🔗 连接 " + lastHost + ":" + lastPort + " ...");
        addLog("📱 Android " + AndroidVersionCompat.getVersionName());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                connectionManager.setThrowOnUnauthorised(true);
                disconnectInternal();

                boolean ok = connectionManager.connect(lastHost, lastPort);

                if (ok) {
                    AdbConnection conn = connectionManager.getAdbConnection();
                    if (conn != null && conn.isConnected()) {
                        deviceSerial = lastHost + ":" + lastPort;
                        mainHandler.post(() -> {
                            notifyStatus(AdbStatus.CONNECTED);
                            updateStatusHint("已连接 " + deviceSerial);
                            addLog("✅ 连接成功: " + deviceSerial);
                            callback.onResult(true);
                        });
                        return;
                    }
                }
                mainHandler.post(() -> {
                    updateStatusHint("连接超时");
                    addLog("❌ 连接超时");
                    callback.onResult(false);
                });

            } catch (AdbPairingRequiredException e) {
                mainHandler.post(() -> {
                    addLog("⚠️ 该设备需要配对，请先进行配对");
                    notifyStatus(AdbStatus.PAIRED);
                    updateStatusHint("需要先配对");
                    callback.onResult(false);
                });
            } catch (AdbAuthenticationFailedException e) {
                mainHandler.post(() -> {
                    updateStatusHint("等待系统调试授权");
                    addLog("⚠️ 授权被拒绝！请在目标设备上允许调试");
                    callback.onResult(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "connect error", e);
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                mainHandler.post(() -> {
                    if (msg.contains("refused")) {
                        updateStatusHint("无线调试端口不可达");
                        addLog("❌ 连接被拒绝，请确保设备已开启无线 ADB");
                    } else if (msg.contains("timeout")) {
                        updateStatusHint("连接超时");
                        addLog("❌ 连接超时，请检查网络");
                    } else if (msg.contains("unreachable") || msg.contains("network")) {
                        updateStatusHint("网络不可达");
                        addLog("❌ 网络不可达");
                    } else {
                        updateStatusHint("连接失败");
                        addLog("❌ " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                    callback.onResult(false);
                });
            } finally {
                executor.shutdown();
            }
        });
    }

    public void disconnect() {
        disconnectInternal();
        notifyStatus(AdbStatus.DISCONNECTED);
        updateStatusHint("已断开连接");
        addLog("🔌 已断开连接");
    }

    private void disconnectInternal() {
        try {
            connectionManager.disconnect();
        } catch (IOException e) {
            Log.e(TAG, "disconnect", e);
        }
        deviceSerial = "";
    }

    public void checkLocalAdb(AdbCallback<Boolean> callback) {
        autoDetectLocalServices(state -> callback.onResult(state.ready));
    }

    public void autoDetectLocalServices(AdbCallback<BackendState> callback) {
        new Thread(() -> {
            BackendState state = detectLocalServiceState();
            mainHandler.post(() -> {
                switch (state.status) {
                    case CONNECTED:
                        notifyStatus(AdbStatus.CONNECTED);
                        break;
                    case PAIRED:
                        notifyStatus(AdbStatus.PAIRED);
                        break;
                    case AUTH_REQUIRED:
                    case DISCONNECTED:
                    default:
                        notifyStatus(AdbStatus.DISCONNECTED);
                        break;
                }
                updateStatusHint(state.summary);
                callback.onResult(state);
            });
        }).start();
    }

    private BackendState detectLocalServiceState() {
        if (mContext == null) {
            return new BackendState(
                BackendStatus.DISCONNECTED, "ADB 未初始化", "请先初始化 ADB 模块。", "", false, false);
        }

        try {
            connectionManager.setThrowOnUnauthorised(true);
            
            // Android 11+ 使用 mDNS 自动发现
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    boolean connected = connectionManager.autoConnect(mContext, AndroidVersionCompat.getConnectionTimeout());
                    if (connected) {
                        AdbConnection conn = connectionManager.getAdbConnection();
                        if (conn != null && conn.isConnected()) {
                            deviceSerial = "auto-connect";
                            addLog("✅ 已通过 mDNS 自动发现并连接无线调试");
                            return new BackendState(
                                BackendStatus.CONNECTED,
                                "无线调试已连接",
                                "已通过系统无线调试服务自动连接。",
                                deviceSerial,
                                true,
                                false
                            );
                        }
                    }
                } catch (AdbPairingRequiredException e) {
                    addLog("🔑 检测到无线调试服务，但需要先配对");
                    return new BackendState(
                        BackendStatus.PAIRED,
                        "需要无线调试配对",
                        "检测到无线调试服务，请先输入系统显示的配对码。",
                        "",
                        false,
                        false
                    );
                } catch (AdbAuthenticationFailedException e) {
                    addLog("⚠️ 已触发调试授权，请留意系统弹窗");
                    return new BackendState(
                        BackendStatus.AUTH_REQUIRED,
                        "等待调试授权",
                        "请查看系统\"允许调试\"弹窗并确认授权。",
                        "",
                        false,
                        false
                    );
                } catch (Throwable ignored) {
                    // 继续回退到旧版端口探测
                }
            }

            // Android 10及以下：尝试直接连接 127.0.0.1:5555 以触发授权弹窗
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                try {
                    boolean connected = connectionManager.connect("127.0.0.1", 5555);
                    if (connected) {
                        AdbConnection conn = connectionManager.getAdbConnection();
                        if (conn != null && conn.isConnected()) {
                            deviceSerial = "127.0.0.1:5555";
                            addLog("✅ 已连接本机无线调试");
                            return new BackendState(
                                BackendStatus.CONNECTED,
                                "无线调试已连接",
                                "已连接本机无线调试端口。",
                                deviceSerial,
                                true,
                                false
                            );
                        }
                    }
                } catch (AdbAuthenticationFailedException e) {
                    addLog("⚠️ 已触发调试授权，请留意系统弹窗");
                    return new BackendState(
                        BackendStatus.AUTH_REQUIRED,
                        "等待调试授权",
                        "请查看系统\"允许调试\"弹窗并确认授权。",
                        "",
                        false,
                        false
                    );
                } catch (Throwable ignored) {
                    // 连接失败，继续检查端口
                }
            }

            // 检查端口是否开放（用于提示用户）
            if (isPortOpen("127.0.0.1", 5555, 800)) {
                return new BackendState(
                    BackendStatus.CONNECTED,
                    "发现本机调试端口",
                    "检测到 127.0.0.1:5555 可达，可尝试直接连接。",
                    "127.0.0.1:5555",
                    true,
                    false
                );
            }

            return new BackendState(
                BackendStatus.DISCONNECTED,
                "未检测到无线调试",
                "未发现可用的本机无线调试服务。",
                "",
                false,
                false
            );
        } catch (Throwable e) {
            addLog("❌ 检测本机调试服务失败: " + e.getMessage());
            return new BackendState(
                BackendStatus.DISCONNECTED,
                "无线调试检测失败",
                "检测本机无线调试服务时出现异常。",
                "",
                false,
                false
            );
        }
    }

    // ===================== Shell（异步） =====================

    public void execShell(String cmd, AdbCallback<String> callback) {
        if (!isConnected()) {
            mainHandler.post(() -> callback.onResult("ERROR: 未连接"));
            return;
        }

        addLog(">>> " + cmd);
        commandHistory.add(cmd);
        if (commandHistory.size() > 50) commandHistory.remove(0);

        new Thread(() -> {
            try {
                AdbStream stream = connectionManager.openStream("shell:" + cmd);
                if (stream == null) {
                    mainHandler.post(() -> callback.onResult("ERROR: 无法打开 shell 流"));
                    return;
                }

                java.io.InputStream is = stream.openInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int idle = 0;
                while (idle < 150) {
                    if (is.available() > 0) {
                        int len = is.read(buf);
                        if (len > 0) { baos.write(buf, 0, len); idle = 0; }
                    } else {
                        if (stream.isClosed()) break;
                        Thread.sleep(100);
                        idle++;
                    }
                }
                stream.close();
                String out = baos.toString(StandardCharsets.UTF_8.name());
                if (!out.isEmpty()) addLog(out);
                mainHandler.post(() -> callback.onResult(out));
            } catch (Exception e) {
                Log.e(TAG, "exec error", e);
                mainHandler.post(() -> callback.onResult("ERROR: " + e.getMessage()));
            }
        }).start();
    }

    // ===================== Shell（同步） =====================

    /**
     * 同步执行 ADB Shell 命令
     *
     * 供 FileManagerActivity、AppManagerActivity 等需要等待结果的场景使用。
     */
    public String executeCommandSync(String cmd) {
        if (!isConnected()) {
            addLog("❌ 未连接，无法执行: " + cmd);
            return "ERROR: 未连接";
        }

        addLog(">>> [SYNC] " + cmd);
        final AtomicReference<String> result = new AtomicReference<>("");
        final CountDownLatch latch = new CountDownLatch(1);

        execShell(cmd, r -> {
            result.set(r);
            latch.countDown();
        });

        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                addLog("⚠️ 命令超时: " + cmd);
                return "ERROR: timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR: interrupted";
        }

        return result.get();
    }

    // ===================== 应用管理 =====================

    public void disableApp(String pkg, AdbCallback<Boolean> c) {
        // Android 16: 尝试多种禁用命令，兼容不同版本
        execShell("pm disable-user --user 0 " + pkg, r -> {
            if (r != null && (r.contains("disabled") || r.contains("enabled=false") || r.contains("Disabled"))) {
                c.onResult(true);
            } else {
                // 备用方案：使用 appops
                execShell("appops set " + pkg + " REQUEST_COMPATIBILITY_MODE deny", r2 -> {
                    c.onResult(r2 != null && !r2.contains("not found"));
                });
            }
        });
    }

    public void enableApp(String pkg, AdbCallback<Boolean> c) {
        execShell("pm enable --user 0 " + pkg, r -> c.onResult(r != null && (r.contains("enabled") || r.contains("Enabled"))));
    }

    public void clearAppData(String pkg, AdbCallback<Boolean> c) {
        execShell("pm clear --user 0 " + pkg, r -> c.onResult(r != null && r.contains("Success")));
    }

    public void forceStopApp(String pkg, AdbCallback<Boolean> c) {
        execShell("am force-stop " + pkg, r -> c.onResult(r != null && !r.contains("ERROR")));
    }

    public void getDeviceProperties(AdbCallback<String> c) { execShell("getprop", c); }

    public void listPackages(AdbCallback<String> c) { execShell("pm list packages -3", c); }

    /**
     * 获取已禁用应用列表（Android 16 兼容）
     * 使用 dumpsys package 获取更可靠的结果
     */
    public void listDisabledPackages(AdbCallback<List<String>> c) {
        // Android 16: 使用 dumpsys package 比 pm list packages 更可靠
        execShell("dumpsys package --user 0 | grep -E 'pkg=.*DISABLED|\\s+DISABLED_USER' | grep -oP 'pkg=\\K[^\\s]+'", r -> {
            if (r != null && !r.trim().isEmpty()) {
                List<String> packages = new java.util.ArrayList<>();
                for (String line : r.split("\\n")) {
                    String pkg = line.trim();
                    if (!pkg.isEmpty() && pkg.contains(".")) {
                        packages.add(pkg);
                    }
                }
                if (!packages.isEmpty()) {
                    c.onResult(packages);
                    return;
                }
            }
            // 备用方案：使用 pm list packages -d --user 0
            execShell("pm list packages -d --user 0", r2 -> {
                if (r2 != null && !r2.trim().isEmpty()) {
                    List<String> packages = new java.util.ArrayList<>();
                    for (String line : r2.split("\\n")) {
                        String lineTrimmed = line.trim();
                        if (lineTrimmed.startsWith("package:")) {
                            String pkg = lineTrimmed.substring(8).trim();
                            if (!pkg.isEmpty()) {
                                packages.add(pkg);
                            }
                        }
                    }
                    c.onResult(packages);
                } else {
                    // 最终备用：pm list packages -d 不带 --user
                    execShell("pm list packages -d", r3 -> {
                        List<String> packages = new java.util.ArrayList<>();
                        if (r3 != null) {
                            for (String line : r3.split("\\n")) {
                                String lineTrimmed = line.trim();
                                if (lineTrimmed.startsWith("package:")) {
                                    String pkg = lineTrimmed.substring(8).trim();
                                    if (!pkg.isEmpty()) {
                                        packages.add(pkg);
                                    }
                                }
                            }
                        }
                        c.onResult(packages);
                    });
                }
            });
        });
    }

    // ===================== 密钥管理 =====================

    /**
     * 加载已有密钥或生成新密钥
     *
     * 存储格式：
     * - adb_rsa    : PKCS8 PEM 私钥
     * - adb_rsa.pub: AndroidPubkey 二进制格式公钥
     */
    private void loadOrCreateKeys() {
        if (mContext == null) return;

        File keyDir = new File(mContext.getFilesDir(), KEY_DIR);
        File privFile = new File(keyDir, PRIVATE_KEY_FILE);
        File pubFile  = new File(keyDir, PUBKEY_FILE);

        try {
            if (privFile.exists() && pubFile.exists()) {
                PrivateKey pk = loadPrivateKey(privFile);
                RSAPublicKey pub = loadAndroidPubkey(pubFile);
                if (pk != null && pub != null) {
                    connectionManager.setKeys(pk, pub);
                    addLog("🔑 已加载已保存的密钥");
                    return;
                }
            }
        } catch (Exception e) {
            addLog("⚠️ 加载密钥失败，将生成新密钥: " + e.getMessage());
        }

        try {
            KeyPair kp = generateKeyPair();
            connectionManager.setKeys(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
            addLog("🔑 已生成新的 RSA-2048 密钥");
        } catch (Exception e) {
            addLog("❌ 生成密钥失败: " + e.getMessage());
            Log.e(TAG, "generateKeyPair", e);
        }
    }

    /**
     * 生成 RSA-2048 密钥对
     *
     * 密钥生成后立即持久化（公钥 + 私钥），避免进程意外终止导致密钥丢失。
     * 连接时不再依赖配对成功后才保存密钥。
     */
    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        RSAPublicKey pubKey = (RSAPublicKey) kp.getPublic();

        // 生成后立即保存（与 saveKeysToFiles 保持一致的格式）
        savePrivateKeyToFile(kp.getPrivate());
        byte[] androidPubkeyBytes = AndroidPubkeyCodec.encode(pubKey);
        savePubkeyToFile(androidPubkeyBytes);
        addLog("🔑 已生成并保存新的 RSA-2048 密钥");
        return kp;
    }

    private void savePrivateKeyToFile(PrivateKey pk) throws IOException {
        File keyDir = new File(mContext.getFilesDir(), KEY_DIR);
        if (!keyDir.exists()) keyDir.mkdirs();
        File f = new File(keyDir, PRIVATE_KEY_FILE);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + android.util.Base64.encodeToString(pk.getEncoded(), android.util.Base64.NO_WRAP)
                + "\n-----END PRIVATE KEY-----\n";
        writeString(f, pem);
    }

    private void savePubkeyToFile(byte[] androidPubkeyBytes) throws IOException {
        File keyDir = new File(mContext.getFilesDir(), KEY_DIR);
        if (!keyDir.exists()) keyDir.mkdirs();
        File f = new File(keyDir, PUBKEY_FILE);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(androidPubkeyBytes);
        }
    }

    private void saveKeysToFiles() {
        try {
            PrivateKey pk = connectionManager.getPrivateKey();
            RSAPublicKey pub = (RSAPublicKey) connectionManager.getCertificate().getPublicKey();
            if (pk != null && pub != null) {
                savePrivateKeyToFile(pk);
                savePubkeyToFile(AndroidPubkeyCodec.encode(pub));
                addLog("💾 密钥已保存到: " + mContext.getFilesDir() + "/" + KEY_DIR);
            }
        } catch (Exception e) {
            Log.e(TAG, "saveKeysToFiles", e);
        }
    }

    private PrivateKey loadPrivateKey(File file) throws Exception {
        String pem = readString(file)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] decoded = android.util.Base64.decode(pem, android.util.Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private RSAPublicKey loadAndroidPubkey(File file) throws Exception {
        byte[] data;
        try (FileInputStream fis = new FileInputStream(file)) {
            data = fis.readAllBytes();
        }
        // 空文件或只有换行，视为无效
        if (data == null || data.length < 64) {
            throw new IOException("公钥文件无效或损坏: " + file.getName());
        }
        return AndroidPubkeyCodec.decode(data);
    }

    private String readString(File file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) baos.write(buf, 0, len);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }

    private void writeString(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    // ===================== 网络工具 =====================

    public static boolean isPortOpen(String host, int port, int timeoutMs) {
        if (host == null || host.isEmpty()) return false;
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== 连接管理器 =====================

    /**
     * libadb-android 连接管理器实现
     *
     * 实现 AbsAdbConnectionManager 的三个抽象方法：
     * - getPrivateKey(): 返回 RSA 私钥
     * - getCertificate(): 返回自定义 Certificate（含原始 RSAPublicKey）
     * - getDeviceName(): 返回设备标识名
     *
     * AndroidPubkeyCertificate.getPublicKey() 直接返回持有的 RSAPublicKey，
     * 因此 AndroidPubkeyCodec.encode() 的输入恒等于生成时的公钥，无格式转换误差。
     */
    private static class MyConnectionManager extends AbsAdbConnectionManager {
        private PrivateKey mPrivateKey;
        private RSAPublicKey mPublicKey;

        public void setKeys(PrivateKey pk, RSAPublicKey pub) {
            this.mPrivateKey = pk;
            this.mPublicKey = pub;
        }

        @Override
        protected PrivateKey getPrivateKey() {
            return mPrivateKey;
        }

        @Override
        protected java.security.cert.Certificate getCertificate() {
            return new AndroidPubkeyCertificate(mPublicKey);
        }

        @Override
        protected String getDeviceName() {
            return "MiaoMiaoTV";
        }
    }

    /**
     * 自定义 Certificate 实现
     *
     * getPublicKey() 直接返回构造时注入的 RSAPublicKey。
     * 不依赖 X.509 DER 解码，避免格式转换引入的误差。
     *
     * libadb-android 使用流程：
     * 1. getCertificate().getPublicKey() → 原始 RSAPublicKey
     * 2. AndroidPubkeyCodec.encode(pubKey) → Android 二进制格式发给设备
     * 3. adbAuthSign(privateKey, token) → 签名
     */
    private static class AndroidPubkeyCertificate extends java.security.cert.Certificate {
        private static final long serialVersionUID = 1L;
        private final RSAPublicKey pubKey;

        AndroidPubkeyCertificate(RSAPublicKey pubKey) {
            super("X.509");
            this.pubKey = pubKey;
        }

        @Override public java.security.PublicKey getPublicKey() { return pubKey; }

        @Override public byte[] getEncoded() throws java.security.cert.CertificateEncodingException {
            try {
                return encodeRSAPublicKey(pubKey);
            } catch (java.security.cert.CertificateEncodingException e) {
                throw e;
            } catch (Exception ex) {
                throw new java.security.cert.CertificateEncodingException(ex.getMessage());
            }
        }

        private byte[] encodeRSAPublicKey(RSAPublicKey pubKey) throws java.security.cert.CertificateEncodingException {
            java.math.BigInteger n = pubKey.getModulus();
            java.math.BigInteger e = pubKey.getPublicExponent();

            // RSA 算法标识符 OID 1.2.840.113549.1.1.1 (PKCS#1)
            byte[] algorithmId = new byte[] {
                0x30, 0x0d, 0x06, 0x09, 0x2a, (byte)0x86, 0x48, (byte)0x86,
                (byte)0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
            };

            byte[] nBytes = toDERInteger(n);
            byte[] eBytes = toDERInteger(e);
            byte[] rsaKey = new byte[4 + nBytes.length + eBytes.length];
            int pos = 0;
            rsaKey[pos++] = 0x02; rsaKey[pos++] = (byte) nBytes.length;
            System.arraycopy(nBytes, 0, rsaKey, pos, nBytes.length); pos += nBytes.length;
            rsaKey[pos++] = 0x02; rsaKey[pos++] = (byte) eBytes.length;
            System.arraycopy(eBytes, 0, rsaKey, pos, eBytes.length);

            byte[] rsaKeyBitString = new byte[1 + rsaKey.length];
            rsaKeyBitString[0] = 0x00;
            System.arraycopy(rsaKey, 0, rsaKeyBitString, 1, rsaKey.length);

            int totalSeqLen = algorithmId.length + 3 + rsaKeyBitString.length;
            byte[] result = new byte[2 + totalSeqLen];
            result[0] = 0x30; result[1] = (byte) totalSeqLen;
            System.arraycopy(algorithmId, 0, result, 2, algorithmId.length);
            int bitStringPos = 2 + algorithmId.length;
            result[bitStringPos++] = 0x03;
            result[bitStringPos++] = (byte) rsaKeyBitString.length;
            System.arraycopy(rsaKeyBitString, 0, result, bitStringPos, rsaKeyBitString.length);
            return result;
        }

        private byte[] toDERInteger(java.math.BigInteger value) {
            byte[] val = value.toByteArray();
            if (val.length > 1 && val[0] == 0x00) {
                byte[] trimmed = new byte[val.length - 1];
                System.arraycopy(val, 1, trimmed, 0, trimmed.length);
                val = trimmed;
            }
            byte[] result = new byte[2 + val.length];
            result[0] = 0x02;
            result[1] = (byte) val.length;
            System.arraycopy(val, 0, result, 2, val.length);
            return result;
        }

        @Override public void verify(java.security.PublicKey key, String sigProvider) {
            throw new UnsupportedOperationException("verify not supported");
        }

        @Override public void verify(java.security.PublicKey key) {
            throw new UnsupportedOperationException("verify not supported");
        }

        @Override public String toString() {
            return "AndroidPubkeyCertificate[pubkey=RSAPublicKey]";
        }
    }
}
