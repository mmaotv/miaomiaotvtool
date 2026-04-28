package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫码推送Activity
 * 功能：
 * 1. 显示二维码（HTTP Server地址）
 * 2. 接收手机推送的网址 → 弹窗确认 → 内置WebView打开
 * 3. 接收手机推送的文件 → 弹窗显示保存路径 → 可跳转文件管理
 */
public class ScanPushActivity extends AppCompatActivity {

    private static final int SERVER_PORT = 18765;
    private static final long LONG_PRESS_THRESHOLD = 3000; // 长按3秒刷新二维码

    private LinearLayout btnBackHome;
    private LinearLayout btnRefreshQr;
    private LinearLayout btnOpenReceived;
    private ImageView ivQrCode;
    private TextView tvLoading;
    private TextView tvIpAddress;
    private TextView tvWaitStatus;
    private LinearLayout layoutIpAddress;

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 长按检测
    private long keyDownTime = 0;
    private boolean isLongPressHandled = false;

    // 当前接收的文件信息
    private String lastReceivedFileName;
    private String lastReceivedFilePath;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
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

        setContentView(R.layout.activity_scan_push);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        initViews();
        startServer();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                keyDownTime = System.currentTimeMillis();
                isLongPressHandled = false;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            long pressDuration = System.currentTimeMillis() - keyDownTime;
            if (pressDuration >= LONG_PRESS_THRESHOLD && !isLongPressHandled) {
                isLongPressHandled = true;
                refreshQrCode();
                Toast.makeText(this, "🔄 二维码已刷新", Toast.LENGTH_SHORT).show();
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    /** 刷新二维码 */
    private void refreshQrCode() {
        tvLoading.setVisibility(View.VISIBLE);
        tvLoading.setText("正在刷新二维码...");
        startServer();
    }

    private void initViews() {
        btnBackHome = findViewById(R.id.btnBackHome);
        btnRefreshQr = findViewById(R.id.btnRefreshQr);
        btnOpenReceived = findViewById(R.id.btnOpenReceived);
        ivQrCode = findViewById(R.id.ivQrCode);
        tvLoading = findViewById(R.id.tvLoading);
        tvIpAddress = findViewById(R.id.tvIpAddress);
        tvWaitStatus = findViewById(R.id.tvWaitStatus);
        layoutIpAddress = findViewById(R.id.layoutIpAddress);

        // 返回首页
        btnBackHome.setOnClickListener(v -> finish());

        // 刷新二维码按钮
        btnRefreshQr.setOnClickListener(v -> {
            refreshQrCode();
            Toast.makeText(this, "二维码已刷新", Toast.LENGTH_SHORT).show();
        });

        // 点击IP地址跳转浏览器
        layoutIpAddress.setOnClickListener(v -> {
            String url = tvIpAddress.getText().toString();
            if (!url.startsWith("http")) return;
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
            }
        });

        // 点击已推送软件按钮，跳转到文件管理的Download目录
        if (btnOpenReceived != null) {
            btnOpenReceived.setOnClickListener(v -> {
                Intent intent = new Intent(this, FileManagerActivity.class);
                intent.putExtra("path", Environment.DIRECTORY_DOWNLOADS);
                if (lastReceivedFileName != null) {
                    intent.putExtra("highlight_file", lastReceivedFileName);
                }
                startActivity(intent);
            });
            attachFocusScale(btnOpenReceived, 1.05f);
        }

        attachFocusScale(btnBackHome, 1.10f);
        attachFocusScale(btnRefreshQr, 1.10f);
        attachFocusScale(layoutIpAddress, 1.05f);
    }

    /** 启动HTTP Server并生成二维码 */
    private void startServer() {
        String localIp = getLocalIpAddress();

        if (localIp.isEmpty() || localIp.equals("0.0.0.0")) {
            tvLoading.setText("❌ 未连接到局域网");
            tvWaitStatus.setText("请先连接WiFi或有线网络");
            tvIpAddress.setText("无法获取IP地址");
            return;
        }

        String serverUrl = "http://" + localIp + ":" + SERVER_PORT + "/";
        tvIpAddress.setText(serverUrl);

        // 生成二维码
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... voids) {
                return generateQrCode(serverUrl, 500);
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {
                tvLoading.setVisibility(View.GONE);
                if (bitmap != null) {
                    ivQrCode.setImageBitmap(bitmap);
                    tvWaitStatus.setText("✅ 二维码已生成，等待手机扫码...");
                } else {
                    tvLoading.setText("❌ 二维码生成失败");
                    tvWaitStatus.setText("请检查网络连接");
                }
            }
        }.execute();

        // 启动Server
        executor = Executors.newCachedThreadPool();
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(SERVER_PORT));

                mainHandler.post(() -> tvWaitStatus.setText("📱 等待手机扫码..."));

                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.execute(() -> handleClient(client));
                    } catch (IOException e) {
                        if (!serverSocket.isClosed()) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    tvLoading.setText("❌ 服务器启动失败");
                    tvWaitStatus.setText("端口可能被占用：" + e.getMessage());
                });
            }
        });
    }

    /** 处理手机端请求 */
    private void handleClient(Socket client) {
        try {
            byte[] buffer = new byte[8192];
            int len = client.getInputStream().read(buffer);
            if (len <= 0) {
                client.close();
                return;
            }

            String request = new String(buffer, 0, len, "UTF-8");
            String method = "";
            String path = "";
            String[] lines = request.split("\r\n");
            if (lines.length > 0) {
                String[] parts = lines[0].split(" ");
                if (parts.length >= 2) {
                    method = parts[0];
                    path = parts[1];
                }
            }

            OutputStream out = client.getOutputStream();
            PrintStream ps = new PrintStream(out, false, "UTF-8");

            if ("GET".equals(method) && ("/".equals(path) || "/input".equals(path))) {
                // 返回输入页面
                String html = buildInputHtml();
                byte[] body = html.getBytes("UTF-8");
                sendResponse(ps, out, 200, "OK", "text/html; charset=UTF-8", body);

            } else if ("GET".equals(method) && path.startsWith("/submit")) {
                // 解析提交的网址
                String url = "";
                if (path.contains("?")) {
                    String query = path.substring(path.indexOf('?') + 1);
                    for (String param : query.split("&")) {
                        if (param.startsWith("url=")) {
                            url = java.net.URLDecoder.decode(param.substring(4), "UTF-8");
                        }
                    }
                }

                if (!url.isEmpty()) {
                    final String finalUrl = url;
                    // 返回成功页面
                    String html = buildSuccessHtml(finalUrl);
                    byte[] body = html.getBytes("UTF-8");
                    sendResponse(ps, out, 200, "OK", "text/html; charset=UTF-8", body);

                    // 主线程处理网址
                    mainHandler.post(() -> showUrlReceivedDialog(finalUrl));
                } else {
                    sendRedirect(ps, "/");
                }

            } else if ("POST".equals(method) && "/upload".equals(path)) {
                // 处理文件上传
                handleUpload(client, request, buffer, len, ps, out);
                return; // handleUpload会自己关闭连接

            } else {
                sendRedirect(ps, "/");
            }

            out.flush();
            client.close();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleUpload(Socket client, String request, byte[] buffer, int len,
                              PrintStream ps, OutputStream out) {
        try {
            long contentLength = 0;
            String boundary = "";
            for (String line : request.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Long.parseLong(line.substring(15).trim());
                }
                if (line.toLowerCase().startsWith("content-type:") && line.contains("boundary=")) {
                    boundary = "--" + line.substring(line.indexOf("boundary=") + 9).trim();
                }
            }

            if (contentLength <= 0 || boundary.isEmpty()) {
                sendError(ps, out, "无效的上传请求");
                client.close();
                return;
            }

            // 计算HTTP头结束位置
            int headerEndIdx = request.indexOf("\r\n\r\n");
            int headerByteLen = headerEndIdx + 4;
            int bodyBytesRead = Math.max(0, len - headerByteLen);

            // 读取请求体
            byte[] bodyData = new byte[(int) contentLength];
            if (bodyBytesRead > 0) {
                System.arraycopy(buffer, headerByteLen, bodyData, 0, bodyBytesRead);
            }
            int totalRead = bodyBytesRead;
            while (totalRead < contentLength) {
                int toRead = (int) Math.min(8192, contentLength - totalRead);
                int r = client.getInputStream().read(bodyData, totalRead, toRead);
                if (r <= 0) break;
                totalRead += r;
            }

            // 提取文件名
            String bodyStr = new String(bodyData, 0, Math.min(totalRead, 4096), "UTF-8");
            String fileName = "file_" + System.currentTimeMillis();
            int nameIdx = bodyStr.indexOf("filename=\"");
            if (nameIdx >= 0) {
                int nameEnd = bodyStr.indexOf("\"", nameIdx + 10);
                if (nameEnd > nameIdx) {
                    fileName = bodyStr.substring(nameIdx + 10, nameEnd);
                }
            }

            // 找到文件数据起始位置
            byte[] boundaryBytes = boundary.getBytes("UTF-8");
            int headerEnd = findBytes(bodyData, totalRead, "\r\n\r\n".getBytes());
            if (headerEnd < 0) {
                sendError(ps, out, "无法解析文件数据");
                client.close();
                return;
            }
            int fileDataStart = headerEnd + 4;

            // 找文件数据结束位置
            byte[] endMarker = ("\r\n" + boundary).getBytes("UTF-8");
            int fileDataEnd = findBytes(bodyData, totalRead, endMarker);
            if (fileDataEnd < 0) fileDataEnd = totalRead;

            int fileLength = fileDataEnd - fileDataStart;
            if (fileLength <= 0) {
                sendError(ps, out, "文件为空");
                client.close();
                return;
            }

            // 保存文件
            final String finalFileName = fileName;
            final int finalFileStart = fileDataStart;
            final int finalFileLength = fileLength;
            final byte[] finalBodyData = bodyData;

            executor.execute(() -> {
                boolean saved = false;
                String savedPath = "";
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+ MediaStore API
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, finalFileName);
                        values.put(MediaStore.Downloads.MIME_TYPE, guessMimeType(finalFileName));
                        values.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS);
                        values.put(MediaStore.Downloads.IS_PENDING, 1);

                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            try (java.io.OutputStream fos = getContentResolver().openOutputStream(uri)) {
                                if (fos != null) {
                                    fos.write(finalBodyData, finalFileStart, finalFileLength);
                                    fos.flush();
                                }
                            }
                            ContentValues done = new ContentValues();
                            done.put(MediaStore.Downloads.IS_PENDING, 0);
                            getContentResolver().update(uri, done, null, null);
                            saved = true;
                            savedPath = Environment.DIRECTORY_DOWNLOADS + "/" + finalFileName;
                        }
                    } else {
                        // Android 9- 传统文件路径
                        File downloadDir = new File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "喵喵嗷影视"
                        );
                        if (!downloadDir.exists()) downloadDir.mkdirs();

                        File outFile = new File(downloadDir, finalFileName);
                        if (outFile.exists()) {
                            String nameNoExt = finalFileName.contains(".")
                                ? finalFileName.substring(0, finalFileName.lastIndexOf('.')) : finalFileName;
                            String ext = finalFileName.contains(".")
                                ? finalFileName.substring(finalFileName.lastIndexOf('.')) : "";
                            int counter = 1;
                            while (outFile.exists()) {
                                outFile = new File(downloadDir, nameNoExt + "(" + counter++ + ")" + ext);
                            }
                        }
                        FileOutputStream fos = new FileOutputStream(outFile);
                        fos.write(finalBodyData, finalFileStart, finalFileLength);
                        fos.flush();
                        fos.close();
                        saved = true;
                        savedPath = outFile.getAbsolutePath();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final boolean finalSaved = saved;
                final String finalSavedPath = savedPath;
                final String finalSavedName = finalFileName;
                lastReceivedFileName = finalSavedName;
                lastReceivedFilePath = finalSavedPath;

                mainHandler.post(() -> {
                    if (finalSaved) {
                        tvWaitStatus.setText("✅ 文件已接收：" + finalSavedName);
                        showFileReceivedDialog(finalSavedName, finalSavedPath);
                    } else {
                        Toast.makeText(ScanPushActivity.this, "❌ 文件保存失败", Toast.LENGTH_LONG).show();
                    }
                });
            });

            // 立即返回成功响应
            String response = "{\"status\":\"ok\",\"filename\":\"" + escapeJson(fileName) + "\"}";
            byte[] resBody = response.getBytes("UTF-8");
            sendResponse(ps, out, 200, "OK", "application/json", resBody);

        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendError(ps, out, "上传失败：" + e.getMessage());
            } catch (IOException ignored) {
            }
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 显示网址接收弹窗 */
    private void showUrlReceivedDialog(String url) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogRoundedStyle);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_custom, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);

        tvIcon.setText("🌐");
        tvTitle.setText("网址接收成功");
        tvMessage.setText(url);
        tvPositive.setText("打开");
        tvNegative.setText("取消");

        AlertDialog dialog = builder.create();
        dialog.setView(dialogView);
        dialog.setCancelable(true);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            // 在内置WebView中打开网址
            Intent intent = new Intent(ScanPushActivity.this, MainActivity.class);
            intent.putExtra("open_url", url);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        btnNegative.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /** 显示文件接收弹窗 */
    private void showFileReceivedDialog(String fileName, String filePath) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogRoundedStyle);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_custom, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);

        tvIcon.setText("📁");
        tvTitle.setText("文件接收成功");
        tvMessage.setText("文件名：" + fileName + "\n保存路径：内部存储/Download");
        tvPositive.setText("查看文件");
        tvNegative.setText("取消");

        AlertDialog dialog = builder.create();
        dialog.setView(dialogView);
        dialog.setCancelable(true);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            // 跳转到文件管理器的Download目录
            Intent intent = new Intent(ScanPushActivity.this, FileManagerActivity.class);
            intent.putExtra("path", Environment.DIRECTORY_DOWNLOADS);
            intent.putExtra("highlight_file", fileName);
            startActivity(intent);
        });

        btnNegative.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /** 显示推送失败弹窗 */
    private void showPushFailedDialog(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.DialogRoundedStyle);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_custom, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);

        tvIcon.setText("❌");
        tvTitle.setText("推送失败");
        tvMessage.setText(message != null ? message : "请重试");
        tvPositive.setText("确定");
        btnNegative.setVisibility(View.GONE);

        AlertDialog dialog = builder.create();
        dialog.setView(dialogView);
        dialog.setCancelable(true);

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btnPositive.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void sendResponse(PrintStream ps, OutputStream out, int code, String status, String contentType, byte[] body) throws IOException {
        ps.print("HTTP/1.1 " + code + " " + status + "\r\n");
        ps.print("Content-Type: " + contentType + "\r\n");
        ps.print("Content-Length: " + body.length + "\r\n");
        ps.print("Connection: close\r\n");
        ps.print("Access-Control-Allow-Origin: *\r\n");
        ps.print("\r\n");
        ps.flush();
        out.write(body);
        out.flush();
    }

    private void sendRedirect(PrintStream ps, String location) throws IOException {
        ps.print("HTTP/1.1 302 Found\r\n");
        ps.print("Location: " + location + "\r\n");
        ps.print("Connection: close\r\n\r\n");
        ps.flush();
    }

    private void sendError(PrintStream ps, OutputStream out, String msg) throws IOException {
        String response = "{\"status\":\"error\",\"message\":\"" + escapeJson(msg) + "\"}";
        byte[] body = response.getBytes("UTF-8");
        sendResponse(ps, out, 400, "Bad Request", "application/json", body);
    }

    private int findBytes(byte[] data, int len, byte[] pattern) {
        outer:
        for (int i = 0; i <= len - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private String guessMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public static Bitmap generateQrCode(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixels[y * width + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;

        } catch (WriterException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.isEmpty()) return ip;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "0.0.0.0";
    }

    /** 生成手机端输入页面的HTML */
    private String buildInputHtml() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
               "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
               "<style>body{font-family:Arial;max-width:400px;margin:50px auto;padding:20px;text-align:center;background:#f5f5f5}" +
               "h2{color:#333}input{width:100%;padding:12px;margin:10px 0;border:1px solid #ddd;border-radius:8px;box-sizing:border-box;font-size:16px}" +
               "button{width:100%;padding:14px;background:#00D9FF;color:#fff;border:none;border-radius:8px;font-size:16px;cursor:pointer;margin-top:10px}" +
               "button:hover{background:#00b8d9}.hint{color:#666;font-size:12px;margin-top:20px}</style></head>" +
               "<body><h2>📺 喵喵嗷影视</h2>" +
               "<input type='text' id='urlInput' placeholder='输入网址后点击推送'>" +
               "<button onclick='pushUrl()'>🔗 推送网址</button>" +
               "<button onclick='selectFile()'>📁 推送文件</button>" +
               "<input type='file' id='fileInput' style='display:none' onchange='uploadFile(this.files[0])'>" +
               "<p class='hint'>手机和电视需在同一WiFi网络</p>" +
               "<script>function pushUrl(){var u=document.getElementById('urlInput').value;if(!u)return;if(!/^https?:/.test(u))u='http://'+u;location.href='/submit?url='+encodeURIComponent(u)}" +
               "function selectFile(){document.getElementById('fileInput').click()}" +
               "function uploadFile(f){if(!f)return;var fd=new FormData();fd.append('file',f);fetch('/upload',{method:'POST',body:fd}).then(r=>r.json()).then(d=>{if(d.status==='ok')alert('上传成功！');else alert('上传失败');location.reload()}).catch(e=>alert('上传失败'))}</script></body></html>";
    }

    /** 生成推送成功页面的HTML */
    private String buildSuccessHtml(String url) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
               "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
               "<style>body{font-family:Arial;max-width:400px;margin:50px auto;padding:20px;text-align:center;background:#f0fff0}" +
               "h2{color:#27ae60}.url{background:#fff;padding:15px;border-radius:8px;word-break:break-all;margin:20px 0;font-size:14px}</style></head>" +
               "<body><h2>✅ 推送成功！</h2>" +
               "<p>网址已发送到电视：</p>" +
               "<div class='url'>" + escapeHtml(url) + "</div>" +
               "<p>电视将自动打开此页面</p></body></html>";
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void attachFocusScale(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            float target = hasFocus ? scale : 1.0f;
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", target),
                ObjectAnimator.ofFloat(view, "scaleY", target)
            );
            set.setDuration(150);
            set.start();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }

    private void stopServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}