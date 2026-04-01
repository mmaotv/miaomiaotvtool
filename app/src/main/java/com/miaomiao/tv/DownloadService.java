package com.miaomiao.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件下载前台服务
 * 所有下载都通过本服务完成，不调用系统下载器
 * 下载目录：公共 Downloads/喵喵嗷影视/
 */
public class DownloadService extends Service {

    public static final String EXTRA_URL        = "extra_url";
    public static final String EXTRA_FILE_NAME  = "extra_file_name";
    public static final String EXTRA_USER_AGENT = "extra_user_agent";
    public static final String EXTRA_MIME_TYPE  = "extra_mime_type";
    public static final String EXTRA_COOKIE     = "extra_cookie";

    private static final String CHANNEL_ID   = "miaomiao_download";
    private static final String CHANNEL_NAME = "\u55b5\u55b5\u5662\u5f71\u89c6\u4e0b\u8f7d";
    private static final int    NOTIF_ID     = 1001;
    private static final String SUB_DIR      = "\u55b5\u55b5\u5662\u5f71\u89c6"; // 喵喵嗷影视

    private NotificationManager notifManager;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        final String url       = intent.getStringExtra(EXTRA_URL);
        final String fileName  = intent.getStringExtra(EXTRA_FILE_NAME);
        final String userAgent = intent.getStringExtra(EXTRA_USER_AGENT);
        String mimeType        = intent.getStringExtra(EXTRA_MIME_TYPE);
        final String cookie    = intent.getStringExtra(EXTRA_COOKIE);

        if (url == null || url.isEmpty()) return START_NOT_STICKY;

        final String actualFileName;
        if (fileName == null || fileName.isEmpty()) {
            actualFileName = "download_" + System.currentTimeMillis();
        } else {
            actualFileName = fileName;
        }

        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = guessMimeTypeFromUrl(url, actualFileName);
        }
        final String finalMimeType = mimeType;

        // 启动前台服务
        startForeground(NOTIF_ID, buildProgressNotification(actualFileName, 0));

        // 在线程池中执行下载
        executor.execute(() -> doDownload(url, actualFileName, userAgent, finalMimeType, cookie, startId));

        return START_NOT_STICKY;
    }

    /**
     * 获取下载目录
     */
    private File getDownloadDir() {
        File dir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        );
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 生成不重复的文件名
     */
    private File getUniqueFile(File dir, String fileName) {
        File outFile = new File(dir, fileName);
        if (!outFile.exists()) return outFile;

        String nameNoExt = fileName.contains(".")
            ? fileName.substring(0, fileName.lastIndexOf('.'))
            : fileName;
        String ext = fileName.contains(".")
            ? fileName.substring(fileName.lastIndexOf('.'))
            : "";
        int counter = 1;
        while (outFile.exists()) {
            outFile = new File(dir, nameNoExt + "(" + counter++ + ")" + ext);
        }
        return outFile;
    }

    private void doDownload(String urlStr, String fileName,
                             String userAgent, String mimeType, String cookie, int startId) {

        File outFile = getUniqueFile(getDownloadDir(), fileName);

        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        boolean success = false;

        try {
            URL url = new URL(urlStr);

            // 处理重定向（最多5次）
            for (int redirect = 0; redirect < 5; redirect++) {
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(60_000);
                conn.setInstanceFollowRedirects(false);

                conn.setRequestProperty("User-Agent",
                    userAgent != null ? userAgent :
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                );
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Connection", "keep-alive");

                int responseCode = conn.getResponseCode();

                // 处理重定向
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("Redirect without Location header");
                    }
                    // 处理相对路径
                    if (location.startsWith("/")) {
                        location = url.getProtocol() + "://" + url.getHost() + location;
                    } else if (!location.startsWith("http")) {
                        location = url.getProtocol() + "://" + url.getHost() + "/" + location;
                    }
                    url = new URL(location);
                    conn.disconnect();
                    conn = null;
                    continue;
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + responseCode + ": " + conn.getResponseMessage());
                }

                break;
            }

            // 从最终 URL 更新文件名（如果原始文件名不太好的话）
            String contentDisp = conn.getHeaderField("Content-Disposition");
            if (contentDisp != null) {
                String parsedName = parseFileNameFromDisposition(contentDisp);
                if (parsedName != null && !parsedName.isEmpty()) {
                    fileName = parsedName;
                    outFile = getUniqueFile(getDownloadDir(), fileName);
                }
            }

            // 获取实际内容长度
            long totalBytes = conn.getContentLengthLong();
            long downloadedBytes = 0;

            in = conn.getInputStream();
            out = new FileOutputStream(outFile);

            byte[] buffer = new byte[16384]; // 16KB 缓冲区
            int bytesRead;
            int lastProgress = -1;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;

                // 更新进度（每2%更新一次，减少通知开销）
                if (totalBytes > 0) {
                    int progress = (int) (downloadedBytes * 100 / totalBytes);
                    if (Math.abs(progress - lastProgress) >= 2 || progress == 100) {
                        lastProgress = progress;
                        final String fn = outFile.getName();
                        notifManager.notify(NOTIF_ID, buildProgressNotification(fn, progress));
                    }
                } else {
                    // 未知大小，每200KB更新一次
                    if (downloadedBytes / 200_000 != lastProgress) {
                        lastProgress = (int) (downloadedBytes / 200_000);
                        final String fn = outFile.getName();
                        String sizeStr = formatSize(downloadedBytes);
                        notifManager.notify(NOTIF_ID, buildProgressNotification(fn + " (" + sizeStr + ")", 0));
                    }
                }
            }
            out.flush();
            success = true;

            // 在 Android 10+ 将文件加入 MediaStore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                addToMediaStore(outFile, mimeType);
            } else {
                // 旧版本通知媒体扫描
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(outFile));
                sendBroadcast(scanIntent);
            }

        } catch (Exception e) {
            e.printStackTrace();
            // 删除不完整的文件
            if (outFile.exists()) outFile.delete();
            onDownloadFailed(fileName, e.getMessage(), startId);
            return;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
            if (conn != null) conn.disconnect();
        }

        if (success) {
            onDownloadComplete(outFile, startId);
        }
    }

    /**
     * 将文件加入 MediaStore（Android 10+）
     */
    private void addToMediaStore(File file, String mimeType) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
            values.put(MediaStore.Downloads.SIZE, file.length());
            values.put(MediaStore.Downloads.DATE_ADDED, System.currentTimeMillis() / 1000);
            values.put(MediaStore.Downloads.IS_PENDING, 0);

            // 使用 RELATIVE_PATH 设置子目录
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR);

            getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 下载成功
     */
    private void onDownloadComplete(File file, int startId) {
        // 构建打开文件的 Intent
        Uri fileUri;
        try {
            fileUri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", file);
        } catch (Exception e) {
            // FileProvider 失败时用 file:// URI
            fileUri = Uri.fromFile(file);
        }

        Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(fileUri, getMimeType(file.getName()));
        openIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("\u4e0b\u8f7d\u5b8c\u6210") // 下载完成
            .setContentText(file.getName() + " (" + formatSize(file.length()) + ")")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build();

        notifManager.notify((int) System.currentTimeMillis(), notif);

        uiHandler.post(() ->
            Toast.makeText(this, "\u4e0b\u8f7d\u5b8c\u6210\uff1a" + file.getName(), Toast.LENGTH_LONG).show()
        );

        stopForeground(true);
        stopSelf(startId);
    }

    /**
     * 下载失败
     */
    private void onDownloadFailed(String fileName, String reason, int startId) {
        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("\u4e0b\u8f7d\u5931\u8d25") // 下载失败
            .setContentText(fileName + " - " + (reason != null ? reason : "unknown"))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build();

        notifManager.notify((int) System.currentTimeMillis(), notif);

        uiHandler.post(() ->
            Toast.makeText(this, "\u4e0b\u8f7d\u5931\u8d25\uff1a" + fileName, Toast.LENGTH_LONG).show()
        );

        stopForeground(true);
        stopSelf(startId);
    }

    /**
     * 构建进度通知
     */
    private Notification buildProgressNotification(String text, int progress) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("\u6b63\u5728\u4e0b\u8f7d") // 正在下载
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);

        if (progress > 0) {
            builder.setProgress(100, progress, false);
        } else {
            builder.setProgress(100, 0, true); // 不确定进度
        }

        return builder.build();
    }

    /**
     * 创建通知频道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("\u55b5\u55b5\u5662\u5f71\u89c6\u6587\u4ef6\u4e0b\u8f7d\u8fdb\u5ea6");
            channel.setShowBadge(false);
            notifManager.createNotificationChannel(channel);
        }
    }

    /**
     * 从 Content-Disposition 解析文件名
     */
    private String parseFileNameFromDisposition(String disposition) {
        if (disposition == null) return null;
        String[] parts = disposition.split(";");
        for (String part : parts) {
            part = part.trim();
            if (part.startsWith("filename=")) {
                String name = part.substring("filename=".length()).trim();
                name = name.replace("\"", "").replace("'", "");
                if (!name.isEmpty()) return name;
            }
            if (part.startsWith("filename*=")) {
                String name = part.substring("filename*=".length()).trim();
                // 处理 RFC 5987 编码：utf-8''filename.ext
                if (name.toLowerCase().startsWith("utf-8''")) {
                    name = name.substring(7);
                }
                name = name.replace("\"", "").replace("'", "");
                if (!name.isEmpty()) return name;
            }
        }
        return null;
    }

    /**
     * 从 URL 推断文件名和 MIME 类型
     */
    private String guessMimeTypeFromUrl(String url, String fileName) {
        String lower = url.toLowerCase();
        if (lower.contains(".mp4") || lower.contains("video/mp4")) return "video/mp4";
        if (lower.contains(".mkv") || lower.contains("video/x-matroska")) return "video/x-matroska";
        if (lower.contains(".avi")) return "video/x-msvideo";
        if (lower.contains(".mp3")) return "audio/mpeg";
        if (lower.contains(".flac")) return "audio/flac";
        if (lower.contains(".aac")) return "audio/aac";
        if (lower.contains(".jpg") || lower.contains(".jpeg")) return "image/jpeg";
        if (lower.contains(".png")) return "image/png";
        if (lower.contains(".gif")) return "image/gif";
        if (lower.contains(".pdf")) return "application/pdf";
        if (lower.contains(".apk")) return "application/vnd.android.package-archive";
        if (lower.contains(".zip")) return "application/zip";
        if (lower.contains(".rar")) return "application/x-rar-compressed";
        if (lower.contains(".doc")) return "application/msword";
        if (lower.contains(".xls")) return "application/vnd.ms-excel";
        return "application/octet-stream";
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     */
    private String getMimeType(String fileName) {
        String ext = fileName.toLowerCase();
        if (ext.endsWith(".mp4")) return "video/mp4";
        if (ext.endsWith(".mkv")) return "video/x-matroska";
        if (ext.endsWith(".avi")) return "video/x-msvideo";
        if (ext.endsWith(".ts")) return "video/mp2t";
        if (ext.endsWith(".mp3")) return "audio/mpeg";
        if (ext.endsWith(".flac")) return "audio/flac";
        if (ext.endsWith(".aac")) return "audio/aac";
        if (ext.endsWith(".wav")) return "audio/wav";
        if (ext.endsWith(".jpg") || ext.endsWith(".jpeg")) return "image/jpeg";
        if (ext.endsWith(".png")) return "image/png";
        if (ext.endsWith(".gif")) return "image/gif";
        if (ext.endsWith(".webp")) return "image/webp";
        if (ext.endsWith(".pdf")) return "application/pdf";
        if (ext.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (ext.endsWith(".zip")) return "application/zip";
        if (ext.endsWith(".rar")) return "application/x-rar-compressed";
        if (ext.endsWith(".7z")) return "application/x-7z-compressed";
        if (ext.endsWith(".doc") || ext.endsWith(".docx")) return "application/msword";
        if (ext.endsWith(".xls") || ext.endsWith(".xlsx")) return "application/vnd.ms-excel";
        return "*/*";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
