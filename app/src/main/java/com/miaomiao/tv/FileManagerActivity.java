package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件管理器 Activity - 重构版 v2
 * 1. 顶部标题栏：返回 + 页面标题 + 当前路径（整合在标题旁）+ ADB状态 + 刷新
 * 2. 顶部功能标签栏（返回按钮为第一项）：返回/文件互传/内部存储/Download/[U盘动态]/搜索/空目录/根/统计/新建/设置
 * 3. 中间文件列表区域（最大化纵向空间）
 * 无底部提示栏，无独立路径栏，无独立返回按钮栏
 */
public class FileManagerActivity extends AppCompatActivity {
    private final PrivilegedBackendManager backendManager = PrivilegedBackendManager.get();

    // 1. 顶部标题栏
    private LinearLayout btnBackHome;
    private LinearLayout btnRefresh;
    private TextView tvAdbStatus;

    // 路径显示（整合在标题旁）
    private TextView tvCurrentPath;

    // 2. 顶部功能标签栏
    private LinearLayout tabFileTransfer;
    private LinearLayout tabInternalStorage;
    private LinearLayout tabDownload;
    private LinearLayout btnFileSearch;
    private LinearLayout btnEmptyDirs;
    private LinearLayout btnRootDir;
    private LinearLayout btnStorageStat;
    private LinearLayout btnNewFolder;
    // btnSettings 已删除
    private LinearLayout usbTabContainer;   // U盘动态按钮容器

    // 兼容旧字段（U盘管理Tab，改为动态）
    private LinearLayout tabUsbStorage;

    // 3. 内容区
    private LinearLayout internalStorageContent;
    private LinearLayout usbStorageContent;
    private LinearLayout downloadContent;
    private LinearLayout fileTransferContent;
    private LinearLayout usbEmptyHint;
    private ScrollView scrollView;
    private LinearLayout fileListContainer;
    private ScrollView usbScrollView;
    private LinearLayout usbListContainer;
    private ScrollView downloadScrollView;
    private LinearLayout downloadListContainer;

    // 返回上级（在标签栏首位）
    private LinearLayout btnParentDir;

    /** 当前目录 */
    private File currentDir;
    /** 所有文件项 View */
    private final List<View> fileItemViews = new ArrayList<>();
    /** 当前选中的文件索引 */
    private int selectedIndex = -1;
    /** 所有文件/文件夹（排序后） */
    private File[] sortedFiles;
    /** 剪贴板：被复制的文件 */
    private File clipboardFile = null;
    /** 剪贴板模式：true=复制，false=移动 */
    private boolean clipboardCopy = true;

    // ===================== 批量操作相关 =====================
    /** 是否处于批量选择模式 */
    private boolean isBatchMode = false;
    /** 批量选中的文件列表 */
    private java.util.List<File> selectedFiles = new java.util.ArrayList<>();
    /** 批量操作工具栏 */
    private LinearLayout batchActionBar;
    /** 批量操作计数显示 */
    private TextView tvBatchCount;
    /** 批量操作按钮 */
    private LinearLayout btnBatchDelete, btnBatchCopy, btnBatchMove, btnBatchRename;
    /** 退出批量模式按钮 */
    private LinearLayout btnExitBatchMode;

    /** 标记：是否显示过权限提示 */
    private static boolean hasShownPermissionTip = false;
    /** 权限模式：true=全权限，false=受限模式 */
    private boolean isFullPermissionMode = true;
    /** ADB是否已连接 */
    private boolean isAdbConnected = false;

    /** 当前Tab索引：0=文件互传,1=内部存储,2=U盘管理,3=Download */
    private int currentTabIndex = 1;

    /** 已发现的U盘列表 */
    private final List<UsbDriveInfo> usbDrives = new ArrayList<>();
    private final List<View> usbItemViews = new ArrayList<>();

    private static class UsbDriveInfo {
        String name;
        String path;
        long totalSize;
        long freeSize;

        String getTotalStr() { return formatFileSize(totalSize); }
        String getFreeStr() { return formatFileSize(freeSize); }
    }

    /** USB插拔广播接收器 */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            detectUsbDrives();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        backendManager.init(this);

        // 初始化 ADB Helper（启用密钥持久化）
        AdbHelper.get().init(this);

        // 沉浸式：隐藏系统UI
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        try {
            setContentView(R.layout.activity_file_manager);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "布局加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            // 注册USB插拔广播
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addAction("android.intent.action.MEDIA_EJECTED");
            filter.addDataScheme("file");
            registerReceiver(usbReceiver, filter);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            initViews();
            initToolbarButtons();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        try {
            checkAndRequestPermissions();
        } catch (Exception e) {
            e.printStackTrace();
            navigateToLimitedMode();
        }

        try {
            detectUsbDrives();
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            checkAdbStatus();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 处理外部传入的路径和高亮文件
        handleIntentExtras();
    }

    /** 处理外部传入的路径和高亮文件参数 */
    private void handleIntentExtras() {
        Intent intent = getIntent();
        if (intent == null) return;

        String path = intent.getStringExtra("path");
        String highlightFile = intent.getStringExtra("highlight_file");

        if (path != null) {
            File targetDir;
            if (path.equals(Environment.DIRECTORY_DOWNLOADS)) {
                targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            } else {
                targetDir = new File(path);
            }

            if (targetDir.exists() && targetDir.isDirectory()) {
                navigateTo(targetDir);

                // 如果有高亮文件，在刷新后高亮显示
                if (highlightFile != null) {
                    mainHandler.postDelayed(() -> highlightFileByName(highlightFile), 300);
                }
            }
        }
    }

    /** 根据文件名高亮显示文件 */
    private void highlightFileByName(String fileName) {
        if (sortedFiles == null || fileItemViews == null) return;

        for (int i = 0; i < sortedFiles.length; i++) {
            if (sortedFiles[i] != null && sortedFiles[i].getName().equals(fileName)) {
                if (i < fileItemViews.size()) {
                    View targetView = fileItemViews.get(i);
                    targetView.requestFocus();
                    // 滚动到该位置
                    if (scrollView != null) {
                        final int finalI = i;
                        scrollView.post(() -> {
                            int y = finalI * (int)(80 * getResources().getDisplayMetrics().density);
                            scrollView.smoothScrollTo(0, y);
                        });
                    }
                }
                break;
            }
        }
    }

    /** 是否已完成初始化检测（用于区分首次加载和后续插入） */
    private boolean usbInitialDetected = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {}
    }

    /** 检查ADB状态 */
    private void checkAdbStatus() {
        BackendState state = backendManager.getCurrentState();
        isAdbConnected = state.ready;
        updateAdbStatusDisplay(state);
        backendManager.addListener(s -> runOnUiThread(() -> {
            isAdbConnected = s.ready;
            updateAdbStatusDisplay(s);
        }));
        backendManager.refreshState(s -> runOnUiThread(() -> {
            isAdbConnected = s.ready;
            updateAdbStatusDisplay(s);
        }));
    }

    /** 更新 ADB 状态显示 */
    private void updateAdbStatusDisplay(BackendState state) {
        if (tvAdbStatus == null) return;
        tvAdbStatus.setText(DialogHelper.buildBackendStatusText(state));
        switch (state.status) {
            case SHIZUKU_READY:
            case CONNECTED:
                tvAdbStatus.setTextColor(0xFF27AE60);
                break;
            case SHIZUKU_PERMISSION_REQUIRED:
            case PAIRED:
            case AUTH_REQUIRED:
                tvAdbStatus.setTextColor(0xFFF39C12);
                break;
            default:
                tvAdbStatus.setTextColor(0xFF95A5A6);
                break;
        }
    }

    /** 通过 ADB 安装 APK 到设备（使用 DialogHelper 白底弹窗） */
    private void showAdbInstallDialog(File apkFile) {
        DialogHelper.showConfirm(this, "📲", "通过ADB安装",
            "是否通过 ADB 将以下 APK 安装到设备？\n\n" + apkFile.getName(),
            "安装", "取消",
            () -> installApkViaAdb(apkFile));
    }

    /** 通过 ADB 执行安装命令 */
    private void installApkViaAdb(File apkFile) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("正在安装...\n" + apkFile.getName());
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(false);
        pd.show();

        new Thread(() -> {
            try {
                if (backendManager.isUsingShizuku()) {
                    backendManager.installApkViaShizuku(apkFile.getAbsolutePath(), success -> runOnUiThread(() -> {
                        pd.dismiss();
                        if (success) {
                            Toast.makeText(this, "✅ 已通过 Shizuku 安装成功", Toast.LENGTH_LONG).show();
                        } else {
                            DialogHelper.showConfirm(FileManagerActivity.this, "📲", "Shizuku 安装失败",
                                "Shizuku 安装失败，是否使用系统安装器安装？\n\n" + apkFile.getName(),
                                "使用系统安装器", "取消",
                                () -> openFile(apkFile));
                        }
                    }));
                    return;
                }
                String remotePath = "/data/local/tmp/" + apkFile.getName();
                String pushResult = backendManager.execShellSync("push \"" + apkFile.getAbsolutePath() + "\" " + remotePath);
                if (pushResult == null || pushResult.contains("error")) {
                    final String err = pushResult != null ? pushResult : "推送失败";
                    runOnUiThread(() -> {
                        pd.dismiss();
                        Toast.makeText(this, "❌ 推送失败: " + err, Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                String installResult = backendManager.execShellSync("install -r -t \"" + remotePath + "\"");
                boolean adbSuccess = installResult != null && installResult.contains("Success");
                if (!adbSuccess) {
                    // ADB安装失败，询问是否使用系统安装器
                    String finalRemotePath = remotePath;
                    File localApkFile = apkFile;
                    runOnUiThread(() -> {
                        pd.dismiss();
                        DialogHelper.showConfirm(FileManagerActivity.this, "📲", "ADB安装失败",
                            "ADB 安装失败，是否使用系统安装器安装？\n\n" + apkFile.getName(),
                            "使用系统安装器", "取消",
                            () -> openFile(localApkFile));
                    });
                    return;
                }
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, "✅ 安装成功！", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, "❌ 安装异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** ADB 连接对话框（安卓10以下直连本机，安卓11+使用简洁布局：IP+端口+取消/连接） */
    private void showAdbConnectDialog() {
        BackendState currentState = backendManager.getCurrentState();
        if (currentState.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED) {
            DialogHelper.showBackendGuide(this, currentState,
                () -> backendManager.requestShizukuPermission(this, granted -> runOnUiThread(() -> backendManager.refreshState(null))),
                null);
            return;
        }
        if (currentState.status == BackendStatus.SHIZUKU_READY) {
            Toast.makeText(this, "Shizuku 已可用，无需再连接无线 ADB", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            // Android 10 及以下：直接连接本机 ADB 服务
            final ProgressDialog pd = new ProgressDialog(this);
            pd.setMessage("正在连接 127.0.0.1:5555 ...");
            pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            pd.setCancelable(true);
            pd.show();

            AdbHelper.get().connect("127.0.0.1", 5555, ok -> runOnUiThread(() -> {
                pd.dismiss();
                if (ok) {
                    backendManager.refreshState(null);
                    Toast.makeText(this, "✅ 本机 ADB 连接成功！", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "❌ 连接失败，请确认本机已开启 ADB 无线调试（adb tcpip 5555）", Toast.LENGTH_LONG).show();
                }
            }));
            return;
        }
        // Android 11+：使用简洁对话框，仅IP+端口+取消/连接（无需配对码）
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_adb_connect, null);

        EditText etIp   = dialogView.findViewById(R.id.dialogInput1);
        EditText etPort = dialogView.findViewById(R.id.dialogInput2);
        android.widget.LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        android.widget.LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);

        // IP 输入过滤（仅数字和点）
        etIp.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etIp.setFilters(new android.text.InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            StringBuilder b = new StringBuilder();
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (Character.isDigit(c) || c == '.') b.append(c);
            }
            return b.toString();
        }});

        // 端口默认 5555
        etPort.setText("5555");

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogStandardStyle).create();
        dialog.setView(dialogView);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        // 取消按钮
        tvNegative.setText("取消");
        btnNegative.setVisibility(View.VISIBLE);
        btnNegative.setOnClickListener(v -> dialog.dismiss());

        // 连接按钮
        tvPositive.setText("连接");
        btnPositive.setOnClickListener(v -> {
            String host = etIp.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();

            if (host.isEmpty()) {
                Toast.makeText(this, "⚠️ 请输入目标设备IP", Toast.LENGTH_SHORT).show();
                return;
            }
            int port = 5555;
            try { port = Integer.parseInt(portStr.isEmpty() ? "5555" : portStr); } catch (Exception ignored) {}

            dialog.dismiss();
            // 直接连接
            AdbHelper.get().connect(host, port, ok -> runOnUiThread(() -> {
                if (ok) {
                    backendManager.refreshState(null);
                    Toast.makeText(this, "✅ ADB 连接成功！", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "❌ 连接失败，请确认设备在线且端口正确", Toast.LENGTH_LONG).show();
                }
            }));
        });

        dialog.show();

        // 弹窗宽度 400dp
        if (dialog.getWindow() != null) {
            int w = (int) (400 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        // 自动聚焦 IP 输入框
        etIp.requestFocus();
        etIp.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etIp, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    /** 初始化工具栏按钮 */
    private void initToolbarButtons() {
        // 刷新按钮
        if (btnRefresh != null) {
            btnRefresh.setOnClickListener(v -> { try { refresh(); } catch (Exception e) { e.printStackTrace(); } });
            attachFocusScale(btnRefresh, 1.05f);
        }

        // 文件互传：直接跳转到扫码推送页面
        if (tabFileTransfer != null) {
            tabFileTransfer.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(this, ScanPushActivity.class);
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(this, "无法打开扫码推送页面", Toast.LENGTH_SHORT).show();
                }
            });
            attachFocusScale(tabFileTransfer, 1.05f);
        }

        // 内部存储
        if (tabInternalStorage != null) {
            tabInternalStorage.setOnClickListener(v -> switchToTab(1));
            attachFocusScale(tabInternalStorage, 1.05f);
        }

        // U盘管理
        if (tabUsbStorage != null) {
            tabUsbStorage.setOnClickListener(v -> switchToTab(2));
            attachFocusScale(tabUsbStorage, 1.05f);
        }

        // Download
        if (tabDownload != null) {
            tabDownload.setOnClickListener(v -> switchToTab(3));
            attachFocusScale(tabDownload, 1.05f);
        }

        // 文件搜索：弹出类型选择
        if (btnFileSearch != null) {
            btnFileSearch.setOnClickListener(v -> showSearchTypeDialog());
            attachFocusScale(btnFileSearch, 1.05f);
        }

        // 空目录
        if (btnEmptyDirs != null) {
            btnEmptyDirs.setOnClickListener(v -> showEmptyDirs());
            attachFocusScale(btnEmptyDirs, 1.05f);
        }

        // 根目录
        if (btnRootDir != null) {
            btnRootDir.setOnClickListener(v -> navigateToRoot());
            attachFocusScale(btnRootDir, 1.05f);
        }

        // 统计
        if (btnStorageStat != null) {
            btnStorageStat.setOnClickListener(v -> showStorageStat());
            attachFocusScale(btnStorageStat, 1.05f);
        }

        // 新建：弹出选择新建文件/文件夹
        if (btnNewFolder != null) {
            btnNewFolder.setOnClickListener(v -> showNewChoiceDialog());
            attachFocusScale(btnNewFolder, 1.05f);
        }
    }

    /** 导航到根目录 */
    private void navigateToRoot() {
        File root = Environment.getExternalStorageDirectory();
        navigateTo(root);
    }

    /** Tab切换 */
    private void switchToTab(int tabIndex) {
        currentTabIndex = tabIndex;

        // 重置所有Tab样式
        resetAllTabStyles();

        // 隐藏所有内容区
        if (internalStorageContent != null) internalStorageContent.setVisibility(View.GONE);
        if (usbStorageContent != null) usbStorageContent.setVisibility(View.GONE);
        if (downloadContent != null) downloadContent.setVisibility(View.GONE);
        if (fileTransferContent != null) fileTransferContent.setVisibility(View.GONE);

        switch (tabIndex) {
            case 0: // 文件互传
                setTabSelected(tabFileTransfer, "📤", "文件互传");
                if (fileTransferContent != null) fileTransferContent.setVisibility(View.VISIBLE);
                if (tvCurrentPath != null) tvCurrentPath.setText("文件互传");
                break;
            case 1: // 内部存储
                setTabSelected(tabInternalStorage, "💾", "内部存储");
                if (internalStorageContent != null) internalStorageContent.setVisibility(View.VISIBLE);
                break;
            case 2: // U盘管理（进入U盘内容区）
                if (usbStorageContent != null) usbStorageContent.setVisibility(View.VISIBLE);
                detectUsbDrives();
                break;
            case 3: // Download
                setTabSelected(tabDownload, "📥", "Download");
                if (downloadContent != null) downloadContent.setVisibility(View.VISIBLE);
                navigateToDownload();
                break;
        }
    }

    private void resetAllTabStyles() {
        if (tabFileTransfer != null) {
            tabFileTransfer.setBackgroundResource(R.drawable.btn_toolbar_bg);
            View child = tabFileTransfer.getChildAt(1);
            if (child instanceof TextView) ((TextView)child).setTextColor(0xFF2C3E50);
        }
        if (tabInternalStorage != null) {
            tabInternalStorage.setBackgroundResource(R.drawable.btn_toolbar_bg);
            View child = tabInternalStorage.getChildAt(1);
            if (child instanceof TextView) ((TextView)child).setTextColor(0xFF2C3E50);
        }
        if (tabDownload != null) {
            tabDownload.setBackgroundResource(R.drawable.btn_toolbar_bg);
            View child = tabDownload.getChildAt(1);
            if (child instanceof TextView) ((TextView)child).setTextColor(0xFF2C3E50);
        }
    }

    private void setTabSelected(LinearLayout tab, String icon, String text) {
        if (tab != null) {
            tab.setBackgroundResource(R.drawable.tab_selected_bg);
            View child = tab.getChildAt(1);
            if (child instanceof TextView) ((TextView)child).setTextColor(0xFF00D9FF);
        }
    }

    private void initViews() {
        // 顶部标题栏
        btnBackHome = findViewById(R.id.btnBackHome);
        btnRefresh = findViewById(R.id.btnRefresh);
        tvAdbStatus = findViewById(R.id.tvAdbStatus);

        // 路径显示（整合在标题旁）
        tvCurrentPath = findViewById(R.id.tvCurrentPath);

        // 顶部功能标签栏
        tabFileTransfer = findViewById(R.id.tabFileTransfer);
        tabInternalStorage = findViewById(R.id.tabInternalStorage);
        tabUsbStorage = null; // 改为动态U盘按钮，不再有固定的tabUsbStorage
        tabDownload = findViewById(R.id.tabDownload);
        btnFileSearch = findViewById(R.id.btnFileSearch);
        btnEmptyDirs = findViewById(R.id.btnEmptyDirs);
        btnRootDir = findViewById(R.id.btnRootDir);
        btnStorageStat = findViewById(R.id.btnStorageStat);
        btnNewFolder = findViewById(R.id.btnNewFolder);
        // btnSettings 已从布局删除，无需 findViewById
        usbTabContainer = findViewById(R.id.usbTabContainer);

        // 内容区
        internalStorageContent = findViewById(R.id.internalStorageContent);
        usbStorageContent = findViewById(R.id.usbStorageContent);
        downloadContent = findViewById(R.id.downloadContent);
        fileTransferContent = findViewById(R.id.fileTransferContent);
        usbEmptyHint = findViewById(R.id.usbEmptyHint);

        // 文件列表
        scrollView = findViewById(R.id.scrollView);
        fileListContainer = findViewById(R.id.fileListContainer);

        // U盘列表
        usbScrollView = findViewById(R.id.usbScrollView);
        usbListContainer = findViewById(R.id.usbListContainer);

        // Download列表
        downloadScrollView = findViewById(R.id.downloadScrollView);
        downloadListContainer = findViewById(R.id.downloadListContainer);

        // 返回按钮（在标签栏首位）
        btnParentDir = findViewById(R.id.btnParentDir);

        // 绑定点击事件
        if (btnBackHome != null) {
            btnBackHome.setOnClickListener(v -> {
                try { finish(); } catch (Exception e) { e.printStackTrace(); }
            });
            attachFocusScale(btnBackHome, 1.08f);
        }

        if (btnParentDir != null) {
            btnParentDir.setOnClickListener(v -> {
                try {
                    if (currentDir != null) {
                        File parent = currentDir.getParentFile();
                        if (parent != null) navigateTo(parent);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            });
            attachFocusScale(btnParentDir, 1.05f);
        }

        // 默认显示内部存储
        switchToTab(1);

        // 默认聚焦返回按钮
        if (btnBackHome != null) {
            btnBackHome.requestFocus();
        }

        // ===================== 批量操作 UI 绑定 =====================
        batchActionBar = findViewById(R.id.batchActionBar);
        tvBatchCount = findViewById(R.id.tvBatchCount);
        btnBatchDelete = findViewById(R.id.btnBatchDelete);
        btnBatchCopy = findViewById(R.id.btnBatchCopy);
        btnBatchMove = findViewById(R.id.btnBatchMove);
        btnBatchRename = findViewById(R.id.btnBatchRename);
        btnExitBatchMode = findViewById(R.id.btnExitBatchMode);

        // 隐藏批量操作栏（默认）
        if (batchActionBar != null) {
            batchActionBar.setVisibility(View.GONE);
        }

        // 批量删除
        if (btnBatchDelete != null) {
            btnBatchDelete.setOnClickListener(v -> batchDelete());
            attachFocusScale(btnBatchDelete, 1.08f);
        }

        // 批量复制
        if (btnBatchCopy != null) {
            btnBatchCopy.setOnClickListener(v -> {
                if (selectedFiles.isEmpty()) {
                    Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                clipboardFile = selectedFiles.get(0).getParentFile();
                clipboardCopy = true;
                Toast.makeText(this, "已复制 " + selectedFiles.size() + " 个文件到剪贴板", Toast.LENGTH_SHORT).show();
            });
            attachFocusScale(btnBatchCopy, 1.08f);
        }

        // 批量移动
        if (btnBatchMove != null) {
            btnBatchMove.setOnClickListener(v -> {
                if (selectedFiles.isEmpty()) {
                    Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                clipboardFile = selectedFiles.get(0).getParentFile();
                clipboardCopy = false;
                Toast.makeText(this, "已标记 " + selectedFiles.size() + " 个文件待移动", Toast.LENGTH_SHORT).show();
            });
            attachFocusScale(btnBatchMove, 1.08f);
        }

        // 批量重命名
        if (btnBatchRename != null) {
            btnBatchRename.setOnClickListener(v -> showBatchRenameDialog());
            attachFocusScale(btnBatchRename, 1.08f);
        }

        // 退出批量模式
        if (btnExitBatchMode != null) {
            btnExitBatchMode.setOnClickListener(v -> exitBatchMode());
            attachFocusScale(btnExitBatchMode, 1.08f);
        }
    }

    /** 导航到指定目录 */
    private void navigateTo(File dir) {
        if (dir == null) {
            Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (!dir.exists()) {
                Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!dir.isDirectory()) {
                Toast.makeText(this, "不是有效的目录", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!dir.canRead()) {
                Toast.makeText(this, "无法读取此目录", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "访问目录失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        currentDir = dir;
        updatePathDisplay();
        refresh();
    }

    /** 更新路径显示（简化显示） */
    private void updatePathDisplay() {
        if (tvCurrentPath == null || currentDir == null) return;

        String path = currentDir.getAbsolutePath();
        String displayPath;

        // 检查是否是Download目录
        if (path.endsWith("/Download") || path.endsWith("Download") ||
            path.equals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath())) {
            displayPath = "内部存储 / Download";
        }
        // 检查是否是根目录
        else if (path.equals("/storage/emulated/0") || path.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            displayPath = "内部存储";
        }
        // 检查是否是U盘
        else if (path.startsWith("/storage/usb") || path.startsWith("/mnt/media_rw")) {
            String usbName = "UDisk" + path.substring(path.lastIndexOf("/") + 1).replaceAll("[^0-9]", "");
            if (usbName.equals("UDisk")) usbName = "UDisk01";
            displayPath = "内部存储 / " + usbName;
        }
        // 其他情况显示最后一级
        else {
            String name = path.substring(path.lastIndexOf('/') + 1);
            displayPath = "内部存储 / " + name;
        }

        tvCurrentPath.setText(displayPath);
    }

    // ===================== 批量操作方法 =====================

    /** 进入批量选择模式 */
    private void enterBatchMode() {
        isBatchMode = true;
        selectedFiles.clear();
        if (batchActionBar != null) {
            batchActionBar.setVisibility(View.VISIBLE);
        }
        updateBatchCount();
        refresh();
        Toast.makeText(this, "📋 进入批量选择模式，点击文件可多选", Toast.LENGTH_SHORT).show();
    }

    /** 退出批量选择模式 */
    private void exitBatchMode() {
        isBatchMode = false;
        selectedFiles.clear();
        if (batchActionBar != null) {
            batchActionBar.setVisibility(View.GONE);
        }
        refresh();
    }

    /** 更新批量选择计数 */
    private void updateBatchCount() {
        if (tvBatchCount != null) {
            tvBatchCount.setText("已选 " + selectedFiles.size() + " 个文件");
        }
    }

    /** 切换文件选择状态 */
    private void toggleFileSelection(File file) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file);
        } else {
            selectedFiles.add(file);
        }
        updateBatchCount();
    }

    /** 批量删除 */
    private void batchDelete() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("确定要删除以下 ").append(selectedFiles.size()).append(" 个文件/文件夹吗？\n\n");
        for (int i = 0; i < Math.min(selectedFiles.size(), 5); i++) {
            sb.append("• ").append(selectedFiles.get(i).getName()).append("\n");
        }
        if (selectedFiles.size() > 5) {
            sb.append("• ...等共").append(selectedFiles.size()).append("个");
        }

        new AlertDialog.Builder(this)
            .setTitle("🗑 批量删除")
            .setMessage(sb.toString())
            .setPositiveButton("删除", (d, w) -> {
                ProgressDialog pd = new ProgressDialog(this);
                pd.setMessage("正在删除文件...");
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setMax(selectedFiles.size());
                pd.setCancelable(false);
                pd.show();

                final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
                final java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
                final int total = selectedFiles.size();

                for (File file : selectedFiles) {
                    new Thread(() -> {
                        boolean success = deleteRecursively(file);
                        if (success) successCount.incrementAndGet();
                        int current = completed.incrementAndGet();
                        runOnUiThread(() -> pd.setProgress(current));
                        if (current >= total) {
                            runOnUiThread(() -> {
                                pd.dismiss();
                                Toast.makeText(FileManagerActivity.this, "✅ 已删除 " + successCount.get() + " 个文件", Toast.LENGTH_SHORT).show();
                            });
                            selectedFiles.clear();
                            exitBatchMode();
                            refresh();
                        }
                    }).start();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 递归删除文件/文件夹 */
    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    /** 显示批量重命名对话框 */
    private void showBatchRenameDialog() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_batch_rename, null);
        EditText etPrefix = dialogView.findViewById(R.id.etRenamePrefix);
        EditText etSuffix = dialogView.findViewById(R.id.etRenameSuffix);
        EditText etRegex = dialogView.findViewById(R.id.etRenameRegex);
        EditText etReplace = dialogView.findViewById(R.id.etRenameReplace);
        LinearLayout btnConfirm = dialogView.findViewById(R.id.btnConfirmRename);
        LinearLayout btnCancel = dialogView.findViewById(R.id.btnCancelRename);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogStandardStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);
        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnConfirm.setOnClickListener(v -> {
            String prefix = etPrefix.getText().toString();
            String suffix = etSuffix.getText().toString();
            String regex = etRegex.getText().toString();
            String replace = etReplace.getText().toString();

            if (prefix.isEmpty() && suffix.isEmpty() && regex.isEmpty()) {
                Toast.makeText(this, "请至少填写一项修改内容", Toast.LENGTH_SHORT).show();
                return;
            }

            dialog.dismiss();
            doBatchRename(prefix, suffix, regex, replace);
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8);
            dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 执行批量重命名 */
    private void doBatchRename(String prefix, String suffix, String regex, String replace) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在重命名文件...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(selectedFiles.size());
        pd.setCancelable(false);
        pd.show();


        final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        final int total = selectedFiles.size();

        for (File file : selectedFiles) {
            new Thread(() -> {
                boolean success = false;
                try {
                    String oldName = file.getName();
                    String newName = oldName;

                    // 添加前缀
                    if (!prefix.isEmpty()) {
                        newName = prefix + newName;
                    }

                    // 添加后缀（在扩展名前）
                    if (!suffix.isEmpty()) {
                        int dotIndex = newName.lastIndexOf('.');
                        if (dotIndex > 0) {
                            newName = newName.substring(0, dotIndex) + suffix + newName.substring(dotIndex);
                        } else {
                            newName = newName + suffix;
                        }
                    }

                    // 正则替换
                    if (!regex.isEmpty()) {
                        newName = newName.replaceAll(regex, replace);
                    }

                    if (!newName.equals(oldName)) {
                        File newFile = new File(file.getParent(), newName);
                        if (!newFile.exists()) {
                            success = file.renameTo(newFile);
                        }
                    } else {
                        success = true; // 名称未变算成功
                    }
                } catch (Exception e) {
                    android.util.Log.e("FileManager", "重命名失败", e);
                }

                if (success) successCount.incrementAndGet();
                int current = completed.incrementAndGet();
                runOnUiThread(() -> pd.setProgress(current));
                if (current >= total) {
                    runOnUiThread(() -> {
                        pd.dismiss();
                        Toast.makeText(FileManagerActivity.this, "✅ 成功重命名 " + successCount.get() + " 个文件", Toast.LENGTH_SHORT).show();
                    });
                    selectedFiles.clear();
                    exitBatchMode();
                    refresh();
                }
            }).start();
        }
    }

    /** 刷新文件列表 */
    private void refresh() {
        if (currentDir == null) {
            File defaultDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!defaultDir.exists()) {
                defaultDir = getExternalFilesDir(null);
            }
            if (defaultDir == null) {
                defaultDir = getFilesDir();
            }
            currentDir = defaultDir;
            updatePathDisplay();
        }

        if (fileListContainer == null) return;

        try {
            fileListContainer.removeAllViews();
        } catch (Exception e) {
            e.printStackTrace();
        }
        fileItemViews.clear();

        File[] files = null;
        try {
            if (currentDir != null && currentDir.exists() && currentDir.isDirectory()) {
                files = currentDir.listFiles();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "无法读取目录内容", Toast.LENGTH_SHORT).show();
        }
        if (files == null) files = new File[0];

        // 排序：文件夹在前，文件在后
        List<File> dirs = new ArrayList<>();
        List<File> regularFiles = new ArrayList<>();
        try {
            for (File f : files) {
                if (f != null) {
                    try {
                        if (f.isDirectory()) dirs.add(f);
                        else regularFiles.add(f);
                    } catch (Exception e) {
                        // 忽略无法访问的文件
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            Collections.sort(dirs, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
            Collections.sort(regularFiles, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
        } catch (Exception e) {
            e.printStackTrace();
        }

        sortedFiles = new File[dirs.size() + regularFiles.size()];
        System.arraycopy(dirs.toArray(), 0, sortedFiles, 0, dirs.size());
        System.arraycopy(regularFiles.toArray(), 0, sortedFiles, dirs.size(), regularFiles.size());

        LayoutInflater inflater = LayoutInflater.from(this);

        // 加载所有文件
        final int totalFiles = sortedFiles.length;

        for (int i = 0; i < totalFiles; i++) {
            try {
                File file = sortedFiles[i];
                if (file != null && file.exists()) {
                    View itemView = createFileItemView(file, i, inflater);
                    fileItemViews.add(itemView);
                    fileListContainer.addView(itemView);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        selectedIndex = -1;
    }

    /** 创建文件项视图 */
    private View createFileItemView(File file, int index, LayoutInflater inflater) {
        if (file == null || !file.exists()) {
            LinearLayout placeholder = new LinearLayout(this);
            placeholder.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48));
            return placeholder;
        }

        LinearLayout itemView = null;
        try {
            itemView = (LinearLayout) inflater.inflate(R.layout.item_file, fileListContainer, false);
        } catch (Exception e) {
            e.printStackTrace();
            itemView = new LinearLayout(this);
            itemView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 80));
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(android.view.Gravity.CENTER);
            TextView tv = new TextView(this);
            tv.setText("加载失败");
            itemView.addView(tv);
            return itemView;
        }

        TextView tvIcon = itemView.findViewById(R.id.tvFileIcon);
        TextView tvName = itemView.findViewById(R.id.tvFileName);
        TextView tvInfo = itemView.findViewById(R.id.tvFileInfo);
        ImageView ivThumb = itemView.findViewById(R.id.ivThumb);

        try {
            String name = file.getName();
            String ext = getFileExtension(name);
            tvName.setText(name);

            if (file.isDirectory()) {
                tvIcon.setText("📁");
                tvIcon.setVisibility(View.VISIBLE);
                if (ivThumb != null) ivThumb.setVisibility(View.GONE);
            } else {
                tvIcon.setText(getFileIcon(name));
                // 尝试加载缩略图（图片/视频/APK）
                loadThumbnail(itemView, file, ext);
            }

            if (file.isDirectory()) {
                File[] children = null;
                try {
                    children = file.listFiles();
                } catch (SecurityException e) {
                    android.util.Log.w("FileManager", "无权限访问: " + file.getPath());
                } catch (Exception e) {
                    android.util.Log.e("FileManager", "列出文件失败: " + file.getPath(), e);
                }
                int count = children != null ? children.length : 0;
                tvInfo.setText(count + " 项");
            } else {
                long size = 0;
                try {
                    size = file.length();
                } catch (SecurityException e) {
                    android.util.Log.w("FileManager", "无权限读取大小: " + file.getPath());
                } catch (Exception e) {
                    android.util.Log.e("FileManager", "获取文件大小失败: " + file.getPath(), e);
                }
                tvInfo.setText(formatFileSize(size));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        final int idx = index;
        itemView.setOnClickListener(v -> {
            try {
                if (idx < sortedFiles.length && sortedFiles[idx] != null) {
                    File f = sortedFiles[idx];

                    // 批量选择模式
                    if (isBatchMode) {
                        toggleFileSelection(f);
                        return;
                    }

                    if (f.isDirectory()) {
                        navigateTo(f);
                    } else if (f.getName().toLowerCase().endsWith(".apk")) {
                        // APK 文件：ADB 已连接时直接执行安装，未连接时询问使用系统安装器
                        if (isAdbConnected) {
                            installApkViaAdb(f);
                        } else {
                            DialogHelper.showConfirm(this, "📲", "APK 安装",
                                "是否安装「" + f.getName() + "」？\n\nADB 未连接，将使用系统安装器。",
                                "安装", "取消",
                                () -> openFile(f));
                        }
                    } else {
                        openFile(f);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show();
            }
        });

        // 长按弹出操作菜单（按需求顺序：打开→删除→复制→剪切→改名→权限→路径）
        itemView.setOnLongClickListener(v -> {
            try {
                if (idx < sortedFiles.length && sortedFiles[idx] != null) {
                    File f = sortedFiles[idx];
                    // 长按进入批量选择模式并选中
                    if (!isBatchMode) {
                        enterBatchMode();
                    }
                    toggleFileSelection(f);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        });

        itemView.setFocusable(true);

        return itemView;
    }

    /** 更新文件项选中状态UI */
    private void updateItemSelectionUI(View itemView, File file) {
        if (selectedFiles.contains(file)) {
            itemView.setBackgroundResource(R.drawable.item_app_selected);
        } else {
            itemView.setBackgroundResource(R.drawable.item_app_normal);
        }
    }

    /** 根据文件名获取文件图标 */
    private String getFileIcon(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return "🖼️";
        } else if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") ||
                   lower.endsWith(".mov") || lower.endsWith(".wmv") || lower.endsWith(".flv")) {
            return "🎬";
        } else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") ||
                   lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".m4a")) {
            return "🎵";
        } else if (lower.endsWith(".pdf")) {
            return "📄";
        } else if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
                   lower.endsWith(".tar") || lower.endsWith(".gz")) {
            return "📦";
        } else if (lower.endsWith(".apk")) {
            return "📲";
        } else if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md")) {
            return "📝";
        }
        return "📄";
    }

    /** 打开文件 */
    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String mimeType = getMimeType(file.getName());
            Uri uri;

            // 使用FileProvider获取Uri（兼容Android 7.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception e) {
                    uri = Uri.fromFile(file);
                }
            } else {
                uri = Uri.fromFile(file);
            }

            if (mimeType != null) {
                intent.setDataAndType(uri, mimeType);
            } else {
                intent.setData(uri);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 获取MIME类型 */
    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        // APK
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        // 视频
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        // 音频
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".wma")) return "audio/x-ms-wma";
        // 图片
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        // 文档
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        // 压缩
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".rar")) return "application/x-rar-compressed";
        if (lower.endsWith(".7z")) return "application/x-7z-compressed";
        // HTML
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        return null;
    }

    /** 显示文件操作菜单（按需求顺序：打开→删除→复制→剪切→压缩→改名→权限→路径） */
    private void showFileOperationMenu(final File file) {
        String lower = file.getName().toLowerCase();
        boolean isApk = lower.endsWith(".apk");


        // 构建设置列表：打开+删除 | 复制+剪切 | 改名+权限 | 复制路径+ADB安装(APK)+APK信息
        java.util.List<String> ops = new java.util.ArrayList<>();
        ops.add("📂 打开");
        ops.add("🗑 删除");
        ops.add("📋 复制");
        ops.add("✂ 剪切");
        ops.add("✏ 改名");
        ops.add("🔐 权限");
        ops.add("📎 复制路径");
        if (isApk) {
            ops.add("📲 ADB安装");
            ops.add("ℹ️ APK信息");
        }

        String[] operations = ops.toArray(new String[0]);

        DialogHelper.showList(this, "📄", file.getName(), operations, new DialogHelper.OnItemClickListenerWithCancel() {
            @Override
            public void onClick(int position) {
                switch (position) {
                    case 0: // 打开
                        if (file.isDirectory()) {
                            navigateTo(file);
                        } else if (isApk) {
                            if (isAdbConnected) {
                                showAdbInstallDialog(file);
                            } else {
                                Toast.makeText(FileManagerActivity.this, "请先连接ADB", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            openFile(file);
                        }
                        break;
                    case 1: // 删除
                        confirmDelete(file);
                        break;
                    case 2: // 复制
                        clipboardFile = file;
                        clipboardCopy = true;
                        Toast.makeText(FileManagerActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                        break;
                    case 3: // 剪切
                        clipboardFile = file;
                        clipboardCopy = false;
                        Toast.makeText(FileManagerActivity.this, "已剪切", Toast.LENGTH_SHORT).show();
                        break;
                    case 4: // 改名
                        showRenameDialog(file);
                        break;
                    case 5: // 权限
                        showChmodDialog(file);
                        break;
                    case 6: // 复制路径
                        copyPath(file);
                        break;
                    case 7: // ADB 安装（仅 APK）
                        if (isAdbConnected) {
                            showAdbInstallDialog(file);
                        } else {
                            // ADB 未连接，尝试直接连接本机调试服务
                            final ProgressDialog pd = new ProgressDialog(FileManagerActivity.this);
                            pd.setMessage("正在连接本机 ADB 服务...");
                            pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                            pd.setCancelable(true);
                            pd.show();

                            AdbHelper.get().connect("127.0.0.1", 5555, ok -> runOnUiThread(() -> {
                                pd.dismiss();
                                if (ok) {
                                    Toast.makeText(FileManagerActivity.this, "✅ 本机 ADB 连接成功！", Toast.LENGTH_LONG).show();
                                    isAdbConnected = true;
                                    showAdbInstallDialog(file);
                                } else {
                                    Toast.makeText(FileManagerActivity.this, "❌ ADB 未连接，请在无线配对中先连接设备", Toast.LENGTH_LONG).show();
                                }
                            }));
                        }
                        break;
                    case 8: // APK信息（仅 APK）
                        showApkInfo(file);
                        break;
                }
            }

            @Override
            public void onCancel() {
                // 粘贴到此处
                if (clipboardFile != null && clipboardFile.exists()) {
                    pasteFile(file.isDirectory() ? file : file.getParentFile());
                }
            }
        });
    }

    /** 复制路径 */
    private void copyPath(File file) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("文件路径", file.getAbsolutePath());
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "已复制路径", Toast.LENGTH_LONG).show();
    }

    /** 显示权限设置对话框 */
    private void showChmodDialog(File file) {
        if (!isAdbConnected) {
            Toast.makeText(this, "需要 ADB 连接才能修改权限", Toast.LENGTH_LONG).show();
            return;
        }

        String[] permissions = {"777 (读写执行)", "755 (读执)", "644 (读写)", "600 (仅自己读写)"};
        String[] permCodes = {"777", "755", "644", "600"};

        DialogHelper.showList(this, "🔐", "设置权限: " + file.getName(), permissions, which -> {
            String perm = permCodes[which];
            new AsyncTask<File, Void, Boolean>() {
                ProgressDialog progress;

                @Override
                protected void onPreExecute() {
                    progress = new ProgressDialog(FileManagerActivity.this);
                    progress.setMessage("正在设置权限 " + perm + "...");
                    progress.setCancelable(false);
                    progress.show();
                }

                @Override
                protected Boolean doInBackground(File... files) {
                    try {
                        String cmd = "chmod " + perm + " \"" + files[0].getAbsolutePath() + "\"";
                        Process p = Runtime.getRuntime().exec(cmd);
                        int ret = p.waitFor();
                        return ret == 0;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return false;
                    }
                }

                @Override
                protected void onPostExecute(Boolean success) {
                    progress.dismiss();
                    Toast.makeText(FileManagerActivity.this,
                        success ? "权限设置成功" : "权限设置失败",
                        Toast.LENGTH_SHORT).show();
                }
            }.execute(file);
        });
    }

    /** 新建：弹出选择新建文件 or 文件夹 */
    private void showNewChoiceDialog() {
        String[] items = {"📁 文件夹", "📄 文件"};
        DialogHelper.showList(this, "➕", "新建", items, which -> {
            if (which == 0) showNewFolderDialog();
            else showNewFileDialog();
        }, null);
    }

    /** 新建文件 */
    private void showNewFileDialog() {
        showInputDialog("📄", "新建文件", "输入文件名（含扩展名，如 note.txt）",
            InputType.TYPE_CLASS_TEXT, name -> {
            if (name.isEmpty()) { Toast.makeText(this, "请输入文件名", Toast.LENGTH_SHORT).show(); return; }
            File newFile = new File(currentDir, name);
            if (newFile.exists()) {
                Toast.makeText(this, "已存在同名文件", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    if (newFile.createNewFile()) {
                        Toast.makeText(this, "文件创建成功", Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "创建失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 新建文件夹 */
    private void showNewFolderDialog() {
        showInputDialog("📁", "新建文件夹", "输入文件夹名称", InputType.TYPE_CLASS_TEXT, name -> {
            if (name.isEmpty()) { Toast.makeText(this, "请输入名称", Toast.LENGTH_SHORT).show(); return; }
            File newDir = new File(currentDir, name);
            if (newDir.exists()) {
                Toast.makeText(this, "已存在同名文件夹", Toast.LENGTH_SHORT).show();
            } else if (newDir.mkdirs()) {
                Toast.makeText(this, "创建成功", Toast.LENGTH_SHORT).show();
                refresh();
            } else {
                Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 搜索类型选择弹窗 */
    private void showSearchTypeDialog() {
        String[] types = {"🔍 全部文件", "🖼 图片", "🎬 视频", "📦 APK安装包", "📄 文档"};
        DialogHelper.showList(this, "🔍", "搜索类型", types, which -> {
            switch (which) {
                case 0: showFileSearchDialog(); break;
                case 1: searchFilesByExtensions("图片", "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic"); break;
                case 2: searchFilesByExtensions("视频", "mp4", "mkv", "avi", "mov", "rmvb", "flv", "m4v", "ts"); break;
                case 3: searchFilesByExtensions("安装包", "apk"); break;
                case 4: searchFilesByExtensions("文档", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"); break;
            }
        }, null);
    }

    /** 按扩展名搜索文件 */
    private void searchFilesByExtensions(String typeName, String... exts) {
        if (currentDir == null) return;
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在搜索" + typeName + "...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(false);
        pd.show();

        final File searchRoot = currentDir;
        new AsyncTask<Void, Void, List<File>>() {
            @Override
            protected List<File> doInBackground(Void... v) {
                List<File> results = new ArrayList<>();
                searchByExtRecursive(searchRoot, exts, results, 0);
                return results;
            }

            @Override
            protected void onPostExecute(List<File> results) {
                pd.dismiss();
                showSearchResults(results, typeName);
            }
        }.execute();
    }

    private void searchByExtRecursive(File dir, String[] exts, List<File> results, int depth) {
        if (depth > 10 || results.size() >= 200) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                searchByExtRecursive(f, exts, results, depth + 1);
            } else {
                String name = f.getName().toLowerCase();
                for (String ext : exts) {
                    if (name.endsWith("." + ext)) {
                        results.add(f);
                        break;
                    }
                }
            }
        }
    }

    /** APK 信息查看 */
    private void showApkInfo(final File apkFile) {
        new AsyncTask<File, Void, ApkInfoResult>() {
            private ProgressDialog pd;

            @Override
            protected void onPreExecute() {
                pd = new ProgressDialog(FileManagerActivity.this);
                pd.setMessage("正在解析 APK 信息...");
                pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                pd.setCancelable(false);
                pd.show();
            }

            @Override
            protected ApkInfoResult doInBackground(File... files) {
                ApkInfoResult result = new ApkInfoResult();
                try {
                    result.filePath = apkFile.getAbsolutePath();
                    result.fileSize = apkFile.length();

                    // 使用 PackageManager 解析 APK
                    PackageManager pm = getPackageManager();
                    PackageInfo pkgInfo = pm.getPackageArchiveInfo(result.filePath, PackageManager.GET_PERMISSIONS | PackageManager.GET_SIGNATURES);

                    if (pkgInfo != null) {
                        result.appName = pkgInfo.applicationInfo.loadLabel(pm).toString();
                        result.packageName = pkgInfo.packageName;
                        result.versionName = pkgInfo.versionName != null ? pkgInfo.versionName : "未知";
                        result.versionCode = pkgInfo.versionCode;

                        // 获取 SDK 版本
                        result.minSdkVersion = pkgInfo.applicationInfo.minSdkVersion;
                        result.targetSdkVersion = pkgInfo.applicationInfo.targetSdkVersion;

                        // 获取权限列表
                        if (pkgInfo.requestedPermissions != null) {
                            for (String perm : pkgInfo.requestedPermissions) {
                                result.permissions.add(getPermissionDescription(perm));
                            }
                        }

                        // 获取签名信息
                        if (pkgInfo.signatures != null && pkgInfo.signatures.length > 0) {
                            try {
                                MessageDigest md = MessageDigest.getInstance("MD5");
                                byte[] digest = md.digest(pkgInfo.signatures[0].toByteArray());
                                StringBuilder sb = new StringBuilder();
                                for (byte b : digest) {
                                    sb.append(String.format("%02X:", b));
                                }
                                result.signatureMD5 = sb.toString().substring(0, sb.length() - 1);

                                md = MessageDigest.getInstance("SHA-1");
                                digest = md.digest(pkgInfo.signatures[0].toByteArray());
                                sb = new StringBuilder();
                                for (byte b : digest) {
                                    sb.append(String.format("%02X:", b));
                                }
                                result.signatureSHA1 = sb.toString().substring(0, sb.length() - 1);
                            } catch (Exception e) {
                                result.signatureMD5 = "解析失败";
                                result.signatureSHA1 = "解析失败";
                            }
                        }
                    }
                } catch (Exception e) {
                    result.error = e.getMessage();
                }
                return result;
            }

            @Override
            protected void onPostExecute(ApkInfoResult result) {
                pd.dismiss();
                if (result.error != null) {
                    Toast.makeText(FileManagerActivity.this, "解析失败: " + result.error, Toast.LENGTH_LONG).show();
                    return;
                }
                showApkInfoDialog(result);
            }
        }.execute(apkFile);
    }

    /** 显示 APK 信息对话框 */
    private void showApkInfoDialog(ApkInfoResult info) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_apk_info, null);
        TextView tvAppName = dialogView.findViewById(R.id.tvApkAppName);
        TextView tvPackage = dialogView.findViewById(R.id.tvApkPackage);
        TextView tvVersion = dialogView.findViewById(R.id.tvApkVersion);
        TextView tvSdk = dialogView.findViewById(R.id.tvApkSdk);
        TextView tvSignature = dialogView.findViewById(R.id.tvApkSignature);
        TextView tvPermissions = dialogView.findViewById(R.id.tvApkPermissions);
        TextView tvFileSize = dialogView.findViewById(R.id.tvApkFileSize);
        TextView tvFilePath = dialogView.findViewById(R.id.tvApkFilePath);
        LinearLayout btnClose = dialogView.findViewById(R.id.btnCloseApkInfo);

        tvAppName.setText(info.appName != null ? info.appName : "未知");
        tvPackage.setText(info.packageName != null ? info.packageName : "未知");
        tvVersion.setText(info.versionName + " (Code: " + info.versionCode + ")");
        tvSdk.setText("最低: API " + info.minSdkVersion + " / 目标: API " + info.targetSdkVersion);
        tvSignature.setText("MD5: " + info.signatureMD5 + "\nSHA1: " + info.signatureSHA1);
        tvFileSize.setText(formatFileSize(info.fileSize));
        tvFilePath.setText(info.filePath);

        // 权限列表
        if (info.permissions.isEmpty()) {
            tvPermissions.setText("无权限请求");
        } else {
            StringBuilder permSb = new StringBuilder();
            for (String perm : info.permissions) {
                permSb.append("• ").append(perm).append("\n");
            }
            tvPermissions.setText(permSb.toString().trim());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogStandardStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);
        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 获取权限描述 */
    private String getPermissionDescription(String permission) {
        // 常见权限的友好名称映射
        java.util.Map<String, String> permNames = new java.util.HashMap<>();
        permNames.put("android.permission.INTERNET", "网络访问");
        permNames.put("android.permission.ACCESS_NETWORK_STATE", "网络状态");
        permNames.put("android.permission.ACCESS_WIFI_STATE", "WiFi状态");
        permNames.put("android.permission.READ_EXTERNAL_STORAGE", "读取存储");
        permNames.put("android.permission.WRITE_EXTERNAL_STORAGE", "写入存储");
        permNames.put("android.permission.READ_PHONE_STATE", "电话状态");
        permNames.put("android.permission.CALL_PHONE", "拨打电话");
        permNames.put("android.permission.CAMERA", "相机");
        permNames.put("android.permission.RECORD_AUDIO", "录音");
        permNames.put("android.permission.ACCESS_FINE_LOCATION", "精确位置");
        permNames.put("android.permission.ACCESS_COARSE_LOCATION", "模糊位置");
        permNames.put("android.permission.SEND_SMS", "发送短信");
        permNames.put("android.permission.RECEIVE_SMS", "接收短信");
        permNames.put("android.permission.READ_SMS", "读取短信");
        permNames.put("android.permission.WRITE_SETTINGS", "系统设置");
        permNames.put("android.permission.REQUEST_INSTALL_PACKAGES", "安装应用");
        permNames.put("android.permission.REQUEST_DELETE_PACKAGES", "删除应用");
        permNames.put("android.permission.SYSTEM_ALERT_WINDOW", "悬浮窗");
        permNames.put("android.permission.FOREGROUND_SERVICE", "前台服务");
        permNames.put("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "忽略电池优化");

        String name = permNames.get(permission);
        return name != null ? name + " (" + permission.substring(permission.lastIndexOf(".") + 1) + ")" : permission.substring(permission.lastIndexOf(".") + 1);
    }

    /** APK 信息结果类 */
    private static class ApkInfoResult {
        String appName;
        String packageName;
        String versionName;
        int versionCode;
        int minSdkVersion;
        int targetSdkVersion;
        String signatureMD5 = "";
        String signatureSHA1 = "";
        long fileSize;
        String filePath;
        String error;
        java.util.List<String> permissions = new java.util.ArrayList<>();
    }

    // ===================== APK 版本对比功能 =====================

    /** APK 版本对比结果类 */
    private static class ApkVersionCompareResult {
        String appName;
        String packageName;
        String apkVersionName;
        int apkVersionCode;
        String installedVersionName;
        int installedVersionCode;
        boolean isInstalled;
        boolean isNewer;
        String error;
    }

    /** 对比 APK 与已安装版本 */
    private void compareApkVersion(final File apkFile) {
        if (!isAdbConnected) {
            Toast.makeText(this, "请先连接ADB设备进行版本对比", Toast.LENGTH_SHORT).show();
            return;
        }

        new AsyncTask<File, Void, ApkVersionCompareResult>() {
            private ProgressDialog pd;

            @Override
            protected void onPreExecute() {
                pd = new ProgressDialog(FileManagerActivity.this);
                pd.setMessage("正在获取APK和已安装版本信息...");
                pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                pd.setCancelable(false);
                pd.show();
            }

            @Override
            protected ApkVersionCompareResult doInBackground(File... files) {
                ApkVersionCompareResult result = new ApkVersionCompareResult();
                try {
                    // 1. 解析 APK 文件信息
                    PackageManager pm = getPackageManager();
                    PackageInfo apkInfo = pm.getPackageArchiveInfo(files[0].getAbsolutePath(), PackageManager.GET_META_DATA);
                    if (apkInfo != null) {
                        result.appName = apkInfo.applicationInfo.loadLabel(pm).toString();
                        result.packageName = apkInfo.packageName;
                        result.apkVersionName = apkInfo.versionName != null ? apkInfo.versionName : "未知";
                        result.apkVersionCode = apkInfo.versionCode;
                    }

                    // 2. 获取已安装应用版本信息（通过 ADB）
                    String pkgCmd = "dumpsys package " + result.packageName + " | grep versionName";
                    backendManager.execShell(pkgCmd, dumpResult -> {
                        if (dumpResult != null && dumpResult.contains("versionName=")) {
                            // 解析 versionName
                            String[] lines = dumpResult.split("\n");
                            for (String line : lines) {
                                if (line.contains("versionName=")) {
                                    String version = line.substring(line.indexOf("versionName=") + 12).trim();
                                    int spaceIdx = version.indexOf(' ');
                                    if (spaceIdx > 0) {
                                        version = version.substring(0, spaceIdx);
                                    }
                                    result.installedVersionName = version;
                                }
                                if (line.contains("versionCode=")) {
                                    String code = line.substring(line.indexOf("versionCode=") + 12).trim();
                                    int spaceIdx = code.indexOf(' ');
                                    if (spaceIdx > 0) {
                                        code = code.substring(0, spaceIdx);
                                    }
                                    try {
                                        result.installedVersionCode = Integer.parseInt(code);
                                    } catch (NumberFormatException ignored) {}
                                }
                            }
                            result.isInstalled = true;
                        } else {
                            result.isInstalled = false;
                        }

                        // 3. 比较版本
                        if (result.isInstalled && result.apkVersionCode > 0 && result.installedVersionCode > 0) {
                            result.isNewer = result.apkVersionCode > result.installedVersionCode;
                        }
                    });
                } catch (Exception e) {
                    result.error = e.getMessage();
                }
                return result;
            }

            @Override
            protected void onPostExecute(ApkVersionCompareResult result) {
                pd.dismiss();
                if (result.error != null) {
                    Toast.makeText(FileManagerActivity.this, "对比失败: " + result.error, Toast.LENGTH_LONG).show();
                    return;
                }
                showVersionCompareDialog(result, apkFile);
            }
        }.execute(apkFile);
    }

    /** 显示版本对比对话框 */
    private void showVersionCompareDialog(ApkVersionCompareResult result, File apkFile) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_apk_version_compare, null);
        TextView tvAppName = dialogView.findViewById(R.id.tvCompareAppName);
        TextView tvPackage = dialogView.findViewById(R.id.tvComparePackage);
        TextView tvApkVersion = dialogView.findViewById(R.id.tvCompareApkVersion);
        TextView tvInstalledVersion = dialogView.findViewById(R.id.tvCompareInstalledVersion);
        TextView tvCompareResult = dialogView.findViewById(R.id.tvCompareResult);
        LinearLayout btnInstall = dialogView.findViewById(R.id.btnCompareInstall);
        LinearLayout btnClose = dialogView.findViewById(R.id.btnCompareClose);

        tvAppName.setText(result.appName != null ? result.appName : "未知应用");
        tvPackage.setText(result.packageName != null ? result.packageName : "未知包名");
        tvApkVersion.setText(result.apkVersionName + " (Code: " + result.apkVersionCode + ")");

        if (result.isInstalled) {
            tvInstalledVersion.setText(result.installedVersionName + " (Code: " + result.installedVersionCode + ")");

            if (result.isNewer) {
                tvCompareResult.setText("⬆️ APK 版本较新");
                tvCompareResult.setTextColor(0xFF4CAF50);
            } else if (result.apkVersionCode == result.installedVersionCode) {
                tvCompareResult.setText("✅ 版本相同");
                tvCompareResult.setTextColor(0xFFFFC107);
            } else {
                tvCompareResult.setText("⬇️ 已安装版本较新");
                tvCompareResult.setTextColor(0xFFFF9800);
            }
        } else {
            tvInstalledVersion.setText("未安装");
            tvCompareResult.setText("📱 可安装");
            tvCompareResult.setTextColor(0xFF2196F3);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogStandardStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);
        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnInstall.setOnClickListener(v -> {
            dialog.dismiss();
            showAdbInstallDialog(apkFile);
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 重命名 */
    private void showRenameDialog(final File file) {
        showInputDialog("✏️", "重命名", file.getName(), InputType.TYPE_CLASS_TEXT, name -> {
            if (name.isEmpty()) { Toast.makeText(this, "请输入名称", Toast.LENGTH_SHORT).show(); return; }
            File newFile = new File(file.getParent(), name);
            if (newFile.exists()) {
                Toast.makeText(this, "已存在同名文件", Toast.LENGTH_SHORT).show();
            } else if (file.renameTo(newFile)) {
                Toast.makeText(this, "重命名成功", Toast.LENGTH_SHORT).show();
                refresh();
            } else {
                Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 确认删除 */
    private void confirmDelete(final File file) {
        DialogHelper.show(this, "🗑️", "确认删除",
            "确定要删除 " + file.getName() + " 吗？",
            "删除", "取消",
            () -> deleteFile(file), null);
    }

    /** 删除文件/文件夹 */
    private void deleteFile(File file) {
        new AsyncTask<File, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(File... files) {
                return deleteRecursively(files[0]);
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (success) {
                    Toast.makeText(FileManagerActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(FileManagerActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute(file);
    }

    /** 粘贴文件 */
    private void pasteFile(File targetDir) {
        if (clipboardFile == null || !clipboardFile.exists()) {
            Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!targetDir.exists() || !targetDir.isDirectory()) {
            targetDir = currentDir;
        }

        final File dest = new File(targetDir, clipboardFile.getName());
        if (dest.exists()) {
            Toast.makeText(this, "目标位置已有同名文件", Toast.LENGTH_SHORT).show();
            return;
        }

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                if (clipboardCopy) {
                    return copyFile(clipboardFile, dest);
                } else {
                    if (clipboardFile.renameTo(dest)) {
                        clipboardFile = null;
                        return true;
                    }
                    return copyFile(clipboardFile, dest) && clipboardFile.delete();
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (success) {
                    clipboardFile = null;
                    Toast.makeText(FileManagerActivity.this, "粘贴成功", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(FileManagerActivity.this, "粘贴失败", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    /** 复制文件 */
    private boolean copyFile(File src, File dest) {
        try {
            if (src.isDirectory()) {
                if (!dest.mkdirs()) return false;
                File[] children = src.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!copyFile(child, new File(dest, child.getName()))) return false;
                    }
                }
            } else {
                InputStream is = new FileInputStream(src);
                OutputStream os = new FileOutputStream(dest);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                is.close();
                os.close();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Download目录 */
    private void navigateToDownload() {
        if (downloadContent != null) downloadContent.setVisibility(View.VISIBLE);
        if (internalStorageContent != null) internalStorageContent.setVisibility(View.GONE);
        if (usbStorageContent != null) usbStorageContent.setVisibility(View.GONE);

        File download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File miaotvDir = new File(download, "\u55b5\u55b5\u5662\u5f71\u89c6");
        final File targetDir = miaotvDir.exists() ? miaotvDir : (download.exists() ? download : null);

        if (targetDir == null) {
            Toast.makeText(this, "下载目录不存在", Toast.LENGTH_SHORT).show();
            return;
        }

        final LayoutInflater inflater = LayoutInflater.from(this);
        if (downloadListContainer != null) downloadListContainer.removeAllViews();

        File[] files = null;
        try {
            files = targetDir.listFiles();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (files == null) files = new File[0];

        List<File> dirs = new ArrayList<>();
        List<File> regularFiles = new ArrayList<>();
        for (File f : files) {
            if (f != null && f.exists()) {
                if (f.isDirectory()) dirs.add(f);
                else regularFiles.add(f);
            }
        }
        Collections.sort(dirs, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
        Collections.sort(regularFiles, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));

        File[] sorted = new File[dirs.size() + regularFiles.size()];
        System.arraycopy(dirs.toArray(), 0, sorted, 0, dirs.size());
        System.arraycopy(regularFiles.toArray(), 0, sorted, dirs.size(), regularFiles.size());

        if (sorted.length == 0) {
            if (downloadListContainer != null) {
                TextView emptyHint = new TextView(this);
                emptyHint.setText("📂 目录为空");
                emptyHint.setTextColor(0xFF95A5A6);
                emptyHint.setTextSize(14f);
                emptyHint.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 40, 0, 0);
                emptyHint.setLayoutParams(lp);
                downloadListContainer.addView(emptyHint);
            }
        }

        for (int i = 0; i < sorted.length; i++) {
            final File file = sorted[i];
            if (file == null) continue;
            LinearLayout itemView = (LinearLayout) inflater.inflate(
                R.layout.item_file, downloadListContainer, false);
            TextView tvIcon = itemView.findViewById(R.id.tvFileIcon);
            TextView tvName = itemView.findViewById(R.id.tvFileName);
            TextView tvInfo = itemView.findViewById(R.id.tvFileInfo);
            ImageView ivThumb = itemView.findViewById(R.id.ivThumb);
            if (file.isDirectory()) {
                if (tvIcon != null) {
                    tvIcon.setText("📁");
                    tvIcon.setVisibility(View.VISIBLE);
                }
                if (ivThumb != null) ivThumb.setVisibility(View.GONE);
            } else {
                String ext = getFileExtension(file.getName());
                if (tvIcon != null) tvIcon.setText(getFileIcon(file.getName()));
                loadThumbnail(itemView, file, ext);
            }
            if (tvName != null) tvName.setText(file.getName());
            if (tvInfo != null) {
                if (file.isDirectory()) {
                    File[] children = file.listFiles();
                    tvInfo.setText((children != null ? children.length : 0) + " 项");
                } else {
                    tvInfo.setText(formatFileSize(file.length()));
                }
            }
            itemView.setFocusable(true);
            final int idx = i;
            itemView.setOnClickListener(v -> {
                if (idx >= sorted.length || sorted[idx] == null) return;
                File f = sorted[idx];
                if (f.isDirectory()) {
                    // 递归进入子目录
                    navigateToDownloadSubDir(f);
                } else {
                    openFile(f);
                }
            });
            if (downloadListContainer != null) downloadListContainer.addView(itemView);
        }

        if (tvCurrentPath != null) {
            tvCurrentPath.setText("Download" + (miaotvDir.exists() ? " / 喵喵嗷影视" : ""));
        }
    }

    private void navigateToDownloadSubDir(File dir) {
        if (downloadListContainer != null) downloadListContainer.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(this);

        File[] files = null;
        try {
            files = dir.listFiles();
        } catch (Exception e) { e.printStackTrace(); }
        if (files == null) files = new File[0];

        List<File> dirs = new ArrayList<>();
        List<File> regularFiles = new ArrayList<>();
        for (File f : files) {
            if (f != null && f.exists()) {
                if (f.isDirectory()) dirs.add(f);
                else regularFiles.add(f);
            }
        }
        Collections.sort(dirs, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
        Collections.sort(regularFiles, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));

        File[] sorted = new File[dirs.size() + regularFiles.size()];
        System.arraycopy(dirs.toArray(), 0, sorted, 0, dirs.size());
        System.arraycopy(regularFiles.toArray(), 0, sorted, dirs.size(), regularFiles.size());

        for (int i = 0; i < sorted.length; i++) {
            final File file = sorted[i];
            if (file == null) continue;
            LinearLayout itemView = (LinearLayout) inflater.inflate(
                R.layout.item_file, downloadListContainer, false);
            TextView tvIcon = itemView.findViewById(R.id.tvFileIcon);
            TextView tvName = itemView.findViewById(R.id.tvFileName);
            TextView tvInfo = itemView.findViewById(R.id.tvFileInfo);
            ImageView ivThumb = itemView.findViewById(R.id.ivThumb);
            if (file.isDirectory()) {
                if (tvIcon != null) {
                    tvIcon.setText("📁");
                    tvIcon.setVisibility(View.VISIBLE);
                }
                if (ivThumb != null) ivThumb.setVisibility(View.GONE);
            } else {
                String ext = getFileExtension(file.getName());
                if (tvIcon != null) tvIcon.setText(getFileIcon(file.getName()));
                loadThumbnail(itemView, file, ext);
            }
            if (tvName != null) tvName.setText(file.getName());
            if (tvInfo != null) {
                if (file.isDirectory()) {
                    File[] children = file.listFiles();
                    tvInfo.setText((children != null ? children.length : 0) + " 项");
                } else {
                    tvInfo.setText(formatFileSize(file.length()));
                }
            }
            itemView.setFocusable(true);
            final int idx = i;
            itemView.setOnClickListener(v -> {
                if (idx >= sorted.length || sorted[idx] == null) return;
                File f = sorted[idx];
                if (f.isDirectory()) {
                    navigateToDownloadSubDir(f);
                } else {
                    openFile(f);
                }
            });
            if (downloadListContainer != null) downloadListContainer.addView(itemView);
        }

        if (tvCurrentPath != null) {
            tvCurrentPath.setText("Download / 喵喵嗷影视 / " + dir.getName());
        }
    }

    /** 检测U盘 */
    private void detectUsbDrives() {
        usbDrives.clear();
        if (usbListContainer != null) {
            usbListContainer.removeAllViews();
        }
        usbItemViews.clear();

        new AsyncTask<Void, Void, List<UsbDriveInfo>>() {
            @Override
            protected List<UsbDriveInfo> doInBackground(Void... voids) {
                List<UsbDriveInfo> drives = new ArrayList<>();

                // 检测 /storage/usb* 目录
                File storageDir = new File("/storage");
                if (storageDir.exists() && storageDir.isDirectory()) {
                    File[] files = storageDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isDirectory() && (f.getName().startsWith("usb") ||
                                f.getName().startsWith("ext") ||
                                f.getName().equals("sdcard1") ||
                                f.getName().equals("sdcard2"))) {
                                if (f.canRead() && f.listFiles() != null) {
                                    addUsbDrive(drives, f);
                                }
                            }
                        }
                    }
                }

                // 检测 /mnt/usb* 目录
                File mntDir = new File("/mnt");
                if (mntDir.exists() && mntDir.isDirectory()) {
                    File[] files = mntDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isDirectory() && f.getName().contains("usb")) {
                                if (f.canRead() && f.listFiles() != null) {
                                    addUsbDrive(drives, f);
                                }
                            }
                        }
                    }
                }

                // 检测 /udisk 目录
                File udiskDir = new File("/udisk");
                if (udiskDir.exists() && udiskDir.isDirectory() && udiskDir.canRead()) {
                    addUsbDrive(drives, udiskDir);
                }

                // 检测 /mnt/media_rw 目录
                File mediaRwDir = new File("/mnt/media_rw");
                if (mediaRwDir.exists()) {
                    File[] files = mediaRwDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isDirectory() && f.canRead()) {
                                addUsbDrive(drives, f);
                            }
                        }
                    }
                }

                return drives;
            }


            @Override
            protected void onPostExecute(List<UsbDriveInfo> drives) {
                if (!usbInitialDetected) {
                    // 首次加载，不弹窗，只显示按钮
                    usbInitialDetected = true;
                } else {
                    // 后续插入检测，发现新U盘时弹窗
                    List<String> oldPaths = new ArrayList<>();
                    for (UsbDriveInfo d : usbDrives) {
                        oldPaths.add(d.path);
                    }
                    for (UsbDriveInfo drive : drives) {
                        if (!oldPaths.contains(drive.path)) {
                            showUsbInsertDialog(drive);
                        }
                    }
                }

                usbDrives.clear();
                usbDrives.addAll(drives);
                displayUsbDrives();
            }
        }.execute();
    }

    /** 检测到U盘插入时弹出对话框 */
    private void showUsbInsertDialog(UsbDriveInfo drive) {
        new AlertDialog.Builder(this, R.style.DialogStandardStyle)
            .setTitle("💿 检测到U盘")
            .setMessage("已插入：" + drive.name + "\n总空间：" + drive.getTotalStr() + "\n可用空间：" + drive.getFreeStr())
            .setPositiveButton("打开", (d, w) -> {
                // 打开U盘根目录
                navigateTo(new File(drive.path));
                switchToTab(1);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 获取U盘显示名称 */
    private String getUsbDisplayName(File f) {
        String name = f.getName();
        if (name.startsWith("usb")) {
            int num = 1;
            try {
                num = Integer.parseInt(name.replaceAll("[^0-9]", ""));
                if (num == 0) num = 1;
            } catch (NumberFormatException e) {
                android.util.Log.w("FileManager", "U盘名称解析失败: " + name);
            }
            return "UDisk" + String.format("%02d", num);
        } else if (name.equals("sdcard1")) {
            return "UDisk01";
        } else if (name.equals("sdcard2")) {
            return "UDisk02";
        }
        return name;
    }

    /** 添加U盘到列表（避免重复） */
    private void addUsbDrive(List<UsbDriveInfo> drives, File f) {
        // 检查是否已存在
        for (UsbDriveInfo d : drives) {
            if (d.path.equals(f.getAbsolutePath())) return;
        }
        UsbDriveInfo info = new UsbDriveInfo();
        info.name = getUsbDisplayName(f);
        info.path = f.getAbsolutePath();
        getStorageInfo(f, info);
        drives.add(info);
    }

    /** 获取存储空间信息 */
    private void getStorageInfo(File f, UsbDriveInfo info) {
        try {
            StatFs stat = new StatFs(f.getAbsolutePath());
            info.totalSize = stat.getBlockCountLong() * stat.getBlockSizeLong();
            info.freeSize = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } catch (Exception e) {
            info.totalSize = 0;
            info.freeSize = 0;
        }
    }

    /** 显示U盘列表 + 动态更新标签栏U盘按钮 */
    private void displayUsbDrives() {
        if (usbListContainer == null) return;
        usbListContainer.removeAllViews();
        usbItemViews.clear();

        // --- 动态更新标签栏U盘按钮 ---
        updateUsbTabButtons();

        // 根据是否有U盘显示/隐藏空提示和列表
        if (usbDrives.isEmpty()) {
            if (usbEmptyHint != null) usbEmptyHint.setVisibility(View.VISIBLE);
            if (usbScrollView != null) usbScrollView.setVisibility(View.GONE);
        } else {
            if (usbEmptyHint != null) usbEmptyHint.setVisibility(View.GONE);
            if (usbScrollView != null) usbScrollView.setVisibility(View.VISIBLE);
        }

        LayoutInflater inflater = LayoutInflater.from(this);

        for (int i = 0; i < usbDrives.size(); i++) {
            UsbDriveInfo drive = usbDrives.get(i);
            LinearLayout itemView = (LinearLayout) inflater.inflate(R.layout.item_usb_drive, usbListContainer, false);

            TextView tvName = itemView.findViewById(R.id.tvUsbName);
            TextView tvTotal = itemView.findViewById(R.id.tvUsbTotal);
            TextView tvFree = itemView.findViewById(R.id.tvUsbFree);
            LinearLayout btnOpenUsb = itemView.findViewById(R.id.btnOpenUsb);

            tvName.setText(drive.name);
            tvTotal.setText("总空间: " + drive.getTotalStr());
            tvFree.setText("可用: " + drive.getFreeStr());

            final String path = drive.path;
            btnOpenUsb.setOnClickListener(v -> {
                // 进入U盘根目录
                navigateTo(new File(path));
                // 切换到内部存储Tab
                switchToTab(1);
            });

            itemView.setFocusable(true);
            usbItemViews.add(itemView);
            usbListContainer.addView(itemView);
        }
    }

    /** 动态更新标签栏里的U盘按钮（有几个盘显示几个，无盘时隐藏容器） */
    private void updateUsbTabButtons() {
        if (usbTabContainer == null) return;
        usbTabContainer.removeAllViews();

        if (usbDrives.isEmpty()) {
            usbTabContainer.setVisibility(View.GONE);
            return;
        }

        usbTabContainer.setVisibility(View.VISIBLE);

        for (int i = 0; i < usbDrives.size(); i++) {
            final UsbDriveInfo drive = usbDrives.get(i);
            LinearLayout btn = new LinearLayout(this);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setBackgroundResource(R.drawable.btn_toolbar_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 34 /* dp */);
            // dp to px
            float density = getResources().getDisplayMetrics().density;
            lp.height = (int)(34 * density);
            lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
            lp.setMarginEnd((int)(6 * density));
            btn.setLayoutParams(lp);
            int px12 = (int)(12 * density);
            btn.setPadding(px12, 0, px12, 0);
            btn.setFocusable(true);
            btn.setClickable(true);

            TextView tvIcon = new TextView(this);
            tvIcon.setText("💿");
            tvIcon.setTextSize(12f);

            TextView tvText = new TextView(this);
            tvText.setText(" " + drive.name);
            tvText.setTextSize(12f);
            tvText.setTextColor(0xFF2C3E50);

            btn.addView(tvIcon);
            btn.addView(tvText);

            final int driveIndex = i;
            btn.setOnClickListener(v -> {
                // 直接导航到U盘目录，切换到内部存储Tab显示文件
                navigateTo(new File(drive.path));
                switchToTab(1);
            });
            attachFocusScale(btn, 1.05f);
            usbTabContainer.addView(btn);
        }
    }

    /** 文件搜索 */
    private void showFileSearchDialog() {
        showInputDialog("🔍", "文件搜索", "输入关键词", InputType.TYPE_CLASS_TEXT, keyword -> {
            if (keyword.isEmpty()) { Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show(); return; }
            searchFiles(keyword);
        });
    }

    /** 统一输入弹窗：圆角白底，半透明遮罩 */
    private void showInputDialog(String icon, String title, String hint, int inputType, java.util.function.Consumer<String> onConfirm) {
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.DialogRoundedStyle);

        android.view.View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_custom, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);

        if (tvIcon != null) tvIcon.setText(icon);
        if (tvTitle != null) tvTitle.setText(title);
        if (tvMessage != null) tvMessage.setVisibility(View.GONE);

        // 在 dialogMessage 下方插入 EditText
        android.widget.LinearLayout parent = (android.widget.LinearLayout) dialogView;
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setBackgroundResource(R.drawable.edit_text_bg);
        int padPx = (int)(8 * getResources().getDisplayMetrics().density);
        int marginPx = (int)(8 * getResources().getDisplayMetrics().density);
        et.setPadding(padPx * 2, padPx, padPx * 2, padPx);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.setMargins(0, 0, 0, marginPx * 2);
        et.setLayoutParams(etLp);
        // 插入到按钮之前
        android.widget.LinearLayout btnRow = dialogView.findViewById(R.id.dialogButtons);
        if (btnRow != null && parent != null) {
            int btnRowIndex = parent.indexOfChild(btnRow);
            parent.addView(et, btnRowIndex);
        }

        if (tvPositive != null) tvPositive.setText("确定");
        if (tvNegative != null) { tvNegative.setText("取消"); btnNegative.setVisibility(View.VISIBLE); }

        dialog.setContentView(dialogView);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            // 统一弹窗宽度：屏幕80%，最大420dp
            int maxWidth = (int)(420 * getResources().getDisplayMetrics().density);
            int screenWidth = (int)(getResources().getDisplayMetrics().widthPixels * 0.8);
            dialog.getWindow().setLayout(
                Math.min(screenWidth, maxWidth),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        if (btnPositive != null) {
            btnPositive.setOnClickListener(v -> {
                dialog.dismiss();
                onConfirm.accept(et.getText().toString().trim());
            });
        }
        if (btnNegative != null) {
            btnNegative.setOnClickListener(v -> dialog.dismiss());
        }
        dialog.show();
    }

    /** 搜索文件 */
    private void searchFiles(String keyword) {
        if (currentDir == null) return;

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在搜索 \"" + keyword + "\"...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(true);
        pd.show();

        new AsyncTask<File, Void, List<File>>() {
            @Override
            protected List<File> doInBackground(File... dirs) {
                List<File> results = new ArrayList<>();
                searchRecursive(dirs[0], keyword.toLowerCase(), results, 0);
                return results;
            }

            private void searchRecursive(File dir, String keyword, List<File> results, int depth) {
                if (depth > 5 || results.size() > 100) return;
                File[] files = dir.listFiles();
                if (files == null) return;

                for (File f : files) {
                    if (f.getName().toLowerCase().contains(keyword)) {
                        results.add(f);
                    }
                    if (f.isDirectory() && !f.getName().startsWith(".")) {
                        searchRecursive(f, keyword, results, depth + 1);
                    }
                }
            }

            @Override
            protected void onPostExecute(List<File> results) {
                pd.dismiss();
                showSearchResults(results, keyword);
            }
        }.execute(currentDir);
    }

    /** 显示搜索结果 */
    private void showSearchResults(List<File> results, String keyword) {
        if (results.isEmpty()) {
            DialogHelper.show(this, "🔍", "搜索结果",
                "未找到包含 \"" + keyword + "\" 的文件", "确定", null);
        } else {
            String[] names = new String[Math.min(results.size(), 50)];
            final File[] files = new File[Math.min(results.size(), 50)];
            for (int i = 0; i < files.length; i++) {
                files[i] = results.get(i);
                names[i] = (results.get(i).isDirectory() ? "📂 " : "📄 ") + results.get(i).getName();
            }

            // 两列列表弹窗
            String[] displayNames = new String[names.length];
            final File[] displayFiles = files;
            for (int i = 0; i < names.length; i++) {
                displayNames[i] = names[i];
            }

            DialogHelper.showList(this, "🔍", "找到 " + results.size() + " 个结果", displayNames, new DialogHelper.OnItemClickListenerWithCancel() {
                @Override
                public void onClick(int position) {
                    File f = displayFiles[position];
                    if (f.isDirectory()) {
                        navigateTo(f);
                    } else {
                        openFile(f);
                    }
                }

                @Override
                public void onCancel() {
                    // 关闭
                }
            });
        }
    }

    /** 空目录 */
    private void showEmptyDirs() {
        if (currentDir == null) return;

        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在扫描空目录...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(true);
        pd.show();

        new AsyncTask<File, Void, List<File>>() {
            @Override
            protected List<File> doInBackground(File... dirs) {
                List<File> emptyDirs = new ArrayList<>();
                findEmptyDirs(dirs[0], emptyDirs, 0);
                return emptyDirs;
            }

            private void findEmptyDirs(File dir, List<File> results, int depth) {
                if (depth > 5 || results.size() > 100) return;
                File[] files = dir.listFiles();
                if (files == null) return;

                for (File f : files) {
                    if (f.isDirectory() && !f.getName().startsWith(".")) {
                        File[] children = f.listFiles();
                        if (children != null && children.length == 0) {
                            results.add(f);
                        } else {
                            findEmptyDirs(f, results, depth + 1);
                        }
                    }
                }
            }

            @Override
            protected void onPostExecute(List<File> results) {
                pd.dismiss();
                if (results.isEmpty()) {
                    Toast.makeText(FileManagerActivity.this, "未找到空目录", Toast.LENGTH_SHORT).show();
                    return;
                }

                String[] names = new String[results.size()];
                final File[] dirs = new File[results.size()];
                for (int i = 0; i < results.size(); i++) {
                    dirs[i] = results.get(i);
                    names[i] = "📁 " + results.get(i).getName();
                }

                DialogHelper.showList(FileManagerActivity.this, "📁", "空目录 (" + results.size() + ")", names, new DialogHelper.OnItemClickListenerWithCancel() {
                    @Override
                    public void onClick(int position) {
                        navigateTo(dirs[position]);
                    }
                    @Override
                    public void onCancel() {}
                });
            }
        }.execute(currentDir);
    }

    /** 存储统计 */
    private void showStorageStat() {
        if (currentDir == null) return;

        new AsyncTask<File, Void, String>() {
            @Override
            protected String doInBackground(File... dirs) {
                File dir = dirs[0];
                int[] counts = countFiles(dir, new int[]{0, 0, 0});

                return "统计信息\n\n" +
                       "文件夹数: " + counts[1] + "\n" +
                       "文件数: " + counts[0] + "\n" +
                       "总大小: " + formatFileSize(counts[2]);
            }

            private int[] countFiles(File dir, int[] counts) {
                File[] files = dir.listFiles();
                if (files == null) return counts;

                for (File f : files) {
                    if (f.isDirectory()) {
                        counts[1]++;
                        countFiles(f, counts);
                    } else {
                        counts[0]++;
                        counts[2] += f.length();
                    }
                }
                return counts;
            }

            @Override
            protected void onPostExecute(String result) {
                DialogHelper.show(FileManagerActivity.this, "📊", "存储统计",
                    result, "确定", null);
            }
        }.execute(currentDir);
    }

    /** 分级权限检测与引导 */
    private void checkAndRequestPermissions() {
        File root;
        boolean hasFullAccess;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：检查所有文件访问权限
            hasFullAccess = android.os.Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10：受分区存储限制，默认走受限模式更安全
            hasFullAccess = false;
        } else {
            // Android 9 及以下：检查传统存储权限
            boolean readGranted = ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean writeGranted = ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            hasFullAccess = readGranted && writeGranted;
        }

        if (hasFullAccess) {
            root = Environment.getExternalStorageDirectory();
            navigateTo(root);
        } else {
            isFullPermissionMode = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 及以下：动态申请传统存储权限
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, 1001);
                // 先进入受限模式，等权限回调后再刷新
                navigateToLimitedMode();
            } else {
                // Android 11+：引导授权所有文件访问
                if (!hasShownPermissionTip) {
                    hasShownPermissionTip = true;
                    showPermissionGuideDialog();
                } else {
                    navigateToLimitedMode();
                }
            }
        }
    }

    /** 显示权限引导对话框 */
    private void showPermissionGuideDialog() {
        String brand = Build.MANUFACTURER.toLowerCase();
        String deviceTip = "";
        if (brand.contains("xiaomi") || brand.contains("redmi")) {
            deviceTip = "\n\n小米/红米：设置 > 应用管理 > 喵喵嗷 > 权限管理 > 允许访问管理所有文件";
        } else if (brand.contains("huawei") || brand.contains("honor")) {
            deviceTip = "\n\n华为/荣耀：设置 > 应用 > 喵喵嗷 > 权限 > 访问管理所有文件";
        } else if (brand.contains("oppo")) {
            deviceTip = "\n\nOPPO：设置 > 应用管理 > 喵喵嗷 > 权限 > 允许管理所有文件";
        } else if (brand.contains("vivo")) {
            deviceTip = "\n\nvivo：设置 > 应用与权限 > 喵喵嗷 > 权限 > 允许管理所有文件";
        }

        String message = "为了访问手机上的所有文件，需要开启「访问所有文件」权限。" + deviceTip;

        DialogHelper.show(this, "🔐", "权限提示", message,
            "去开启权限", "受限模式继续",
            () -> openAppSettings(),
            () -> navigateToLimitedMode());
    }

    /** 打开应用设置页面 */
    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e2) {
                Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_LONG).show();
            }
        }
        navigateToLimitedMode();
    }

    /** 降级到受限模式 */
    private void navigateToLimitedMode() {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir.exists() && downloadsDir.canRead()) {
            currentDir = downloadsDir;
            updatePathDisplay();
            Toast.makeText(this, "受限模式：只能访问下载目录", Toast.LENGTH_LONG).show();
        } else {
            File privateDir = getExternalFilesDir(null);
            if (privateDir == null) privateDir = getFilesDir();
            currentDir = privateDir;
            updatePathDisplay();
            Toast.makeText(this, "受限模式", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isFullPermissionMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    isFullPermissionMode = true;
                    File root = Environment.getExternalStorageDirectory();
                    Toast.makeText(this, "权限已开启", Toast.LENGTH_SHORT).show();
                    navigateTo(root);
                }
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Android 9 及以下：检查传统权限是否已授权
                boolean readGranted = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean writeGranted = ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (readGranted && writeGranted) {
                    isFullPermissionMode = true;
                    File root = Environment.getExternalStorageDirectory();
                    Toast.makeText(this, "权限已开启", Toast.LENGTH_SHORT).show();
                    navigateTo(root);
                }
            }
        }
        detectUsbDrives();
        checkAdbStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                isFullPermissionMode = true;
                File root = Environment.getExternalStorageDirectory();
                navigateTo(root);
            } else {
                // 权限被拒绝，保持受限模式
                Toast.makeText(this, "权限被拒绝，只能访问应用私有目录", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 焦点放大动画 */
    private void attachFocusScale(View view, float scale) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float s = hasFocus ? scale : 1.0f;
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                ObjectAnimator.ofFloat(v, "scaleX", s),
                ObjectAnimator.ofFloat(v, "scaleY", s)
            );
            set.setDuration(120);
            set.start();
        });
    }

    /** 格式化文件大小 */
    private static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        int exp = (int) (Math.log(size) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", size / Math.pow(1024, exp), pre);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (clipboardFile != null) {
                new AlertDialog.Builder(this)
                    .setTitle("剪贴板")
                    .setMessage("已 " + (clipboardCopy ? "复制" : "剪切") + "：" + clipboardFile.getName())
                    .setPositiveButton("粘贴到当前位置", (d, w) -> {
                        if (currentDir != null) pasteFile(currentDir);
                    })
                    .setNegativeButton("清除剪贴板", (d, w) -> {
                        clipboardFile = null;
                        Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show();
                    })
                    .show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===================== 缩略图加载 =====================

    /** 异步加载略缩图（图片/视频/APK） */
    private void loadThumbnail(View itemView, File f, String ext) {
        ImageView ivThumb = itemView.findViewById(R.id.ivThumb);
        TextView tvIcon = itemView.findViewById(R.id.tvFileIcon);
        if (ivThumb == null) return;

        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                try {
                    if (ext.equals("apk")) return getApkIcon(f);
                    else if (ext.equals("jpg") || ext.equals("jpeg")
                            || ext.equals("png") || ext.equals("gif")
                            || ext.equals("bmp") || ext.equals("webp")) {
                        return getImageThumbnail(f, 128);
                    } else if (ext.equals("mp4") || ext.equals("mkv") || ext.equals("avi")
                            || ext.equals("mov") || ext.equals("wmv") || ext.equals("flv")
                            || ext.equals("webm") || ext.equals("m4v") || ext.equals("3gp")) {
                        return getVideoThumbnail(f, 128);
                    }
                } catch (Exception e) {
                    android.util.Log.e("FileManager", "获取视频缩略图失败: " + f.getPath(), e);
                }
                return null;
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    ivThumb.setImageBitmap(bitmap);
                    ivThumb.setVisibility(View.VISIBLE);
                    if (tvIcon != null) tvIcon.setVisibility(View.GONE);
                }
            }
        }.execute();
    }

    /** 从 APK 文件提取应用图标 */
    private Bitmap getApkIcon(File apkFile) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(apkFile.getAbsolutePath(), 0);
            Drawable icon = pm.getApplicationIcon(info);
            if (icon != null) {
                if (icon instanceof android.graphics.drawable.BitmapDrawable) {
                    return ((android.graphics.drawable.BitmapDrawable) icon).getBitmap();
                }
                Bitmap bitmap = Bitmap.createBitmap(
                        icon.getIntrinsicWidth(), icon.getIntrinsicHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                icon.draw(canvas);
                return bitmap;
            }
        } catch (Exception e) {
            android.util.Log.e("FileManager", "生成图标失败", e);
        }
        return null;
    }

    /** 生成图片缩略图 */
    private Bitmap getImageThumbnail(File file, int size) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            int sampleSize = Math.max(opts.outWidth / size, opts.outHeight / size);
            if (sampleSize < 1) sampleSize = 1;
            opts.inSampleSize = sampleSize;
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        } catch (Exception e) { return null; }
    }

    /** 从视频提取首帧作为缩略图 */
    private Bitmap getVideoThumbnail(File file, int size) {
        try {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(1000000);
            retriever.release();
            if (frame == null) return null;
            int w = frame.getWidth(), h = frame.getHeight();
            int scale = Math.max(w / size, h / size);
            if (scale < 1) scale = 1;
            return Bitmap.createScaledBitmap(frame, w / scale, h / scale, true);
        } catch (Exception e) { return null; }
    }

    /** 获取文件扩展名（小写） */
    private String getFileExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
