package com.miaomiao.tv;

import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
 * 扫码输入功能
 * 1. 在局域网启动一个 HTTP Server
 * 2. 生成包含该 Server 地址的二维码
 * 3. 手机扫码后打开网页输入框
 * 4. 手机提交网址 → TV 端自动跳转
 */
public class QrInputHelper {

    private static final int SERVER_PORT = 18765;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Dialog dialog;
    private UrlReceivedCallback callback;

    public interface UrlReceivedCallback {
        void onUrlReceived(String url);
    }

    public QrInputHelper(Context context, UrlReceivedCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * 显示二维码对话框并启动 HTTP Server
     */
    public void show() {
        dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_Dialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_qr_input);
        dialog.setCancelable(true);

        ImageView ivQrCode   = dialog.findViewById(R.id.ivQrCode);
        TextView tvIpAddress = dialog.findViewById(R.id.tvIpAddress);
        TextView tvWaitStatus = dialog.findViewById(R.id.tvWaitStatus);
        TextView tvQrHint    = dialog.findViewById(R.id.tvQrHint);
        LinearLayout btnCancel = dialog.findViewById(R.id.btnCancelQr);
        LinearLayout btnUploadFile = dialog.findViewById(R.id.btnUploadFile);

        // 先获取 IP
        String localIp = getLocalIpAddress();

        btnCancel.setOnClickListener(v -> dismiss());
        btnUploadFile.setOnClickListener(v -> {
            // 跳转到下载管理页面
            dismiss();
            Intent intent = new Intent(context, DownloadsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        });
        dialog.setOnDismissListener(d -> stopServer());

        String serverUrl = "http://" + localIp + ":" + SERVER_PORT + "/";

        if (!localIp.isEmpty() && !localIp.equals("0.0.0.0")) {
            // 显示 IP:端口 格式，方便手机浏览器直接输入
            tvIpAddress.setText(serverUrl);
            // 点击 IP 地址直接在系统浏览器打开
            tvIpAddress.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show();
                }
            });
            tvIpAddress.setTextColor(0xFF7B61FF); // 紫色高亮

            // 生成二维码
            Bitmap qrBitmap = generateQrCode(serverUrl, 600);
            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap);
            }

            // 启动 HTTP Server
            startServer(localIp, tvWaitStatus);
        } else {
            // 未连接到局域网
            tvIpAddress.setText("\u672a\u8fde\u63a5\u5c40\u57df\u7f51");
            tvIpAddress.setTextColor(0xFFFF5252); // 红色提示
            tvWaitStatus.setText("\u65e0\u6cd5\u542f\u52a8\u670d\u52a1\u5668\uff0c\u8bf7\u5148\u8fde\u63a5 WiFi \u6216\u7f51\u7ebf");
            ivQrCode.setImageResource(android.R.drawable.ic_dialog_alert);
        }

        dialog.show();
    }

    private void startServer(String localIp, TextView tvWaitStatus) {
        executor = Executors.newCachedThreadPool();
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(SERVER_PORT));

                while (!serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.execute(() -> handleClient(client, tvWaitStatus));
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
                    if (tvWaitStatus != null && dialog != null && dialog.isShowing()) {
                        tvWaitStatus.setText("\u542f\u52a8\u670d\u52a1\u5668\u5931\u8d25: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void handleClient(Socket client, TextView tvWaitStatus) {
        try {
            byte[] buffer = new byte[4096];
            int len = client.getInputStream().read(buffer);
            if (len <= 0) { client.close(); return; }

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
                ps.print("HTTP/1.1 200 OK\r\n");
                ps.print("Content-Type: text/html; charset=UTF-8\r\n");
                ps.print("Content-Length: " + body.length + "\r\n");
                ps.print("Connection: close\r\n\r\n");
                ps.flush();
                out.write(body);
                out.flush();

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
                    ps.print("HTTP/1.1 200 OK\r\n");
                    ps.print("Content-Type: text/html; charset=UTF-8\r\n");
                    ps.print("Content-Length: " + body.length + "\r\n");
                    ps.print("Connection: close\r\n\r\n");
                    ps.flush();
                    out.write(body);
                    out.flush();

                    // 在主线程回调
                    mainHandler.post(() -> {
                        if (callback != null) callback.onUrlReceived(finalUrl);
                    });
                } else {
                    sendRedirect(ps, "/");
                }

            } else if ("GET".equals(method) && "/upload".equals(path)) {
                // 返回文件上传页面
                String html = buildUploadHtml();
                byte[] body = html.getBytes("UTF-8");
                ps.print("HTTP/1.1 200 OK\r\n");
                ps.print("Content-Type: text/html; charset=UTF-8\r\n");
                ps.print("Content-Length: " + body.length + "\r\n");
                ps.print("Connection: close\r\n\r\n");
                ps.flush();
                out.write(body);
                out.flush();

            } else if ("POST".equals(method) && "/upload".equals(path)) {
                // 处理文件上传
                handleUpload(client, request, buffer, len, out, ps, tvWaitStatus);
                return; // handleUpload 会自己关闭连接

            } else if ("GET".equals(method) && "/jump".equals(path)) {
                // 跳转按钮：打开文件接收目录
                sendRedirect(ps, "file:///storage/emulated/0/Download/%E5%96%B0%E5%96%B0%E5%97%8E%E5%BD%B1%E8%A6%96/");
                return;

            } else {
                // 404
                ps.print("HTTP/1.1 302 Found\r\nLocation: /\r\nConnection: close\r\n\r\n");
                ps.flush();
            }

            out.flush();
            client.close();
        } catch (Exception e) {
            e.printStackTrace();
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void sendRedirect(PrintStream ps, String location) throws IOException {
        ps.print("HTTP/1.1 302 Found\r\n");
        ps.print("Location: " + location + "\r\n");
        ps.print("Connection: close\r\n\r\n");
        ps.flush();
    }

    /**
     * 构建手机端输入页面 HTML
     */
    private String buildInputHtml() {
        return "<!DOCTYPE html>\n" +
            "<html lang='zh'>\n" +
            "<head>\n" +
            "<meta charset='UTF-8'>\n" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0'>\n" +
            "<title>\u55b5\u55b5\u5662\u5f71\u89c6</title>\n" +
            "<style>\n" +
            "* { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "body { background: #0d0d1a; color: #fff; font-family: -apple-system, sans-serif;\n" +
            "       display: flex; align-items: center; justify-content: center;\n" +
            "       min-height: 100vh; padding: 16px; }\n" +
            ".card { background: #1a1a2e; border-radius: 16px; padding: 20px;\n" +
            "        width: 100%; max-width: 380px; box-shadow: 0 8px 32px rgba(0,0,0,0.5); }\n" +
            ".logo { font-size: 22px; font-weight: bold; text-align: center; margin-bottom: 16px;\n" +
            "        background: linear-gradient(135deg, #7B61FF, #FF6090); -webkit-background-clip: text;\n" +
            "        -webkit-text-fill-color: transparent; }\n" +
            ".section { margin-bottom: 16px; }\n" +
            ".section-title { font-size: 12px; color: #889; margin-bottom: 8px; text-align: center; }\n" +
            "input[type=url] { width: 100%; padding: 12px; border-radius: 10px;\n" +
            "  background: #0d1030; border: 2px solid #2a2a4a; color: #fff; font-size: 15px; outline: none; }\n" +
            "input:focus { border-color: #7B61FF; }\n" +
            ".btn { width: 100%; padding: 12px; border: none; border-radius: 10px;\n" +
            "       color: #fff; font-size: 15px; font-weight: bold; cursor: pointer; margin-top: 8px; }\n" +
            ".btn-url { background: linear-gradient(135deg, #7B61FF, #FF6090); }\n" +
            ".btn-file { background: linear-gradient(135deg, #00C853, #00E676); }\n" +
            ".btn:active { opacity: 0.8; }\n" +
            ".btn:disabled { opacity: 0.4; cursor: not-allowed; }\n" +
            ".upload-area { border: 2px dashed #3a3a5a; border-radius: 10px; padding: 16px;\n" +
            "             text-align: center; cursor: pointer; }\n" +
            ".upload-area:active { border-color: #00C853; }\n" +
            ".upload-icon { font-size: 28px; }\n" +
            ".upload-text { color: #889; font-size: 12px; margin-top: 4px; }\n" +
            "#fileInput { display: none; }\n" +
            ".file-info { margin-top: 8px; padding: 8px; background: #0d1030; border-radius: 8px;\n" +
            "             font-size: 11px; color: #aab; display: none; }\n" +
            ".progress { margin-top: 8px; display: none; }\n" +
            ".progress-bar { height: 4px; background: #2a2a4a; border-radius: 2px; overflow: hidden; }\n" +
            ".progress-fill { height: 100%; background: #00C853; border-radius: 2px; width: 0%; }\n" +
            ".progress-text { text-align: center; font-size: 11px; color: #667; margin-top: 4px; }\n" +
            "</style>\n" +
            "</head>\n" +
            "<body>\n" +
            "<div class='card'>\n" +
            "  <div class='logo'>&#128049; \u55b5\u55b5\u5662\u5f71\u89c6</div>\n" +
            "  \n" +
            "  <div class='section'>\n" +
            "    <div class='section-title'>\ud83d\udd17 \u63a8\u9001\u7f51\u5740\u5230\u7535\u89c6</div>\n" +
            "    <form action='/submit' method='get' id='form'>\n" +
            "      <input type='url' name='url' id='urlInput' placeholder='https://...' autocomplete='off'/>\n" +
            "      <button type='submit' class='btn btn-url'>\u53d1\u9001\u7f51\u5740</button>\n" +
            "    </form>\n" +
            "  </div>\n" +
            "  \n" +
            "  <div class='section'>\n" +
            "    <div class='section-title'>\ud83d\udce4 \u4e0a\u4f20\u6587\u4ef6\u5230\u7535\u89c6</div>\n" +
            "    <div class='upload-area' id='uploadArea' onclick='document.getElementById(\"fileInput\").click()'>\n" +
            "      <div class='upload-icon'>&#128228;</div>\n" +
            "      <div class='upload-text'>\u70b9\u51fb\u9009\u62e9\u6587\u4ef6</div>\n" +
            "    </div>\n" +
            "    <input type='file' id='fileInput' onchange='onFileSelected(this)'/>\n" +
            "    <div class='file-info' id='fileInfo'></div>\n" +
            "    <div class='progress' id='progress'>\n" +
            "      <div class='progress-bar'><div class='progress-fill' id='progressFill'></div></div>\n" +
            "      <div class='progress-text' id='progressText'></div>\n" +
            "    </div>\n" +
            "    <button class='btn btn-file' id='btnUpload' onclick='doUpload()' style='display:none'>\u4e0a\u4f20\u6587\u4ef6</button>\n" +
            "  </div>\n" +
            "</div>\n" +
            "<script>\n" +
            "document.getElementById('form').onsubmit = function(e) {\n" +
            "  var v = document.getElementById('urlInput').value.trim();\n" +
            "  if (!v) { e.preventDefault(); return false; }\n" +
            "  if (!v.startsWith('http')) { document.getElementById('urlInput').value = 'https://' + v; }\n" +
            "};\n" +
            "var selectedFile = null;\n" +
            "function onFileSelected(input) {\n" +
            "  if (input.files && input.files[0]) {\n" +
            "    selectedFile = input.files[0];\n" +
            "    var sizeStr = selectedFile.size < 1024*1024 ? (selectedFile.size/1024).toFixed(1)+' KB' : (selectedFile.size/1024/1024).toFixed(1)+' MB';\n" +
            "    document.getElementById('fileInfo').style.display = 'block';\n" +
            "    document.getElementById('fileInfo').textContent = selectedFile.name + ' (' + sizeStr + ')';\n" +
            "    document.getElementById('btnUpload').style.display = 'block';\n" +
            "    document.getElementById('btnUpload').disabled = false;\n" +
            "  }\n" +
            "}\n" +
            "function doUpload() {\n" +
            "  if (!selectedFile) return;\n" +
            "  var btn = document.getElementById('btnUpload');\n" +
            "  var prog = document.getElementById('progress');\n" +
            "  prog.style.display = 'block';\n" +
            "  btn.disabled = true;\n" +
            "  btn.textContent = '\u4e0a\u4f20\u4e2d...';\n" +
            "  var xhr = new XMLHttpRequest();\n" +
            "  var fd = new FormData();\n" +
            "  fd.append('file', selectedFile);\n" +
            "  xhr.upload.onprogress = function(e) {\n" +
            "    if (e.lengthComputable) {\n" +
            "      var pct = Math.round(e.loaded / e.total * 100);\n" +
            "      document.getElementById('progressFill').style.width = pct + '%';\n" +
            "      document.getElementById('progressText').textContent = pct + '%';\n" +
            "    }\n" +
            "  };\n" +
            "  xhr.onload = function() {\n" +
            "    if (xhr.status === 200) {\n" +
            "      document.getElementById('progressFill').style.width = '100%';\n" +
            "      document.getElementById('progressText').textContent = '\u2714 \u6210\u529f!';\n" +
            "      btn.textContent = '\u2714 \u4e0a\u4f20\u6210\u529f';\n" +
            "    } else {\n" +
            "      document.getElementById('progressText').textContent = '\u5931\u8d25: ' + xhr.status;\n" +
            "      btn.disabled = false; btn.textContent = '\u91cd\u8bd5';\n" +
            "    }\n" +
            "  };\n" +
            "  xhr.onerror = function() { document.getElementById('progressText').textContent = '\u7f51\u7edc\u9519\u8bef'; btn.disabled = false; };\n" +
            "  xhr.open('POST', '/upload', true);\n" +
            "  xhr.send(fd);\n" +
            "}\n" +
            "</script>\n" +
            "</body></html>";
    }

    /**
     * 手机端提交成功后显示的页面
     */
    private String buildSuccessHtml(String url) {
        return "<!DOCTYPE html>\n" +
            "<html lang='zh'>\n" +
            "<head>\n" +
            "<meta charset='UTF-8'>\n" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
            "<title>\u53d1\u9001\u6210\u529f</title>\n" +
            "<style>\n" +
            "body { background: #0d0d1a; color: #fff; font-family: sans-serif;\n" +
            "       display: flex; align-items: center; justify-content: center;\n" +
            "       min-height: 100vh; text-align: center; padding: 20px; }\n" +
            ".card { background: #1a1a2e; border-radius: 20px; padding: 40px 24px; max-width: 400px; width: 100%; }\n" +
            ".icon { font-size: 60px; margin-bottom: 16px; }\n" +
            ".title { font-size: 22px; font-weight: bold; color: #00E5A0; margin-bottom: 10px; }\n" +
            ".url { font-size: 13px; color: #889; word-break: break-all; margin-top: 12px;\n" +
            "       background: #0d1030; padding: 12px; border-radius: 10px; }\n" +
            "</style>\n" +
            "</head>\n" +
            "<body>\n" +
            "<div class='card'>\n" +
            "  <div class='icon'>&#9989;</div>\n" +
            "  <div class='title'>\u5df2\u53d1\u9001\u5230\u7535\u89c6&#33;;</div>\n" +
            "  <div class='hint'>\u7f51\u5740\u5df2\u63a8\u9001\uff0c\u7535\u89c6\u6b63\u5728\u6253\u5f00</div>\n" +
            "  <div class='url'>" + escapeHtml(url) + "</div>\n" +
            "  <a class='btn' href='/jump'>\u6253\u5f00\u63a5\u6536\u6587\u4ef6\u7684\u6587\u4ef6\u5939</a>\n" +
            "</div>\n" +
            "</body></html>";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 构建手机端文件上传页面 HTML
     */
    private String buildUploadHtml() {
        return "<!DOCTYPE html>\n" +
            "<html lang='zh'>\n" +
            "<head>\n" +
            "<meta charset='UTF-8'>\n" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0'>\n" +
            "<title>\u55b5\u55b5\u5662\u5f71\u89c6 - \u4e0a\u4f20\u6587\u4ef6</title>\n" +
            "<style>\n" +
            "* { box-sizing: border-box; margin: 0; padding: 0; }\n" +
            "body { background: #0d0d1a; color: #fff; font-family: -apple-system, sans-serif;\n" +
            "       display: flex; align-items: center; justify-content: center;\n" +
            "       min-height: 100vh; padding: 20px; }\n" +
            ".card { background: #1a1a2e; border-radius: 20px; padding: 32px 24px;\n" +
            "        width: 100%; max-width: 400px; box-shadow: 0 8px 32px rgba(0,0,0,0.5); }\n" +
            ".logo { font-size: 28px; font-weight: bold; text-align: center; margin-bottom: 6px;\n" +
            "        background: linear-gradient(135deg, #7B61FF, #FF6090); -webkit-background-clip: text;\n" +
            "        -webkit-text-fill-color: transparent; }\n" +
            ".sub { text-align: center; color: #778; font-size: 13px; margin-bottom: 28px; }\n" +
            ".upload-area { border: 2px dashed #3a3a5a; border-radius: 16px; padding: 32px 16px;\n" +
            "             text-align: center; cursor: pointer; transition: border-color 0.2s; }\n" +
            ".upload-area:active, .upload-area.dragover { border-color: #7B61FF; }\n" +
            ".upload-icon { font-size: 48px; margin-bottom: 8px; }\n" +
            ".upload-text { color: #889; font-size: 14px; }\n" +
            ".file-info { margin-top: 16px; padding: 12px; background: #0d1030; border-radius: 10px;\n" +
            "             font-size: 13px; color: #aab; display: none; word-break: break-all; }\n" +
            "#fileInput { display: none; }\n" +
            ".btn { width: 100%; padding: 14px; margin-top: 16px; border: none;\n" +
            "       border-radius: 12px; color: #fff; font-size: 18px; font-weight: bold;\n" +
            "       cursor: pointer; letter-spacing: 2px; transition: opacity 0.2s; }\n" +
            ".btn-upload { background: linear-gradient(135deg, #7B61FF, #FF6090); }\n" +
            ".btn-back { background: #2a2a4a; }\n" +
            ".btn:active { opacity: 0.8; }\n" +
            ".btn:disabled { opacity: 0.4; cursor: not-allowed; }\n" +
            ".progress { margin-top: 16px; display: none; }\n" +
            ".progress-bar { height: 6px; background: #2a2a4a; border-radius: 3px; overflow: hidden; }\n" +
            ".progress-fill { height: 100%; background: linear-gradient(90deg, #7B61FF, #FF6090);\n" +
            "                border-radius: 3px; width: 0%; transition: width 0.3s; }\n" +
            ".progress-text { text-align: center; font-size: 12px; color: #667; margin-top: 6px; }\n" +
            ".hint { margin-top: 12px; font-size: 12px; color: #556; text-align: center; }\n" +
            "</style>\n" +
            "</head>\n" +
            "<body>\n" +
            "<div class='card'>\n" +
            "  <div class='logo'>&#128049; \u55b5\u55b5\u5662\u5f71\u89c6</div>\n" +
            "  <div class='sub'>\u4e0a\u4f20\u6587\u4ef6\u5230\u7535\u89c6</div>\n" +
            "  <div class='upload-area' id='uploadArea' onclick='document.getElementById(\"fileInput\").click()'>\n" +
            "    <div class='upload-icon'>&#128228;</div>\n" +
            "    <div class='upload-text'>\u70b9\u51fb\u9009\u62e9\u6587\u4ef6</div>\n" +
            "  </div>\n" +
            "  <input type='file' id='fileInput' onchange='onFileSelected(this)'/>\n" +
            "  <div class='file-info' id='fileInfo'></div>\n" +
            "  <div class='progress' id='progress'>\n" +
            "    <div class='progress-bar'><div class='progress-fill' id='progressFill'></div></div>\n" +
            "    <div class='progress-text' id='progressText'>\u4e0a\u4f20\u4e2d...</div>\n" +
            "  </div>\n" +
            "  <button class='btn btn-upload' id='btnUpload' onclick='doUpload()' disabled>\u4e0a\u4f20\u5230\u7535\u89c6</button>\n" +
            "  <a href='/' class='btn btn-back' style='display:block;text-align:center;text-decoration:none;'>\u8fd4\u56de\u8f93\u5165\u7f51\u5740</a>\n" +
            "  <div class='hint'>\u6587\u4ef6\u5c06\u4fdd\u5b58\u5230\u7535\u89c6 Download/\u55b5\u55b5\u5662\u5f71\u89c6/ \u76ee\u5f55</div>\n" +
            "</div>\n" +
            "<script>\n" +
            "var selectedFile = null;\n" +
            "function onFileSelected(input) {\n" +
            "  if (input.files && input.files[0]) {\n" +
            "    selectedFile = input.files[0];\n" +
            "    var sizeStr = selectedFile.size < 1024*1024\n" +
            "      ? (selectedFile.size/1024).toFixed(1)+' KB'\n" +
            "      : (selectedFile.size/1024/1024).toFixed(1)+' MB';\n" +
            "    document.getElementById('fileInfo').style.display = 'block';\n" +
            "    document.getElementById('fileInfo').textContent =\n" +
            "      selectedFile.name + ' (' + sizeStr + ')';\n" +
            "    document.getElementById('btnUpload').disabled = false;\n" +
            "  }\n" +
            "}\n" +
            "function doUpload() {\n" +
            "  if (!selectedFile) return;\n" +
            "  var btn = document.getElementById('btnUpload');\n" +
            "  var prog = document.getElementById('progress');\n" +
            "  var fill = document.getElementById('progressFill');\n" +
            "  var text = document.getElementById('progressText');\n" +
            "  btn.disabled = true;\n" +
            "  btn.textContent = '\u4e0a\u4f20\u4e2d...';\n" +
            "  prog.style.display = 'block';\n" +
            "  var xhr = new XMLHttpRequest();\n" +
            "  var fd = new FormData();\n" +
            "  fd.append('file', selectedFile);\n" +
            "  xhr.upload.onprogress = function(e) {\n" +
            "    if (e.lengthComputable) {\n" +
            "      var pct = Math.round(e.loaded / e.total * 100);\n" +
            "      fill.style.width = pct + '%';\n" +
            "      text.textContent = pct + '%';\n" +
            "    }\n" +
            "  };\n" +
            "  xhr.onload = function() {\n" +
            "    if (xhr.status === 200) {\n" +
            "      fill.style.width = '100%';\n" +
            "      text.textContent = '\u2714 \u4e0a\u4f20\u6210\u529f\uff01';\n" +
            "      btn.textContent = '\u2714 \u4e0a\u4f20\u6210\u529f';\n" +
            "      btn.style.background = '#00C853';\n" +
            "    } else {\n" +
            "      text.textContent = '\u2716 \u4e0a\u4f20\u5931\u8d25\uff1a' + xhr.status;\n" +
            "      btn.disabled = false;\n" +
            "      btn.textContent = '\u91cd\u8bd5';\n" +
            "    }\n" +
            "  };\n" +
            "  xhr.onerror = function() {\n" +
            "    text.textContent = '\u2716 \u7f51\u7edc\u9519\u8bef';\n" +
            "    btn.disabled = false;\n" +
            "    btn.textContent = '\u91cd\u8bd5';\n" +
            "  };\n" +
            "  xhr.open('POST', '/upload', true);\n" +
            "  xhr.send(fd);\n" +
            "}\n" +
            "</script>\n" +
            "</body></html>";
    }

    /**
     * 处理文件上传（multipart POST）
     * 手机上传的文件保存到 Download/喵喵嗷影视/ 目录
     */
    private void handleUpload(Socket client, String request,
            byte[] buffer, int len,
            OutputStream out, PrintStream ps, TextView tvWaitStatus) {
        try {
            // 解析 Content-Length 和 boundary
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
                sendUploadError(ps, out, "\u65e0\u6548\u7684\u4e0a\u4f20\u8bf7\u6c42");
                client.close();
                return;
            }

            // 读取已有的请求体部分（初始4096缓冲区中除HTTP头之外的部分）
            // 注意：HTTP头是纯ASCII，字节数 = 字符数，indexOf查找安全
            int headerEndIdx = request.indexOf("\r\n\r\n");
            int headerByteLen = headerEndIdx + 4; // HTTP头（含\r\n\r\n）的字节数
            int bodyBytesRead = Math.max(0, len - headerByteLen); // 初始缓冲区中已读到的body字节数

            // 读取剩余的请求体
            byte[] bodyData = new byte[(int) contentLength];
            if (bodyBytesRead > 0) {
                // 从原始buffer中拷贝已读到的body部分（buffer是原始字节，不是re-encode的）
                System.arraycopy(buffer, headerByteLen, bodyData, 0, bodyBytesRead);
            }
            int totalRead = bodyBytesRead;
            while (totalRead < contentLength) {
                int toRead = (int) Math.min(8192, contentLength - totalRead);
                int r = client.getInputStream().read(bodyData, totalRead, toRead);
                if (r <= 0) break;
                totalRead += r;
            }

            // 解析 multipart 数据，提取文件名和文件内容
            String bodyStr = new String(bodyData, 0, Math.min(totalRead, 4096), "UTF-8");
            String fileName = "upload_" + System.currentTimeMillis();
            // 尝试从 Content-Disposition 提取文件名
            int nameIdx = bodyStr.indexOf("filename=\"");
            if (nameIdx >= 0) {
                int nameEnd = bodyStr.indexOf("\"", nameIdx + 10);
                if (nameEnd > nameIdx) {
                    fileName = bodyStr.substring(nameIdx + 10, nameEnd);
                }
            }

            // 找到文件数据的起始位置（\r\n\r\n 在第一个 boundary 之后）
            byte[] boundaryBytes = boundary.getBytes("UTF-8");
            int headerEnd = findBytes(bodyData, totalRead, "\r\n\r\n".getBytes());
            if (headerEnd < 0) {
                sendUploadError(ps, out, "\u65e0\u6cd5\u89e3\u6790\u6587\u4ef6\u6570\u636e");
                client.close();
                return;
            }
            int fileDataStart = headerEnd + 4; // 跳过 \r\n\r\n
            // 文件数据结束位置（倒数 boundary 之前的 \r\n）
            byte[] endMarker = ("\r\n" + boundary).getBytes("UTF-8");
            int fileDataEnd = findBytes(bodyData, totalRead, endMarker);
            if (fileDataEnd < 0) fileDataEnd = totalRead;

            int fileLength = fileDataEnd - fileDataStart;
            if (fileLength <= 0) {
                sendUploadError(ps, out, "\u6587\u4ef6\u4e3a\u7a7a");
                client.close();
                return;
            }

            // 保存文件到 Download/喵喵嗷影视/
            final String finalFileName = fileName;
            final int finalFileLength = fileLength;
            final int finalFileStart = fileDataStart;
            final byte[] finalBodyData = bodyData;

            // 在后台线程保存文件
            executor.execute(() -> {
                boolean saved = false;
                String savedName = finalFileName;
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // ===== Android 10+ : MediaStore API，无需存储权限 =====
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, finalFileName);
                        values.put(MediaStore.Downloads.MIME_TYPE, guessMimeType(finalFileName));
                        values.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/\u55b5\u55b5\u5662\u5f71\u89c6");
                        values.put(MediaStore.Downloads.IS_PENDING, 1);

                        Uri uri = context.getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri == null) throw new IOException("MediaStore insert failed");

                        try (java.io.OutputStream fos =
                                context.getContentResolver().openOutputStream(uri)) {
                            if (fos == null) throw new IOException("Cannot open MediaStore stream");
                            fos.write(finalBodyData, finalFileStart, finalFileLength);
                            fos.flush();
                        }

                        // 写完清除 pending 标记
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Downloads.IS_PENDING, 0);
                        context.getContentResolver().update(uri, done, null, null);
                        saved = true;
                    } else {
                        // ===== Android 9- : 传统文件路径写入 =====
                        File downloadDir = new File(
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                            "\u55b5\u55b5\u5662\u5f71\u89c6"
                        );
                        if (!downloadDir.exists()) downloadDir.mkdirs();

                        // 生成不重复文件名
                        File outFile = new File(downloadDir, finalFileName);
                        if (outFile.exists()) {
                            String nameNoExt = finalFileName.contains(".")
                                ? finalFileName.substring(0, finalFileName.lastIndexOf('.'))
                                : finalFileName;
                            String ext = finalFileName.contains(".")
                                ? finalFileName.substring(finalFileName.lastIndexOf('.'))
                                : "";
                            int counter = 1;
                            while (outFile.exists()) {
                                outFile = new File(downloadDir,
                                    nameNoExt + "(" + counter++ + ")" + ext);
                            }
                        }
                        savedName = outFile.getName();

                        FileOutputStream fos = new FileOutputStream(outFile);
                        fos.write(finalBodyData, finalFileStart, finalFileLength);
                        fos.flush();
                        fos.close();
                        saved = true;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final boolean finalSaved = saved;
                final String finalSavedName = savedName;
                mainHandler.post(() -> {
                    if (finalSaved) {
                        if (tvWaitStatus != null && dialog != null && dialog.isShowing()) {
                            tvWaitStatus.setText("\u2705 \u5df2\u63a5\u6536\u6587\u4ef6\uff1a" + finalSavedName);
                        }
                        Toast.makeText(context,
                            "\u6587\u4ef6\u5df2\u4fdd\u5b58\uff1a" + finalSavedName,
                            Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(context,
                            "\u6587\u4ef6\u4fdd\u5b58\u5931\u8d25",
                            Toast.LENGTH_LONG).show();
                    }
                });
            });

            // 立即返回 HTTP 成功响应（文件保存在后台异步进行）
            String response = "{\"status\":\"ok\",\"filename\":\"" + escapeJson(finalFileName) + "\",\"size\":" + fileLength + "}";
            byte[] resBody = response.getBytes("UTF-8");
            ps.print("HTTP/1.1 200 OK\r\n");
            ps.print("Content-Type: application/json; charset=UTF-8\r\n");
            ps.print("Content-Length: " + resBody.length + "\r\n");
            ps.print("Access-Control-Allow-Origin: *\r\n");
            ps.print("Connection: close\r\n\r\n");
            ps.flush();
            out.write(resBody);
            out.flush();

        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendUploadError(ps, out, "\u4e0a\u4f20\u5931\u8d25\uff1a" + e.getMessage());
            } catch (IOException ignored) {}
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void sendUploadError(PrintStream ps, OutputStream out, String msg) throws IOException {
        String response = "{\"status\":\"error\",\"message\":\"" + escapeJson(msg) + "\"}";
        byte[] body = response.getBytes("UTF-8");
        ps.print("HTTP/1.1 400 Bad Request\r\n");
        ps.print("Content-Type: application/json; charset=UTF-8\r\n");
        ps.print("Content-Length: " + body.length + "\r\n");
        ps.print("Connection: close\r\n\r\n");
        ps.flush();
        out.write(body);
        out.flush();
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
        if (lower.endsWith(".flac")) return "audio/flac";
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

    /**
     * 用 ZXing 生成二维码 Bitmap
     */
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

    /**
     * 获取本机局域网 IP 地址
     * 优先通过遍历所有网络接口获取（支持 WiFi / 以太网 / 网线）
     * WiFi 作为 fallback
     */
    private String getLocalIpAddress() {
        try {
            // 优先：遍历所有网络接口，找局域网 IPv4
            java.net.NetworkInterface iface;
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                iface = interfaces.nextElement();
                // 跳过 loopback 和无效接口
                if (iface.isLoopback() || !iface.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        // 过滤掉容器/虚拟地址（如 172.x、192.168.56.x 等常见虚拟机网段可保留，
                        // 但排除 127.x loopback 已在上层过滤）
                        if (ip != null && !ip.isEmpty()) {
                            return ip;
                        }
                    }
                }
            }

            // Fallback：尝试从 WifiManager 获取
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    return Formatter.formatIpAddress(ipInt);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "0.0.0.0";
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        stopServer();
    }

    private void stopServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
