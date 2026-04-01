package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件管理器 Activity
 * 支持：浏览本地文件、新建文件夹、重命名、删除
 * 适配 TV 遥控器操作
 */
public class FileManagerActivity extends AppCompatActivity {

    private LinearLayout btnBackHome;
    private LinearLayout btnParentDir;
    private LinearLayout btnNewFolder;
    private LinearLayout btnRename;
    private LinearLayout btnDelete;
    private LinearLayout btnRefresh;
    private TextView tvCurrentPath;
    private ScrollView scrollView;
    private LinearLayout fileListContainer;

    /** 当前目录 */
    private File currentDir;
    /** 所有文件项 View */
    private final List<View> fileItemViews = new ArrayList<>();
    /** 当前选中的文件索引（相对于当前显示的文件列表） */
    private int selectedIndex = -1;
    /** 所有文件/文件夹（排序后） */
    private File[] sortedFiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        // 从内部存储根目录开始
        File root = Environment.getExternalStorageDirectory();
        navigateTo(root);
    }

    private void initViews() {
        btnBackHome    = findViewById(R.id.btnBackHome);
        btnParentDir   = findViewById(R.id.btnParentDir);
        btnNewFolder   = findViewById(R.id.btnNewFolder);
        btnRename      = findViewById(R.id.btnRename);
        btnDelete      = findViewById(R.id.btnDelete);
        btnRefresh     = findViewById(R.id.btnRefresh);
        tvCurrentPath  = findViewById(R.id.tvCurrentPath);
        scrollView     = findViewById(R.id.scrollView);
        fileListContainer = findViewById(R.id.fileListContainer);

        // 返回首页
        btnBackHome.setOnClickListener(v -> finish());

        // 返回上级
        btnParentDir.setOnClickListener(v -> {
            File parent = currentDir.getParentFile();
            if (parent != null) navigateTo(parent);
        });

        // 刷新
        btnRefresh.setOnClickListener(v -> refresh());

        // 新建文件夹
        btnNewFolder.setOnClickListener(v -> showNewFolderDialog());

        // 重命名
        btnRename.setOnClickListener(v -> {
            if (selectedIndex < 0 || sortedFiles == null || selectedIndex >= sortedFiles.length) {
                Toast.makeText(this, "请先选择一个文件或文件夹", Toast.LENGTH_SHORT).show();
                return;
            }
            showRenameDialog(sortedFiles[selectedIndex]);
        });

        // 删除
        btnDelete.setOnClickListener(v -> {
            if (selectedIndex < 0 || sortedFiles == null || selectedIndex >= sortedFiles.length) {
                Toast.makeText(this, "请先选择一个文件或文件夹", Toast.LENGTH_SHORT).show();
                return;
            }
            showDeleteConfirm(sortedFiles[selectedIndex]);
        });

        // 给底部操作按钮加焦点动画
        attachFocusScale(btnBackHome,   1.10f);
        attachFocusScale(btnParentDir,  1.10f);
        attachFocusScale(btnNewFolder,  1.10f);
        attachFocusScale(btnRename,      1.10f);
        attachFocusScale(btnDelete,      1.10f);
        attachFocusScale(btnRefresh,     1.10f);

        // 初始时让 btnBackHome 获得焦点（不选任何文件）
        btnBackHome.requestFocus();
    }

    /**
     * 导航到指定目录
     */
    private void navigateTo(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        currentDir = dir;
        tvCurrentPath.setText(dir.getAbsolutePath());
        refresh();
    }

    /**
     * 刷新文件列表
     */
    private void refresh() {
        fileItemViews.clear();
        fileListContainer.removeAllViews();
        selectedIndex = -1;

        File[] files = currentDir.listFiles();
        if (files == null) files = new File[0];

        // 文件夹排前面，然后按名称排序（忽略大小写）
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
        for (int i = 0; i < sortedFiles.length; i++) {
            File f = sortedFiles[i];
            View itemView = inflater.inflate(R.layout.item_file, fileListContainer, false);

            TextView tvIcon    = itemView.findViewById(R.id.tvIcon);
            TextView tvName   = itemView.findViewById(R.id.tvFileName);
            TextView tvSize   = itemView.findViewById(R.id.tvFileSize);

            tvName.setText(f.getName());

            if (f.isDirectory()) {
                tvIcon.setText("\uD83D\uDCC1");       // 📁
                tvSize.setText("文件夹");
                tvSize.setVisibility(View.VISIBLE);
            } else {
                tvIcon.setText(getFileIcon(f.getName()));
                tvSize.setText(formatFileSize(f.length()));
                tvSize.setVisibility(View.VISIBLE);
            }

            // 点击进入文件夹
            final int idx = i;
            itemView.setOnClickListener(v -> {
                File clicked = sortedFiles[idx];
                if (clicked.isDirectory()) {
                    navigateTo(clicked);
                } else {
                    // 打开文件（尝试用系统 Intent）
                    openFile(clicked);
                }
            });

            // 遥控器确认键直接触发点击
            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        File clicked = sortedFiles[idx];
                        if (clicked.isDirectory()) {
                            navigateTo(clicked);
                        } else {
                            openFile(clicked);
                        }
                        return true;
                    }
                    if (keyCode == KeyEvent.KEYCODE_MENU) {
                        // 长按菜单：选中（高亮）
                        setSelected(idx);
                        return true;
                    }
                }
                return false;
            });

            attachFocusScale(itemView, 1.04f);
            fileItemViews.add(itemView);
            fileListContainer.addView(itemView);
        }

        // 顶部加个返回上级提示（如果不在根目录）
        if (currentDir.getParentFile() != null) {
            // 空列表提示
            if (sortedFiles.length == 0) {
                TextView emptyHint = new TextView(this);
                emptyHint.setText("\uD83D\uDCC1 当前目录为空");
                emptyHint.setTextSize(16);
                emptyHint.setTextColor(0xFF556688);
                emptyHint.setGravity(android.view.Gravity.CENTER);
                emptyHint.setPadding(0, 40, 0, 40);
                fileListContainer.addView(emptyHint);
            }
        } else {
            if (sortedFiles.length == 0) {
                TextView emptyHint = new TextView(this);
                emptyHint.setText("\uD83D\uDCC1 内部存储为空");
                emptyHint.setTextSize(16);
                emptyHint.setTextColor(0xFF556688);
                emptyHint.setGravity(android.view.Gravity.CENTER);
                emptyHint.setPadding(0, 40, 0, 40);
                fileListContainer.addView(emptyHint);
            }
        }
    }

    private void setSelected(int idx) {
        // 清除旧选中
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
            // 滚动到可见
            scrollView.requestChildFocus(v, v);
        }
    }

    // ===================== D-pad 导航 =====================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                if (selectedIndex > 0) {
                    setSelected(selectedIndex - 1);
                } else if (selectedIndex == -1) {
                    // 初始选中第一个文件
                    if (!fileItemViews.isEmpty()) {
                        setSelected(0);
                    }
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                if (selectedIndex < fileItemViews.size() - 1) {
                    setSelected(selectedIndex + 1);
                } else if (selectedIndex == -1 && !fileItemViews.isEmpty()) {
                    setSelected(0);
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                if (selectedIndex >= 0 && selectedIndex < sortedFiles.length) {
                    File f = sortedFiles[selectedIndex];
                    if (f.isDirectory()) {
                        navigateTo(f);
                    } else {
                        openFile(f);
                    }
                }
                return true;
            }
            case KeyEvent.KEYCODE_BACK: {
                File parent = currentDir.getParentFile();
                if (parent != null) {
                    navigateTo(parent);
                    return true;
                }
                break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ===================== 文件操作 =====================

    /** 新建文件夹 */
    private void showNewFolderDialog() {
        EditText et = new EditText(this);
        et.setHint("文件夹名称");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
            .setTitle("\uD83D\uDCC1 新建文件夹")
            .setView(et)
            .setPositiveButton("创建", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                File newDir = new File(currentDir, name);
                if (newDir.exists()) {
                    Toast.makeText(this, "同名文件夹已存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (newDir.mkdir()) {
                    Toast.makeText(this, "创建成功：" + name, Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(this, "创建失败，请检查权限", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 重命名 */
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
                    Toast.makeText(this, "重命名失败，请检查权限", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 删除确认 */
    private void showDeleteConfirm(File file) {
        String msg = file.isDirectory() ? "确认删除文件夹及其所有内容？\n\n" + file.getName()
                                              : "确认删除文件？\n\n" + file.getName();
        new AlertDialog.Builder(this)
            .setTitle("\uD83D\uDDD1\uFE0F 确认删除")
            .setMessage(msg)
            .setPositiveButton("删除", (d, w) -> {
                boolean ok = deleteRecursively(file);
                if (ok) {
                    Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(this, "删除失败，请检查权限", Toast.LENGTH_SHORT).show();
                }
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

    /** 用系统 Intent 打开文件 */
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
            case "mp3": case "wav": case "aac": case "flac": case "ogg":
            case "m4a": case "wma":
                return "\uD83C\uDFB5";  // 🎵
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "svg":
                return "\uD83D\uDDBC\uFE0F";  // 🖼️
            case "pdf":
                return "\uD83D\uDCC4";  // 📄
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return "\uD83D\uDCE6";  // 📦
            case "apk":
                return "\uD83D\uDCF0";  // 📱
            case "txt": case "log": case "md":
                return "\uD83D\uDCDD";  // 📝
            case "xml": case "json": case "html": case "css": case "js":
                return "\uD83D\uDCDD";  // 📝
            default:
                return "\uD83D\uDCC4";  // 📄
        }
    }

    /**
     * 焦点缩放动画
     */
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
