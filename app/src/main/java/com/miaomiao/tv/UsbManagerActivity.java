package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * U盘管理器 Activity
 * 1. 监听 USB 插拔广播（主动弹窗提醒）
 * 2. 浏览 U 盘文件，支持重命名/删除
 * 3. 适配 TV 遥控器 D-pad 操作
 */
public class UsbManagerActivity extends AppCompatActivity {

    public static final String ACTION_USB_PERMISSION =
            "com.miaomiao.tv.USB_PERMISSION";

    private LinearLayout btnBackHome;
    private LinearLayout btnParentDir;
    private LinearLayout btnRename;
    private LinearLayout btnDelete;
    private LinearLayout btnRefresh;
    private TextView tvCurrentPath;
    private TextView tvUsbIcon;
    private LinearLayout usbEmptyView;
    private ScrollView scrollView;
    private LinearLayout fileListContainer;

    /** 当前目录 */
    private File currentDir;
    /** 所有文件项 View */
    private final List<View> fileItemViews = new ArrayList<>();
    /** 当前选中的文件索引 */
    private int selectedIndex = -1;
    /** 所有文件/文件夹（排序后） */
    private File[] sortedFiles;

    /** USB 广播接收器 */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_CHECKING.equals(action)) {
                // 有存储设备插入，检查是否是 U 盘
                refreshUsbState();
            } else if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_REMOVED.equals(action)) {
                // 存储设备移除
                onUsbRemoved();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_manager);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        detectAndNavigateToUsb();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注册 USB 广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_CHECKING);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        ContextCompat.registerReceiver(this, usbReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }

    private void initViews() {
        btnBackHome       = findViewById(R.id.btnBackHome);
        btnParentDir      = findViewById(R.id.btnParentDir);
        btnRename         = findViewById(R.id.btnRename);
        btnDelete         = findViewById(R.id.btnDelete);
        btnRefresh        = findViewById(R.id.btnRefresh);
        tvCurrentPath     = findViewById(R.id.tvCurrentPath);
        tvUsbIcon         = findViewById(R.id.tvUsbIcon);
        usbEmptyView      = findViewById(R.id.usbEmptyView);
        scrollView        = findViewById(R.id.scrollView);
        fileListContainer = findViewById(R.id.fileListContainer);

        btnBackHome.setOnClickListener(v -> finish());
        btnParentDir.setOnClickListener(v -> {
            File parent = currentDir != null ? currentDir.getParentFile() : null;
            if (parent != null) navigateTo(parent);
        });
        btnRefresh.setOnClickListener(v -> detectAndNavigateToUsb());

        btnRename.setOnClickListener(v -> {
            if (selectedIndex < 0 || sortedFiles == null || selectedIndex >= sortedFiles.length) {
                Toast.makeText(this, "请先选择一个文件或文件夹", Toast.LENGTH_SHORT).show();
                return;
            }
            showRenameDialog(sortedFiles[selectedIndex]);
        });

        btnDelete.setOnClickListener(v -> {
            if (selectedIndex < 0 || sortedFiles == null || selectedIndex >= sortedFiles.length) {
                Toast.makeText(this, "请先选择一个文件或文件夹", Toast.LENGTH_SHORT).show();
                return;
            }
            showDeleteConfirm(sortedFiles[selectedIndex]);
        });

        attachFocusScale(btnBackHome,  1.10f);
        attachFocusScale(btnParentDir, 1.10f);
        attachFocusScale(btnRename,    1.10f);
        attachFocusScale(btnDelete,    1.10f);
        attachFocusScale(btnRefresh,   1.10f);

        btnBackHome.requestFocus();
    }

    // ===================== USB 检测 =====================

    /**
     * 扫描并跳转到 U 盘（如果有多个取第一个外部存储）
     */
    private void detectAndNavigateToUsb() {
        File usbRoot = findUsbDrive();
        if (usbRoot != null) {
            navigateTo(usbRoot);
            tvUsbIcon.setText("\uD83D\uDCBB"); // 💻
            usbEmptyView.setVisibility(View.GONE);
        } else {
            showUsbEmpty();
        }
    }

    /**
     * 查找 U 盘挂载路径
     * 优先找 /storage/<uuid> 类型（非内部存储）的外部 SD 卡 / USB 设备
     */
    private File findUsbDrive() {
        File storageRoot = new File("/storage");
        if (!storageRoot.exists()) return null;

        File[] mounts = storageRoot.listFiles();
        if (mounts == null) return null;

        String internalPath = Environment.getExternalStorageDirectory().getAbsolutePath();

        for (File mount : mounts) {
            if (!mount.isDirectory() || !mount.canRead()) continue;
            String path = mount.getAbsolutePath();
            // 排除内部存储本身
            if (path.equals(internalPath)) continue;
            // 排除 emulated（模拟存储）
            if (path.contains("emulated")) continue;
            // 排除 self（自己的内部存储视图）
            if (mount.getName().equals("self")) continue;

            // 优先找外部 SD 或 USB
            String name = mount.getName().toLowerCase();
            if (name.contains("sd") || name.contains("usb") || name.contains("external")) {
                // 确认有可读内容（检查是否真的挂载了）
                File[] test = mount.listFiles();
                if (test != null || mount.canRead()) {
                    return mount;
                }
            }
        }

        // fallback：如果没有明确 USB，找第一个非内部存储的挂载点
        for (File mount : mounts) {
            if (!mount.isDirectory() || !mount.canRead()) continue;
            String path = mount.getAbsolutePath();
            if (path.equals(internalPath) || path.contains("emulated") || mount.getName().equals("self")) continue;
            File[] test = mount.listFiles();
            if (test != null || mount.canRead()) {
                return mount;
            }
        }

        return null;
    }

    private void onUsbRemoved() {
        currentDir = null;
        sortedFiles = new File[0];
        fileItemViews.clear();
        fileListContainer.removeAllViews();
        selectedIndex = -1;
        showUsbEmpty();
    }

    private void showUsbEmpty() {
        tvUsbIcon.setText("\uD83D\uDCE6"); // 📦
        tvCurrentPath.setText("未检测到 U 盘");
        usbEmptyView.setVisibility(View.VISIBLE);
        scrollView.setVisibility(View.GONE);
    }

    private void refreshUsbState() {
        File usb = findUsbDrive();
        if (usb != null) {
            navigateTo(usb);
            usbEmptyView.setVisibility(View.GONE);
            scrollView.setVisibility(View.VISIBLE);
        } else {
            showUsbEmpty();
        }
    }

    // ===================== 目录浏览 =====================

    private void navigateTo(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "目录不可访问", Toast.LENGTH_SHORT).show();
            return;
        }
        currentDir = dir;
        tvCurrentPath.setText(dir.getAbsolutePath());
        usbEmptyView.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
        refresh();
    }

    private void refresh() {
        fileItemViews.clear();
        fileListContainer.removeAllViews();
        selectedIndex = -1;

        if (currentDir == null) {
            detectAndNavigateToUsb();
            return;
        }

        File[] files = currentDir.listFiles();
        if (files == null) files = new File[0];

        List<File> folderList = new ArrayList<>();
        List<File> fileList = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory()) folderList.add(f);
            else fileList.add(f);
        }
        Collections.sort(folderList, (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));
        Collections.sort(fileList,   (a, b) -> a.getName().toLowerCase().compareTo(b.getName().toLowerCase()));

        List<File> all = new ArrayList<>(folderList);
        all.addAll(fileList);
        sortedFiles = all.toArray(new File[0]);

        LayoutInflater inflater = LayoutInflater.from(this);

        if (sortedFiles.length == 0) {
            TextView emptyHint = new TextView(this);
            emptyHint.setText("\uD83D\uDCC1 U \u76D8\u4E3A\u7A7A");
            emptyHint.setTextSize(16);
            emptyHint.setTextColor(0xFF556688);
            emptyHint.setGravity(android.view.Gravity.CENTER);
            emptyHint.setPadding(0, 60, 0, 60);
            fileListContainer.addView(emptyHint);
        }

        for (int i = 0; i < sortedFiles.length; i++) {
            File f = sortedFiles[i];
            View itemView = inflater.inflate(R.layout.item_file, fileListContainer, false);

            TextView tvIcon = itemView.findViewById(R.id.tvIcon);
            TextView tvName = itemView.findViewById(R.id.tvFileName);
            TextView tvSize = itemView.findViewById(R.id.tvFileSize);

            tvName.setText(f.getName());

            if (f.isDirectory()) {
                tvIcon.setText("\uD83D\uDCC1"); // 📁
                tvSize.setText("文件夹");
            } else {
                tvIcon.setText(getFileIcon(f.getName()));
                tvSize.setText(formatFileSize(f.length()));
            }

            final int idx = i;
            itemView.setOnClickListener(v -> {
                File clicked = sortedFiles[idx];
                if (clicked.isDirectory()) navigateTo(clicked);
                else openFile(clicked);
            });

            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        File clicked = sortedFiles[idx];
                        if (clicked.isDirectory()) navigateTo(clicked);
                        else openFile(clicked);
                        return true;
                    }
                }
                return false;
            });

            attachFocusScale(itemView, 1.04f);
            fileItemViews.add(itemView);
            fileListContainer.addView(itemView);
        }
    }

    private void setSelected(int idx) {
        if (selectedIndex >= 0 && selectedIndex < fileItemViews.size()) {
            View old = fileItemViews.get(selectedIndex);
            old.setScaleX(1.0f);
            old.setScaleY(1.0f);
            old.setElevation(4f);
        }
        selectedIndex = idx;
        if (idx >= 0 && idx < fileItemViews.size()) {
            View v = fileItemViews.get(idx);
            v.setScaleX(1.06f);
            v.setScaleY(1.06f);
            v.setElevation(16f);
            v.requestFocus();
            scrollView.requestChildFocus(v, v);
        }
    }

    // ===================== D-pad 导航 =====================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                if (selectedIndex > 0) setSelected(selectedIndex - 1);
                else if (selectedIndex == -1 && !fileItemViews.isEmpty()) setSelected(0);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (selectedIndex < fileItemViews.size() - 1) setSelected(selectedIndex + 1);
                else if (selectedIndex == -1 && !fileItemViews.isEmpty()) setSelected(0);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                if (selectedIndex >= 0 && selectedIndex < sortedFiles.length) {
                    File f = sortedFiles[selectedIndex];
                    if (f.isDirectory()) navigateTo(f);
                    else openFile(f);
                }
                return true;
            }
            case KeyEvent.KEYCODE_BACK: {
                if (currentDir != null) {
                    File parent = currentDir.getParentFile();
                    if (parent != null) navigateTo(parent);
                    else finish();
                    return true;
                }
                break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===================== 文件操作 =====================

    private void showRenameDialog(File file) {
        EditText et = new EditText(this);
        et.setText(file.getName());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(48, 24, 48, 24);
        et.selectAll();
        new AlertDialog.Builder(this)
            .setTitle("\u270F\uFE0F 重命名")
            .setMessage("当前：" + file.getName())
            .setView(et)
            .setPositiveButton("确定", (d, w) -> {
                String newName = et.getText().toString().trim();
                if (newName.isEmpty() || newName.equals(file.getName())) return;
                File dest = new File(currentDir, newName);
                if (dest.exists()) {
                    Toast.makeText(this, "同名文件已存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (file.renameTo(dest)) {
                    Toast.makeText(this, "重命名成功", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showDeleteConfirm(File file) {
        String msg = file.isDirectory()
                ? "确认删除文件夹及其所有内容？\n\n" + file.getName()
                : "确认删除文件？\n\n" + file.getName();
        new AlertDialog.Builder(this)
            .setTitle("\uD83D\uDDD1\uFE0F 确认删除")
            .setMessage(msg)
            .setPositiveButton("删除", (d, w) -> {
                boolean ok = deleteRecursively(file);
                Toast.makeText(this, ok ? "删除成功" : "删除失败", Toast.LENGTH_SHORT).show();
                if (ok) refresh();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return f.delete();
    }

    private void openFile(File file) {
        try {
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            String mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(getExtension(file.getName()));
            if (mime != null) {
                intent.setDataAndType(androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file), mime);
            } else {
                intent.setDataAndType(androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file), "*/*");
            }
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开该类型文件", Toast.LENGTH_SHORT).show();
        }
    }

    // ===================== 工具方法 =====================

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String getFileIcon(String name) {
        String ext = getExtension(name);
        switch (ext) {
            case "mp4": case "mkv": case "avi": case "mov": case "wmv": case "flv":
            case "webm": case "m4v": case "3gp":
                return "\uD83C\uDFAC";  // 🎬
            case "mp3": case "wav": case "aac": case "flac": case "ogg": case "m4a":
                return "\uD83C\uDFB5";  // 🎵
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "webp":
                return "\uD83D\uDDBC\uFE0F";  // 🖼️
            case "pdf": return "\uD83D\uDCC4";  // 📄
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return "\uD83D\uDCE6";  // 📦
            case "apk": return "\uD83D\uDCF0";  // 📱
            default: return "\uD83D\uDCC4";  // 📄
        }
    }

    private void attachFocusScale(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            float target = hasFocus ? scale : 1.0f;
            float elevTarget = hasFocus ? 20f : 4f;
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", target),
                ObjectAnimator.ofFloat(view, "scaleY", target),
                ObjectAnimator.ofFloat(view, "elevation", elevTarget)
            );
            set.setDuration(150);
            set.start();
        });
    }
}
