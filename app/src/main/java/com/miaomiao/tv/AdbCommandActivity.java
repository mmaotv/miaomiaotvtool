package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.content.Intent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * ADB 命令执行界面
 * 提供命令输入窗口和日志显示
 */
public class AdbCommandActivity extends AppCompatActivity {
    private final PrivilegedBackendManager backendManager = PrivilegedBackendManager.get();

    private LinearLayout btnBack;
    private LinearLayout btnClearLog;
    private TextView tvStatus;
    private LinearLayout btnConnect;
    private ScrollView scrollLog;
    private TextView tvLog;
    private EditText etCommand;
    private LinearLayout btnSend;
    private TextView btnQuickPm;
    private TextView btnQuickProp;
    private TextView btnQuickShell;
    private TextView btnQuickInstall;
    private TextView btnQuickDumpsys;
    private TextView tvHistoryHint;

    private final StringBuilder logBuilder = new StringBuilder();
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;
    private boolean isExecuting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化 ADB Helper（启用密钥持久化）
        backendManager.init(this);

        // 沉浸式：隐藏系统UI
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(R.layout.activity_adb_command);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        initListeners();
        checkAdbStatus();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        btnClearLog = findViewById(R.id.btnClearLog);
        tvStatus = findViewById(R.id.tvStatus);
        btnConnect = findViewById(R.id.btnConnect);
        scrollLog = findViewById(R.id.scrollLog);
        tvLog = findViewById(R.id.tvLog);
        etCommand = findViewById(R.id.etCommand);
        btnSend = findViewById(R.id.btnSend);
        btnQuickPm = findViewById(R.id.btnQuickPm);
        btnQuickProp = findViewById(R.id.btnQuickProp);
        btnQuickShell = findViewById(R.id.btnQuickShell);
        btnQuickInstall = findViewById(R.id.btnQuickInstall);
        btnQuickDumpsys = findViewById(R.id.btnQuickDumpsys);
        tvHistoryHint = findViewById(R.id.tvHistoryHint);

        // 初始日志
        appendLog("💻 ADB 命令终端 v1.0");
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        appendLog("");
    }

    private void initListeners() {
        // 返回按钮（点击返回，长按首页）
        btnBack.setOnClickListener(v -> finish());
        btnBack.setOnLongClickListener(v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
            return true;
        });

        // 清空日志
        btnClearLog.setOnClickListener(v -> {
            logBuilder.setLength(0);
            tvLog.setText("");
            appendLog("🗑 日志已清空");
        });

        // 连接设备
        btnConnect.setOnClickListener(v -> showConnectDialog());

        // 发送命令
        btnSend.setOnClickListener(v -> executeCommand());

        // 输入框回车发送
        etCommand.setOnEditorActionListener((v, actionId, event) -> {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                executeCommand();
                return true;
            }
            return false;
        });

        // 快捷命令
        btnQuickPm.setOnClickListener(v -> insertQuickCommand("pm list packages -3"));
        btnQuickProp.setOnClickListener(v -> insertQuickCommand("getprop"));
        btnQuickShell.setOnClickListener(v -> insertQuickCommand("ls /sdcard/"));
        btnQuickInstall.setOnClickListener(v -> insertQuickCommand("pm install "));
        btnQuickDumpsys.setOnClickListener(v -> insertQuickCommand("dumpsys activity top"));

        // 历史命令导航
        etCommand.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    navigateHistory(-1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    navigateHistory(1);
                    return true;
                }
            }
            return false;
        });

        // 焦点动画
        attachFocusScale(btnBack, 1.10f);
        attachFocusScale(btnClearLog, 1.10f);
        attachFocusScale(btnConnect, 1.10f);
        attachFocusScale(btnSend, 1.10f);
        attachFocusScale(btnQuickPm, 1.08f);
        attachFocusScale(btnQuickProp, 1.08f);
        attachFocusScale(btnQuickShell, 1.08f);
        attachFocusScale(btnQuickInstall, 1.08f);
        attachFocusScale(btnQuickDumpsys, 1.08f);

        etCommand.requestFocus();
    }

    private void checkAdbStatus() {
        BackendState state = backendManager.getCurrentState();
        updateStatusDisplay(state);
        appendLog("📡 当前后端：" + DialogHelper.buildBackendStatusText(state));
        // 显示版本兼容信息
        appendLog("📱 " + AdbHelper.get().getAndroidVersionInfo());
        appendLog(AdbHelper.get().getCompatibilityHint());

        backendManager.addListener(newState -> runOnUiThread(() -> updateStatusDisplay(newState)));
        backendManager.refreshState(newState -> runOnUiThread(() -> updateStatusDisplay(newState)));
    }

    private void updateStatusDisplay(BackendState state) {
        tvStatus.setText(DialogHelper.buildBackendStatusText(state));
        switch (state.status) {
            case SHIZUKU_READY:
            case CONNECTED:
                tvStatus.setTextColor(0xFF27AE60);
                break;
            case SHIZUKU_PERMISSION_REQUIRED:
            case PAIRED:
            case AUTH_REQUIRED:
                tvStatus.setTextColor(0xFFF39C12);
                break;
            default:
                tvStatus.setTextColor(0xFF95A5A6);
                break;
        }
    }

    private void appendLog(String text) {
        logBuilder.append(text).append("\n");
        tvLog.setText(logBuilder.toString());
        // 滚动到底部
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void insertQuickCommand(String cmd) {
        etCommand.setText(cmd);
        etCommand.setSelection(cmd.length());
        etCommand.requestFocus();
    }

    private void executeCommand() {
        if (isExecuting) {
            Toast.makeText(this, "命令执行中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }

        String cmd = etCommand.getText().toString().trim();
        if (cmd.isEmpty()) return;

        if (!backendManager.isReady()) {
            appendLog("❌ 当前没有可用的特权后端");
            Toast.makeText(this, "请先授权 Shizuku 或连接无线 ADB", Toast.LENGTH_SHORT).show();
            return;
        }

        isExecuting = true;
        commandHistory.add(cmd);
        historyIndex = commandHistory.size();

        if (commandHistory.size() > 0) {
            tvHistoryHint.setVisibility(View.VISIBLE);
        }

        etCommand.setText("");

        appendLog(">>> " + cmd);

        backendManager.execShell(cmd, result -> {
            runOnUiThread(() -> {
                isExecuting = false;
                if (result != null && !result.isEmpty()) {
                    appendLog(result);
                }
                appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            });
        });
    }

    private void navigateHistory(int direction) {
        if (commandHistory.isEmpty()) return;

        historyIndex += direction;
        if (historyIndex < 0) historyIndex = 0;
        if (historyIndex >= commandHistory.size()) {
            historyIndex = commandHistory.size();
            etCommand.setText("");
            return;
        }

        etCommand.setText(commandHistory.get(historyIndex));
        etCommand.setSelection(etCommand.getText().length());
    }

    private void showWirelessPairDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_adb_pair, null);

        EditText etHost = dialogView.findViewById(R.id.etHost);
        EditText etPort = dialogView.findViewById(R.id.etPort);
        EditText etCode = dialogView.findViewById(R.id.etCode);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogStandardStyle).setView(dialogView).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        dialogView.findViewById(R.id.btnPositive).setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            if (host.isEmpty() || code.isEmpty()) { Toast.makeText(this, "请填写完整信息", Toast.LENGTH_SHORT).show(); return; }
            Integer port = parsePortOrToast(portStr, 5555);
            if (port == null) return;
            dialog.dismiss();
            doWirelessPair(host, port, code);
        });
        dialogView.findViewById(R.id.btnNegative).setOnClickListener(v -> dialog.dismiss());
        etHost.requestFocus();
        etHost.postDelayed(() -> { InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (imm != null) imm.showSoftInput(etHost, InputMethodManager.SHOW_IMPLICIT); }, 200);
        dialog.show();
    }

    private void doConnect(String host, int port) {
        appendLog("🔗 正在连接 " + host + ":" + port + " ...");

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在连接...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(false);
        pd.show();

        AdbHelper.get().connect(host, port, success -> {
            pd.dismiss();
            runOnUiThread(() -> {
                if (success) {
                    appendLog("✅ 连接成功");
                    backendManager.refreshState(null);
                    Toast.makeText(this, "ADB 连接成功", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("❌ 连接失败，请检查IP和端口");
                    Toast.makeText(this, "连接失败", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private volatile boolean isPairingCancelled = false;
    
    private void doWirelessPair(String host, int port, String code) {
        isPairingCancelled = false;
        appendLog("🔑 正在配对 " + host + ":" + port + " ...");

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在配对...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(true);
        pd.setOnCancelListener(dialog -> {
            isPairingCancelled = true;
            appendLog("⚠️ 配对已取消");
            Toast.makeText(this, "配对已取消", Toast.LENGTH_SHORT).show();
        });
        pd.show();

        AdbHelper.get().pair(host, port, code, pairSuccess -> {
            if (isPairingCancelled) return;
            
            if (pairSuccess) {
                pd.dismiss();
                runOnUiThread(() -> {
                    appendLog("✅ 配对成功");
                    appendLog("💡 请使用无线调试页面显示的连接端口继续连接");
                    Toast.makeText(this, "配对成功，请手动连接调试端口", Toast.LENGTH_SHORT).show();
                });
            } else {
                pd.dismiss();
                runOnUiThread(() -> {
                    appendLog("❌ 配对失败，请检查配对码");
                    Toast.makeText(this, "配对失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ===================== 连接对话框 =====================

    /** 显示连接对话框 */
    private void showConnectDialog() {
        BackendState state = backendManager.getCurrentState();
        if (state.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED) {
            DialogHelper.showBackendGuide(this, state,
                () -> backendManager.requestShizukuPermission(this, granted -> runOnUiThread(() -> {
                    if (granted) {
                        appendLog("✅ Shizuku 授权成功");
                        backendManager.refreshState(null);
                    } else {
                        appendLog("❌ Shizuku 授权失败或被拒绝");
                    }
                })),
                null);
            return;
        }
        if (state.status == BackendStatus.SHIZUKU_READY) {
            Toast.makeText(this, "Shizuku 已可用，可直接执行命令", Toast.LENGTH_SHORT).show();
            return;
        }
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_adb_connect, null);

        EditText etHost = dialogView.findViewById(R.id.dialogInput1);
        EditText etPort = dialogView.findViewById(R.id.dialogInput2);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);

        // 默认填入本机地址
        etHost.setText("127.0.0.1");
        etPort.setText("5555");
        
        // 添加版本兼容提示
        TextView tvHint = dialogView.findViewById(R.id.tvHint);
        if (tvHint != null) {
            if (AndroidVersionCompat.requiresPairing()) {
                tvHint.setText("💡 Android 11+ 首次连接需要配对码\n请在设备上查看配对码并输入");
            } else {
                tvHint.setText("💡 Android 5-10 版本无需配对码\n只需确保设备已开启无线 ADB");
            }
            tvHint.setVisibility(View.VISIBLE);
        }

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogStandardStyle).setView(dialogView).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.CENTER);
        }

        btnPositive.setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            if (host.isEmpty()) {
                Toast.makeText(this, "请输入设备IP地址", Toast.LENGTH_SHORT).show();
                return;
            }
            Integer port = parsePortOrToast(portStr, 5555);
            if (port == null) return;
            dialog.dismiss();
            doConnect(host, port);
        });
        btnNegative.setOnClickListener(v -> dialog.dismiss());
        etHost.requestFocus();
        etHost.postDelayed(() -> { InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (imm != null) imm.showSoftInput(etHost, InputMethodManager.SHOW_IMPLICIT); }, 200);
        dialog.show();
    }

    private Integer parsePortOrToast(String portStr, int defaultPort) {
        if (portStr == null || portStr.trim().isEmpty()) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(portStr.trim());
            if (port < 1 || port > 65535) {
                Toast.makeText(this, "端口范围必须在 1-65535", Toast.LENGTH_SHORT).show();
                return null;
            }
            return port;
        } catch (NumberFormatException e) {
            Toast.makeText(this, "端口必须是数字", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private void attachFocusScale(View view, float scale) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? scale : 1.0f;
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                ObjectAnimator.ofFloat(v, "scaleX", s),
                ObjectAnimator.ofFloat(v, "scaleY", s)
            );
            set.setDuration(150);
            set.start();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 可选：断开连接
        // AdbHelper.get().disconnect();
    }
}
