package com.miaomiao.tv;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 喵喵嗷影视 - 主界面
 * 首页：6个按钮（直播/点播/工具/扫码输入/文件管理/U盘）
 * 首页不显示光标，进入WebView时自动开启光标控制
 */
public class MainActivity extends AppCompatActivity {

    // ---- 从 strings.xml 读取，通过 build_helper.py 快速修改 ----
    private String LIVE_URL;
    private String VOD_URL;
    private String TOOLS_URL;

    private static final int PERMISSION_REQUEST_CODE = 100;

    // 视图
    private View homePage;
    private View webPage;
    private LinearLayout btnLive;
    private LinearLayout btnVod;
    private LinearLayout btnTools;
    private LinearLayout btnQrInput;
    private LinearLayout btnFileMgr;
    private LinearLayout btnUsbMgr;
    private LinearLayout toolbar;
    private LinearLayout btnBack;
    private LinearLayout btnForward;
    private LinearLayout btnRefresh;
    private LinearLayout btnGo;
    private LinearLayout btnHome;
    private LinearLayout btnDownloads;
    private EditText etUrl;
    private ProgressBar progressBar;
    private WebView webView;
    private CursorView cursorView;

    private QrInputHelper qrInputHelper;
    private boolean mouseModeEnabled = true; // 默认开启鼠标模式
    private long menuKeyDownTime = 0;

    // ---- USB 广播 ----
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                // U 盘插入，弹出提示
                showUsbMountedToast();
            } else if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_REMOVED.equals(action)) {
                // U 盘移除
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 横屏 + 全屏 + 常亮
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        setContentView(R.layout.activity_main);

        // 读取配置
        LIVE_URL  = getString(R.string.live_url);
        VOD_URL   = getString(R.string.vod_url);
        TOOLS_URL = getString(R.string.tools_url);

        initViews();
        setupWebView();
        checkPermissions();

        // 初始状态：首页隐藏光标
        cursorView.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注册 USB 广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addDataScheme("file");
        ContextCompat.registerReceiver(this, usbReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        if (webView != null) webView.onPause();
    }

    private void initViews() {
        homePage     = findViewById(R.id.homePage);
        webPage      = findViewById(R.id.webPage);
        btnLive      = findViewById(R.id.btnLive);
        btnVod       = findViewById(R.id.btnVod);
        btnTools     = findViewById(R.id.btnTools);
        btnQrInput   = findViewById(R.id.btnQrInput);
        btnFileMgr   = findViewById(R.id.btnFileMgr);
        btnUsbMgr    = findViewById(R.id.btnUsbMgr);
        toolbar      = findViewById(R.id.toolbar);
        btnBack      = findViewById(R.id.btnBack);
        btnForward   = findViewById(R.id.btnForward);
        btnRefresh   = findViewById(R.id.btnRefresh);
        btnGo        = findViewById(R.id.btnGo);
        btnHome      = findViewById(R.id.btnHome);
        btnDownloads = findViewById(R.id.btnDownloads);
        etUrl        = findViewById(R.id.etUrl);
        progressBar  = findViewById(R.id.progressBar);
        webView      = findViewById(R.id.webView);
        cursorView   = findViewById(R.id.cursorView);

        // 首页大按钮：焦点放大动画（缩小缩放比例防止描边溢出）
        attachFocusScale(btnLive,      1.06f);
        attachFocusScale(btnVod,       1.06f);
        attachFocusScale(btnTools,     1.06f);
        attachFocusScale(btnQrInput,   1.06f);
        attachFocusScale(btnFileMgr,   1.06f);
        attachFocusScale(btnUsbMgr,    1.06f);

        // 工具栏小按钮：轻微放大
        attachFocusScale(btnBack,      1.15f);
        attachFocusScale(btnForward,   1.15f);
        attachFocusScale(btnRefresh,   1.15f);
        attachFocusScale(btnGo,        1.15f);
        attachFocusScale(btnHome,      1.15f);
        attachFocusScale(btnDownloads, 1.15f);

        // 首页按钮点击事件
        btnLive.setOnClickListener(v -> openUrl(LIVE_URL));
        btnVod.setOnClickListener(v -> openUrl(VOD_URL));
        btnTools.setOnClickListener(v -> openUrl(TOOLS_URL));
        btnQrInput.setOnClickListener(v -> showQrInput());
        btnFileMgr.setOnClickListener(v -> startActivity(new Intent(this, FileManagerActivity.class)));
        btnUsbMgr.setOnClickListener(v -> startActivity(new Intent(this, UsbManagerActivity.class)));

        // 工具栏按钮
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnGo.setOnClickListener(v -> navigateToUrl(etUrl.getText().toString()));
        btnHome.setOnClickListener(v -> showHomePage());
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigateToUrl(etUrl.getText().toString());
                return true;
            }
            return false;
        });
        btnDownloads.setOnClickListener(v ->
            startActivity(new Intent(this, DownloadsActivity.class))
        );
    }

    private void showQrInput() {
        if (qrInputHelper != null) {
            qrInputHelper.dismiss();
        }
        qrInputHelper = new QrInputHelper(this, url -> {
            openUrl(url);
            Toast.makeText(this, "\u5df2\u63a5\u6536\u7f51\u5740\uff1a" + url, Toast.LENGTH_LONG).show();
        });
        qrInputHelper.show();
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "\u7f51\u5740\u672a\u914d\u7f6e", Toast.LENGTH_SHORT).show();
            return;
        }
        showWebPage();
        webView.loadUrl(url);
        etUrl.setText(url);
    }

    private void navigateToUrl(String input) {
        if (input == null || input.trim().isEmpty()) return;
        input = input.trim();
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            if (input.contains(".")) {
                input = "https://" + input;
            } else {
                input = "https://www.bing.com/search?q=" + Uri.encode(input);
            }
        }
        webView.loadUrl(input);
    }

    private void showHomePage() {
        homePage.setVisibility(View.VISIBLE);
        webPage.setVisibility(View.GONE);
        // 首页隐藏光标
        cursorView.setVisibility(View.GONE);
    }

    private void showWebPage() {
        homePage.setVisibility(View.GONE);
        webPage.setVisibility(View.VISIBLE);
        // 进入 WebView 显示光标
        cursorView.setVisibility(mouseModeEnabled ? View.VISIBLE : View.GONE);
        // 重置光标位置到中心
        cursorView.resetPosition();
        // 进入网页时弹出一次提示
        lastHintTime = 0;
        showMouseModeHint();
    }

    /** 显示鼠标模式操作提示（用自定义 View 实现，不使用 Toast） */
    private void showMouseModeHint() {
        // 避免短时间内重复弹出
        long now = System.currentTimeMillis();
        if (now - lastHintTime < 3000) return;
        lastHintTime = now;

        TextView tv = new TextView(this);
        tv.setText("\uD83C\uDFAF 长按菜单键进入/退出鼠标模式\n进入后用上下左右键控制光标选择\n未进入鼠标模式可直接用遥控器方向键选择");
        tv.setTextSize(15);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(40, 24, 40, 24);
        tv.setLineSpacing(6f, 1.2f);

        AlertDialog d = new AlertDialog.Builder(this)
            .setTitle("")
            .setView(tv)
            .setPositiveButton("\u77E5\u9053\u4E86", null)
            .setCancelable(true)
            .create();
        if (d.getWindow() != null) {
            d.getWindow().setGravity(Gravity.CENTER);
            d.getWindow().setBackgroundDrawableResource(android.R.drawable.dialog_holo_dark_frame);
        }
        d.show();

        // 3秒后自动关闭
        tv.postDelayed(() -> {
            try {
                if (d.isShowing()) d.dismiss();
            } catch (Exception ignored) {}
        }, 3000);
    }

    private long lastHintTime = 0;

    /** U 盘插入时弹出提示 */
    private void showUsbMountedToast() {
        TextView tv = new TextView(this);
        tv.setText("\uD83D\uDCBB U \u76D8\u5DF2\u63D2\u5165\uFF01");
        tv.setTextSize(18);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(40, 20, 40, 20);

        AlertDialog d = new AlertDialog.Builder(this)
            .setTitle("")
            .setView(tv)
            .setPositiveButton("\u6253\u5F00 U \u76D8", (dialog, which) -> {
                startActivity(new Intent(this, UsbManagerActivity.class));
            })
            .setNegativeButton("\u5FFD\u7565", null)
            .create();
        if (d.getWindow() != null) {
            d.getWindow().setGravity(Gravity.CENTER);
        }
        d.show();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setUserAgentString(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                if (url != null) etUrl.setText(url);
                updateNavButtons();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        startActivity(intent);
                    } catch (Exception ignored) {}
                    return true;
                }
                view.loadUrl(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progressBar.setProgress(newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (view.getUrl() != null) etUrl.setText(view.getUrl());
            }
        });

        webView.setDownloadListener(
            (url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, userAgent, contentDisposition, mimeType, contentLength)
        );
    }

    private void handleDownload(String url, String userAgent,
            String contentDisposition, String mimeType, long contentLength) {
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        new AlertDialog.Builder(this)
            .setTitle("\u4e0b\u8f7d\u6587\u4ef6")
            .setMessage("\u6587\u4ef6\u540d\uff1a" + fileName + "\n\n\u786e\u8ba4\u4e0b\u8f7d\uff1f")
            .setPositiveButton("\u4e0b\u8f7d", (d, w) -> startDownloadService(url, fileName, userAgent, mimeType))
            .setNegativeButton("\u53d6\u6d88", null)
            .show();
    }

    private void startDownloadService(String url, String fileName, String userAgent, String mimeType) {
        String cookie = CookieManager.getInstance().getCookie(url);
        Intent intent = new Intent(this, DownloadService.class);
        intent.putExtra(DownloadService.EXTRA_URL, url);
        intent.putExtra(DownloadService.EXTRA_FILE_NAME, fileName);
        intent.putExtra(DownloadService.EXTRA_USER_AGENT, userAgent);
        intent.putExtra(DownloadService.EXTRA_MIME_TYPE, mimeType);
        intent.putExtra(DownloadService.EXTRA_COOKIE, cookie);
        startService(intent);
        Toast.makeText(this, "\u5f00\u59cb\u4e0b\u8f7d\uff1a" + fileName, Toast.LENGTH_LONG).show();
    }

    private void updateNavButtons() {
        btnBack.setAlpha(webView.canGoBack() ? 1.0f : 0.4f);
        btnForward.setAlpha(webView.canGoForward() ? 1.0f : 0.4f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 只有在 WebView 页面才处理光标控制
        if (webPage.getVisibility() == View.VISIBLE) {
            // 如果地址栏正在编辑中，按方向键则退出编辑并转移焦点
            if (etUrl.hasFocus() && !mouseModeEnabled) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        // 清除地址栏焦点，焦点转移到工具栏第一个可focus的按钮
                        etUrl.clearFocus();
                        btnBack.requestFocus();
                        return true;
                }
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (mouseModeEnabled) {
                        cursorView.moveCursor(0, -CursorView.STEP);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (mouseModeEnabled) {
                        cursorView.moveCursor(0, CursorView.STEP);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (mouseModeEnabled) {
                        cursorView.moveCursor(-CursorView.STEP, 0);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (mouseModeEnabled) {
                        cursorView.moveCursor(CursorView.STEP, 0);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (mouseModeEnabled) {
                        cursorView.performClick(webView);
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_BACK:
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        showHomePage();
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    // 记录按下时间
                    menuKeyDownTime = System.currentTimeMillis();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (webPage.getVisibility() == View.VISIBLE && keyCode == KeyEvent.KEYCODE_MENU) {
            long pressDuration = System.currentTimeMillis() - menuKeyDownTime;
            if (pressDuration >= 500) {
                // 长按菜单键：切换鼠标模式
                mouseModeEnabled = !mouseModeEnabled;
                cursorView.setVisibility(mouseModeEnabled ? View.VISIBLE : View.GONE);
                Toast.makeText(this,
                    mouseModeEnabled ? "\uD83C\uDFAF 已进入鼠标模式，用方向键控制光标"
                                   : "\uD83D\uDCBB 已退出鼠标模式，用方向键直接选择",
                    Toast.LENGTH_LONG).show();
            } else {
                // 短按菜单键：弹出提示 + 切换工具栏
                showMouseModeHint();
                toolbar.setVisibility(
                    toolbar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
                );
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 及以下：需要读写存储权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!perms.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }

        // Android 11+ 检查「所有文件访问」权限（用于文件管理功能）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                // 弹框引导用户去系统设置授权
                new AlertDialog.Builder(this)
                    .setTitle("需要文件访问权限")
                    .setMessage("为了能够下载和管理文件，需要授予「所有文件访问」权限。\n\n请在接下来的设置页面中开启。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                            );
                            startActivity(intent);
                        } catch (Exception e) {
                            // 部分设备不支持精确跳转，跳到通用设置
                            try {
                                startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception ignored) {}
                        }
                    })
                    .setNegativeButton("暂不授权", null)
                    .show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // 权限申请结果处理（可按需扩展）
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    // 某项权限被拒绝
                    android.util.Log.w("Permission", "Denied: " + permissions[i]);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webPage.getVisibility() == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                showHomePage();
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (qrInputHelper != null) qrInputHelper.dismiss();
        if (webView != null) webView.destroy();
    }

    /**
     * 焦点放大/缩小动画
     */
    private void attachFocusScale(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            float target = hasFocus ? scale : 1.0f;
            float elevTarget = hasFocus ? 16f : 8f;
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
