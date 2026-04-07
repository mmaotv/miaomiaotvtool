package com.miaomiao.tv;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 投屏接收服务 - 后台运行，接收手机投屏
 * 使用HTTP协议接收视频流并通过本地WebView播放
 */
public class CastReceiverService extends Service {

    private static final String TAG = "CastReceiverService";
    public static final int PORT = 18766; // 投屏服务端口（public供外部访问）
    private static final String SERVICE_NAME = "MiaoMiaoTV"; // NSD 服务名称
    private static final String SERVICE_TYPE_HTTP = "_http._tcp.";  // HTTP 服务类型
    private static final String SERVICE_TYPE_AIRPLAY = "_airplay._tcp."; // AirPlay 服务类型

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Handler mainHandler;
    private boolean isRunning = false;

    // NSD 相关
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListenerHttp;
    private NsdManager.RegistrationListener nsdListenerAirplay;
    private NsdServiceInfo serviceInfoHttp;
    private NsdServiceInfo serviceInfoAirplay;

    // 投屏状态监听器
    public interface CastListener {
        void onCastStart(String deviceName, String url);
        void onCastStop();
        void onDeviceConnected(String deviceName);
    }
    private static final CopyOnWriteArrayList<CastListener> listeners = new CopyOnWriteArrayList<>();

    // 投屏历史
    public static class CastHistory {
        public String deviceName;
        public String url;
        public long timestamp;
        public String type; // "photo", "video", "screen"
    }
    private static final CopyOnWriteArrayList<CastHistory> castHistory = new CopyOnWriteArrayList<>();

    public static void addListener(CastListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    public static void removeListener(CastListener listener) {
        listeners.remove(listener);
    }
    public static CopyOnWriteArrayList<CastHistory> getHistory() {
        return castHistory;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        Log.d(TAG, "投屏服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            startServer();
        }
        return START_STICKY;
    }

    private void startServer() {
        isRunning = true;
        executor = Executors.newCachedThreadPool();
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "投屏服务已启动，端口：" + PORT);

                // 通知UI服务已启动
                mainHandler.post(() -> {
                    notifyServiceStarted();
                    // 注册 NSD 服务让手机可以发现
                    registerNsdService();
                });

                while (isRunning) {
                    try {
                        Socket client = serverSocket.accept();
                        if (!isRunning) break;
                        executor.execute(() -> handleClient(client));
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.e(TAG, "接收连接失败: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "服务启动失败: " + e.getMessage());
            }
        });
    }

    /**
     * 注册 NSD 服务让同一局域网内的设备可以发现投屏服务
     */
    private void registerNsdService() {
        if (nsdManager == null) {
            Log.w(TAG, "NSD Manager 不可用");
            return;
        }

        // 创建 HTTP 服务的 NSD 注册监听器
        nsdListenerHttp = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                serviceInfoHttp = info;
                String name = info.getServiceName();
                Log.d(TAG, "HTTP 服务已注册: " + name);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "HTTP 服务注册失败: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                Log.d(TAG, "HTTP 服务已注销");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "HTTP 服务注销失败: " + errorCode);
            }
        };

        // 创建 AirPlay 服务的 NSD 注册监听器
        nsdListenerAirplay = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                serviceInfoAirplay = info;
                String name = info.getServiceName();
                Log.d(TAG, "AirPlay 服务已注册: " + name);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "AirPlay 服务注册失败: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                Log.d(TAG, "AirPlay 服务已注销");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "AirPlay 服务注销失败: " + errorCode);
            }
        };

        // 获取本机 IP
        String localIp = getLocalIP();
        if (localIp.equals("127.0.0.1")) {
            // 尝试获取真实 IP
            localIp = getRealLocalIP();
        }

        // 注册 HTTP 服务
        NsdServiceInfo httpServiceInfo = new NsdServiceInfo();
        httpServiceInfo.setServiceName(SERVICE_NAME + "_http");
        httpServiceInfo.setServiceType(SERVICE_TYPE_HTTP);
        httpServiceInfo.setPort(PORT);
        // 添加 TXT 记录，包含设备信息
        httpServiceInfo.setAttribute("deviceId", android.os.Build.MODEL);
        httpServiceInfo.setAttribute("features", "airplay");

        try {
            nsdManager.registerService(httpServiceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListenerHttp);
            Log.d(TAG, "正在注册 HTTP NSD 服务...");
        } catch (Exception e) {
            Log.e(TAG, "注册 HTTP NSD 服务失败: " + e.getMessage());
        }

        // 注册 AirPlay 服务（让 iPhone 的 AirPlay 可以发现）
        NsdServiceInfo airplayServiceInfo = new NsdServiceInfo();
        airplayServiceInfo.setServiceName(SERVICE_NAME);
        airplayServiceInfo.setServiceType(SERVICE_TYPE_AIRPLAY);
        airplayServiceInfo.setPort(PORT);
        // AirPlay 协议需要的属性
        airplayServiceInfo.setAttribute("deviceid", SERVICE_NAME + "_" + android.os.Build.MODEL);
        airplayServiceInfo.setAttribute("features", "0x5A7FFFF7"); // AirPlay 特性标志
        airplayServiceInfo.setAttribute("model", "AppleTV");
        airplayServiceInfo.setAttribute("pk", ""); // pairing buffer (empty if not paired)
        airplayServiceInfo.setAttribute("srcvers", "220.68"); // AirPlay 版本

        try {
            nsdManager.registerService(airplayServiceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListenerAirplay);
            Log.d(TAG, "正在注册 AirPlay NSD 服务...");
        } catch (Exception e) {
            Log.e(TAG, "注册 AirPlay NSD 服务失败: " + e.getMessage());
        }
    }

    /**
     * 获取真实的本机 IP 地址
     */
    private String getRealLocalIP() {
        try {
            java.net.NetworkInterface networkInterface = java.net.NetworkInterface.getNetworkInterfaces().nextElement();
            if (networkInterface != null) {
                Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取真实 IP 失败: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    /**
     * 注销 NSD 服务
     */
    private void unregisterNsdService() {
        if (nsdManager != null) {
            try {
                if (nsdListenerHttp != null) {
                    nsdManager.unregisterService(nsdListenerHttp);
                }
                if (nsdListenerAirplay != null) {
                    nsdManager.unregisterService(nsdListenerAirplay);
                }
            } catch (Exception e) {
                Log.e(TAG, "注销 NSD 服务失败: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            String clientIP = client.getInetAddress().getHostAddress();
            Log.d(TAG, "收到来自 " + clientIP + " 的连接");

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(client.getOutputStream());

            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }

            Log.d(TAG, "请求: " + requestLine);

            // 解析请求
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }

            String path = parts[1];

            // 处理不同的投屏请求
            if (path.equals("/cast")) {
                // 处理投屏请求
                handleCastRequest(reader, writer, clientIP);
            } else if (path.equals("/status")) {
                // 状态查询
                writer.println("HTTP/1.1 200 OK");
                writer.println("Content-Type: application/json");
                writer.println();
                writer.println("{\"status\":\"ready\",\"type\":\"cast-receiver\"}");
                writer.flush();
            } else if (path.startsWith("/notify/")) {
                // 投屏通知
                String notifyType = path.substring(8);
                handleNotify(notifyType, reader, writer, clientIP);
            } else {
                // 返回投屏控制页面
                sendControlPage(writer);
            }

            client.close();
        } catch (Exception e) {
            Log.e(TAG, "处理客户端失败: " + e.getMessage());
        }
    }

    /** 处理投屏请求 */
    private void handleCastRequest(BufferedReader reader, PrintWriter writer, String clientIP) throws IOException {
        // 读取请求头
        String line;
        String contentType = "";
        String content = "";

        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-type:")) {
                contentType = line.substring(13).trim();
            }
        }

        // 读取POST内容（如果有）
        if (contentType.contains("application/x-www-form-urlencoded")) {
            int contentLength = 0;
            reader.reset();
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            if (contentLength > 0) {
                char[] buffer = new char[contentLength];
                reader.read(buffer);
                content = new String(buffer);
            }
        }

        // 解析投屏内容
        String url = "";
        String type = "video";
        String title = "投屏内容";

        String[] params = content.split("&");
        for (String param : params) {
            String[] kv = param.split("=");
            if (kv.length == 2) {
                String key = kv[0];
                String value = java.net.URLDecoder.decode(kv[1], "UTF-8");
                switch (key) {
                    case "url": url = value; break;
                    case "type": type = value; break;
                    case "title": title = value; break;
                }
            }
        }

        // 添加到历史记录
        final CastHistory history = new CastHistory();
        history.deviceName = "设备 " + clientIP;
        history.url = url;
        history.timestamp = System.currentTimeMillis();
        history.type = type;
        castHistory.add(0, history);

        // 只保留最近50条
        while (castHistory.size() > 50) {
            castHistory.remove(castHistory.size() - 1);
        }

        // 保存最终值用于lambda
        final String finalDeviceName = history.deviceName;
        final String finalUrl = url;

        // 通知监听器
        mainHandler.post(() -> {
            for (CastListener listener : listeners) {
                listener.onCastStart(finalDeviceName, finalUrl);
            }
        });

        // 返回成功响应
        writer.println("HTTP/1.1 200 OK");
        writer.println("Content-Type: text/plain");
        writer.println();
        writer.println("OK");
        writer.flush();

        Log.d(TAG, "收到投屏请求: " + url + " from " + clientIP);
    }

    /** 处理通知请求 */
    private void handleNotify(String type, BufferedReader reader, PrintWriter writer, String clientIP) {
        if (type.equals("start")) {
            mainHandler.post(() -> {
                for (CastListener listener : listeners) {
                    listener.onDeviceConnected("设备 " + clientIP);
                }
            });
        } else if (type.equals("stop")) {
            mainHandler.post(() -> {
                for (CastListener listener : listeners) {
                    listener.onCastStop();
                }
            });
        }

        writer.println("HTTP/1.1 200 OK");
        writer.println();
        writer.flush();
    }

    /** 发送投屏控制页面 */
    private void sendControlPage(PrintWriter writer) {
        String ip = getLocalIP();
        String page = "<!DOCTYPE html><html><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<title>喵喵嗷投屏</title>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:'PingFang SC','Microsoft YaHei',sans-serif;background:#0D0D1A;color:#fff;min-height:100vh;display:flex;flex-direction:column}" +
            ".header{background:#1a1a2e;padding:20px;text-align:center;border-bottom:1px solid #333}" +
            ".header h1{font-size:24px;margin-bottom:8px}" +
            ".header p{color:#888;font-size:14px}" +
            ".content{flex:1;padding:20px}" +
            ".tip{background:#1a1a2e;border-radius:12px;padding:20px;margin-bottom:20px}" +
            ".tip h3{color:#00D9FF;margin-bottom:12px}" +
            ".tip ol{color:#ccc;line-height:2;font-size:14px;padding-left:20px}" +
            ".history{background:#1a1a2e;border-radius:12px;padding:20px}" +
            ".history h3{color:#FFD700;margin-bottom:12px}" +
            ".history-item{padding:12px 0;border-bottom:1px solid #333;display:flex;justify-content:space-between;align-items:center}" +
            ".history-item:last-child{border-bottom:none}" +
            ".history-item .name{color:#fff}" +
            ".history-item .time{color:#666;font-size:12px}" +
            ".btn-cast{background:linear-gradient(135deg,#00D9FF,#7B61FF);color:#fff;border:none;padding:15px 30px;border-radius:25px;font-size:16px;cursor:pointer;display:block;width:100%;margin-top:20px}" +
            ".btn-cast:active{transform:scale(0.98)}" +
            "input{width:100%;padding:12px 15px;background:#2a2a3e;border:1px solid #444;border-radius:8px;color:#fff;font-size:14px;margin:10px 0}" +
            ".status{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#1a1a2e;padding:10px 20px;border-radius:20px;font-size:14px;color:#00D9FF}" +
            "</style></head><body>" +
            "<div class='header'>" +
            "<h1>🐱 喵喵嗷投屏接收</h1>" +
            "<p>本机IP: " + ip + ":" + PORT + "</p>" +
            "</div>" +
            "<div class='content'>" +
            "<div class='tip'>" +
            "<h3>📱 手机投屏步骤</h3>" +
            "<ol>" +
            "<li>确保手机和本设备在同一WiFi网络</li>" +
            "<li>打开手机浏览器访问: <b style='color:#00D9FF'>" + ip + ":" + PORT + "</b></li>" +
            "<li>在网页中输入视频URL并点击投屏</li>" +
            "<li>视频将直接在本设备上播放</li>" +
            "</ol>" +
            "</div>" +
            "<div class='tip'>" +
            "<h3>🎬 快速投屏</h3>" +
            "<form id='castForm' action='/cast' method='POST'>" +
            "<input type='text' name='url' id='urlInput' placeholder='输入视频URL（支持mp4/m3u8等）' required>" +
            "<input type='text' name='title' placeholder='视频标题（可选）'>" +
            "<input type='hidden' name='type' value='video'>" +
            "<button type='submit' class='btn-cast'>📡 开始投屏</button>" +
            "</form>" +
            "</div>" +
            "<div class='history' id='historyDiv'>" +
            "<h3>📋 投屏历史</h3>" +
            "<div id='historyList'>加载中...</div>" +
            "</div>" +
            "</div>" +
            "<div class='status'>✅ 投屏服务运行中</div>" +
            "<script>" +
            "function loadHistory(){" +
            "  fetch('/history').then(r=>r.json()).then(d=>{" +
            "    if(d.length==0){" +
            "      document.getElementById('historyList').innerHTML='<p style=\\'color:#666\\'>暂无投屏记录</p>';" +
            "    }else{" +
            "      let html='';" +
            "      d.forEach(h=>{" +
            "        let date=new Date(h.timestamp);" +
            "        let time=date.toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'});" +
            "        html+=\"<div class='history-item'><span class='name'>\"+h.deviceName+\"</span><span class='time'>\"+time+\"</span></div>\";" +
            "      });" +
            "      document.getElementById('historyList').innerHTML=html;" +
            "    }" +
            "  }).catch(e=>console.log(e));" +
            "}" +
            "document.getElementById('castForm').onsubmit=function(){" +
            "  setTimeout(()=>{this.reset();loadHistory();},500);" +
            "};" +
            "loadHistory();" +
            "setInterval(loadHistory,5000);" +
            "</script>" +
            "</body></html>";

        writer.println("HTTP/1.1 200 OK");
        writer.println("Content-Type: text/html; charset=UTF-8");
        writer.println("Content-Length: " + page.getBytes().length);
        writer.println();
        writer.print(page);
        writer.flush();
    }

    /** 获取本机IP地址 - 优先获取局域网IP */
    private String getLocalIP() {
        try {
            // 遍历所有网络接口查找真实IP
            Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    // 跳过回环地址和链路本地地址
                    if (!addr.isLoopbackAddress() &&
                        !addr.isLinkLocalAddress() &&
                        addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取IP失败: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    /** 通知服务已启动 */
    private void notifyServiceStarted() {
        for (CastListener listener : listeners) {
            // 可以通知UI更新状态
        }
    }

    /** 获取服务状态 */
    public static boolean isServiceRunning() {
        return true; // 简化判断
    }

    /** 获取投屏地址 - 使用真实局域网IP */
    public static String getCastUrl() {
        String ip = getLocalIPStatic();
        if (ip == null || ip.isEmpty() || ip.equals("127.0.0.1")) {
            // 尝试备用方法
            try {
                java.net.InetAddress addr = java.net.InetAddress.getLocalHost();
                ip = addr.getHostAddress();
            } catch (Exception e) {
                return "";
            }
        }
        return "http://" + ip + ":" + PORT;
    }

    /** 获取本机IP的静态方法（供外部调用） */
    private static String getLocalIPStatic() {
        try {
            Enumeration<java.net.NetworkInterface> interfaces =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() &&
                        !addr.isLinkLocalAddress() &&
                        addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取IP失败: " + e.getMessage());
        }
        return "127.0.0.1";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        // 注销 NSD 服务
        unregisterNsdService();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        if (executor != null) {
            executor.shutdownNow();
        }
        Log.d(TAG, "投屏服务已停止");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
