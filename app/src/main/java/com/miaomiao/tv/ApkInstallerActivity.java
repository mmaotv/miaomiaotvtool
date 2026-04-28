package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 内置 APK 安装器 Activity
 *
 * 功能：
 *  - 解析 APK 基础信息（应用名、包名、版本、大小、SDK要求、权限数、签名摘要）
 *  - 检测是否已安装及版本差异，显示「升级 / 降级 / 重装」提示
 *  - 一键安装 / 取消
 *  - Android 8.0+ 自动引导开启「未知来源」安装权限
 *  - 适配 TV 遥控器 D-pad 操作
 */
public class ApkInstallerActivity extends AppCompatActivity {

    private static final int REQUEST_INSTALL_UNKNOWN = 1002;

    // ===== Views =====
    private ImageView   ivIcon;
    private TextView    tvAppName;
    private TextView    tvPackageName;
    private TextView    tvVersion;
    private TextView    tvInstallStatus;   // 已安装 / 未安装 / 升级 / 降级
    private TextView    tvSize;
    private TextView    tvMinSdk;
    private TextView    tvPerms;
    private TextView    tvSignature;
    private TextView    tvStatus;
    private ProgressBar progressBar;
    private Button      btnInstall;
    private Button      btnCancel;
    private LinearLayout btnBack;

    // ===== 数据 =====
    private String      filePath;
    private String      fileName;
    private PackageInfo packageInfo;
    private boolean     hasParsed = false;
    private long        fileSize  = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 沉浸式：隐藏系统UI
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(R.layout.activity_apk_installer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initViews();
        handleIntent();
    }

    // ===================== 初始化 =====================

    private void initViews() {
        ivIcon          = findViewById(R.id.ivApkIcon);
        tvAppName       = findViewById(R.id.tvAppName);
        tvPackageName   = findViewById(R.id.tvPackageName);
        tvVersion       = findViewById(R.id.tvVersion);
        tvInstallStatus = findViewById(R.id.tvInstallStatus);
        tvSize          = findViewById(R.id.tvSize);
        tvMinSdk        = findViewById(R.id.tvMinSdk);
        tvPerms         = findViewById(R.id.tvPerms);
        tvSignature     = findViewById(R.id.tvSignature);
        tvStatus        = findViewById(R.id.tvStatus);
        progressBar     = findViewById(R.id.progressBar);
        btnInstall      = findViewById(R.id.btnInstall);
        btnCancel       = findViewById(R.id.btnCancel);
        btnBack         = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());
        btnInstall.setOnClickListener(v -> installApk());
        btnCancel.setOnClickListener(v -> finish());

        attachFocusScale(btnInstall, 1.08f);
        attachFocusScale(btnCancel,  1.08f);

        tvStatus.setText("正在解析 APK...");
        progressBar.setVisibility(View.VISIBLE);
        btnInstall.setEnabled(false);
        btnInstall.requestFocus();
    }

    // ===================== Intent 处理 =====================

    private void handleIntent() {
        Intent intent = getIntent();
        filePath = intent.getStringExtra("file_path");
        fileName = intent.getStringExtra("file_name");

        if (filePath == null) {
            Uri uri = intent.getData();
            if (uri != null) {
                filePath = uri.getPath();
                fileName = uri.getLastPathSegment();
            }
        }

        if (filePath == null) {
            Toast.makeText(this, "无效的文件路径", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            Toast.makeText(this, "文件不存在: " + filePath, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        fileSize = file.length();
        tvSize.setText(formatFileSize(fileSize));

        if (fileName == null) fileName = file.getName();
        tvAppName.setText(fileName); // 先用文件名占位

        parseApkInfo(filePath);
    }

    // ===================== 解析 APK =====================

    private void parseApkInfo(String path) {
        new AsyncTask<String, Void, PackageInfo>() {
            private Drawable icon;
            private String   signStr;
            private String   installedVersionName;
            private long     installedVersionCode = -1;

            @Override
            protected PackageInfo doInBackground(String... params) {
                try {
                    String apkPath = params[0];
                    PackageManager pm = getPackageManager();

                    PackageInfo pkgInfo = pm.getPackageArchiveInfo(apkPath,
                            PackageManager.GET_PERMISSIONS);
                    if (pkgInfo == null) return null;

                    pkgInfo.applicationInfo.sourceDir      = apkPath;
                    pkgInfo.applicationInfo.publicSourceDir = apkPath;
                    icon    = pm.getApplicationIcon(pkgInfo.applicationInfo);
                    signStr = calcFileHash(apkPath);

                    // 检查是否已安装
                    try {
                        PackageInfo installed = pm.getPackageInfo(pkgInfo.packageName, 0);
                        installedVersionName = installed.versionName;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            installedVersionCode = installed.getLongVersionCode();
                        } else {
                            installedVersionCode = installed.versionCode;
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                        // 未安装
                    }
                    return pkgInfo;
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(PackageInfo pkgInfo) {
                progressBar.setVisibility(View.GONE);
                if (pkgInfo != null) {
                    packageInfo = pkgInfo;
                    hasParsed   = true;
                    displayApkInfo(pkgInfo, icon, signStr,
                            installedVersionName, installedVersionCode);
                    btnInstall.setEnabled(true);
                    btnInstall.requestFocus();
                } else {
                    tvStatus.setText("⚠ 解析失败，文件可能损坏");
                    tvStatus.setVisibility(View.VISIBLE);
                    btnInstall.setEnabled(false);
                }
            }
        }.execute(path);
    }

    // ===================== 展示信息 =====================

    private void displayApkInfo(PackageInfo pkgInfo, Drawable icon, String signature,
                                 String installedVer, long installedVerCode) {
        tvStatus.setVisibility(View.GONE);

        // 图标
        if (icon != null) ivIcon.setImageDrawable(icon);

        // 应用名
        String appName = pkgInfo.applicationInfo
                .loadLabel(getPackageManager()).toString();
        tvAppName.setText(appName);

        // 包名
        tvPackageName.setText(pkgInfo.packageName);

        // 版本号
        long newVerCode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            newVerCode = pkgInfo.getLongVersionCode();
        } else {
            newVerCode = pkgInfo.versionCode;
        }
        tvVersion.setText("v" + pkgInfo.versionName + "  (" + newVerCode + ")");

        // 安装状态提示
        if (installedVerCode < 0) {
            tvInstallStatus.setText("📦 未安装");
            tvInstallStatus.setTextColor(0xFF4CAF50);
            btnInstall.setText("安装");
        } else if (newVerCode > installedVerCode) {
            tvInstallStatus.setText("⬆ 升级  已安装: v" + installedVer);
            tvInstallStatus.setTextColor(0xFF2196F3);
            btnInstall.setText("升级安装");
        } else if (newVerCode < installedVerCode) {
            tvInstallStatus.setText("⬇ 降级  已安装: v" + installedVer);
            tvInstallStatus.setTextColor(0xFFFF9800);
            btnInstall.setText("降级安装");
        } else {
            tvInstallStatus.setText("♻ 重装  已安装: v" + installedVer);
            tvInstallStatus.setTextColor(0xFF9C27B0);
            btnInstall.setText("重新安装");
        }
        tvInstallStatus.setVisibility(View.VISIBLE);

        // SDK 版本
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tvMinSdk.setText("最低 API " + pkgInfo.applicationInfo.minSdkVersion
                    + "  (目标: API " + pkgInfo.applicationInfo.targetSdkVersion + ")");
        }

        // 权限
        if (pkgInfo.requestedPermissions != null && pkgInfo.requestedPermissions.length > 0) {
            tvPerms.setText(pkgInfo.requestedPermissions.length + " 个权限 (点击查看)");
            tvPerms.setOnClickListener(v -> showPermissionsDialog(pkgInfo.requestedPermissions));
        } else {
            tvPerms.setText("无特殊权限");
        }

        // 签名摘要（取前 20 字符显示）
        if (signature != null && signature.length() > 20) {
            tvSignature.setText("SHA-256: " + signature.substring(0, 20) + "…");
        } else if (signature != null) {
            tvSignature.setText("SHA-256: " + signature);
        }
    }

    /** 权限列表弹窗 */
    private void showPermissionsDialog(String[] permissions) {
        StringBuilder sb = new StringBuilder();
        for (String perm : permissions) {
            String name = perm.substring(perm.lastIndexOf('.') + 1);
            sb.append("• ").append(name).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("权限列表 (" + permissions.length + ")")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .show();
    }

    // ===================== 安装流程 =====================

    private void installApk() {
        if (!hasParsed) {
            Toast.makeText(this, "请等待解析完成", Toast.LENGTH_SHORT).show();
            return;
        }

        // Android 8.0+ 需要「安装未知来源」权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                requestInstallPermission();
                return;
            }
        }

        startInstall();
    }

    private void requestInstallPermission() {
        new AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("安装 APK 需要开启「安装未知来源应用」权限\n\n请在设置页面中为本应用开启该权限后返回。")
                .setPositiveButton("前往设置", (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_INSTALL_UNKNOWN);
                    } catch (Exception e) {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_UNKNOWN) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                startInstall();
            } else {
                Toast.makeText(this, "未获得安装权限，无法安装", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startInstall() {
        try {
            File apkFile = new File(filePath);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(apkFile);
            }
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "启动安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ===================== 遥控器 =====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (btnInstall.isFocused()) {
                    installApk();
                } else if (btnCancel.isFocused()) {
                    finish();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                btnInstall.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                btnCancel.requestFocus();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===================== 工具方法 =====================

    /** 计算文件前 1 MB 的 SHA-256（用于快速签名展示） */
    private String calcFileHash(String apkPath) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(apkPath)) {
                byte[] buf   = new byte[8192];
                int    len;
                int    total = 0;
                while ((len = fis.read(buf)) > 0 && total < 1024 * 1024) {
                    md.update(buf, 0, len);
                    total += len;
                }
            }
            byte[]        digest = md.digest();
            StringBuilder sb     = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String formatFileSize(long size) {
        if (size < 0) return "—";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024));
        return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private void attachFocusScale(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                    ObjectAnimator.ofFloat(view, "scaleX", hasFocus ? scale : 1.0f),
                    ObjectAnimator.ofFloat(view, "scaleY", hasFocus ? scale : 1.0f),
                    ObjectAnimator.ofFloat(view, "elevation", hasFocus ? 12f : 4f)
            );
            set.setDuration(150);
            set.start();
        });
    }
}
