package com.miaomiao.tv;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 喵喵嗷影视 - 主界面
 * 首页：直播/点播/工具/扫码输入 四大按钮 + 文本框
 * 浏览器页：可编辑地址栏 + 后退/前进/刷新/首页/下载管理
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

        // 读取配置（支持修改 strings.xml 快速更换）
        LIVE_URL  = getString(R.string.live_url);
        VOD_URL   = getString(R.string.vod_url);
        TOOLS_URL = getString(R.string.tools_url);

        initViews();
        setupWebView();
        checkPermissions();
    }

    private void initViews() {
        homePage     = findViewById(R.id.homePage);
        webPage      = findViewById(R.id.webPage);
        btnLive      = findViewById(R.id.btnLive);
        btnVod       = findViewById(R.id.btnVod);
        btnTools     = findViewById(R.id.btnTools);
        btnQrInput   = findViewById(R.id.btnQrInput);
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

        // 首页大按钮：添加焦点放大动画
        attachFocusScale(btnLive,    1.10f);
        attachFocusScale(btnVod,     1.10f);
        attachFocusScale(btnTools,   1.10f);
        attachFocusScale(btnQrInput, 1.10f);

        // 工具栏小按钮：轻微放大
        attachFocusScale(btnBack,      1.15f);
        attachFocusScale(btnForward,   1.15f);
        attachFocusScale(btnRefresh,   1.15f);
        attachFocusScale(btnGo,        1.15f);
        attachFocusScale(btnHome,      1.15f);
        attachFocusScale(btnDownloads, 1.15f);

        // 直播按钮
        btnLive.setOnClickListener(v -> openUrl(LIVE_URL));

        // 点播按钮
        btnVod.setOnClickListener(v -> openUrl(VOD_URL));

        // 工具按钮
        btnTools.setOnClickListener(v -> openUrl(TOOLS_URL));

        // 扫码输入按钮
        btnQrInput.setOnClickListener(v -> showQrInput());

        // 后退
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });

        // 前进
        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });

        // 刷新
        btnRefresh.setOnClickListener(v -> webView.reload());

        // GO 按钮
        btnGo.setOnClickListener(v -> navigateToUrl(etUrl.getText().toString()));

        // 首页按钮（返回主页）
        btnHome.setOnClickListener(v -> showHomePage());

        // 地址栏回车
        etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigateToUrl(etUrl.getText().toString());
                return true;
            }
            return false;
        });

        // 下载管理
        btnDownloads.setOnClickListener(v ->
            startActivity(new Intent(this, DownloadsActivity.class))
        );
    }

    /**
     * 显示扫码输入对话框
     */
    private void showQrInput() {
        if (qrInputHelper != null) {
            qrInputHelper.dismiss();
        }
        qrInputHelper = new QrInputHelper(this, url -> {
            // 手机输入网址后，TV端打开它
            openUrl(url);
            Toast.makeText(this, "\u5df2\u63a5\u6536\u7f51\u5740\uff1a" + url, Toast.LENGTH_LONG).show();
        });
        qrInputHelper.show();
    }

    /**
     * 打开 URL：切换到 WebView 页
     */
    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "\u7f51\u5740\u672a\u914d\u7f6e", Toast.LENGTH_SHORT).show();
            return;
        }
        showWebPage();
        webView.loadUrl(url);
        etUrl.setText(url);
    }

    /**
     * 地址栏跳转
     */
    private void navigateToUrl(String input) {
        if (input == null || input.trim().isEmpty()) return;
        input = input.trim();
        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            // 自动补全协议
            if (input.contains(".")) {
                input = "https://" + input;
            } else {
                // 视为搜索词
                input = "https://www.bing.com/search?q=" + Uri.encode(input);
            }
        }
        webView.loadUrl(input);
    }

    private void showHomePage() {
        homePage.setVisibility(View.VISIBLE);
        webPage.setVisibility(View.GONE);
    }

    private void showWebPage() {
        homePage.setVisibility(View.GONE);
        webPage.setVisibility(View.VISIBLE);
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
                // 同步更新地址栏
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
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                cursorView.moveCursor(0, -CursorView.STEP);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                cursorView.moveCursor(0, CursorView.STEP);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                cursorView.moveCursor(-CursorView.STEP, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                cursorView.moveCursor(CursorView.STEP, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (webPage.getVisibility() == View.VISIBLE) {
                    cursorView.performClick(webView);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (webPage.getVisibility() == View.VISIBLE) {
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        showHomePage();
                    }
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MENU:
                if (webPage.getVisibility() == View.VISIBLE) {
                    toolbar.setVisibility(
                        toolbar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
                    );
                    return true;
                }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            List<String> perms = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (!perms.isEmpty()) {
                ActivityCompat.requestPermissions(this,
                    perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (qrInputHelper != null) qrInputHelper.dismiss();
        webView.destroy();
    }

    /**
     * 给 View 附加焦点放大/缩小动画
     * 获焦时缩放到 scale，失焦时恢复 1.0
     */
    private void attachFocusScale(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            float target = hasFocus ? scale : 1.0f;
            float elevTarget = hasFocus ? 24f : 8f;
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
