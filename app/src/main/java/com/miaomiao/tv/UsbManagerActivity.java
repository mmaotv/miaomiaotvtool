package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
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
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * U盘管理器 Activity
 * 支持多U盘：可以从AppManagerActivity传入指定U盘路径
 * 显示U盘空间信息
 * 支持重命名/删除/移动/复制
 * 适配 TV 遥控器 D-pad 操作
 */
public class UsbManagerActivity extends AppCompatActivity {

    private LinearLayout btnBackHome;
    private LinearLayout btnParentDir;
    private LinearLayout btnRefresh;
    private TextView tvCurrentPath;
    private TextView tvUsbIcon;
    private TextView tvStorageInfo;
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
    /** 剪贴板 */
    private File clipboardFile = null;
    private boolean clipboardCopy = true;
    /** 当前U盘路径（外部传入） */
    private String usbPath;
    private String usbName;
    private long totalSize;
    private long freeSize;

    /** USB 广播接收器 */
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_CHECKING.equals(action)) {
                refreshUsbState();
            } else if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_REMOVED.equals(action)) {
                onUsbRemoved();
            }
        }
    };

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

        setContentView(R.layout.activity_usb_manager);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 获取传入的U盘路径
        usbPath = getIntent().getStringExtra("usb_path");
        usbName = getIntent().getStringExtra("usb_name");

        initViews();

        // 如果传入了U盘路径，直接使用；否则自动检测
        if (usbPath != null && !usbPath.isEmpty()) {
            File usbDir = new File(usbPath);
            if (usbDir.exists() && usbDir.isDirectory()) {
                navigateTo(usbDir);
                updateStorageInfo(usbPath);
            } else {
                detectAndNavigateToUsb();
            }
        } else {
            detectAndNavigateToUsb();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }

    private void initViews() {
        btnBackHome       = findViewById(R.id.btnBackHome);
        btnParentDir      = findViewById(R.id.btnParentDir);
        btnRefresh        = findViewById(R.id.btnRefresh);
        tvCurrentPath     = findViewById(R.id.tvCurrentPath);
        tvUsbIcon         = findViewById(R.id.tvUsbIcon);
        tvStorageInfo     = findViewById(R.id.tvStorageInfo);
        usbEmptyView      = findViewById(R.id.usbEmptyView);
        scrollView        = findViewById(R.id.scrollView);
        fileListContainer = findViewById(R.id.fileListContainer);

        btnBackHome.setOnClickListener(v -> finish());
        btnParentDir.setOnClickListener(v -> {
            File parent = currentDir != null ? currentDir.getParentFile() : null;
            if (parent != null) navigateTo(parent);
        });
        btnRefresh.setOnClickListener(v -> {
            if (currentDir != null) {
                updateStorageInfo(currentDir.getPath());
                refresh();
            } else {
                detectAndNavigateToUsb();
            }
        });

        attachFocusScale(btnBackHome,  1.10f);
        attachFocusScale(btnParentDir, 1.10f);
        attachFocusScale(btnRefresh,   1.10f);

        btnBackHome.requestFocus();
    }

    /** 更新存储空间信息 */
    private void updateStorageInfo(String path) {
        new Thread(() -> {
            try {
                StatFs stat = new StatFs(path);
                totalSize = stat.getBlockCountLong() * stat.getBlockSizeLong();
                freeSize = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();

                runOnUiThread(() -> {
                    if (tvStorageInfo != null) {
                        String totalStr = formatFileSize(totalSize);
                        String freeStr = formatFileSize(freeSize);
                        tvStorageInfo.setText("总空间: " + totalStr + " | 可用: " + freeStr);
                        tvStorageInfo.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (tvStorageInfo != null) {
                        tvStorageInfo.setVisibility(View.GONE);
                    }
                });
            }
        }).start();
    }

    // ===================== USB 检测 =====================

    private void detectAndNavigateToUsb() {
        File usbRoot = findUsbDrive();
        if (usbRoot != null) {
            navigateTo(usbRoot);
            tvUsbIcon.setText("\uD83D\uDCBB");
            usbEmptyView.setVisibility(View.GONE);
        } else {
            showUsbEmpty();
        }
    }

    /** 查找 U 盘挂载路径 */
    private File findUsbDrive() {
        File storageRoot = new File("/storage");
        if (!storageRoot.exists()) return null;

        File[] mounts = storageRoot.listFiles();
        if (mounts == null) return null;

        String internalPath = Environment.getExternalStorageDirectory().getAbsolutePath();

        for (File mount : mounts) {
            if (!mount.isDirectory() || !mount.canRead()) continue;
            String path = mount.getAbsolutePath();
            if (path.equals(internalPath)) continue;
            if (path.contains("emulated")) continue;
            if (mount.getName().equals("self")) continue;

            String name = mount.getName().toLowerCase();
            if (name.contains("sd") || name.contains("usb") || name.contains("external")) {
                File[] test = mount.listFiles();
                if (test != null || mount.canRead()) return mount;
            }
        }

        // fallback：找第一个非内部存储的挂载点
        for (File mount : mounts) {
            if (!mount.isDirectory() || !mount.canRead()) continue;
            String path = mount.getAbsolutePath();
            if (path.equals(internalPath) || path.contains("emulated") || mount.getName().equals("self")) continue;
            File[] test = mount.listFiles();
            if (test != null || mount.canRead()) return mount;
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
        tvUsbIcon.setText("\uD83D\uDCE6");
        tvCurrentPath.setText("\u672A\u68C0\u6D4B\u5230 U \u76D8");
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
            Toast.makeText(this, "\u76EE\u5F55\u4E0D\u53EF\u8BBF\u95EE", Toast.LENGTH_SHORT).show();
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
                tvIcon.setText("\uD83D\uDCC1");
                tvSize.setText("\u6587\u4EF6\u5939");
            } else {
                String ext = getExtension(f.getName()).toLowerCase();
                tvIcon.setText(getFileEmoji(ext));
                tvSize.setText(formatFileSize(f.length()));
                loadThumbnail(itemView, f, ext);
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
    }

    /** 异步加载略缩图 */
    private void loadThumbnail(View itemView, File f, String ext) {
        ImageView ivThumb = itemView.findViewById(R.id.ivThumb);
        TextView tvIcon   = itemView.findViewById(R.id.tvIcon);

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
                } catch (Exception e) {}
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
        } catch (Exception e) {}
        return null;
    }

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

    private void setSelected(int idx) {
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
            case KeyEvent.KEYCODE_MENU: {
                if (selectedIndex >= 0 && selectedIndex < sortedFiles.length) {
                    showContextMenu(sortedFiles[selectedIndex]);
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

    // ===================== 文件操作菜单 =====================

    private void showContextMenu(File file) {
        String[] ops = new String[]{"\u6253\u5F00", "\u91CD\u547D\u540D", "\u79FB\u52A8", "\u590D\u5236", "\u5220\u9664"};

        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setItems(ops, (d, which) -> {
                switch (which) {
                    case 0:
                        if (file.isDirectory()) navigateTo(file);
                        else openFile(file);
                        break;
                    case 1:
                        showRenameDialog(file);
                        break;
                    case 2:
                        clipboardFile = file;
                        clipboardCopy = false;
                        showMoveCopyDialog(file, false);
                        break;
                    case 3:
                        clipboardFile = file;
                        clipboardCopy = true;
                        showMoveCopyDialog(file, true);
                        break;
                    case 4:
                        showDeleteConfirm(file);
                        break;
                }
            })
            .setNegativeButton("\u53D6\u6D88", null)
            .show();
    }

    private void showMoveCopyDialog(File file, boolean isCopy) {
        String opName = isCopy ? "\u590D\u5236\u5230\u6B64\u5904" : "\u79FB\u52A8\u5230\u6B64\u5904";
        String msg = (isCopy ? "\u786E\u8BA4\u590D\u5236\u5230\u5F53\u524D\u76EE\u5F55\uFF1F\n\n" : "\u786E\u8BA4\u79FB\u52A8\u5230\u5F53\u524D\u76EE\u5F55\uFF1F\n\n")
                + "\u6765\u6E90\uFF1A" + file.getAbsolutePath();

        new AlertDialog.Builder(this)
            .setTitle(file.getName())
            .setMessage(msg)
            .setPositiveButton(opName, (d, w) -> {
                boolean ok = isCopy ? copyFile(clipboardFile, currentDir) : moveFile(clipboardFile, currentDir);
                Toast.makeText(this, ok ? (isCopy ? "\u590D\u5236\u6210\u529F" : "\u79FB\u52A8\u6210\u529F") : (isCopy ? "\u590D\u5236\u5931\u8D25" : "\u79FB\u52A8\u5931\u8D25"), Toast.LENGTH_SHORT).show();
                if (ok) { clipboardFile = null; refresh(); }
            })
            .setNegativeButton("\u53D6\u6D88", null)
            .show();
    }

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

    private boolean moveFile(File src, File destDir) {
        File dest = new File(destDir, src.getName());
        if (dest.exists()) {
            Toast.makeText(this, "\u76EE\u6807\u4F4D\u7F6E\u5DF2\u6709\u540D\u540C\u6587\u4EF6", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (src.renameTo(dest)) return true;
        if (copyFile(src, destDir)) {
            deleteRecursively(src);
            return true;
        }
        return false;
    }

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
                    Toast.makeText(this, "\u91CD\u547D\u540D\u5931\u8D25", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("\u53D6\u6D88", null)
            .show();
    }

    private void showDeleteConfirm(File file) {
        String msg = file.isDirectory()
                ? "\u786E\u8BA4\u5220\u9664\u6587\u4EF6\u5939\u53CA\u5176\u6240\u6709\u5185\u5BB9\uFF1F\n\n" + file.getName()
                : "\u786E\u8BA4\u5220\u9664\u6587\u4EF6\uFF1F\n\n" + file.getName();
        new AlertDialog.Builder(this)
            .setTitle("\uD83D\uDDD1\uFE0F \u786E\u8BA4\u5220\u9664")
            .setMessage(msg)
            .setPositiveButton("\u5220\u9664", (d, w) -> {
                boolean ok = deleteRecursively(file);
                Toast.makeText(this, ok ? "\u5220\u9664\u6210\u529F" : "\u5220\u9664\u5931\u8D25", Toast.LENGTH_SHORT).show();
                if (ok) refresh();
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

    // ===================== 文件打开逻辑 =====================

    /**
     * 打开文件 — 根据文件类型智能分派：
     *   视频/音频 → 弹窗选择「内置播放器」或「其他应用」
     *   APK       → 弹窗选择「内置安装器」或「系统安装」
     *   其他      → 系统 Intent
     */
    private void openFile(File file) {
        String ext = getExtension(file.getName()).toLowerCase();

        if (isVideoFile(ext) || isAudioFile(ext)) {
            showMediaOpenDialog(file, ext);
            return;
        }

        if (ext.equals("apk")) {
            showApkOpenDialog(file);
            return;
        }

        openWithSystem(file, ext);
    }

    /** APK 文件打开方式选择弹窗 */
    private void showApkOpenDialog(File file) {
        String[] options = {
            "📦 内置 APK 安装器",
            "📱 系统安装器"
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle("📂 " + file.getName())
            .setItems(options, (d, which) -> {
                if (which == 0) openWithApkInstaller(file);
                else openWithSystem(file, "apk");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 媒体文件（视频/音频）打开方式选择弹窗 */
    private void showMediaOpenDialog(File file, String ext) {
        String[] options = {
            "🎬 用其他应用打开"
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle("📂 " + file.getName())
            .setItems(options, (d, which) -> {
                openWithSystem(file, ext);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 使用内置 APK 安装器打开 */
    private void openWithApkInstaller(File file) {
        Intent intent = new Intent(this, ApkInstallerActivity.class);
        intent.putExtra("file_path", file.getAbsolutePath());
        intent.putExtra("file_name", file.getName());
        startActivity(intent);
    }

    /** 使用系统 Intent 打开（通用后备方案） */
    private void openWithSystem(File file, String ext) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String mime = getMimeType(ext);
            Uri uri;
            try {
                uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", file);
            } catch (Exception e) {
                uri = Uri.fromFile(file);
            }
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开此文件，请安装相关应用", Toast.LENGTH_SHORT).show();
        }
    }

    /** 根据扩展名返回精确的 MIME 类型 */
    private String getMimeType(String ext) {
        switch (ext) {
            case "mp4":  case "m4v":  return "video/mp4";
            case "mkv":               return "video/x-matroska";
            case "avi":               return "video/x-msvideo";
            case "mov":               return "video/quicktime";
            case "wmv":               return "video/x-ms-wmv";
            case "flv":               return "video/x-flv";
            case "webm":              return "video/webm";
            case "3gp":               return "video/3gpp";
            case "ts": case "m2ts":   return "video/mp2ts";
            case "mpg": case "mpeg":  return "video/mpeg";
            case "vob":               return "video/dvd";
            case "mp3":               return "audio/mpeg";
            case "wav":               return "audio/wav";
            case "aac":               return "audio/aac";
            case "flac":              return "audio/flac";
            case "ogg":               return "audio/ogg";
            case "m4a":               return "audio/mp4";
            case "wma":               return "audio/x-ms-wma";
            case "opus":              return "audio/opus";
            case "ape":               return "audio/x-ape";
            case "jpg": case "jpeg":  return "image/jpeg";
            case "png":               return "image/png";
            case "gif":               return "image/gif";
            case "bmp":               return "image/bmp";
            case "webp":              return "image/webp";
            case "pdf":               return "application/pdf";
            case "txt": case "log":   return "text/plain";
            case "html": case "htm":  return "text/html";
            case "xml":               return "text/xml";
            case "json":              return "application/json";
            case "zip":               return "application/zip";
            case "rar":               return "application/x-rar-compressed";
            case "7z":                return "application/x-7z-compressed";
            case "apk":               return "application/vnd.android.package-archive";
            default:
                String sys = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext);
                return sys != null ? sys : "*/*";
        }
    }

    /** 判断是否为视频文件 */
    private boolean isVideoFile(String ext) {
        switch (ext) {
            case "mp4": case "mkv": case "avi": case "mov": case "wmv":
            case "flv": case "webm": case "m4v": case "3gp": case "ts":
            case "m2ts": case "mpg": case "mpeg": case "vob": case "rmvb":
            case "rm":  case "divx":
                return true;
            default: return false;
        }
    }

    /** 判断是否为音频文件 */
    private boolean isAudioFile(String ext) {
        switch (ext) {
            case "mp3": case "wav": case "aac": case "flac": case "ogg":
            case "m4a": case "wma": case "ape": case "opus": case "alac":
            case "aiff": case "mid": case "midi":
                return true;
            default: return false;
        }
    }

    /** 兼容旧代码 */
    private boolean isMediaFile(String ext) {
        return isVideoFile(ext) || isAudioFile(ext);
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
            case "mp3": case "wav": case "aac": case "flac": case "ogg": case "m4a":
                return "\uD83C\uDFB5";
            case "jpg": case "jpeg": case "png": case "gif": case "bmp": case "webp":
                return "\uD83D\uDDBC\uFE0F";
            case "pdf": return "\uD83D\uDCC4";
            case "zip": case "rar": case "7z": case "tar": case "gz":
                return "\uD83D\uDCE6";
            case "apk": return "\uD83D\uDCF0";
            default: return "\uD83D\uDCC4";
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
