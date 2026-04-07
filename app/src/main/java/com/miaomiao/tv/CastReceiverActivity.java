package com.miaomiao.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 投屏接收Activity - 显示投屏内容
 * 接收来自手机的图片/视频投屏
 */
public class CastReceiverActivity extends Activity implements CastReceiverService.CastListener {

    private WebView webView;
    private ImageView imageView;
    private LinearLayout infoPanel;
    private LinearLayout controlsPanel;
    private TextView tvDeviceInfo;
    private TextView tvCastUrl;
    private LinearLayout btnBack;
    private LinearLayout btnHistory;
    private LinearLayout btnApps;
    private LinearLayout btnRefresh;
    private ProgressBar progressBar;

    private Handler mainHandler;
    private String currentCastUrl = null;
    private String currentDeviceName = null;

    private static final int REQUEST_HISTORY = 1001;
    private static final int REQUEST_APPS = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 横屏 + 全屏 + 常亮
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_cast_receiver);

        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        setupWebView();
        startCastService();
    }

    private void initViews() {
        webView = findViewById(R.id.webView);
        imageView = findViewById(R.id.imageView);
        infoPanel = findViewById(R.id.infoPanel);
        controlsPanel = findViewById(R.id.controlsPanel);
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        tvCastUrl = findViewById(R.id.tvCastUrl);
        btnBack = findViewById(R.id.btnBack);
        btnHistory = findViewById(R.id.btnHistory);
        btnApps = findViewById(R.id.btnApps);
        btnRefresh = findViewById(R.id.btnRefresh);
        progressBar = findViewById(R.id.progressBar);

        // 显示投屏地址
        String castUrl = CastReceiverService.getCastUrl();
        tvCastUrl.setText("📱 手机访问: " + castUrl + " 即可投屏");

        // 按钮事件
        btnBack.setOnClickListener(v -> finish());
        btnHistory.setOnClickListener(v -> showHistory());
        btnApps.setOnClickListener(v -> showAppManager());
        btnRefresh.setOnClickListener(v -> refreshContent());

        // 焦点效果
        setFocusEffect(btnBack);
        setFocusEffect(btnHistory);
        setFocusEffect(btnApps);
        setFocusEffect(btnRefresh);

        // 默认显示说明界面
        showGuidePage();
    }

    private void setFocusEffect(View view) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? 1.08f : 1.0f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        // 添加JS接口
        webView.addJavascriptInterface(new CastJSInterface(), "castReceiver");
    }

    /** 显示引导页面 */
    private void showGuidePage() {
        String castUrl = CastReceiverService.getCastUrl();
        String html = "<!DOCTYPE html><html><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:'PingFang SC','Microsoft YaHei',sans-serif;background:linear-gradient(135deg,#0D0D1A 0%,#1a1a2e 100%);color:#fff;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:20px}" +
            ".logo{font-size:80px;margin-bottom:20px;animation:pulse 2s infinite}" +
            "@keyframes pulse{0%,100%{transform:scale(1)}50%{transform:scale(1.1)}}" +
            "h1{font-size:36px;margin-bottom:10px;background:linear-gradient(90deg,#00D9FF,#7B61FF);-webkit-background-clip:text;-webkit-text-fill-color:transparent}" +
            ".subtitle{color:#888;font-size:16px;margin-bottom:40px}" +
            ".url-box{background:#1a1a2e;border:2px dashed #00D9FF;border-radius:16px;padding:20px 40px;margin-bottom:30px}" +
            ".url-box .label{color:#888;font-size:14px;margin-bottom:8px}" +
            ".url-box .url{color:#00D9FF;font-size:24px;font-weight:bold;word-break:break-all}" +
            ".tip{color:#666;font-size:14px;line-height:2}" +
            ".tip strong{color:#FFD700}" +
            ".step{display:flex;gap:20px;margin-top:40px;flex-wrap:wrap;justify-content:center}" +
            ".step-item{background:#1a1a2e;padding:20px;border-radius:12px;width:180px}" +
            ".step-item .num{background:#00D9FF;color:#000;width:32px;height:32px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:bold;margin:0 auto 12px}" +
            ".step-item .text{color:#ccc;font-size:14px}" +
            ".status{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#1a1a2e;padding:10px 30px;border-radius:30px;border:1px solid #00D9FF}" +
            ".status span{color:#00FF00;margin-left:8px}" +
            "</style></head><body>" +
            "<div class='logo'>📺</div>" +
            "<h1>喵喵嗷投屏接收</h1>" +
            "<p class='subtitle'>让您的设备成为投屏接收器</p>" +
            "<div class='url-box'>" +
            "<div class='label'>📱 手机访问以下地址即可投屏</div>" +
            "<div class='url'>" + castUrl + "</div>" +
            "</div>" +
            "<div class='step'>" +
            "<div class='step-item'><div class='num'>1</div><div class='text'>确保在同一WiFi</div></div>" +
            "<div class='step-item'><div class='num'>2</div><div class='text'>手机浏览器打开地址</div></div>" +
            "<div class='step-item'><div class='num'>3</div><div class='text'>输入视频URL投屏</div></div>" +
            "</div>" +
            "<p class='tip' style='margin-top:40px'>" +
            "<strong>💡 提示：</strong>也可以使用投屏APP扫描下方二维码<br>" +
            "支持DLNA/AirPlay协议的手机可直接发现本设备" +
            "</p>" +
            "<div class='status'>🟢 服务运行中<span>●</span></div>" +
            "</body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        webView.setVisibility(View.VISIBLE);
        imageView.setVisibility(View.GONE);
    }

    /** 启动投屏服务 */
    private void startCastService() {
        Intent intent = new Intent(this, CastReceiverService.class);
        startService(intent);
        CastReceiverService.addListener(this);
    }

    /** 显示投屏内容 */
    @Override
    public void onCastStart(String deviceName, String url) {
        mainHandler.post(() -> {
            currentCastUrl = url;
            currentDeviceName = deviceName;

            // 更新信息面板
            tvDeviceInfo.setText("📱 " + deviceName + " 正在投屏");
            tvDeviceInfo.setVisibility(View.VISIBLE);
            infoPanel.setVisibility(View.VISIBLE);

            // 隐藏引导页面
            if (url.contains(".jpg") || url.contains(".jpeg") ||
                url.contains(".png") || url.contains(".gif") ||
                url.contains(".webp") || url.contains(".bmp")) {
                // 图片投屏
                loadImage(url);
            } else {
                // 视频投屏
                loadVideo(url);
            }
        });
    }

    @Override
    public void onCastStop() {
        mainHandler.post(() -> {
            Toast.makeText(this, "投屏已结束", Toast.LENGTH_SHORT).show();
            showGuidePage();
        });
    }

    @Override
    public void onDeviceConnected(String deviceName) {
        mainHandler.post(() -> {
            Toast.makeText(this, deviceName + " 已连接", Toast.LENGTH_SHORT).show();
        });
    }

    /** 加载图片 */
    private void loadImage(String url) {
        webView.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);

        // 显示加载中
        imageView.setImageResource(android.R.drawable.ic_menu_gallery);

        new Thread(() -> {
            try {
                URL imageUrl = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) imageUrl.openConnection();
                conn.setDoInput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                InputStream is = conn.getInputStream();
                Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();

                mainHandler.post(() -> {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "图片加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 加载视频 */
    private void loadVideo(String url) {
        imageView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        // 使用视频播放器页面
        String html = "<!DOCTYPE html><html><head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{background:#000;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center}" +
            "video{width:100%;height:100%;max-width:1920px}" +
            ".info{position:fixed;top:0;left:0;right:0;background:rgba(0,0,0,0.8);padding:15px;color:#fff;text-align:center;z-index:100}" +
            ".info h2{font-size:18px;margin-bottom:5px}" +
            ".info p{font-size:14px;color:#888}" +
            ".controls{position:fixed;bottom:0;left:0;right:0;background:rgba(0,0,0,0.8);padding:15px;display:flex;justify-content:center;gap:20px;z-index:100}" +
            ".controls button{background:#00D9FF;color:#000;border:none;padding:10px 30px;border-radius:20px;font-size:14px;cursor:pointer}" +
            "</style></head><body>" +
            "<div class='info'>" +
            "<h2>📺 " + currentDeviceName + " 投屏中</h2>" +
            "<p>" + url + "</p>" +
            "</div>" +
            "<video id='player' src='" + url + "' controls autoplay playsinline>" +
            "您的浏览器不支持视频播放" +
            "</video>" +
            "<div class='controls'>" +
            "<button onclick='document.getElementById(\"player\").requestFullscreen()'>全屏播放</button>" +
            "</div>" +
            "<script>" +
            "document.getElementById('player').play().catch(e=>console.log('autoplay blocked'));" +
            "</script>" +
            "</body></html>";

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    /** 显示历史记录 */
    private void showHistory() {
        Intent intent = new Intent(this, CastHistoryActivity.class);
        startActivityForResult(intent, REQUEST_HISTORY);
    }

    /** 显示应用管理 */
    private void showAppManager() {
        Intent intent = new Intent(this, AppManagerActivity.class);
        startActivityForResult(intent, REQUEST_APPS);
    }

    /** 刷新内容 */
    private void refreshContent() {
        if (currentCastUrl != null) {
            if (currentCastUrl.contains(".jpg") || currentCastUrl.contains(".png")) {
                loadImage(currentCastUrl);
            } else {
                loadVideo(currentCastUrl);
            }
            Toast.makeText(this, "🔄 刷新中...", Toast.LENGTH_SHORT).show();
        } else {
            showGuidePage();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        CastReceiverService.addListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CastReceiverService.removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CastReceiverService.removeListener(this);
        if (webView != null) {
            webView.destroy();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            new AlertDialog.Builder(this)
                .setTitle("退出投屏")
                .setMessage("确定要退出投屏接收吗？\n投屏服务将继续在后台运行。")
                .setPositiveButton("退出", (d, w) -> finish())
                .setNegativeButton("取消", null)
                .show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** JS接口 */
    class CastJSInterface {
        @JavascriptInterface
        public void onVideoEnd() {
            mainHandler.post(() -> showGuidePage());
        }

        @JavascriptInterface
        public void onError(String msg) {
            mainHandler.post(() -> {
                Toast.makeText(CastReceiverActivity.this, "播放错误: " + msg, Toast.LENGTH_LONG).show();
            });
        }
    }
}
