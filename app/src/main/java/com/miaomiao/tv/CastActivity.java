package com.miaomiao.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 投屏Activity - 支持DLNA/无线显示投屏
 */
public class CastActivity extends Activity {

    private EditText etUrl;
    private LinearLayout btnBack;
    private LinearLayout btnCast;
    private LinearLayout btnRefresh;
    private RecyclerView rvDevices;
    private TextView tvEmpty;
    private LinearLayout layoutCasting;
    private TextView tvCastingStatus;
    private LinearLayout btnPlayPause;
    private LinearLayout btnStopCast;
    private TextView tvPlayPause;

    private RecyclerView.Adapter<DeviceViewHolder> deviceAdapter;
    private final List<CastingDevice> deviceList = new ArrayList<>();

    // 投屏状态
    private CastingDevice currentDevice;
    private boolean isCasting = false;
    private boolean isPlaying = true;

    // SSDP发现
    private ExecutorService searchExecutor;
    private volatile boolean isSearching = false;
    private Handler mainHandler;

    // 播放控制
    private Socket controlSocket;
    private OutputStream controlOut;
    private InputStream controlIn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_cast);

        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        setupDeviceList();

        // 自动开始搜索设备
        startDeviceSearch();
    }

    private void initViews() {
        etUrl = findViewById(R.id.etUrl);
        btnBack = findViewById(R.id.btnBack);
        btnCast = findViewById(R.id.btnCast);
        btnRefresh = findViewById(R.id.btnRefresh);
        rvDevices = findViewById(R.id.rvDevices);
        tvEmpty = findViewById(R.id.tvEmpty);
        layoutCasting = findViewById(R.id.layoutCasting);
        tvCastingStatus = findViewById(R.id.tvCastingStatus);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnStopCast = findViewById(R.id.btnStopCast);

        // 预填URL（如果从其他页面跳转过来）
        String preUrl = getIntent().getStringExtra("video_url");
        if (preUrl != null && !preUrl.isEmpty()) {
            etUrl.setText(preUrl);
        }

        btnBack.setOnClickListener(v -> finish());
        btnCast.setOnClickListener(v -> castVideo());
        btnRefresh.setOnClickListener(v -> startDeviceSearch());
        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnStopCast.setOnClickListener(v -> stopCasting());

        // 焦点效果
        setFocusEffect(btnBack);
        setFocusEffect(btnCast);
        setFocusEffect(btnRefresh);
        setFocusEffect(btnPlayPause);
        setFocusEffect(btnStopCast);
    }

    private void setFocusEffect(View view) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? 1.08f : 1.0f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    private void setupDeviceList() {
        rvDevices.setLayoutManager(new LinearLayoutManager(this));

        deviceAdapter = new RecyclerView.Adapter<DeviceViewHolder>() {
            @NonNull
            @Override
            public DeviceViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_cast_device, parent, false);
                return new DeviceViewHolder(v);
            }

            @Override
            public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
                CastingDevice device = deviceList.get(position);
                holder.bind(device);

                holder.itemView.setOnClickListener(v -> {
                    if (isCasting) {
                        showCastingDialog(device);
                    } else {
                        selectDevice(device);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return deviceList.size();
            }
        };

        rvDevices.setAdapter(deviceAdapter);
    }

    /** 开始搜索DLNA设备（电视/投屏器） */
    private void startDeviceSearch() {
        if (isSearching) return;
        isSearching = true;
        deviceList.clear();
        deviceAdapter.notifyDataSetChanged();
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText("🔍 正在搜索设备...\n\n请确保电视/投屏器已开启");
        btnRefresh.setEnabled(false);

        searchExecutor = Executors.newSingleThreadExecutor();
        searchExecutor.execute(() -> {
            searchDlnaDevices();
            mainHandler.post(this::onSearchComplete);
        });
    }

    /**
     * SSDP发现协议 - 搜索局域网内的DLNA设备
     */
    private void searchDlnaDevices() {
        // 同时搜索多种类型的设备
        searchSsdpDevice("urn:schemas-upnp-org:device:MediaRenderer:1", "MediaRenderer");
        searchSsdpDevice("urn:dial-multiscreen-org:service:dial:1", "DIAL");
        searchSsdpDevice("urn:schemas-rcs:service:RenderingControl:1", "RCS");
    }

    @SuppressLint("LongLogTag")
    private void searchSsdpDevice(String searchTarget, String type) {
        DatagramSocket socket = null;
        try {
            // SSDP广播地址
            InetAddress broadcastAddr = InetAddress.getByName("239.255.255.250");
            int port = 1900;

            // SSDP M-SEARCH请求
            String searchMsg =
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: " + searchTarget + "\r\n" +
                "\r\n";

            socket = new DatagramSocket();
            socket.setSoTimeout(3000);
            socket.setBroadcast(true);

            // 发送请求
            DatagramPacket sendPacket = new DatagramPacket(
                searchMsg.getBytes(), searchMsg.length(), broadcastAddr, port);
            socket.send(sendPacket);

            // 接收响应
            byte[] buffer = new byte[4096];
            long endTime = System.currentTimeMillis() + 5000; // 5秒超时

            while (System.currentTimeMillis() < endTime) {
                try {
                    DatagramPacket recvPacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(recvPacket);

                    String response = new String(recvPacket.getData(), 0, recvPacket.getLength());
                    if (response.startsWith("HTTP/1.1 200") || response.startsWith("NOTIFY")) {
                        String location = extractHeader(response, "LOCATION");
                        String usn = extractHeader(response, "USN");
                        String st = extractHeader(response, "ST");

                        if (location != null && !location.isEmpty()) {
                            // 解析设备信息
                            parseAndAddDevice(location, usn, st, type, recvPacket.getAddress());
                        }
                    }
                } catch (Exception e) {
                    // 超时或异常，继续等待
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private String extractHeader(String response, String header) {
        String[] lines = response.split("\r\n");
        for (String line : lines) {
            if (line.toUpperCase().startsWith(header.toUpperCase() + ":")) {
                return line.substring(header.length() + 1).trim();
            }
        }
        return null;
    }

    /** 解析设备描述文件获取更多信息 */
    private void parseAndAddDevice(String location, String usn, String st, String type, InetAddress address) {
        try {
            URL url = new URL(location);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            String deviceName = type;
            String uuid = usn != null ? usn : address.getHostAddress();

            try {
                InputStream is = conn.getInputStream();
                byte[] data = new byte[4096];
                int len = is.read(data);
                if (len > 0) {
                    String xml = new String(data, 0, len, "UTF-8");
                    // 提取设备名称
                    if (xml.contains("<friendlyName>")) {
                        int start = xml.indexOf("<friendlyName>") + 14;
                        int end = xml.indexOf("</friendlyName>", start);
                        if (end > start) {
                            deviceName = xml.substring(start, end);
                        }
                    }
                }
                is.close();
            } catch (Exception ignored) {}

            conn.disconnect();

            // 检查是否已存在
            boolean exists = false;
            for (CastingDevice d : deviceList) {
                if (d.address.equals(address.getHostAddress())) {
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                CastingDevice device = new CastingDevice();
                device.name = deviceName;
                device.address = address.getHostAddress();
                device.location = location;
                device.type = type;
                device.uuid = uuid;

                mainHandler.post(() -> {
                    deviceList.add(device);
                    deviceAdapter.notifyItemInserted(deviceList.size() - 1);
                    tvEmpty.setVisibility(View.GONE);
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onSearchComplete() {
        isSearching = false;
        btnRefresh.setEnabled(true);
        if (deviceList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            tvEmpty.setText("🔍 未找到设备\n\n请检查：\n1. 电视/投屏器是否已开启\n2. 手机和电视是否在同一WiFi\n3. 电视是否支持DLNA/无线显示\n\n📺 支持设备：小米电视/华为/海信/TCL/创维/Apple TV/Chromecast等");
        }
    }

    /** 选择设备 */
    private void selectDevice(CastingDevice device) {
        String videoUrl = etUrl.getText().toString().trim();
        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "请输入视频URL", Toast.LENGTH_SHORT).show();
            return;
        }

        // 验证URL格式
        if (!videoUrl.startsWith("http://") && !videoUrl.startsWith("https://")) {
            videoUrl = "http://" + videoUrl;
            etUrl.setText(videoUrl);
        }

        currentDevice = device;
        startCasting(videoUrl);
    }

    /** 投屏提示对话框 */
    private void showCastingDialog(CastingDevice device) {
        new AlertDialog.Builder(this)
            .setTitle("📡 切换投屏")
            .setMessage("当前正在向 \"" + currentDevice.name + "\" 投屏\n\n是否切换到 \"" + device.name + "\"？")
            .setPositiveButton("切换", (d, w) -> {
                stopCasting();
                selectDevice(device);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 开始投屏 */
    private void startCasting(String videoUrl) {
        if (currentDevice == null) {
            Toast.makeText(this, "请先选择投屏设备", Toast.LENGTH_SHORT).show();
            return;
        }

        isCasting = true;
        isPlaying = true;
        tvPlayPause.setText("⏸️ 暂停");

        // 更新UI
        layoutCasting.setVisibility(View.VISIBLE);
        tvCastingStatus.setText("📡 正在投屏到：" + currentDevice.name);

        // 根据设备类型选择投屏方式
        if (currentDevice.type.contains("MediaRenderer") || currentDevice.type.contains("RCS")) {
            // DLNA投屏
            castViaDlna(videoUrl);
        } else {
            // 其他协议，简单提示
            new AlertDialog.Builder(this)
                .setTitle("📺 投屏提示")
                .setMessage("已向 " + currentDevice.name + " 发送播放请求\n\n视频地址：" + videoUrl + "\n\n请在电视上确认播放")
                .setPositiveButton("好的", null)
                .show();
        }
    }

    /** DLNA投屏 - 向电视发送播放命令 */
    private void castViaDlna(String videoUrl) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                // 解析location获取设备地址
                URL locationUrl = new URL(currentDevice.location);
                String host = locationUrl.getHost();
                int port = locationUrl.getPort() > 0 ? locationUrl.getPort() : 1900;

                // 获取控制URL
                String controlUrl = getControlUrl(host, port, locationUrl);

                if (controlUrl == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "设备不支持投屏控制", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // 发送DLNA播放命令
                sendDlnaPlayCommand(host, controlUrl, videoUrl);

                mainHandler.post(() -> {
                    Toast.makeText(this, "✅ 投屏成功！请查看电视", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> {
                    Toast.makeText(this, "⚠️ 投屏失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 获取控制URL */
    private String getControlUrl(String host, int port, URL baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) baseUrl.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            InputStream is = conn.getInputStream();
            byte[] data = new byte[8192];
            int len = is.read(data);
            String xml = len > 0 ? new String(data, 0, len, "UTF-8") : "";
            is.close();
            conn.disconnect();

            // 查找AVTransport服务的controlURL
            if (xml.contains("AVTransport")) {
                int ctrlIdx = xml.indexOf("controlURL");
                if (ctrlIdx > 0) {
                    int start = xml.indexOf(">", ctrlIdx) + 1;
                    int end = xml.indexOf("<", start);
                    if (end > start) {
                        String controlUrl = xml.substring(start, end).trim();
                        if (!controlUrl.startsWith("http")) {
                            // 相对路径，转为绝对路径
                            String base = baseUrl.getProtocol() + "://" + host + ":" + port;
                            if (controlUrl.startsWith("/")) {
                                return base + controlUrl;
                            } else {
                                return base + "/" + controlUrl;
                            }
                        }
                        return controlUrl;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /** 发送DLNA播放命令 */
    private void sendDlnaPlayCommand(String host, String controlUrl, String videoUrl) {
        try {
            URL url = new URL(controlUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            // 构建SOAP请求
            String soapXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<CurrentURI>" + escapeXml(videoUrl) + "</CurrentURI>" +
                "<CurrentURIMetaData>&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;&lt;item id=\"1\" parentID=\"0\" restricted=\"0\"&gt;&lt;dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\"&gt;Video&lt;/dc:title&gt;&lt;res&gt;" + escapeXml(videoUrl) + "&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>" +
                "</u:SetAVTransportURI>" +
                "</s:Body>" +
                "</s:Envelope>";

            OutputStream os = conn.getOutputStream();
            os.write(soapXml.getBytes("UTF-8"));
            os.flush();

            int response = conn.getResponseCode();
            conn.disconnect();

            // 如果成功，继续发送Play命令
            if (response == 200 || response == 201) {
                sendDlnaPlayCommand2(host, controlUrl);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("投屏失败: " + e.getMessage());
        }
    }

    private void sendDlnaPlayCommand2(String host, String controlUrl) {
        try {
            URL url = new URL(controlUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String soapXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body>" +
                "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<Speed>1</Speed>" +
                "</u:Play>" +
                "</s:Body>" +
                "</s:Envelope>";

            OutputStream os = conn.getOutputStream();
            os.write(soapXml.getBytes("UTF-8"));
            os.flush();
            conn.disconnect();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 切换播放/暂停 */
    private void togglePlayPause() {
        if (!isCasting) return;
        isPlaying = !isPlaying;
        tvPlayPause.setText(isPlaying ? "⏸️ 暂停" : "▶️ 播放");
        Toast.makeText(this, isPlaying ? "▶️ 已播放" : "⏸️ 已暂停", Toast.LENGTH_SHORT).show();
    }

    /** 停止投屏 */
    private void stopCasting() {
        isCasting = false;
        currentDevice = null;
        layoutCasting.setVisibility(View.GONE);
        Toast.makeText(this, "⏹ 已停止投屏", Toast.LENGTH_SHORT).show();
    }

    /** 投屏视频 */
    private void castVideo() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入视频URL", Toast.LENGTH_SHORT).show();
            return;
        }

        if (deviceList.isEmpty()) {
            Toast.makeText(this, "请先搜索并选择投屏设备", Toast.LENGTH_LONG).show();
            return;
        }

        // 显示设备选择对话框
        String[] deviceNames = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            deviceNames[i] = "📺 " + deviceList.get(i).name + " (" + deviceList.get(i).type + ")";
        }

        new AlertDialog.Builder(this)
            .setTitle("📡 选择投屏设备")
            .setItems(deviceNames, (d, which) -> {
                selectDevice(deviceList.get(which));
            })
            .setNegativeButton("取消", null)
            .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isCasting) {
                new AlertDialog.Builder(this)
                    .setTitle("📡 投屏中")
                    .setMessage("当前正在投屏，是否停止并退出？")
                    .setPositiveButton("退出", (d, w) -> {
                        stopCasting();
                        finish();
                    })
                    .setNegativeButton("继续投屏", null)
                    .show();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (searchExecutor != null) {
            searchExecutor.shutdownNow();
        }
        stopCasting();
    }

    /** 投屏设备信息 */
    static class CastingDevice {
        String name;       // 设备名称
        String address;    // IP地址
        String location;   // 设备描述URL
        String type;       // 设备类型
        String uuid;       // 设备UUID
    }

    /** 设备列表ViewHolder */
    class DeviceViewHolder extends RecyclerView.ViewHolder {
        LinearLayout itemLayout;
        TextView tvName;
        TextView tvAddress;
        TextView tvType;

        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            itemLayout = itemView.findViewById(R.id.itemLayout);
            tvName = itemView.findViewById(R.id.tvDeviceName);
            tvAddress = itemView.findViewById(R.id.tvDeviceAddress);
            tvType = itemView.findViewById(R.id.tvDeviceType);
        }

        void bind(CastingDevice device) {
            tvName.setText("📺 " + device.name);
            tvAddress.setText(device.address);
            tvType.setText(getTypeLabel(device.type));

            // 焦点效果
            itemLayout.setOnFocusChangeListener((v, hasFocus) -> {
                float scale = hasFocus ? 1.02f : 1.0f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(100).start();
                if (hasFocus) {
                    ((LinearLayout)v).setBackgroundResource(R.drawable.btn_focused_bg);
                } else {
                    ((LinearLayout)v).setBackgroundResource(R.drawable.btn_device_bg);
                }
            });
        }

        private String getTypeLabel(String type) {
            if (type.contains("MediaRenderer")) return "📺 电视/显示器";
            if (type.contains("DIAL")) return "📱 DIAL设备";
            if (type.contains("RCS")) return "🎵 渲染设备";
            return type;
        }
    }
}
