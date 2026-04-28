package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 关于页面
 * 功能：
 * 1. 显示 App Logo、名称、版本
 * 2. 显示简介、联系方式、GitHub 地址
 * 3. 点击按钮打开 GitHub 页面
 */
public class AboutActivity extends AppCompatActivity {

    private LinearLayout btnBack;
    private LinearLayout btnOpenGithub;
    private LinearLayout btnClose;
    private LinearLayout btnViewLogs;
    private TextView tvAppName;
    private TextView tvVersion;
    private TextView tvVersionDesc;
    private TextView tvAboutMessage;
    private TextView tvContact;
    private TextView tvGithub;
    private TextView tvCopyright;

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

        setContentView(R.layout.activity_about);

        initViews();
        initData();
        attachFocusEffects();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        btnOpenGithub = findViewById(R.id.btnOpenGithub);
        btnClose = findViewById(R.id.btnClose);
        btnViewLogs = findViewById(R.id.btnViewLogs);
        tvAppName = findViewById(R.id.tvAppName);
        tvVersion = findViewById(R.id.tvVersion);
        tvVersionDesc = findViewById(R.id.tvVersionDesc);
        tvAboutMessage = findViewById(R.id.tvAboutMessage);
        tvContact = findViewById(R.id.tvContact);
        tvGithub = findViewById(R.id.tvGithub);
        tvCopyright = findViewById(R.id.tvCopyright);

        // 返回按钮
        btnBack.setOnClickListener(v -> finish());

        // 打开 GitHub
        btnOpenGithub.setOnClickListener(v -> openGithub());

        // 日志按钮
        btnViewLogs.setOnClickListener(v -> showLogMenu());

        // 关闭按钮
        btnClose.setOnClickListener(v -> finish());
    }

    private void initData() {
        // App 名称
        String appName = getString(R.string.app_name);
        tvAppName.setText(appName);

        // 版本号
        String versionName = getVersionName();
        tvVersion.setText("版本 " + versionName);

        // 简介（从strings.xml读取，支持快速配置）
        String aboutMessage = getString(R.string.about_message);
        tvAboutMessage.setText(aboutMessage != null ? aboutMessage : "");

        // 版本描述
        tvVersionDesc.setText("本次升级新增扫码推送与U盘管理功能");

        // 联系方式（来自快速配置）
        String aboutContact = getString(R.string.about_contact);
        if (aboutContact != null && !aboutContact.isEmpty()) {
            tvContact.setText("📧 邮箱：" + aboutContact);
        } else {
            tvContact.setVisibility(View.GONE);
        }

        // GitHub 地址（来自快速配置）
        String aboutGithub = getString(R.string.about_github);
        if (aboutGithub != null && !aboutGithub.isEmpty()) {
            String displayUrl = aboutGithub;
            if (!displayUrl.startsWith("http://") && !displayUrl.startsWith("https://")) {
                displayUrl = "https://" + displayUrl;
            }
            tvGithub.setText("🔗 " + displayUrl);
            tvGithub.setTag(displayUrl);
        } else {
            tvGithub.setVisibility(View.GONE);
        }

        // 版权年份（动态）
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        tvCopyright.setText("© " + year + " " + appName);
    }

    private void attachFocusEffects() {
        // 返回按钮
        btnBack.setOnFocusChangeListener(createFocusListener(1.08f));
        btnOpenGithub.setOnFocusChangeListener(createFocusListener(1.08f));
        btnViewLogs.setOnFocusChangeListener(createFocusListener(1.08f));
        btnClose.setOnFocusChangeListener(createFocusListener(1.08f));
    }

    private View.OnFocusChangeListener createFocusListener(float scale) {
        return (view, hasFocus) -> {
            float targetScale = hasFocus ? scale : 1.0f;
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", targetScale),
                ObjectAnimator.ofFloat(view, "scaleY", targetScale)
            );
            set.setDuration(150);
            set.start();
        };
    }

    private void openGithub() {
        String githubUrl = (String) tvGithub.getTag();
        if (githubUrl == null || githubUrl.isEmpty()) {
            Toast.makeText(this, "GitHub 地址未配置", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 使用系统浏览器打开
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private String getVersionName() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.0";
        }
    }

    /** 显示日志菜单（查看/导出/清除） */
    private void showLogMenu() {
        String[] items = {"📋 查看日志", "📤 导出日志", "🗑 清除日志"};

        View dialogView = View.inflate(this, R.layout.dialog_list, null);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        tvTitle.setText("📋 日志功能");
        LinearLayout listContainer = dialogView.findViewById(R.id.listItemsContainer);

        // 动态创建列表项
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        int margin6 = (int) (6 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            View itemView = inflater.inflate(R.layout.item_dialog_list, listContainer, false);
            TextView tvItem = itemView.findViewById(R.id.itemText);
            tvItem.setText(items[i]);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, margin6);
            itemView.setLayoutParams(lp);
            itemView.setMinimumHeight((int) (48 * getResources().getDisplayMetrics().density));
            itemView.setOnClickListener(v -> onLogMenuItemClick(index));
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                float s = hasFocus ? 1.05f : 1.0f;
                v.setScaleX(s);
                v.setScaleY(s);
            });
            listContainer.addView(itemView);
        }

        // 添加取消按钮
        View divider = new View(this);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(0, margin6, 0, margin6);
        divider.setLayoutParams(divLp);
        divider.setBackgroundColor(0xFFE0E0E0);
        listContainer.addView(divider);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogStandardStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);
        AlertDialog logDialog = builder.create();

        View cancelView = inflater.inflate(R.layout.item_dialog_list, listContainer, false);
        TextView tvCancel = cancelView.findViewById(R.id.itemText);
        tvCancel.setText("取消");
        tvCancel.setTextColor(0xFF7F8C8D);
        cancelView.setMinimumHeight((int) (48 * getResources().getDisplayMetrics().density));
        cancelView.setOnClickListener(v -> logDialog.dismiss());
        listContainer.addView(cancelView);

        if (logDialog.getWindow() != null) {
            logDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            logDialog.getWindow().setGravity(Gravity.CENTER);
            int w = (int) (400 * getResources().getDisplayMetrics().density);
            logDialog.getWindow().setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        logDialog.show();
    }

    private void onLogMenuItemClick(int index) {
        switch (index) {
            case 0: // 查看日志
                showLogViewer();
                break;
            case 1: // 导出日志
                exportLogs();
                break;
            case 2: // 清除日志
                clearLogs();
                break;
        }
    }

    /** 查看日志内容 */
    private void showLogViewer() {
        View dialogView = View.inflate(this, R.layout.dialog_list, null);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        tvTitle.setText("📋 日志查看");
        LinearLayout listContainer = dialogView.findViewById(R.id.listItemsContainer);
        // 清空默认内容，添加日志文本视图
        listContainer.removeAllViews();

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        TextView tvLog = new TextView(this);
        tvLog.setTextSize(11f);
        tvLog.setTextColor(0xFF2C3E50);
        tvLog.setPadding(16, 16, 16, 16);
        tvLog.setHorizontallyScrolling(true);

        String appLog = AppLogger.get().getLogContent();
        String crashLog = AppLogger.get().getCrashLogContent();

        StringBuilder sb = new StringBuilder();
        sb.append("=== 应用日志 ===\n");
        if (appLog != null && !appLog.isEmpty()) {
            sb.append(appLog);
        } else {
            sb.append("(无日志)\n");
        }
        sb.append("\n=== 崩溃日志 ===\n");
        if (crashLog != null && !crashLog.isEmpty()) {
            sb.append(crashLog);
        } else {
            sb.append("(无崩溃日志)\n");
        }
        tvLog.setText(sb.toString());
        scrollView.addView(tvLog);
        listContainer.addView(scrollView);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogStandardStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);
        AlertDialog logDialog = builder.create();

        if (logDialog.getWindow() != null) {
            logDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            logDialog.getWindow().setGravity(Gravity.CENTER);
            int w = (int) (Math.min(600, getResources().getDisplayMetrics().widthPixels * 0.9));
            int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.6);
            logDialog.getWindow().setLayout(w, h);
        }
        logDialog.show();
    }

    /** 导出日志 */
    private void exportLogs() {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("正在导出日志...");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(true);
        pd.show();

        new Thread(() -> {
            String path = AppLogger.get().exportLogs();
            runOnUiThread(() -> {
                pd.dismiss();
                if (path != null) {
                    Toast.makeText(this, "✅ 日志已导出至：\n" + path, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "❌ 导出失败，请检查存储权限", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** 清除日志 */
    private void clearLogs() {
        new AlertDialog.Builder(this)
            .setTitle("确认清除日志？")
            .setMessage("将清除所有应用日志和崩溃日志，此操作不可恢复。")
            .setPositiveButton("清除", (d, w) -> {
                AppLogger.get().clearLogs();
                Toast.makeText(this, "✅ 日志已清除", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("取消", null)
            .show();
    }
}
