package com.miaomiao.tv;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
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

        btnCancel.setOnClickListener(v -> dismiss());
        dialog.setOnDismissListener(d -> stopServer());

        // 先获取 IP
        String localIp = getLocalIpAddress();
        String serverUrl = "http://" + localIp + ":" + SERVER_PORT + "/";

        if (!localIp.isEmpty() && !localIp.equals("0.0.0.0")) {
            tvIpAddress.setText("\u624b\u673a\u8fde\u540c\u4e00\u4e2a\u5c40\u57df\u7f51\uff0c\u626b\u7801\u8f93\u5165\u7f51\u5740");

            // 生成二维码
            Bitmap qrBitmap = generateQrCode(serverUrl, 600);
            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap);
            }

            // 启动 HTTP Server
            startServer(localIp, tvWaitStatus);
        } else {
            tvIpAddress.setText("\u672a\u8fde\u63a5\u5c40\u57df\u7f51\uff0c\u8bf7\u5148\u8fde\u63a5 WiFi \u6216\u7f51\u7ebf");
            tvWaitStatus.setText("\u65e0\u6cd5\u542f\u52a8\u670d\u52a1\u5668");
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
                        dismiss();
                    });
                } else {
                    sendRedirect(ps, "/");
                }

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
            "<title>\u55b5\u55b5\u5662\u5f71\u89c6 - \u8f93\u5165\u7f51\u5740</title>\n" +
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
            "input[type=url], input[type=text] {\n" +
            "  width: 100%; padding: 14px 16px; border-radius: 12px;\n" +
            "  background: #0d1030; border: 2px solid #2a2a4a;\n" +
            "  color: #fff; font-size: 16px; outline: none;\n" +
            "  transition: border-color 0.2s; }\n" +
            "input:focus { border-color: #7B61FF; }\n" +
            ".btn { width: 100%; padding: 14px; margin-top: 16px; border: none;\n" +
            "       border-radius: 12px; background: linear-gradient(135deg, #7B61FF, #FF6090);\n" +
            "       color: #fff; font-size: 18px; font-weight: bold; cursor: pointer;\n" +
            "       letter-spacing: 2px; transition: opacity 0.2s; }\n" +
            ".btn:active { opacity: 0.8; }\n" +
            ".hint { margin-top: 12px; font-size: 12px; color: #556; text-align: center; }\n" +
            "</style>\n" +
            "</head>\n" +
            "<body>\n" +
            "<div class='card'>\n" +
            "  <div class='logo'>&#128049; \u55b5\u55b5\u5662\u5f71\u89c6</div>\n" +
            "  <div class='sub'>\u8f93\u5165\u8981\u5728\u7535\u89c6\u4e0a\u6253\u5f00\u7684\u7f51\u5740</div>\n" +
            "  <form action='/submit' method='get' id='form'>\n" +
            "    <input type='url' name='url' id='urlInput'\n" +
            "           placeholder='https://...'\n" +
            "           autocomplete='off' autocorrect='off' autocapitalize='off'\n" +
            "           spellcheck='false'/>\n" +
            "    <button type='submit' class='btn'>\u53d1\u9001\u5230\u7535\u89c6</button>\n" +
            "  </form>\n" +
            "  <div class='hint'>\u70b9\u51fb\u6309\u9215\u540e\u7535\u89c6\u5c06\u81ea\u52a8\u6253\u5f00\u8be5\u7f51\u5740</div>\n" +
            "</div>\n" +
            "<script>\n" +
            "document.getElementById('urlInput').focus();\n" +
            "document.getElementById('form').onsubmit = function(e) {\n" +
            "  var v = document.getElementById('urlInput').value.trim();\n" +
            "  if (!v) { e.preventDefault(); return false; }\n" +
            "  if (!v.startsWith('http')) { \n" +
            "    document.getElementById('urlInput').value = 'https://' + v;\n" +
            "  }\n" +
            "};\n" +
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
            "  <div>\u7535\u89c6\u6b63\u5728\u6253\u5f00\u8be5\u7f51\u5740</div>\n" +
            "  <div class='url'>" + escapeHtml(url) + "</div>\n" +
            "</div>\n" +
            "</body></html>";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
