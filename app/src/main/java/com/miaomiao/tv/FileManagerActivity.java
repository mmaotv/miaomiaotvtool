package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
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
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件管理器 Activity
 * 支持：浏览本地文件、新建文件夹、重命名、删除、复制、移动
 * 适配 TV 遥控器操作
 * 长按 OK 键 / 菜单键 弹出文件操作菜单
 */
public class FileManagerActivity extends AppCompatActivity {

    private LinearLayout btnBackHome;
    private LinearLayout btnParentDir;
    private LinearLayout btnNewFolder;
    private LinearLayout btnRefresh;
    private TextView tvCurrentPath;
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
    /** 剪贴板：被复制的文件 */
    private File clipboardFile = null;
    /** 剪贴板模式：true=复制，false=移动 */
    private boolean clipboardCopy = true;

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

        // 给底部操作按钮加焦点动画
        attachFocusScale(btnBackHome,   1.10f);
        attachFocusScale(btnParentDir,  1.10f);
        attachFocusScale(btnNewFolder,  1.10f);
        attachFocusScale(btnRefresh,     1.10f);

        // 初始时让 btnBackHome 获得焦点
        btnBackHome.requestFocus();
    }

    /** 导航到指定目录 */
    private void navigateTo(File dir) {
        if (!dir.exists() || !dir.isDirectory()) {
            Toast.makeText(this, "目录不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        currentDir = dir;
        tvCurrentPath.setText(dir.getAbsolutePath());
        refresh();
    }

    /** 刷新文件列表 */
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
                tvIcon.setText("\uD83D\uDCC1");
                tvSize.setText("\u6587\u4EF6\u5939");
                tvSize.setVisibility(View.VISIBLE);
            } else {
                String ext = getExtension(f.getName()).toLowerCase();
                tvIcon.setText(getFileEmoji(ext));
                tvSize.setText(formatFileSize(f.length()));
                tvSize.setVisibility(View.VISIBLE);
                // 异步加载略缩图
                loadThumbnail(itemView, f, ext);
            }

            // 点击进入文件夹 / 打开文件
            final int idx = i;
            itemView.setOnClickListener(v -> {
                File clicked = sortedFiles[idx];
                if (clicked.isDirectory()) {
                    navigateTo(clicked);
                } else {
                    openFile(clicked);
                }
            });

            // 遥控器按键处理
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
                    // 菜单键：弹出操作菜单
                    if (keyCode == KeyEvent.KEYCODE_MENU) {
                        showContextMenu(sortedFiles[idx]);
                        return true;
                    }
                }
                return false;
            });

            attachFocusScale(itemView, 1.04f);
            fileItemViews.add(itemView);
            fileListContainer.addView(itemView);
        }

        // 空目录提示
        if (sortedFiles.length == 0) {
            TextView emptyHint = new TextView(this);
            String hint = currentDir.getParentFile() != null ? "\uD83D\uDCC1 \u5F53\u524D\u76EE\u5F55\u4E3A\u7A7A" : "\uD83D\uDCC1 \u5185\u90E8\u5B58\u50A8\u4E3A\u7A7A";
            emptyHint.setText(hint);
            emptyHint.setTextSize(16);
            emptyHint.setTextColor(0xFF556688);
            emptyHint.setGravity(android.view.Gravity.CENTER);
            emptyHint.setPadding(0, 40, 0, 40);
            fileListContainer.addView(emptyHint);
        }
    }

    /** 异步加载略缩图 */
    private void loadThumbnail(View itemView, File f, String ext) {
        ImageView ivThumb = itemView.findViewById(R.id.ivThumb);
        TextView tvIcon   = itemView.findViewById(R.id.tvIcon);

        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                try {
                    if (ext.equals("apk")) {
                        return getApkIcon(f);
                    } else if (ext.equals("jpg") || ext.equals("jpeg")
                            || ext.equals("png") || ext.equals("gif")
                            || ext.equals("bmp") || ext.equals("webp")) {
                        return getImageThumbnail(f, 128);
                    } else if (ext.equals("mp4") || ext.equals("mkv") || ext.equals("avi")
                            || ext.equals("mov") || ext.equals("wmv") || ext.equals("flv")
                            || ext.equals("webm") || ext.equals("m4v") || ext.equals("3gp")) {
                        return getVideoThumbnail(f, 128);
                    }
                } catch (Exception e) { /* ignore */ }
                return null;
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (bitmap != null) {
                    ivThumb.setImageBitmap(bitmap);
                    ivThumb.setVisibility(View.VISIBLE);
                    tvIcon.setVisibility(View.GONE);
                }
            }
        }.execute();
    }

    /** 提取 APK 图标 */
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
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /** 图片略缩图 */
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

    /** 视频略缩图 */
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

    // ===================== D-pad 导航 =====================
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 长按 OK 键：弹出上下文菜单
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && event.isLongPress()) {
            if (selectedIndex >= 0 && selectedIndex < sortedFiles.length) {
                showContextMenu(sortedFiles[selectedIndex]);
                return true;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                if (selectedIndex > 0) {
                    setSelected(selectedIndex - 1);
                } else if (selectedIndex == -1 && !fileItemViews.isEmpty()) {
                    setSelected(0);
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
            case KeyEvent.KEYCODE_MENU: {
                if (selectedIndex >= 0 && selectedIndex < sortedFiles.length) {
                    showContextMenu(sortedFiles[selectedIndex]);
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

    private void setSelected(int idx) {
        // 清除旧选中
        if (selectedIndex >= 0 && selectedIndex < fileItemViews.size()) {
            View old = fileItemViews.get(selectedIndex);
            old.setScaleX(1.0f);
            old.setScaleY(1.0f);
            old.setElevation(4f);
            TextView oldSel = old.findViewById(R.id.tvSelected);
            if (oldSel != null) oldSel.setVisibility(View.GONE);
        }
        selectedIndex = idx;
        if (idx >= 0 && idx < fileItemViews.size()) {
            View v = fileItemViews.get(idx);
            v.setScaleX(1.06f);
            v.setScaleY(1.06f);
            v.setElevation(16f);
            v.requestFocus();
            TextView sel = v.findViewById(R.id.tvSelected);
            if (sel != null) sel.setVisibility(View.VISIBLE);
            scrollView.requestChildFocus(v, v);
        }
    }

    // ===================== 文件操作菜单 =====================

    /** 弹出文件操作菜单 */
    private void showContextMenu(File file) {
        String[] ops = new String[]{"\u6253\u5F00", "\u91CD\u547D\u540D", "\u79FB\u52A8", "\u590D\u5236", "\u5220\u9664"};

        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setItems(ops, (d, which) -> {
                switch (which) {
                    case 0: // 打开
                        if (file.isDirectory()) navigateTo(file);
                        else openFile(file);
                        break;
                    case 1: // 重命名
                        showRenameDialog(file);
                        break;
                    case 2: // 移动
                        clipboardFile = file;
                        clipboardCopy = false;
                        showMoveCopyDialog(file, false);
                        break;
                    case 3: // 复制
                        clipboardFile = file;
                        clipboardCopy = true;
                        showMoveCopyDialog(file, true);
                        break;
                    case 4: // 删除
                        showDeleteConfirm(file);
                        break;
                }
            })
            .setNegativeButton("\u53D6\u6D88", null)
            .show();
    }

    /** 显示移动/复制确认对话框 */
    private void showMoveCopyDialog(File file, boolean isCopy) {
        String opName = isCopy ? "\u590D\u5236\u5230\u6B64\u5904" : "\u79FB\u52A8\u5230\u6B64\u5904";
        String msg = (isCopy ? "\u786E\u8BA4\u590D\u5236\u5230\u5F53\u524D\u76EE\u5F55\uFF1F\n\n" : "\u786E\u8BA4\u79FB\u52A8\u5230\u5F53\u524D\u76EE\u5F55\uFF1F\n\n")
                + "\u6765\u6E90\uFF1A" + file.getAbsolutePath();

        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setMessage(msg)
            .setPositiveButton(opName, (d, w) -> {
                boolean ok = isCopy ? copyFile(clipboardFile, currentDir) : moveFile(clipboardFile, currentDir);
                if (ok) {
                    Toast.makeText(this, isCopy ? "\u590D\u5236\u6210\u529F" : "\u79FB\u52A8\u6210\u529F", Toast.LENGTH_SHORT).show();
                    clipboardFile = null;
                    refresh();
                } else {
                    Toast.makeText(this, isCopy ? "\u590D\u5236\u5931\u8D25" : "\u79FB\u52A8\u5931\u8D25", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("\u53D6\u6D88", null)
            .show();
    }

    /** 复制文件（或文件夹） */
    private boolean copyFile(File src, File destDir) {
        File dest = new File(destDir, src.getName());
        if (src.isDirectory()) {
            if (!dest.mkdirs()) return false;
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!copyFile(child, dest)) return false;
                }
            }
            return true;
        } else {
            try (InputStream in = new java.io.FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                return true;
            } catch (Exception e) { return false; }
        }
    }

    /** 移动文件 */
    private boolean moveFile(File src, File destDir) {
        File dest = new File(destDir, src.getName());
        if (dest.exists()) {
            Toast.makeText(this, "\u76EE\u6807\u4F4D\u7F6E\u5DF2\u6709\u540D\u540C\u6587\u4EF6", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (src.renameTo(dest)) return true;
        // rename 失败则尝试复制+删除
        if (copyFile(src, destDir)) {
            deleteRecursively(src);
            return true;
        }
        return false;
    }

    /** 新建文件夹 */
    private void showNewFolderDialog() {
        EditText et = new EditText(this);
        et.setHint("\u6587\u4EF6\u5939\u540D\u79F0");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(48, 24, 48, 24);

        new AlertDialog.Builder(this)
            .setTitle("\uD83D\uDCC1 \u65B0\u5EFA\u6587\u4EF6\u5939")
            .setView(et)
            .setPositiveButton("\u521B\u5EFA", (d, w) -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "\u540D\u79F0\u4E0D\u80FD\u4E3A\u7A7A", Toast.LENGTH_SHORT).show();
                    return;
                }
                File newDir = new File(currentDir, name);
                if (newDir.exists()) {
                    Toast.makeText(this, "\u540C\u540D\u6587\u4EF6\u5939\u5DF2\u5B58\u5728", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (newDir.mkdir()) {
                    Toast.makeText(this, "\u521B\u5EFA\u6210\u529F\uFF1A" + name, Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(this, "\u521B\u5EFA\u5931\u8D25\uFF0C\u8BF7\u68C0\u67E5\u6743\u9650", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("\u53D6\u6D88", null)
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
            .setTitle("\u270F\uFE0F \u91CD\u547D\u540D")
            .setMessage("\u5F53\u524D\uFF1A" + file.getName())
            .setView(et)
            .setPositiveButton("\u786E\u5B9A", (d, w) -> {
                String newName = et.getText().toString().trim();
                if (newName.isEmpty() || newName.equals(file.getName())) return;
                File dest = new File(currentDir, newName);
                if (dest.exists()) {
                    Toast.makeText(this, "\u540C\u540D\u6587\u4EF6\u5DF2\u5B58\u5728", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (file.renameTo(dest)) {
                    Toast.makeText(this, "\u91CD\u547D\u540D\u6210\u529F", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(this, "\u91CD\u547D\u540D\u5931\u8D25\uFF0C\u8BF7\u68C0\u67E5\u6743\u9650", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("\u53D6\u6D88", null)
            .show();
    }

    /** 删除确认 */
    private void showDeleteConfirm(File file) {
        String msg = file.isDirectory() ? "\u786E\u8BA4\u5220\u9664\u6587\u4EF6\u5939\u53CA\u5176\u6240\u6709\u5185\u5BB9\uFF1F\n\n" + file.getName()
                                              : "\u786E\u8BA4\u5220\u9664\u6587\u4EF6\uFF1F\n\n" + file.getName();
        new AlertDialog.Builder(this)
            .setTitle("\uD83D\uDDD1\uFE0F \u786E\u8BA4\u5220\u9664")
            .setMessage(msg)
            .setPositiveButton("\u5220\u9664", (d, w) -> {
                boolean ok = deleteRecursively(file);
                if (ok) {
                    Toast.makeText(this, "\u5220\u9664\u6210\u529F", Toast.LENGTH_SHORT).show();
                    refresh();
                } else {
                    Toast.makeText(this, "\u5220\u9664\u5931\u8D25\uFF0C\u8BF7\u68C0\u67E5\u6743\u9650", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("\u53D6\u6D88", null)
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
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(getExtension(file.getName()));
            Uri uri;
            try {
                uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                uri = Uri.fromFile(file);
            }
            if (mime != null) {
                intent.setDataAndType(uri, mime);
            } else {
                intent.setDataAndType(uri, "*/*");
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "\u65E0\u6CD5\u6253\u5F00\u8BE5\u6587\u4EF6\uFF0C\u8BF7\u5B89\u88C5\u76F8\u5173\u5E94\u7528", Toast.LENGTH_SHORT).show();
        }
    }

    // ===================== 工具方法 =====================

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private String formatFileSize(long size) {
        if (size < 0) return "\u2014";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String getFileEmoji(String ext) {
        switch (ext) {
            case "mp4": case "mkv": case "avi": case "mov": case "wmv": case "flv":
            case "webm": case "m4v": case "3gp":
                return "\uD83C\uDFAC";
            case "mp3": case "wav": case "aac": case "flac": case "ogg":
            case "m4a": case "wma":
                return "\uD83C\uDFB5";
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "svg":
                return "\uD83D\uDDBC\uFE0F";
            case "pdf":
                return "\uD83D\uDCC4";
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return "\uD83D\uDCE6";
            case "apk":
                return "\uD83D\uDCF0";
            case "txt": case "log": case "md":
            case "xml": case "json": case "html": case "css": case "js":
                return "\uD83D\uDCDD";
            default:
                return "\uD83D\uDCC4";
        }
    }

    /** 焦点缩放动画 */
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
