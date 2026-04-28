package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用管理器 Activity
 * 功能：
 * 1. 已安装应用列表（网格显示，像桌面一样）
 * 2. 点击应用弹出操作菜单（启动/卸载/提取/批量/禁用/重置）
 * 3. ADB工具（连接状态、无线调试配对）
 */
public class AppManagerActivity extends AppCompatActivity {
    private final PrivilegedBackendManager backendManager = PrivilegedBackendManager.get();

    private LinearLayout btnBackHome;
    private TextView tvAdbStatus;
    private LinearLayout btnPersonalApps;
    private LinearLayout btnSystemApps;
    private LinearLayout btnAllApps;
    private LinearLayout btnDisabledApps;
    private LinearLayout btnAppSettings;
    private LinearLayout btnAdbWirelessPair;
    private LinearLayout btnAdbCommand;
    private LinearLayout leftNavPanel;    // 左侧导航面板，批量模式时隐藏

    private RecyclerView appGridView;
    private LinearLayout disabledAppsPanel;
    private RecyclerView disabledAppsGridView;
    private LinearLayout systemAppsPanel;
    private RecyclerView systemAppsGridView;
    private LinearLayout adbPanel;
    private LinearLayout btnRefreshAdb;
    private LinearLayout btnEnterBatch;   // 进入批量选择按钮
    private LinearLayout batchActionBar;
    private TextView tvBatchCount;
    private TextView tvSelectAllText;
    private LinearLayout btnBatchSelectAll;
    private LinearLayout btnBatchInvert;
    private LinearLayout btnBatchUninstall;
    private LinearLayout btnBatchExtract;
    private LinearLayout btnBatchDisable;
    private LinearLayout btnBatchReset;
    private LinearLayout btnBatchCancel;
    private LinearLayout btnAdbDisconnect;

    /** 当前应用分类 */
    private AppCategory currentCategory = AppCategory.PERSONAL;

    /** 应用分类枚举 */
    private enum AppCategory {
        PERSONAL,  // 个人应用（非系统）
        SYSTEM,    // 系统应用
        ALL,       // 全部应用
        DISABLED   // 已禁用（特殊，不走通用流程）
    }

    /** 已安装应用列表（全部） */
    private final List<AppInfo> installedApps = new ArrayList<>();
    /** 个人应用列表（非系统） */
    private final List<AppInfo> personalApps = new ArrayList<>();
    /** 系统应用列表 */
    private final List<AppInfo> systemApps = new ArrayList<>();
    private AppAdapter appAdapter;

    /** 批量选择模式 */
    private boolean isBatchMode = false;
    /** 选中的应用列表 */
    private final List<AppInfo> selectedApps = new ArrayList<>();
    /** ADB是否已连接 */
    private boolean isAdbConnected = false;

    /** 已禁用应用列表 */
    private final List<AppInfo> disabledApps = new ArrayList<>();
    /** 禁用列表适配器 */
    private AppAdapter disabledAppAdapter;
    /** 系统应用适配器 */
    private AppAdapter systemAppAdapter;

    private static class AppInfo {
        String label;
        String packageName;
        boolean isSystem;
        String version;
        String sourceDir;
        long apkSize;
        ApplicationInfo appInfo;
    }

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

        setContentView(R.layout.activity_app_manager);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        showAppsByCategory(AppCategory.PERSONAL);
        checkAdbStatus();
    }

    private void initViews() {
        btnBackHome       = findViewById(R.id.btnBackHome);
        tvAdbStatus       = findViewById(R.id.tvAdbStatus);
        btnPersonalApps   = findViewById(R.id.btnPersonalApps);
        btnSystemApps     = findViewById(R.id.btnSystemApps);
        btnAllApps        = findViewById(R.id.btnAllApps);
        btnDisabledApps   = findViewById(R.id.btnDisabledApps);
        btnAppSettings    = findViewById(R.id.btnAppSettings);
        btnAdbWirelessPair = findViewById(R.id.btnAdbWirelessPair);
        btnAdbCommand     = findViewById(R.id.btnAdbCommand);
        leftNavPanel      = findViewById(R.id.leftNavPanel);
        appGridView       = findViewById(R.id.appGridView);
        disabledAppsPanel = findViewById(R.id.disabledAppsPanel);
        disabledAppsGridView = findViewById(R.id.disabledAppsGridView);
        systemAppsPanel   = findViewById(R.id.systemAppsPanel);
        systemAppsGridView = findViewById(R.id.systemAppsGridView);
        adbPanel          = findViewById(R.id.adbPanel);
        btnRefreshAdb     = findViewById(R.id.btnRefreshAdb);
        btnEnterBatch     = findViewById(R.id.btnEnterBatch);
        batchActionBar    = findViewById(R.id.batchActionBar);
        tvBatchCount      = findViewById(R.id.tvBatchCount);
        tvSelectAllText   = findViewById(R.id.tvSelectAllText);
        btnBatchSelectAll = findViewById(R.id.btnBatchSelectAll);
        btnBatchInvert    = findViewById(R.id.btnBatchInvert);
        btnBatchUninstall = findViewById(R.id.btnBatchUninstall);
        btnBatchExtract   = findViewById(R.id.btnBatchExtract);
        btnBatchDisable  = findViewById(R.id.btnBatchDisable);
        btnBatchReset    = findViewById(R.id.btnBatchReset);
        btnBatchCancel   = findViewById(R.id.btnBatchCancel);
        btnAdbDisconnect = findViewById(R.id.btnAdbDisconnect);

        // 返回首页
        btnBackHome.setOnClickListener(v -> {
            if (isBatchMode) {
                exitBatchMode();
            } else {
                finish();
            }
        });

        // 显示个人应用
        btnPersonalApps.setOnClickListener(v -> {
            showAppsByCategory(AppCategory.PERSONAL);
        });

        // 显示系统应用
        btnSystemApps.setOnClickListener(v -> {
            showAppsByCategory(AppCategory.SYSTEM);
        });

        // 显示全部应用
        btnAllApps.setOnClickListener(v -> {
            showAppsByCategory(AppCategory.ALL);
        });

        // 显示已禁用软件
        btnDisabledApps.setOnClickListener(v -> {
            if (!isAdbConnected) {
                Toast.makeText(this, "需要先连接ADB才能查看已禁用软件", Toast.LENGTH_SHORT).show();
                return;
            }
            showDisabledApps();
            updateNavSelection(btnDisabledApps);
        });

        // 设备信息（原应用设置）
        btnAppSettings.setOnClickListener(v -> showDeviceInfoDialog());

        // 无线ADB配对
        btnAdbWirelessPair.setOnClickListener(v -> showWirelessPairDialog());

        // ADB命令终端
        btnAdbCommand.setOnClickListener(v -> {
            startActivity(new Intent(AppManagerActivity.this, AdbCommandActivity.class));
        });

        // 刷新ADB状态
        btnRefreshAdb.setOnClickListener(v -> checkAdbStatus());

        // 断开ADB连接
        if (btnAdbDisconnect != null) {
            btnAdbDisconnect.setOnClickListener(v -> {
                if (isAdbConnected) {
                    AdbHelper.get().disconnect();
                    isAdbConnected = false;
                    updateAdbStatusDisplay(new BackendState(
                        BackendStatus.DISCONNECTED,
                        "无线 ADB 已断开",
                        "当前未连接无线 ADB，可改用 Shizuku 或重新连接。",
                        "",
                        false,
                        false
                    ));
                    Toast.makeText(this, "🔌 ADB已断开", Toast.LENGTH_SHORT).show();
                }
            });
            attachFocusScale(btnAdbDisconnect, 1.10f);
        }

        // 进入批量选择模式
        btnEnterBatch.setOnClickListener(v -> enterBatchMode());
        attachFocusScale(btnEnterBatch, 1.08f);

        // 批量操作
        btnBatchSelectAll.setOnClickListener(v -> toggleSelectAll());
        btnBatchInvert.setOnClickListener(v -> invertSelection());
        btnBatchUninstall.setOnClickListener(v -> batchUninstall());
        btnBatchExtract.setOnClickListener(v -> batchExtract());
        btnBatchDisable.setOnClickListener(v -> batchDisable());
        btnBatchReset.setOnClickListener(v -> batchReset());
        btnBatchCancel.setOnClickListener(v -> exitBatchMode());

        // 批量操作按钮焦点动画
        attachFocusScale(btnBatchSelectAll, 1.08f);
        attachFocusScale(btnBatchInvert, 1.08f);
        attachFocusScale(btnBatchUninstall, 1.08f);
        attachFocusScale(btnBatchExtract, 1.08f);
        attachFocusScale(btnBatchDisable, 1.08f);
        attachFocusScale(btnBatchReset, 1.08f);
        attachFocusScale(btnBatchCancel, 1.08f);

        // 初始化应用网格
        setupAppGrid();

        if (disabledAppsGridView != null) {
            disabledAppAdapter = new AppAdapter();
            disabledAppsGridView.setLayoutManager(new GridLayoutManager(this, 5));
            disabledAppsGridView.setAdapter(disabledAppAdapter);
        }

        if (systemAppsGridView != null) {
            systemAppAdapter = new AppAdapter();
            systemAppsGridView.setLayoutManager(new GridLayoutManager(this, 5));
            systemAppsGridView.setAdapter(systemAppAdapter);
        }

        btnPersonalApps.requestFocus();
    }

    /** 设置应用网格 */
    private void setupAppGrid() {
        appAdapter = new AppAdapter();
        appGridView.setLayoutManager(new GridLayoutManager(this, 5));
        appGridView.setAdapter(appAdapter);
    }

    /** 根据分类显示应用列表 */
    private void showAppsByCategory(AppCategory category) {
        // 退出批量模式
        if (isBatchMode) exitBatchMode();

        currentCategory = category;
        appGridView.setVisibility(View.VISIBLE);
        if (disabledAppsPanel != null) disabledAppsPanel.setVisibility(View.GONE);
        if (systemAppsPanel != null) systemAppsPanel.setVisibility(View.GONE);
        adbPanel.setVisibility(View.GONE);

        // 加载/刷新应用列表
        loadInstalledApps();
    }

    /** 显示已禁用软件列表 */
    private void showDisabledApps() {
        appGridView.setVisibility(View.GONE);
        if (disabledAppsPanel != null) disabledAppsPanel.setVisibility(View.VISIBLE);
        adbPanel.setVisibility(View.GONE);
        loadDisabledApps();
    }

    /** 加载已禁用应用 */
    private AsyncTask<Void, Void, List<AppInfo>> currentDisableTask;
    private static final int DISABLE_LOAD_TIMEOUT_MS = 30000; // 30秒超时

    private void loadDisabledApps() {
        if (disabledAppAdapter == null) return;
        disabledApps.clear();
        disabledAppAdapter.notifyDataSetChanged();

        // 取消正在进行的加载任务
        if (currentDisableTask != null && !currentDisableTask.isCancelled()) {
            currentDisableTask.cancel(true);
        }

        // 先检查 ADB 是否已连接
        if (!isAdbConnected) {
            Toast.makeText(this, "⚠️ 请先连接ADB设备", Toast.LENGTH_SHORT).show();
            return;
        }

        currentDisableTask = new AsyncTask<Void, Void, List<AppInfo>>() {
            ProgressDialog pd;
            private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
            private final Runnable timeoutRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isCancelled() && pd != null && pd.isShowing()) {
                        pd.dismiss();
                        Toast.makeText(AppManagerActivity.this, "⏱️ 检测超时，请重试", Toast.LENGTH_SHORT).show();
                        cancel(true);
                    }
                }
            };

            @Override
            protected void onPreExecute() {
                pd = new ProgressDialog(AppManagerActivity.this);
                pd.setMessage("正在获取已禁用应用...");
                pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                pd.setCancelable(true); // 允许用户取消
                pd.setOnCancelListener(dialog -> cancel(true));
                pd.show();
                // 启动超时计时器
                timeoutHandler.postDelayed(timeoutRunnable, DISABLE_LOAD_TIMEOUT_MS);
            }

            @Override
            protected List<AppInfo> doInBackground(Void... voids) {
                if (isCancelled()) return new ArrayList<>();
                List<AppInfo> disabled = new ArrayList<>();
                
                try {
                    // 使用新的 listDisabledPackages 方法（Android 16 兼容）
                    // 这里使用 CountDownLatch 等待异步结果
                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final List<String>[] packages = new java.util.List[1];
                    
                    backendManager.listDisabledPackages(pkgList -> {
                        packages[0] = pkgList;
                        latch.countDown();
                    });
                    
                    // 等待结果，最多30秒
                    if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        android.util.Log.d("AppManager", "获取已禁用应用超时");
                        return disabled;
                    }
                    
                    if (packages[0] == null || packages[0].isEmpty()) {
                        android.util.Log.d("AppManager", "没有已禁用的应用");
                        return disabled;
                    }
                    
                    android.util.Log.d("AppManager", "找到 " + packages[0].size() + " 个已禁用应用");
                    
                    PackageManager pm = getPackageManager();
                    for (String pkgName : packages[0]) {
                        if (isCancelled()) break;
                        try {
                            PackageInfo pkgInfo = pm.getPackageInfo(pkgName, 0);
                            AppInfo app = new AppInfo();
                            app.packageName = pkgInfo.packageName;
                            app.label = pm.getApplicationLabel(pkgInfo.applicationInfo).toString();
                            app.isSystem = (pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                            app.appInfo = pkgInfo.applicationInfo;
                            app.version = pkgInfo.versionName != null ? pkgInfo.versionName : "未知";
                            app.sourceDir = pkgInfo.applicationInfo.sourceDir;
                            app.apkSize = new File(pkgInfo.applicationInfo.sourceDir).length();
                            disabled.add(app);
                            android.util.Log.d("AppManager", "解析已禁用应用: " + app.label);
                        } catch (PackageManager.NameNotFoundException e) {
                            // 忽略找不到的包
                        }
                    }
                    android.util.Log.d("AppManager", "共解析 " + disabled.size() + " 个已禁用应用");
                } catch (Exception e) {
                    android.util.Log.e("AppManager", "加载已禁用应用失败", e);
                    e.printStackTrace();
                }
                return disabled;
            }

            @Override
            protected void onPostExecute(List<AppInfo> apps) {
                timeoutHandler.removeCallbacks(timeoutRunnable);
                if (pd != null && pd.isShowing()) {
                    pd.dismiss();
                }
                android.util.Log.d("AppManager", "onPostExecute: " + apps.size() + " 个已禁用应用");
                if (apps.isEmpty()) {
                    Toast.makeText(AppManagerActivity.this, "没有已禁用的应用", Toast.LENGTH_SHORT).show();
                }
                disabledApps.clear();
                disabledApps.addAll(apps);
                disabledAppAdapter.notifyDataSetChanged();
            }

            @Override
            protected void onCancelled() {
                timeoutHandler.removeCallbacks(timeoutRunnable);
                if (pd != null && pd.isShowing()) {
                    pd.dismiss();
                }
            }
        };
        currentDisableTask.execute();
    }

    /** 显示ADB面板 */
    private void showAdbPanel() {
        appGridView.setVisibility(View.GONE);
        if (disabledAppsPanel != null) disabledAppsPanel.setVisibility(View.GONE);
        adbPanel.setVisibility(View.VISIBLE);
        checkAdbStatus();
    }

    /** 更新导航选中状态 */
    private void updateNavSelection(View selected) {
        resetNavSelection();
        if (selected.getBackground() != null) {
            selected.getBackground().setLevel(1);
        }
    }

    private void resetNavSelection() {
        // 重置为未选中样式
    }

    /** 进入批量选择模式 */
    private void enterBatchMode() {
        isBatchMode = true;
        selectedApps.clear();
        batchActionBar.setVisibility(View.VISIBLE);
        // 隐藏左侧侧边栏，给应用列表更多空间
        if (leftNavPanel != null) leftNavPanel.setVisibility(View.GONE);
        
        // 根据当前分类更新全选计数
        switch (currentCategory) {
            case PERSONAL:
                tvBatchCount.setText("已选 0 个应用（共 " + personalApps.size() + " 个）");
                break;
            case SYSTEM:
                tvBatchCount.setText("已选 0 个应用（共 " + systemApps.size() + " 个）");
                break;
            case ALL:
            default:
                tvBatchCount.setText("已选 0 个应用（共 " + installedApps.size() + " 个）");
                break;
            case DISABLED:
                tvBatchCount.setText("已选 0 个应用");
                break;
        }
        
        // ADB 连接时显示禁用和重置按钮
        if (btnBatchDisable != null) btnBatchDisable.setVisibility(isAdbConnected ? View.VISIBLE : View.GONE);
        if (btnBatchReset != null) btnBatchReset.setVisibility(isAdbConnected ? View.VISIBLE : View.GONE);
        
        updateBatchCount();
        appAdapter.notifyDataSetChanged();
        Toast.makeText(this, "📋 进入批量选择模式，长按可多选", Toast.LENGTH_SHORT).show();
    }
    
    /** 更新批量选择计数 */
    private void updateBatchCount() {
        int count = selectedApps.size();
        int total = 0;
        switch (currentCategory) {
            case PERSONAL: total = personalApps.size(); break;
            case SYSTEM: total = systemApps.size(); break;
            case ALL: total = installedApps.size(); break;
            default: total = installedApps.size(); break;
        }
        tvBatchCount.setText("已选 " + count + " 个（共 " + total + " 个）");
        
        // 更新全选按钮文字
        if (count == total) {
            tvSelectAllText.setText(" 取消全选");
        } else {
            tvSelectAllText.setText(" 全选");
        }
    }

    /** 退出批量选择模式 */
    private void exitBatchMode() {
        isBatchMode = false;
        selectedApps.clear();
        batchActionBar.setVisibility(View.GONE);
        // 恢复左侧侧边栏
        if (leftNavPanel != null) leftNavPanel.setVisibility(View.VISIBLE);
        appAdapter.notifyDataSetChanged();
    }

    /** 切换应用选择状态 */
    private void toggleAppSelection(AppInfo app) {
        if (selectedApps.contains(app)) {
            selectedApps.remove(app);
        } else {
            selectedApps.add(app);
        }
        updateBatchCount();
        appAdapter.notifyDataSetChanged();
    }
    
    /** 全选/取消全选 */
    private void toggleSelectAll() {
        if (selectedApps.size() == installedApps.size()) {
            // 取消全选
            selectedApps.clear();
            Toast.makeText(this, "☑ 已取消全选", Toast.LENGTH_SHORT).show();
        } else {
            // 全选
            selectedApps.clear();
            selectedApps.addAll(installedApps);
            Toast.makeText(this, "☑ 已全选 " + installedApps.size() + " 个应用", Toast.LENGTH_SHORT).show();
        }
        updateBatchCount();
        appAdapter.notifyDataSetChanged();
    }
    
    /** 反选 */
    private void invertSelection() {
        List<AppInfo> newSelection = new ArrayList<>();
        for (AppInfo app : installedApps) {
            if (!selectedApps.contains(app)) {
                newSelection.add(app);
            }
        }
        selectedApps.clear();
        selectedApps.addAll(newSelection);
        updateBatchCount();
        appAdapter.notifyDataSetChanged();
        Toast.makeText(this, "⟲ 已反选，当前 " + selectedApps.size() + " 个应用", Toast.LENGTH_SHORT).show();
    }

    /** 批量卸载 (ADB) */
    private void batchUninstall() {
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "请先选择要卸载的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isAdbConnected) {
            // ADB未连接，提示使用系统卸载器
            StringBuilder sb = new StringBuilder();
            sb.append("批量卸载需要ADB连接。\n\n");
            sb.append("已选 ").append(selectedApps.size()).append(" 个应用：\n");
            for (int i = 0; i < Math.min(selectedApps.size(), 3); i++) {
                sb.append("• ").append(selectedApps.get(i).label).append("\n");
            }
            if (selectedApps.size() > 3) {
                sb.append("• ...等共").append(selectedApps.size()).append("个");
            }
            sb.append("\n是否跳转到系统卸载界面？");
            new AlertDialog.Builder(this)
                .setTitle("⚠️ 需要ADB连接")
                .setMessage(sb.toString())
                .setPositiveButton("连接ADB", (d, w) -> {
                    showWirelessPairDialog();
                })
                .setNegativeButton("取消", null)
                .show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("确定要卸载以下 ").append(selectedApps.size()).append(" 个应用吗？\n\n");
        for (AppInfo app : selectedApps) {
            sb.append("• ").append(app.label).append("\n");
        }
        sb.append("\n将通过 ADB 卸载");

        new AlertDialog.Builder(this)
            .setTitle("🗑 批量卸载")
            .setMessage(sb.toString())
            .setPositiveButton("卸载", (d, w) -> {
                ProgressDialog pd = new ProgressDialog(this);
                pd.setMessage("正在批量卸载应用...");
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setMax(selectedApps.size());
                pd.setCancelable(false);
                pd.show();

                // 使用 AtomicInteger 保证线程安全
                final AtomicInteger completedCount = new AtomicInteger(0);
                final int totalCount = selectedApps.size();
                
                for (AppInfo app : selectedApps) {
                    String cmd = "pm uninstall " + app.packageName;
                    backendManager.execShell(cmd, result -> {
                        int current = completedCount.incrementAndGet();
                        runOnUiThread(() -> {
                            pd.setProgress(current);
                            if (result != null && result.contains("Success")) {
                                Toast.makeText(this, "✅ 已卸载: " + app.label, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "❌ 卸载失败: " + app.label, Toast.LENGTH_SHORT).show();
                            }
                        });
                        // 检查是否所有任务都完成
                        if (current >= totalCount) {
                            runOnUiThread(() -> {
                                pd.dismiss();
                                Toast.makeText(this, "✅ 批量卸载完成", Toast.LENGTH_SHORT).show();
                            });
                            // 刷新列表
                            runOnUiThread(this::loadInstalledApps);
                            exitBatchMode();
                        }
                    });
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 批量提取 APK */
    private void batchExtract() {
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "请先选择要提取的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("确定要提取以下 ").append(selectedApps.size()).append(" 个应用的安装包吗？\n\n");
        for (AppInfo app : selectedApps) {
            sb.append("• ").append(app.label).append("\n");
        }

        new AlertDialog.Builder(this)
            .setTitle("📦 批量提取")
            .setMessage(sb.toString())
            .setPositiveButton("提取", (d, w) -> {
                for (AppInfo app : selectedApps) {
                    String destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
                    String destPath = destDir + "/" + app.label.replaceAll("[^a-zA-Z0-9\u4e00-\u9fa5]", "_") + ".apk";
                    String cmd = "cp " + app.sourceDir + " " + destPath;
                    backendManager.execShell(cmd, result -> {
                        runOnUiThread(() -> {
                            if (result == null || result.contains("ERROR") || result.contains("No such file")) {
                                Toast.makeText(this, "❌ 提取失败: " + app.label, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "✅ 已提取: " + app.label, Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                }
                exitBatchMode();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 批量禁用 (ADB) */
    private void batchDisable() {
        if (!isAdbConnected) {
            Toast.makeText(this, "⚠️ 请先连接ADB设备", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "请先选择要禁用的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("确定要禁用以下 ").append(selectedApps.size()).append(" 个应用吗？\n\n");
        for (AppInfo app : selectedApps) {
            sb.append("• ").append(app.label).append("\n");
        }
        sb.append("\n禁用后应用将不再运行，不会弹广告。");

        new AlertDialog.Builder(this)
            .setTitle("🚫 批量禁用")
            .setMessage(sb.toString())
            .setPositiveButton("禁用", (d, w) -> {
                ProgressDialog pd = new ProgressDialog(this);
                pd.setMessage("正在批量禁用应用...");
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setMax(selectedApps.size());
                pd.setCancelable(false);
                pd.show();

                final AtomicInteger completedCount = new AtomicInteger(0);
                final int totalCount = selectedApps.size();
                for (AppInfo app : selectedApps) {
                    String cmd = "pm disable-user --user 0 " + app.packageName;
                    backendManager.execShell(cmd, result -> {
                        int current = completedCount.incrementAndGet();
                        runOnUiThread(() -> pd.setProgress(current));
                        if (current >= totalCount) {
                            runOnUiThread(() -> {
                                pd.dismiss();
                                Toast.makeText(this, "✅ 已禁用 " + current + " 个应用", Toast.LENGTH_SHORT).show();
                            });
                            // 刷新列表
                            runOnUiThread(this::loadInstalledApps);
                            exitBatchMode();
                        }
                    });
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 批量重置 (ADB) */
    private void batchReset() {
        if (!isAdbConnected) {
            Toast.makeText(this, "⚠️ 请先连接ADB设备", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "请先选择要重置的应用", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("确定要重置以下 ").append(selectedApps.size()).append(" 个应用吗？\n\n");
        for (AppInfo app : selectedApps) {
            sb.append("• ").append(app.label).append("\n");
        }
        sb.append("\n这将清空应用的所有数据（缓存、账号信息等）。");

        new AlertDialog.Builder(this)
            .setTitle("🔄 批量重置")
            .setMessage(sb.toString())
            .setPositiveButton("重置", (d, w) -> {
                ProgressDialog pd = new ProgressDialog(this);
                pd.setMessage("正在批量重置应用...");
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setMax(selectedApps.size());
                pd.setCancelable(false);
                pd.show();

                final AtomicInteger completedCount = new AtomicInteger(0);
                final int totalCount = selectedApps.size();
                for (AppInfo app : selectedApps) {
                    String cmd = "pm clear " + app.packageName;
                    backendManager.execShell(cmd, result -> {
                        int current = completedCount.incrementAndGet();
                        runOnUiThread(() -> pd.setProgress(current));
                        if (current >= totalCount) {
                            runOnUiThread(() -> {
                                pd.dismiss();
                                Toast.makeText(this, "✅ 已重置 " + current + " 个应用", Toast.LENGTH_SHORT).show();
                            });
                            exitBatchMode();
                        }
                    });
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 加载已安装应用（填充三个列表） */
    private void loadInstalledApps() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                List<AppInfo> all = new ArrayList<>();
                List<AppInfo> personal = new ArrayList<>();
                List<AppInfo> system = new ArrayList<>();
                PackageManager pm = getPackageManager();
                
                // 使用更完整的标志获取应用信息，兼容高版本Android
                int flags = PackageManager.GET_META_DATA;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    flags |= PackageManager.GET_SIGNING_CERTIFICATES;
                } else {
                    flags |= PackageManager.GET_SIGNATURES;
                }
                
                List<PackageInfo> packages = pm.getInstalledPackages(flags);

                for (PackageInfo pkg : packages) {
                    // 过滤掉自身
                    if (pkg.packageName.equals(getPackageName())) continue;
                    AppInfo app = new AppInfo();
                    app.packageName = pkg.packageName;
                    app.label = pm.getApplicationLabel(pkg.applicationInfo).toString();
                    app.isSystem = (pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    app.appInfo = pkg.applicationInfo;
                    app.version = pkg.versionName != null ? pkg.versionName : "未知";
                    app.sourceDir = pkg.applicationInfo.sourceDir;
                    app.apkSize = new File(app.sourceDir).length();

                    all.add(app);
                    if (app.isSystem) {
                        system.add(app);
                    } else {
                        personal.add(app);
                    }
                }

                installedApps.clear();
                installedApps.addAll(all);
                personalApps.clear();
                personalApps.addAll(personal);
                systemApps.clear();
                systemApps.addAll(system);
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                // 根据当前分类显示对应列表
                switch (currentCategory) {
                    case PERSONAL:
                        showPersonalApps();
                        break;
                    case SYSTEM:
                        showSystemApps();
                        break;
                    case ALL:
                        showAllApps();
                        break;
                    case DISABLED:
                        // 已禁用不走此流程
                        break;
                }
            }
        }.execute();
    }

    /** 显示个人应用 */
    private void showPersonalApps() {
        appGridView.setVisibility(View.VISIBLE);
        if (disabledAppsPanel != null) disabledAppsPanel.setVisibility(View.GONE);
        if (systemAppsPanel != null) systemAppsPanel.setVisibility(View.GONE);
        appAdapter.setAppList(personalApps);
        appAdapter.notifyDataSetChanged();
        updateNavSelection(btnPersonalApps);
        Toast.makeText(this, "个人应用 " + personalApps.size() + " 个", Toast.LENGTH_SHORT).show();
    }

    /** 显示系统应用 */
    private void showSystemApps() {
        appGridView.setVisibility(View.GONE);
        if (disabledAppsPanel != null) disabledAppsPanel.setVisibility(View.GONE);
        if (systemAppsPanel != null) systemAppsPanel.setVisibility(View.VISIBLE);
        if (systemAppAdapter != null) {
            systemAppAdapter.setAppList(systemApps);
            systemAppAdapter.notifyDataSetChanged();
        }
        updateNavSelection(btnSystemApps);
        Toast.makeText(this, "系统应用 " + systemApps.size() + " 个", Toast.LENGTH_SHORT).show();
    }

    /** 显示全部应用 */
    private void showAllApps() {
        appGridView.setVisibility(View.VISIBLE);
        if (disabledAppsPanel != null) disabledAppsPanel.setVisibility(View.GONE);
        if (systemAppsPanel != null) systemAppsPanel.setVisibility(View.GONE);
        appAdapter.setAppList(installedApps);
        appAdapter.notifyDataSetChanged();
        updateNavSelection(btnAllApps);
        Toast.makeText(this, "全部应用 " + installedApps.size() + " 个", Toast.LENGTH_SHORT).show();
    }

    /** 显示应用操作弹窗（直接传入 AppInfo，三类面板通用） */
    private void showAppActions(AppInfo app) {

        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_app_actions, null);

        // 设置弹窗内容
        ImageView ivIcon = popupView.findViewById(R.id.ivPopupIcon);
        TextView tvName = popupView.findViewById(R.id.tvPopupAppName);
        TextView tvPackage = popupView.findViewById(R.id.tvPopupPackage);
        try {
            ivIcon.setImageDrawable(getPackageManager().getApplicationIcon(app.packageName));
        } catch (Exception e) {
            ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }
        tvName.setText(app.label);
        tvPackage.setText(app.packageName);

        // 批量选择模式按钮
        LinearLayout btnBatchMode = popupView.findViewById(R.id.btnBatchMode);
        btnBatchMode.setOnClickListener(v -> {
            if (popupDialog != null) popupDialog.dismiss();
            enterBatchMode();
        });

        // 创建弹窗
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        AlertDialog dialog = builder
            .setView(popupView)
            .create();

        // 保存dialog引用
        popupDialog = dialog;

        // 操作按钮
        popupView.findViewById(R.id.btnPopupOpen).setOnClickListener(v -> {
            dialog.dismiss();
            launchApp(app);
        });

        popupView.findViewById(R.id.btnPopupExtract).setOnClickListener(v -> {
            dialog.dismiss();
            extractApk(app);
        });

        popupView.findViewById(R.id.btnPopupUninstall).setOnClickListener(v -> {
            dialog.dismiss();
            uninstallApp(app);
        });

        // 先显示弹窗，避免UI卡顿
        dialog.show();

        // 设置弹窗尺寸（统一宽度）
        WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        int maxWidth = (int)(420 * getResources().getDisplayMetrics().density);
        int screenWidth = (int)(getResources().getDisplayMetrics().widthPixels * 0.8);
        params.width = Math.min(screenWidth, maxWidth);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        dialog.getWindow().setAttributes(params);

        // ADB功能（仅在ADB连接时显示）- 在 dialog.show() 之后操作
        View adbDivider = popupView.findViewById(R.id.adbDivider);
        View btnDisable = popupView.findViewById(R.id.btnPopupDisable);
        View btnReset = popupView.findViewById(R.id.btnPopupReset);

        if (isAdbConnected) {
            View adbButtonsRow = popupView.findViewById(R.id.adbButtonsRow);
            if (adbButtonsRow != null) {
                adbButtonsRow.setVisibility(View.VISIBLE);
            }

            // 使用 PackageManager 本地API判断是否为已禁用应用（非阻塞）
            boolean isDisabled = isAppDisabled(app.packageName);
            TextView tvDisableBtnText = btnDisable.findViewById(R.id.tvDisableText);
            if (tvDisableBtnText != null) {
                tvDisableBtnText.setText(isDisabled ? "启用" : "禁用");
            }

            btnDisable.setOnClickListener(v -> {
                dialog.dismiss();
                if (isDisabled) {
                    enableApp(app);
                } else {
                    disableApp(app);
                }
            });

            btnReset.setOnClickListener(v -> {
                dialog.dismiss();
                resetApp(app);
            });
        }
    }

    private AlertDialog popupDialog;

    /** 启动应用 */
    private void launchApp(AppInfo app) {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "无法启动此应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法启动此应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 提取APK安装包 */
    private void extractApk(AppInfo app) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在提取 " + app.label + "...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(false);
        pd.show();

        new AsyncTask<AppInfo, Void, File>() {
            @Override
            protected File doInBackground(AppInfo... apps) {
                AppInfo info = apps[0];
                try {
                    File srcFile = new File(info.sourceDir);
                    File destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!destDir.exists()) destDir.mkdirs();

                    File destFile = new File(destDir, info.label.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_") + ".apk");

                    // 复制文件
                    InputStream is = new FileInputStream(srcFile);
                    OutputStream os = new FileOutputStream(destFile);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) > 0) {
                        os.write(buf, 0, len);
                    }
                    is.close();
                    os.close();

                    return destFile;
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(File result) {
                pd.dismiss();
                if (result != null) {
                    Toast.makeText(AppManagerActivity.this, "✅ 已提取到：\n" + result.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    // 刷新下载目录
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    Uri uri = Uri.parse("file://" + result.getParentFile().getAbsolutePath());
                    intent.setData(uri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { startActivity(intent); } catch (Exception e) { }
                } else {
                    Toast.makeText(AppManagerActivity.this, "❌ 提取失败", Toast.LENGTH_SHORT).show();
                }
            }
        }.execute(app);
    }

    /** 卸载应用 */
    private void uninstallApp(AppInfo app) {
        if (app.isSystem) {
            new AlertDialog.Builder(this)
                .setTitle("⚠️ 提示")
                .setMessage(app.label + " 是系统应用\n\n要卸载系统应用需要root权限。\n是否跳转到应用设置页面？")
                .setPositiveButton("去设置", (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + app.packageName));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
        } else {
            Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }
    }

    /** 禁用应用 (ADB) */
    private void disableApp(AppInfo app) {
        if (!isAdbConnected) {
            Toast.makeText(this, "需要先连接ADB才能使用此功能", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("🚫 禁用应用")
            .setMessage("确定要禁用 " + app.label + " 吗？\n\n禁用后应用将不再运行，不会弹广告，但不会被删除。\n后续可在应用管理中重新启用。")
            .setPositiveButton("禁用", (d, w) -> {
                backendManager.disableApp(app.packageName, success -> {
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(AppManagerActivity.this, "✅ 已禁用：" + app.label, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(AppManagerActivity.this, "❌ 禁用失败，请确保ADB已连接", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 重置应用数据 (ADB) */
    private void resetApp(AppInfo app) {
        if (!isAdbConnected) {
            Toast.makeText(this, "需要先连接ADB才能使用此功能", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("🔄 重置应用")
            .setMessage("确定要重置 " + app.label + " 吗？\n\n这将清空应用的所有数据（缓存、账号信息、使用记录），但不会删除应用本身。\n\n常用于解决：卡顿、闪退、登录异常等问题。")
            .setPositiveButton("重置", (d, w) -> {
                backendManager.clearAppData(app.packageName, success -> {
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(AppManagerActivity.this, "✅ 已重置：" + app.label, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(AppManagerActivity.this, "❌ 重置失败，请确保ADB已连接", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 检查ADB状态，未连接时自动尝试连接本机无线调试 */
    /** 检查应用是否为已禁用状态 */
    private boolean isAppDisabled(String packageName) {
        try {
            ApplicationInfo appInfo = getPackageManager().getApplicationInfo(packageName, 0);
            // ApplicationInfo.enabled 表示应用是否启用
            return !appInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** 启用已禁用的应用 (ADB) */
    private void enableApp(AppInfo app) {
        if (!isAdbConnected) {
            Toast.makeText(this, "需要先连接ADB才能使用此功能", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("✅ 启用应用")
            .setMessage("确定要启用 " + app.label + " 吗？\n\n启用后应用将恢复正常运行。")
            .setPositiveButton("启用", (d, w) -> {
                backendManager.enableApp(app.packageName, success -> {
                    runOnUiThread(() -> {
                        if (success) {
                            Toast.makeText(AppManagerActivity.this, "✅ 已启用：" + app.label, Toast.LENGTH_SHORT).show();
                            loadInstalledApps();
                        } else {
                            Toast.makeText(AppManagerActivity.this, "❌ 启用失败，请确保ADB已连接", Toast.LENGTH_SHORT).show();
                        }
                    });
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void checkAdbStatus() {
        if (tvAdbStatus != null) tvAdbStatus.setText("🔌 检测中...");
        backendManager.addListener(state -> runOnUiThread(() -> {
            isAdbConnected = state.ready;
            updateAdbStatusDisplay(state);
            if (isBatchMode) {
                if (btnBatchDisable != null) btnBatchDisable.setVisibility(isAdbConnected ? View.VISIBLE : View.GONE);
                if (btnBatchReset != null) btnBatchReset.setVisibility(isAdbConnected ? View.VISIBLE : View.GONE);
            }
        }));
        backendManager.refreshState(state -> runOnUiThread(() -> {
            isAdbConnected = state.ready;
            updateAdbStatusDisplay(state);
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
        if (btnAdbDisconnect != null) btnAdbDisconnect.setVisibility(backendManager.isAdbConnected() ? View.VISIBLE : View.GONE);
    }

    /** 尝试自动检测并连接本机无线调试端口 */
    private void tryAutoConnectWirelessAdb() {
        BackendState state = backendManager.getCurrentState();
        if (state.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED) {
            backendManager.requestShizukuPermission(this, granted -> runOnUiThread(() -> backendManager.refreshState(null)));
            return;
        }
        // 优先尝试连接上次成功连接的设备
        String lastHost = getLastAdbHost();
        int lastPort = getLastAdbPort();

        if (lastHost != null && lastPort > 0) {
            // 有历史记录，优先尝试上次连接
            tryConnectWithRetry(lastHost, lastPort, () -> {
                // 上次连接失败，尝试默认端口
                tryDefaultPorts();
            });
        } else {
            // 没有历史记录，尝试默认端口
            tryDefaultPorts();
        }
    }

    /** 尝试连接指定设备，可重试 */
    private void tryConnectWithRetry(String host, int port, Runnable onFail) {
        runOnUiThread(() -> {
            if (tvAdbStatus != null) {
                tvAdbStatus.setText("🔌 adb：正在连接 " + host + ":" + port + " ...");
            }
        });

        // 先检测端口是否可用
        new Thread(() -> {
            boolean portAvailable = false;
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(host, port), 500);
                socket.close();
                portAvailable = true;
            } catch (Exception ignored) {}

            if (!portAvailable) {
                runOnUiThread(onFail);
                return;
            }

            // 端口可用，尝试 ADB 连接
            AdbHelper.get().connect(host, port, connected -> {
                if (connected) {
                    runOnUiThread(() -> {
                        isAdbConnected = true;
                        tvAdbStatus.setText("🔗 adb：已连接 " + host + ":" + port);
                        tvAdbStatus.setTextColor(0xFF27AE60);
                        Toast.makeText(this, "✅ 已自动连接无线ADB", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(onFail);
                }
            });
        }).start();
    }

    /** 尝试默认端口 5555 */
    private void tryDefaultPorts() {
        runOnUiThread(() -> {
            if (tvAdbStatus != null) {
                tvAdbStatus.setText("🔌 adb：正在检测本机端口...");
            }
        });

        // 先检测端口是否可用
        new Thread(() -> {
            int defaultPort = 5555;
            boolean portAvailable = false;
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", defaultPort), 500);
                socket.close();
                portAvailable = true;
            } catch (Exception ignored) {}

            if (!portAvailable) {
                runOnUiThread(() -> {
                    tvAdbStatus.setText("🔌 adb：未开启无线调试");
                    tvAdbStatus.setTextColor(0xFF95A5A6);
                });
                return;
            }

            // 端口可用，尝试 ADB 连接
            AdbHelper.get().connect("127.0.0.1", defaultPort, connected -> {
                if (connected) {
                    // 连接成功，保存为最后连接
                    saveLastAdbHost("127.0.0.1", defaultPort);
                    runOnUiThread(() -> {
                        isAdbConnected = true;
                        tvAdbStatus.setText("🔗 adb：已连接 127.0.0.1:" + defaultPort);
                        tvAdbStatus.setTextColor(0xFF27AE60);
                        Toast.makeText(this, "✅ 已自动连接无线ADB", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(() -> {
                        tvAdbStatus.setText("🔌 adb：连接失败，请开启无线调试");
                        tvAdbStatus.setTextColor(0xFFE74C3C);
                    });
                }
            });
        }).start();
    }

    /** 保存最后连接的 ADB 设备信息 */
    private static final String PREF_ADB = "adb_last_device";
    private static final String KEY_LAST_HOST = "last_host";
    private static final String KEY_LAST_PORT = "last_port";

    private void saveLastAdbHost(String host, int port) {
        getSharedPreferences(PREF_ADB, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_HOST, host)
            .putInt(KEY_LAST_PORT, port)
            .apply();
    }

    private String getLastAdbHost() {
        return getSharedPreferences(PREF_ADB, Context.MODE_PRIVATE)
            .getString(KEY_LAST_HOST, null);
    }

    private int getLastAdbPort() {
        return getSharedPreferences(PREF_ADB, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_PORT, 0);
    }

    /** 设备信息弹窗（TV适配：宽度取屏幕55%，最小500dp） */
    private void showDeviceInfoDialog() {
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        android.view.View dialogView = inflater.inflate(R.layout.dialog_device_info, null);

        android.widget.TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        android.widget.LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        android.widget.TextView tvPositive = dialogView.findViewById(R.id.tvPositive);

        // 构建设备信息
        StringBuilder sb = new StringBuilder();
        sb.append("📱 品牌：").append(Build.BRAND).append("\n");
        sb.append("📦 型号：").append(Build.MODEL).append("\n");
        sb.append("🏭 厂商：").append(Build.MANUFACTURER).append("\n\n");
        sb.append("🤖 Android：").append(Build.VERSION.RELEASE)
          .append("  (API ").append(Build.VERSION.SDK_INT).append(")\n");
        // 系统版本若过长则分两行显示
        String displayBuild = Build.DISPLAY;
        sb.append("🔧 系统版本：");
        if (displayBuild.length() > 30) {
            sb.append("\n    ").append(displayBuild);
        } else {
            sb.append(displayBuild);
        }
        sb.append("\n\n");

        // 内存
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo memInfo = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);
            long totalMem = memInfo.totalMem / (1024 * 1024);
            long availMem = memInfo.availMem / (1024 * 1024);
            sb.append("💾 总内存：").append(totalMem).append(" MB\n");
            sb.append("🟢 可用内存：").append(availMem).append(" MB\n\n");
        } catch (Exception e) {
            sb.append("💾 内存：获取失败\n\n");
        }

        // 存储
        try {
            android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            long total = stat.getTotalBytes() / (1024 * 1024 * 1024);
            long free = stat.getFreeBytes() / (1024 * 1024 * 1024);
            sb.append("🗄 存储总量：").append(total).append(" GB\n");
            sb.append("🟢 可用存储：").append(free).append(" GB\n\n");
        } catch (Exception e) {
            sb.append("🗄 存储：获取失败\n\n");
        }

        // IP
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        sb.append("🌐 ").append(iface.getName()).append("：")
                          .append(addr.getHostAddress()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("🌐 IP地址：获取失败\n");
        }

        sb.append("\n🔗 ADB状态：").append(isAdbConnected ? "已连接" : "未连接");
        tvMessage.setText(sb.toString().trim());

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, R.style.DialogRoundedStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);
        android.app.AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(android.view.Gravity.CENTER);
        }

        btnPositive.setOnClickListener(v -> dialog.dismiss());

        // 必须先 show() 再 setLayout()，否则 window 尚未附着，设置无效
        dialog.show();

        if (dialog.getWindow() != null) {
            float density = getResources().getDisplayMetrics().density;
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            // 宽度：屏幕55%，但不低于500dp
            int minWidth = (int)(500 * density);
            int targetWidth = (int)(screenWidth * 0.55f);
            int dialogWidth = Math.max(minWidth, targetWidth);
            dialog.getWindow().setLayout(dialogWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 执行ADB连接 */
    private void doAdbConnect(String host, int port) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在连接 " + host + ":" + port + " ...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(false);
        pd.show();

        AdbHelper.get().connect(host, port, ok -> {
            pd.dismiss();
            runOnUiThread(() -> {
                if (ok) {
                    // 保存为最后连接的设备
                    saveLastAdbHost(host, port);
                    Toast.makeText(this, "✅ ADB连接成功！", Toast.LENGTH_LONG).show();
                    checkAdbStatus();
                } else {
                    Toast.makeText(this, "❌ 连接失败，请确认设备IP和端口", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 执行无线配对 - 支持取消 */
    private volatile boolean isPairingCancelled = false;
    private void doWirelessPair(String host, int port, String code) {
        isPairingCancelled = false;
        
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在配对...\n请等待...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(true);
        pd.setOnCancelListener(dialog -> {
            isPairingCancelled = true;
            Toast.makeText(this, "配对已取消", Toast.LENGTH_SHORT).show();
        });
        pd.show();

        AdbHelper.get().pair(host, port, code, pairOk -> {
            if (isPairingCancelled) return;
            
            if (!pairOk) {
                pd.dismiss();
                runOnUiThread(() -> {
                    Toast.makeText(this, "❌ 配对失败，请检查配对码", Toast.LENGTH_LONG).show();
                    checkAdbStatus();
                });
                return;
            }

            if (isPairingCancelled) return;

            pd.dismiss();
            runOnUiThread(() -> {
                Toast.makeText(this, "✅ 配对成功，请使用连接端口手动连接", Toast.LENGTH_LONG).show();
                checkAdbStatus();
            });
        });
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
            set.setDuration(150);
            set.start();
        });
    }

    /** 应用适配器 - 支持动态数据源 */
    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {

        /** 当前显示的应用列表（由外部设置） */
        private List<AppInfo> displayList = installedApps;

        void setAppList(List<AppInfo> list) {
            this.displayList = list;
        }

        List<AppInfo> getDisplayList() {
            return displayList;
        }

        @Override
        public AppViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
            return new AppViewHolder(view);
        }

        @Override
        public void onBindViewHolder(AppViewHolder holder, int position) {
            if (position < 0 || position >= displayList.size()) return;
            AppInfo app = displayList.get(position);
            holder.bind(app, position);
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        class AppViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            ImageView ivSystemBadge;
            TextView tvLabel;
            TextView tvCheckmark;
            View viewSelectedBg;
            LinearLayout mainContent;

            AppViewHolder(View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.ivAppIcon);
                ivSystemBadge = itemView.findViewById(R.id.ivSystemBadge);
                tvLabel = itemView.findViewById(R.id.tvAppLabel);
                tvCheckmark = itemView.findViewById(R.id.tvCheckmark);
                viewSelectedBg = itemView.findViewById(R.id.viewSelectedBg);
                mainContent = (LinearLayout) itemView.findViewById(R.id.mainContent);

                // 遥控器焦点：仅外发光高亮，批量模式下也不冲突
                itemView.setOnFocusChangeListener((v, hasFocus) -> {
                    float scale = hasFocus ? 1.10f : 1.0f;
                    AnimatorSet set = new AnimatorSet();
                    set.playTogether(
                        ObjectAnimator.ofFloat(v, "scaleX", scale),
                        ObjectAnimator.ofFloat(v, "scaleY", scale)
                    );
                    set.setDuration(120);
                    set.start();
                    // 焦点高亮使用外发光背景
                    if (mainContent != null) {
                        if (hasFocus) {
                            mainContent.setBackgroundResource(R.drawable.focus_glow_effect);
                        } else {
                            int position = getAdapterPosition();
                            if (position != RecyclerView.NO_POSITION && position >= 0 && position < displayList.size()) {
                                if (isBatchMode && selectedApps.contains(displayList.get(position))) {
                                    mainContent.setBackgroundResource(R.drawable.item_app_selected);
                                } else {
                                    mainContent.setBackgroundResource(R.drawable.item_app_normal);
                                }
                            } else {
                                mainContent.setBackgroundResource(R.drawable.item_app_normal);
                            }
                        }
                    }
                });
            }

            void bind(AppInfo app, int position) {
                ivIcon.setImageDrawable(null);
                try {
                    Drawable icon = getPackageManager().getApplicationIcon(app.packageName);
                    ivIcon.setImageDrawable(icon);
                } catch (Exception e) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
                }

                tvLabel.setText(app.label);
                ivSystemBadge.setVisibility(app.isSystem ? View.VISIBLE : View.GONE);

                boolean isSelected = selectedApps.contains(app);

                if (isBatchMode) {
                    tvCheckmark.setVisibility(isSelected ? View.VISIBLE : View.GONE);
                    viewSelectedBg.setVisibility(View.GONE);
                    if (mainContent != null) {
                        mainContent.setBackgroundResource(isSelected
                            ? R.drawable.item_app_selected
                            : R.drawable.item_app_normal);
                    }
                    float scale = isSelected ? 1.06f : 1.0f;
                    itemView.setScaleX(scale);
                    itemView.setScaleY(scale);
                } else {
                    tvCheckmark.setVisibility(View.GONE);
                    viewSelectedBg.setVisibility(View.GONE);
                    if (mainContent != null) {
                        mainContent.setBackgroundResource(R.drawable.item_app_normal);
                    }
                    itemView.setScaleX(1.0f);
                    itemView.setScaleY(1.0f);
                }

                itemView.setTag(position);
                itemView.setOnClickListener(v -> {
                    int pos = (int) v.getTag();
                    if (pos < 0 || pos >= displayList.size()) return;
                    AppInfo clickedApp = displayList.get(pos);

                    if (isBatchMode) {
                        animateSelection(v, selectedApps.contains(clickedApp));
                        toggleAppSelection(clickedApp);
                    } else {
                        showAppActions(clickedApp);
                    }
                });

                itemView.setOnLongClickListener(v -> {
                    int pos = (int) v.getTag();
                    if (pos < 0 || pos >= displayList.size()) return false;
                    AppInfo clickedApp = displayList.get(pos);
                    if (!isBatchMode) {
                        showAppActions(clickedApp);
                    } else {
                        toggleAppSelection(clickedApp);
                    }
                    return true;
                });
            }

            private void animateSelection(View v, boolean wasSelected) {
                float targetScale = wasSelected ? 1.0f : 1.06f;
                AnimatorSet set = new AnimatorSet();
                set.playTogether(
                    ObjectAnimator.ofFloat(v, "scaleX", targetScale),
                    ObjectAnimator.ofFloat(v, "scaleY", targetScale)
                );
                set.setDuration(120);
                set.start();
            }
        }
    }

    // ===================== 配对对话框 =====================

    /** 显示无线配对对话框 */
    private void showWirelessPairDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_adb_pair, null);
        
        EditText etHost = dialogView.findViewById(R.id.etHost);
        EditText etPort = dialogView.findViewById(R.id.etPort);
        EditText etCode = dialogView.findViewById(R.id.etCode);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);

        // 默认填入本机地址
        etHost.setText("127.0.0.1");
        etPort.setText("5555");

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, R.style.DialogStandardStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);
        android.app.AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int w = (int) (420 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setLayout(w, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        btnPositive.setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            int port = 5555;
            try { port = Integer.parseInt(portStr.isEmpty() ? "5555" : portStr); } catch (Exception ignored) {}

            if (host.isEmpty()) {
                Toast.makeText(this, "⚠️ 请输入目标设备IP", Toast.LENGTH_SHORT).show();
                return;
            }

            dialog.dismiss();
            if (!code.isEmpty()) {
                // 有配对码 → 先配对再连接
                doWirelessPair(host, port, code);
            } else {
                // 无配对码 → 直接连接（已配对设备）
                doAdbConnect(host, port);
            }
        });

        btnNegative.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // 菜单键切换ADB面板和应用列表
            if (appGridView.getVisibility() == View.VISIBLE) {
                showAdbPanel();
            } else {
                showAppsByCategory(AppCategory.ALL);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 取消所有正在进行的 AsyncTask，防止内存泄漏 */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 取消加载已禁用应用的 AsyncTask
        if (currentDisableTask != null && !currentDisableTask.isCancelled()) {
            currentDisableTask.cancel(true);
            currentDisableTask = null;
        }
        
        // 取消弹窗引用
        if (popupDialog != null && popupDialog.isShowing()) {
            popupDialog.dismiss();
            popupDialog = null;
        }
    }
}