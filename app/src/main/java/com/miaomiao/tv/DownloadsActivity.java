package com.miaomiao.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 下载管理界面 - 展示已下载的文件列表
 * 优先扫描 Download/喵喵嗷影视/ 目录
 */
public class DownloadsActivity extends Activity {

    private static final String SUB_DIR = "\u55b5\u55b5\u5662\u5f71\u89c6"; // 喵喵嗷影视

    private ListView downloadList;
    private LinearLayout emptyView;
    private List<FileInfo> files = new ArrayList<>();
    private DownloadAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 横屏 + 全屏
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_downloads);

        downloadList = findViewById(R.id.downloadList);
        emptyView    = findViewById(R.id.emptyView);

        LinearLayout btnBack = findViewById(R.id.btnDlBack);
        btnBack.setOnClickListener(v -> finish());

        LinearLayout btnClear = findViewById(R.id.btnClearAll);
        btnClear.setOnClickListener(v -> showClearConfirm());

        loadFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFiles();
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    /**
     * 加载下载文件列表
     */
    private void loadFiles() {
        files.clear();

        // === 主要扫描目录：公共 Downloads/喵喵嗷影视/ ===
        File appDownloadDir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        );
        scanDirectory(appDownloadDir, true);

        // === 备选扫描：应用私有下载目录 ===
        File privateDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (privateDir != null) {
            scanDirectory(privateDir, false);
        }

        // === 最后备选：公共 Downloads 根目录（只取最近7天的） ===
        File publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (publicDir != null && publicDir.exists()) {
            File[] allFiles = publicDir.listFiles();
            if (allFiles != null) {
                long sevenDaysAgo = System.currentTimeMillis() - 7L * 86400 * 1000;
                for (File f : allFiles) {
                    if (f.isFile() && f.lastModified() > sevenDaysAgo && !f.getName().startsWith(".")) {
                        addFileIfNew(f);
                    }
                }
            }
        }

        // 按修改时间降序排序
        files.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));

        if (files.isEmpty()) {
            downloadList.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            downloadList.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            if (adapter == null) {
                adapter = new DownloadAdapter(this, files);
                downloadList.setAdapter(adapter);
            } else {
                adapter.clear();
                adapter.addAll(files);
                adapter.notifyDataSetChanged();
            }
        }
    }

    /**
     * 扫描指定目录下的文件
     */
    private void scanDirectory(File dir, boolean isAppDir) {
        if (dir == null || !dir.exists()) return;

        File[] fileArray = dir.listFiles();
        if (fileArray == null) return;

        for (File f : fileArray) {
            if (f.isFile() && !f.getName().startsWith(".")) {
                if (isAppDir) {
                    files.add(new FileInfo(f, true));
                } else {
                    addFileIfNew(f);
                }
            }
        }
    }

    /**
     * 添加文件（避免重复）
     */
    private void addFileIfNew(File f) {
        for (FileInfo existing : files) {
            if (existing.file.getAbsolutePath().equals(f.getAbsolutePath())) {
                return;
            }
        }
        files.add(new FileInfo(f, false));
    }

    private void showClearConfirm() {
        new AlertDialog.Builder(this)
            .setTitle("\u6e05\u7a7a\u8bb0\u5f55") // 清空记录
            .setMessage("\u4ec5\u6e05\u7a7a\u5217\u8868\u663e\u793a\uff0c\u4e0d\u5220\u9664\u5b9e\u9645\u6587\u4ef6\u3002\u786e\u8ba4\uff1f")
            .setPositiveButton("\u786e\u8ba4", (d, w) -> {
                files.clear();
                if (adapter != null) adapter.notifyDataSetChanged();
                downloadList.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
                Toast.makeText(this, "\u5df2\u6e05\u7a7a\u663e\u793a", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("\u53d6\u6d88", null)
            .show();
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
            );
            String mimeType = getMimeType(file.getName());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            // FileProvider 失败时用 file:// URI 尝试
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(file), getMimeType(file.getName()));
                startActivity(intent);
            } catch (Exception e2) {
                Toast.makeText(this, "\u65e0\u6cd5\u6253\u5f00\u6587\u4ef6\uff1a" + file.getName(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showDeleteConfirm(FileInfo info) {
        new AlertDialog.Builder(this)
            .setTitle("\u5220\u9664\u6587\u4ef6") // 删除文件
            .setMessage("\u786e\u5b9a\u5220\u9664 " + info.file.getName() + " ?\n\u8be5\u64cd\u4f5c\u4e0d\u53ef\u64a4\u9500\uff01")
            .setPositiveButton("\u5220\u9664", (d, w) -> {
                if (info.file.delete()) {
                    loadFiles();
                    Toast.makeText(this, "\u5df2\u5220\u9664", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "\u5220\u9664\u5931\u8d25", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("\u53d6\u6d88", null)
            .show();
    }

    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".rar")) return "application/x-rar-compressed";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return "*/*";
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    private static String getFileIcon(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".apk")) return "\ud83d\udce6"; // 📦
        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi") || lower.endsWith(".ts"))
            return "\ud83c\udfac"; // 🎬
        if (lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".aac") || lower.endsWith(".wav"))
            return "\ud83c\udfb5"; // 🎵
        if (lower.endsWith(".pdf")) return "\ud83d\udcd5"; // 📕
        if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z"))
            return "\ud83d\uddc2"; // 🗜
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif"))
            return "\ud83d\uddbc"; // 🖼
        if (lower.endsWith(".doc") || lower.endsWith(".docx"))
            return "\ud83d\udcc3"; // 📃
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx"))
            return "\ud83d\udcca"; // 📊
        return "\ud83d\udcc4"; // 📄
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        DecimalFormat df = new DecimalFormat("#.#");
        if (bytes < 1024 * 1024) return df.format(bytes / 1024.0) + " KB";
        if (bytes < 1024L * 1024 * 1024) return df.format(bytes / (1024.0 * 1024)) + " MB";
        return df.format(bytes / (1024.0 * 1024.0 * 1024)) + " GB";
    }

    private static String formatDate(long time) {
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return sdf.format(new Date(time));
    }

    private static String getRelativeTime(long time) {
        long diff = System.currentTimeMillis() - time;
        if (diff < 60_000) return "\u521a\u521a"; // 刚刚
        if (diff < 3600_000) return (diff / 60_000) + "\u5206\u949f\u524d"; // 分钟前
        if (diff < 86400_000) return (diff / 3600_000) + "\u5c0f\u65f6\u524d"; // 小时前
        if (diff < 172800_000) return "\u6628\u5929"; // 昨天
        return (diff / 86400_000) + "\u5929\u524d"; // 天前
    }

    // ====================================================================
    // 文件信息包装
    // ====================================================================
    private static class FileInfo {
        final File file;
        final boolean isAppDownload;
        final long lastModified;

        FileInfo(File file, boolean isAppDownload) {
            this.file = file;
            this.isAppDownload = isAppDownload;
            this.lastModified = file.lastModified();
        }
    }

    // ====================================================================
    // 列表适配器
    // ====================================================================
    class DownloadAdapter extends ArrayAdapter<FileInfo> {
        DownloadAdapter(Context ctx, List<FileInfo> data) {
            super(ctx, R.layout.item_download, data);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_download, parent, false);
            }
            FileInfo info = getItem(position);
            if (info == null) return convertView;

            File file = info.file;

            TextView tvIcon = convertView.findViewById(R.id.tvFileIcon);
            TextView tvName = convertView.findViewById(R.id.tvFileName);
            TextView tvSize = convertView.findViewById(R.id.tvFileSize);
            ProgressBar pb  = convertView.findViewById(R.id.progressBar);
            LinearLayout btnOpen = convertView.findViewById(R.id.btnOpen);

            tvIcon.setText(getFileIcon(file.getName()));

            // 截断过长文件名
            String displayName = file.getName();
            if (displayName.length() > 50) {
                displayName = displayName.substring(0, 47) + "...";
            }
            tvName.setText(displayName);

            tvSize.setText(formatSize(file.length()) + "  \u00b7  " + getRelativeTime(file.lastModified()));
            pb.setVisibility(View.GONE);

            btnOpen.setOnClickListener(v -> openFile(file));

            // 长按删除
            convertView.setOnLongClickListener(v -> {
                showDeleteConfirm(info);
                return true;
            });

            return convertView;
        }
    }
}
