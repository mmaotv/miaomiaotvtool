package com.miaomiao.tv;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;
import android.app.ProgressDialog;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import android.content.SharedPreferences;
import android.widget.TextView;

/**
 * 喵喵嗷影视 - 主界面
 * 首页：7个按钮（2行4列 - 投屏已移除）
 * 第一行：直播、点播、工具、历史记录
 * 第二行：扫码输入、文件管理、U盘
 * 首页不显示光标，进入WebView时自动开启光标控制
 */
public class MainActivity extends AppCompatActivity {
    private final PrivilegedBackendManager backendManager = PrivilegedBackendManager.get();

    // ---- 从 strings.xml 读取，通过 build_helper.py 快速修改 ----
    private String LIVE_URL;
    private String VOD_URL;
    private String TOOLS_URL;
    // 按钮名称（可自定义）
    private String BTN_LIVE;
    private String BTN_VOD;
    private String BTN_TOOLS;
    private String BTN_QR;
    private String BTN_FILE;
    private String BTN_APP;

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String PREF_NAME = "miaomiao_prefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch";
    private static final String KEY_MOUSE_HINT_SHOWN = "mouse_hint_shown";

    // 视图
    private View homePage;
    private View webPage;
    private LinearLayout btnLive;
    private LinearLayout btnVod;
    private LinearLayout btnTools;
    private LinearLayout btnHistoryHome;
    private LinearLayout btnQrInput;
    private LinearLayout btnFileMgr;
    private LinearLayout btnUsbMgr;
    private LinearLayout btnAbout;
    private LinearLayout toolbar;
    private LinearLayout btnBack;
    private LinearLayout btnForward;
    private LinearLayout btnRefresh;
    private LinearLayout btnGo;
    private LinearLayout btnHome;
    private LinearLayout btnDownloads;
    private LinearLayout btnBookmark;
    private LinearLayout btnHistory;
    private EditText etUrl;
    private ProgressBar progressBar;
    private WebView webView;
    private CursorView cursorView;
    private TextView tvAdbStatus;
    private TextView tvBookmarkIcon;
    private View focusStealer;  // 焦点接收器
    private View scrollButtonsContainer;  // 右侧翻页按钮容器
    private ImageButton btnPageUp;       // 上翻按钮
    private ImageButton btnPageDown;      // 下翻按钮

    private QrInputHelper qrInputHelper;
    private BookmarkManager bookmarkManager;
    private WebHistoryManager historyManager;
    // Web页面固定使用光标模式，不支持切换

    private static final int REQUEST_BOOKMARK = 2001;

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

        // 初始化日志系统
        AppLogger.get().init(this);
        AppLogger.get().installGlobalExceptionHandler();

        backendManager.init(this);

        // 横屏 + 常亮（移除 FLAG_FULLSCREEN，避免与软键盘 adjustResize 冲突）
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // 沉浸式：隐藏系统UI（状态栏/导航栏），且键盘弹出时可正常显示
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(R.layout.activity_main);

        // 读取配置
        LIVE_URL  = getString(R.string.live_url);
        VOD_URL   = getString(R.string.vod_url);
        TOOLS_URL = getString(R.string.tools_url);
        // 读取按钮名称
        BTN_LIVE  = getString(R.string.btn_live);
        BTN_VOD   = getString(R.string.btn_vod);
        BTN_TOOLS = getString(R.string.btn_tools);
        BTN_QR    = getString(R.string.btn_qr);
        BTN_FILE  = getString(R.string.btn_file);
        BTN_APP   = getString(R.string.btn_app);

        bookmarkManager = new BookmarkManager(this);
        historyManager = new WebHistoryManager(this);

        initViews();
        setupWebView();
        checkPermissions();
        checkAdbOnStart();

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
        btnHistoryHome = findViewById(R.id.btnHistoryHome);
        btnQrInput   = findViewById(R.id.btnQrInput);
        btnFileMgr   = findViewById(R.id.btnFileMgr);
        btnUsbMgr    = findViewById(R.id.btnUsbMgr);
        btnAbout     = findViewById(R.id.btnAbout);
        toolbar      = findViewById(R.id.toolbar);
        btnBack      = findViewById(R.id.btnBack);
        btnForward   = findViewById(R.id.btnForward);
        btnRefresh   = findViewById(R.id.btnRefresh);
        btnGo        = findViewById(R.id.btnGo);
        btnHome      = findViewById(R.id.btnHome);
        btnDownloads = findViewById(R.id.btnDownloads);
        btnBookmark  = findViewById(R.id.btnBookmark);
        btnHistory   = findViewById(R.id.btnHistory);
        etUrl        = findViewById(R.id.etUrl);
        tvAdbStatus  = findViewById(R.id.tvAdbStatus);
        tvBookmarkIcon = findViewById(R.id.tvBookmarkIcon);
        progressBar  = findViewById(R.id.progressBar);
        webView      = findViewById(R.id.webView);
        cursorView   = findViewById(R.id.cursorView);
        focusStealer = findViewById(R.id.focusStealer);
        scrollButtonsContainer = findViewById(R.id.scrollButtonsContainer);
        btnPageUp    = findViewById(R.id.btnPageUp);
        btnPageDown  = findViewById(R.id.btnPageDown);

        // ADB 状态栏（仅作状态显示，无需点击）
        tvAdbStatus.setFocusable(false);
        tvAdbStatus.setClickable(false);

        // 动态设置首页按钮文字
        TextView tvBtnLive   = findViewById(R.id.tvBtnLive);
        TextView tvBtnVod    = findViewById(R.id.tvBtnVod);
        TextView tvBtnTools  = findViewById(R.id.tvBtnTools);
        TextView tvBtnQr     = findViewById(R.id.tvBtnQr);
        TextView tvBtnFile   = findViewById(R.id.tvBtnFile);
        TextView tvBtnApp    = findViewById(R.id.tvBtnUsb);
        if (tvBtnLive != null)  tvBtnLive.setText(BTN_LIVE);
        if (tvBtnVod != null)   tvBtnVod.setText(BTN_VOD);
        if (tvBtnTools != null) tvBtnTools.setText(BTN_TOOLS);
        if (tvBtnQr != null)    tvBtnQr.setText(BTN_QR);
        if (tvBtnFile != null)  tvBtnFile.setText(BTN_FILE);
        if (tvBtnApp != null)   tvBtnApp.setText(BTN_APP);

        // 首页大按钮：焦点放大动画
        attachFocusScale(btnLive,      1.08f);
        attachFocusScale(btnVod,       1.08f);
        attachFocusScale(btnTools,     1.08f);
        attachFocusScale(btnHistoryHome, 1.08f);
        attachFocusScale(btnQrInput,   1.08f);
        attachFocusScale(btnFileMgr,   1.08f);
        attachFocusScale(btnUsbMgr,    1.08f);
        attachFocusScale(btnAbout,     1.08f);

        // 关于按钮点击
        btnAbout.setOnClickListener(v -> {
            Intent intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
        });

        // Web模式下不使用按钮放大动画，避免焦点状态干扰

        // 首页按钮点击事件
        btnLive.setOnClickListener(v -> openUrl(LIVE_URL));
        btnVod.setOnClickListener(v -> openUrl(VOD_URL));
        btnTools.setOnClickListener(v -> openUrl(TOOLS_URL));
        btnHistoryHome.setOnClickListener(v -> startActivity(
            new Intent(this, BookmarkActivity.class)));
        btnQrInput.setOnClickListener(v -> {
            // 跳转到扫码推送页面
            Intent intent = new Intent(this, ScanPushActivity.class);
            startActivity(intent);
        });
        btnFileMgr.setOnClickListener(v -> {
            Intent intent = new Intent(this, FileManagerActivity.class);
            intent.putExtra("path", "/storage/emulated/0");
            startActivity(intent);
        });
        btnUsbMgr.setOnClickListener(v -> {
            Intent intent = new Intent(this, AppManagerActivity.class);
            startActivity(intent);
        });

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
        btnDownloads.setOnClickListener(v -> {
            // 跳转到文件管理器，自动进入下载目录
            Intent intent = new Intent(this, FileManagerActivity.class);
            intent.putExtra("path", android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
            startActivity(intent);
        });
        btnBookmark.setOnClickListener(v -> onBookmarkClick());
        btnHistory.setOnClickListener(v -> startActivityForResult(
            new Intent(this, BookmarkActivity.class), REQUEST_BOOKMARK));

        // 右侧翻页按钮
        btnPageUp.setOnClickListener(v -> {
            if (webView != null) {
                webView.scrollBy(0, -webView.getHeight() / 2);
            }
        });
        btnPageDown.setOnClickListener(v -> {
            if (webView != null) {
                webView.scrollBy(0, webView.getHeight() / 2);
            }
        });

        // 注册光标点击回调：检测是否点中输入框，若是则弹出软键盘
        cursorView.setOnClickCallback(new CursorView.OnClickCallback() {
            @Override
            public void onClicked(final float cssX, final float cssY, final WebView wv) {
                // 通过 JS 判断点击的元素类型，决定是否弹键盘
                String detectJs = String.format(
                    "(function(){" +
                    "  var el=document.elementFromPoint(%f,%f);" +
                    "  if(!el) return 'none';" +
                    "  var t=el.tagName?el.tagName.toLowerCase():'';" +
                    "  if(t==='input'||t==='textarea'||el.isContentEditable) return 'input';" +
                    "  return 'other';" +
                    "})();",
                    cssX, cssY
                );
                // Android 6 兼容：使用匿名内部类替代 lambda，确保 WebView 有效
                if (wv == null || wv.getContext() == null) return;
                try {
                    wv.evaluateJavascript(detectJs, new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String result) {
                            if (wv == null || wv.getContext() == null) return;
                            if ("\"input\"".equals(result)) {
                                // 点中了输入框，延迟一点让 WebView 完成 focus，再弹键盘
                                wv.postDelayed(() -> {
                                    if (wv == null || wv.getContext() == null) return;
                                    try {
                                        wv.requestFocus();
                                        InputMethodManager imm = (InputMethodManager)
                                            getSystemService(Context.INPUT_METHOD_SERVICE);
                                        if (imm != null) {
                                            imm.showSoftInput(wv, InputMethodManager.SHOW_FORCED);
                                        }
                                    } catch (Exception e) {
                                        // 忽略弹键盘异常
                                    }
                                }, 100);
                            }
                        }
                    });
                } catch (Exception e) {
                    // Android 6 兼容：忽略 evaluateJavascript 异常
                }
            }
        });
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

    /** 收藏按钮点击：直接添加/删除当前页收藏 */
    private void onBookmarkClick() {
        String currentUrl = webView.getUrl();
        String currentTitle = "";
        try {
            currentTitle = webView.getTitle();
        } catch (Exception ignored) {}

        if (currentUrl == null || currentUrl.isEmpty()) {
            // 无当前页面 → 提示
            Toast.makeText(this, "请先打开一个网页", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bookmarkManager.exists(currentUrl)) {
            // 已收藏 → 删除收藏
            for (BookmarkManager.Bookmark b : bookmarkManager.getAll()) {
                if (currentUrl.equals(b.url)) {
                    bookmarkManager.delete(b.id);
                    Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show();
                    updateBookmarkIcon(currentUrl);
                    return;
                }
            }
        } else {
            // 未收藏 → 直接添加
            BookmarkManager.Bookmark b = new BookmarkManager.Bookmark(
                null, 
                currentTitle.isEmpty() ? currentUrl : currentTitle,
                currentUrl,
                System.currentTimeMillis()
            );
            if (bookmarkManager.add(b)) {
                Toast.makeText(this, "已添加收藏", Toast.LENGTH_SHORT).show();
            }
            updateBookmarkIcon(currentUrl);
        }
    }

    /** 更新收藏图标状态（根据当前URL是否已收藏） */
    private void updateBookmarkIcon(String url) {
        if (tvBookmarkIcon == null) return;
        boolean isBookmarked = bookmarkManager.exists(url);
        tvBookmarkIcon.setText(isBookmarked ? "★" : "☆");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BOOKMARK && resultCode == RESULT_OK && data != null) {
            String url = data.getStringExtra("bookmark_url");
            String title = data.getStringExtra("bookmark_title");
            if (url != null && !url.isEmpty()) {
                openUrl(url);
                Toast.makeText(this, "打开收藏：" + title, Toast.LENGTH_SHORT).show();
                // 更新收藏图标
                updateBookmarkIcon(url);
            }
        }
    }

    private void openUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
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
        etUrl.setText(input);  // 回写修正后的URL
        // 隐藏键盘
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etUrl.getWindowToken(), 0);
        webView.loadUrl(input);
    }

    private void showHomePage() {
        homePage.setVisibility(View.VISIBLE);
        webPage.setVisibility(View.GONE);
        // 首页隐藏光标
        cursorView.setVisibility(View.GONE);
        // 隐藏右侧翻页按钮
        if (scrollButtonsContainer != null) {
            scrollButtonsContainer.setVisibility(View.GONE);
        }
    }

    private void showWebPage() {
        homePage.setVisibility(View.GONE);
        webPage.setVisibility(View.VISIBLE);
        // Web页面固定显示光标
        cursorView.setVisibility(View.VISIBLE);
        // 显示右侧翻页按钮
        if (scrollButtonsContainer != null) {
            scrollButtonsContainer.setVisibility(View.VISIBLE);
        }
        // 重置光标位置到中心
        cursorView.resetPosition();
        // 清除工具栏按钮焦点，确保OK键控制光标
        clearToolbarFocus();
        // 首次进入网页时弹出提示
        showMouseModeHint();
    }

    /** 显示光标模式操作提示（仅首次进入网页时显示） */
    private void showMouseModeHint() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean hasShownHint = prefs.getBoolean(KEY_MOUSE_HINT_SHOWN, false);
        if (hasShownHint) return;
        prefs.edit().putBoolean(KEY_MOUSE_HINT_SHOWN, true).apply();

        DialogHelper.show(this, "📍", "光标模式",
            "用方向键移动光标\n按确认键/OK点击\n按菜单键显示/隐藏工具栏",
            "知道了", null);
    }

    /** U 盘插入时弹出提示 */
    private void showUsbMountedToast() {
        DialogHelper.show(this, "💾", "U盘已插入",
            "是否打开U盘管理？",
            "打开", "忽略",
            () -> startActivity(new Intent(this, UsbManagerActivity.class)),
            null);
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
        // 使用配置文件中的UA（从strings.xml读取）
        String ua = getString(R.string.user_agent);
        if (ua != null && !ua.isEmpty()) {
            settings.setUserAgentString(ua);
        } else {
            settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
            );
        }

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
                if (url != null) {
                    etUrl.setText(url);
                    // 添加到浏览历史
                    String title = "";
                    try { title = view.getTitle(); } catch (Exception ignored) {}
                    historyManager.addHistory(url, title);
                    // 更新收藏图标状态
                    updateBookmarkIcon(url);
                }
                updateNavButtons();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;

                // 处理 intent:// 协议（Deep Link）
                if (url.startsWith("intent://")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        // 检查是否有应用可以处理这个 Intent
                        if (intent.resolveActivity(getPackageManager()) != null) {
                            startActivity(intent);
                        } else {
                            // 如果没有应用处理，尝试下载 Play Store
                            String packageName = intent.getPackage();
                            if (packageName != null) {
                                Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=" + packageName));
                                if (marketIntent.resolveActivity(getPackageManager()) != null) {
                                    startActivity(marketIntent);
                                } else {
                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
                                }
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.e("WebView", "Failed to handle intent:// URL: " + url, e);
                    }
                    return true;
                }

                // 处理 market:// 协议（应用市场）
                if (url.startsWith("market://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        if (intent.resolveActivity(getPackageManager()) != null) {
                            startActivity(intent);
                        }
                    } catch (Exception e) {
                        android.util.Log.e("WebView", "Failed to handle market:// URL", e);
                    }
                    return true;
                }

                // 处理 tel:// 协议（电话）
                if (url.startsWith("tel:")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
                        if (intent.resolveActivity(getPackageManager()) != null) {
                            startActivity(intent);
                        }
                    } catch (Exception e) {
                        android.util.Log.e("WebView", "Failed to handle tel:// URL", e);
                    }
                    return true;
                }

                // 处理 mailto:// 协议（邮件）
                if (url.startsWith("mailto:")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse(url));
                        if (intent.resolveActivity(getPackageManager()) != null) {
                            startActivity(intent);
                        }
                    } catch (Exception e) {
                        android.util.Log.e("WebView", "Failed to handle mailto:// URL", e);
                    }
                    return true;
                }

                // 处理 https/http 协议，让 WebView 正常加载
                // 返回 false 表示让 WebView 处理 URL
                return false;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "\u5f00\u59cb\u4e0b\u8f7d\uff1a" + fileName, Toast.LENGTH_LONG).show();
    }

    private void updateNavButtons() {
        btnBack.setAlpha(webView.canGoBack() ? 1.0f : 0.4f);
        btnForward.setAlpha(webView.canGoForward() ? 1.0f : 0.4f);
    }

    /**
     * 清除工具栏所有按钮的焦点，让遥控器OK键重新控制光标
     * 解决点击工具栏按钮后焦点停留在按钮上导致OK键一直触发该按钮的问题
     */
    private void clearToolbarFocus() {
        if (toolbar == null) return;
        
        // 延迟清除焦点，确保点击事件处理完成后再执行
        toolbar.postDelayed(() -> {
            // 方法1：清除工具栏自身的焦点
            toolbar.clearFocus();
            
            // 方法2：遍历工具栏内的所有子视图，清除焦点
            clearFocusRecursively(toolbar);
            
            // 方法3：请求焦点回到根视图（让光标模式重新接管）
            View rootView = getWindow().getDecorView();
            if (rootView != null) {
                rootView.requestFocus();
            }
            
            // 方法4：将焦点设置到透明的焦点接收器上
            if (focusStealer != null) {
                focusStealer.requestFocus();
            }
        }, 100);
    }
    
    /**
     * 递归清除视图层级中的所有焦点
     */
    private void clearFocusRecursively(ViewGroup parent) {
        if (parent == null) return;
        
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child != null) {
                if (child.isFocused()) {
                    child.clearFocus();
                }
                if (child instanceof ViewGroup) {
                    clearFocusRecursively((ViewGroup) child);
                }
            }
        }
    }

    /**
     * 根据光标坐标判断并触发对应的工具栏按钮点击
     * 工具栏布局：后退 | 前进 | 刷新 | [地址栏] | GO | 首页 | 下载 | 收藏 | 历史
     */
    private void performToolbarButtonClick(float cursorScreenX, float cursorScreenY) {
        if (toolbar == null || webView == null) {
            return;
        }

        // 使用 View.post() 确 保视图布局已完成，避免 getLocationOnScreen 异常
        toolbar.post(() -> {
            try {
                int[] toolbarLoc = new int[2];
                toolbar.getLocationOnScreen(toolbarLoc);
                int toolbarHeight = toolbar.getHeight();

                // 如果光标 Y 坐标不在工具栏高度范围内，交给 WebView 处理
                // 扩大范围：工具栏高度 + 10dp 容错，避免光标步进时刚好跳过
                int touchTolerance = (int) (10 * getResources().getDisplayMetrics().density);
                if (cursorScreenY < toolbarLoc[1] - touchTolerance 
                    || cursorScreenY > toolbarLoc[1] + toolbarHeight + touchTolerance) {
                    cursorView.performClick(webView);
                    return;
                }

                // 光标在工具栏上的相对 X 坐标
                float relX = cursorScreenX - toolbarLoc[0];

                // 获取工具栏内各按钮的绝对位置并检测点击
                int[] btnLoc = new int[2];
                // X方向容错：按钮宽度 ± 15dp，避免光标步进时跳过按钮
                int xTolerance = (int) (15 * getResources().getDisplayMetrics().density);

                // 后退按钮
                if (btnBack != null) {
                    btnBack.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnBack.getWidth() + xTolerance) {
                        btnBack.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 前进按钮
                if (btnForward != null) {
                    btnForward.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnForward.getWidth() + xTolerance) {
                        btnForward.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 刷新按钮
                if (btnRefresh != null) {
                    btnRefresh.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnRefresh.getWidth() + xTolerance) {
                        btnRefresh.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 地址栏区域
                if (etUrl != null) {
                    etUrl.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + etUrl.getWidth() + xTolerance) {
                        etUrl.requestFocus();
                        etUrl.setSelection(etUrl.getText().length());
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(etUrl, InputMethodManager.SHOW_IMPLICIT);
                        return;
                    }
                }

                // GO 按钮
                if (btnGo != null) {
                    btnGo.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnGo.getWidth() + xTolerance) {
                        btnGo.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 首页按钮
                if (btnHome != null) {
                    btnHome.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnHome.getWidth() + xTolerance) {
                        btnHome.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 下载按钮
                if (btnDownloads != null) {
                    btnDownloads.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnDownloads.getWidth() + xTolerance) {
                        btnDownloads.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 收藏按钮
                if (btnBookmark != null) {
                    btnBookmark.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnBookmark.getWidth() + xTolerance) {
                        btnBookmark.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 历史/书签列表按钮
                if (btnHistory != null) {
                    btnHistory.getLocationOnScreen(btnLoc);
                    float btnRelX = btnLoc[0] - toolbarLoc[0];
                    if (relX >= btnRelX - xTolerance && relX <= btnRelX + btnHistory.getWidth() + xTolerance) {
                        btnHistory.performClick();
                        clearToolbarFocus();
                        return;
                    }
                }

                // 没有匹配到任何按钮，默认交给 WebView 处理
                cursorView.performClick(webView);

            } catch (Exception e) {
                // getLocationOnScreen 可能失败（如窗口状态异常），降级处理
                cursorView.performClick(webView);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 只有在 WebView 页面才处理光标控制
        if (webPage.getVisibility() == View.VISIBLE) {
            // 如果地址栏正在编辑中，按方向键则退出编辑并转移焦点
            if (etUrl.hasFocus()) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        // 清除地址栏焦点，光标模式下由方向键控制光标
                        etUrl.clearFocus();
                        return true;
                }
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    // 上下方向键始终移动光标
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
                    // 优先检测是否点击了顶部工具栏按钮
                    if (toolbar.getVisibility() == View.VISIBLE && cursorView.isOverView(toolbar)) {
                        // 获取光标在屏幕上的绝对坐标，然后传给工具栏点击处理
                        int[] cursorLoc = new int[2];
                        cursorView.getLocationOnScreen(cursorLoc);
                        float screenX = cursorView.getCursorPosition().x + cursorLoc[0];
                        float screenY = cursorView.getCursorY() + cursorLoc[1];
                        performToolbarButtonClick(screenX, screenY);
                    } else {
                        // 点击 WebView 区域
                        cursorView.performClick(webView);
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    // 先清除工具栏焦点，确保按钮不会拦截
                    if (toolbar != null) clearFocusRecursively(toolbar);
                    if (webView.canGoBack()) {
                        webView.goBack();
                    } else {
                        showHomePage();
                    }
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    // 菜单键在 onKeyUp 中处理（切换工具栏）
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (webPage.getVisibility() == View.VISIBLE && keyCode == KeyEvent.KEYCODE_MENU) {
            // 菜单键：切换工具栏显示/隐藏
            toolbar.setVisibility(
                toolbar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
            );
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

        // Android 13+：申请细粒度媒体权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO);
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

        // Android 14+ 引导用户授权 MANAGE_EXTERNAL_STORAGE（手机/平板需要）
        // Android 10-13 使用 MediaStore API 无需额外权限
        // Android 9- 申请传统存储权限
        checkStoragePermission();
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

    /**
     * 方案 C：区分版本处理存储权限
     * - Android 14+：引导授权 MANAGE_EXTERNAL_STORAGE（仅首次启动时提示）
     * - Android 10-13：MediaStore 无需额外权限
     * - Android 9-：已申请传统权限
     */
    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= 34) { // Android 14+
            // 检查是否是首次启动
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            boolean isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true);

            if (isFirstLaunch && !android.os.Environment.isExternalStorageManager()) {
                prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
                DialogHelper.show(this, "📁", "需要文件访问权限",
                    "为了能够管理外部存储文件（如 U 盘、下载目录），需要授予「所有文件访问」权限。\n\n请在设置页面中开启此权限。",
                    "去授权", "暂不授权",
                    () -> {
                        try {
                            Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                            );
                            startActivity(intent);
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception ignored) {}
                        }
                    },
                    () -> Toast.makeText(this, "未授权将只能访问应用私有目录", Toast.LENGTH_LONG).show()
                );
            }
        }
        // Android 10-13：MediaStore API 无需额外权限
        // Android 9-：已在 checkPermissions() 中申请 WRITE_EXTERNAL_STORAGE
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

    // ===================== ADB 启动检测 =====================

    /** App启动时自动检测ADB状态，未连接时弹出连接对话框 */
    private void checkAdbOnStart() {
        updateAdbStatusText("🔌 adb：检测中...");
        backendManager.addListener(state -> runOnUiThread(() -> updateAdbStatusText(DialogHelper.buildBackendStatusText(state))));
        backendManager.refreshState(state -> runOnUiThread(() -> {
            updateAdbStatusText(DialogHelper.buildBackendStatusText(state));
            if (state.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED) {
                DialogHelper.showBackendGuide(this, state,
                    () -> backendManager.requestShizukuPermission(this, granted -> {
                        if (granted) backendManager.refreshState(null);
                    }),
                    null);
            } else if (state.status == BackendStatus.AUTH_REQUIRED) {
                Toast.makeText(this, state.detail, Toast.LENGTH_LONG).show();
            }
        }));
    }

    /** 无线ADB配对/连接对话框（IP+端口+配对码，配对码选填） */
    private void showWirelessAdbDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_adb_pair, null);

        EditText etHost = dialogView.findViewById(R.id.etHost);
        EditText etPort = dialogView.findViewById(R.id.etPort);
        EditText etCode = dialogView.findViewById(R.id.etCode);
        android.widget.LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        android.widget.LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);

        // IP 输入过滤
        etHost.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etHost.setFilters(new android.text.InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            StringBuilder b = new StringBuilder();
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (Character.isDigit(c) || c == '.') b.append(c);
            }
            return b.toString();
        }});

        // 端口默认 5555
        etPort.setText("5555");
        // 配对码提示
        etCode.setHint("配对码(选填)");

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogStandardStyle).create();
        dialog.setView(dialogView);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        // 取消按钮
        btnNegative.setOnClickListener(v -> dialog.dismiss());

        // 连接按钮（配对码选填）
        btnPositive.setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            int port = 5555;
            try { port = Integer.parseInt(portStr.isEmpty() ? "5555" : portStr); } catch (Exception ignored) {}

            if (host.isEmpty()) {
                Toast.makeText(this, "⚠️ 请输入目标设备IP", Toast.LENGTH_SHORT).show();
                return;
            }

            dialog.dismiss();
            if (!code.isEmpty()) {
                // 有配对码 → 先配对再连接
                doWirelessPairAndConnect(host, port, code);
            } else {
                // 无配对码 → 直接连接（已配对设备）
                final ProgressDialog pd = new ProgressDialog(this);
                pd.setMessage("正在连接 " + host + ":" + port + " ...");
                pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                pd.setCancelable(false);
                pd.show();

                AdbHelper.get().connect(host, port, ok -> runOnUiThread(() -> {
                    pd.dismiss();
                    if (ok) {
                        updateAdbStatusText("🔗 adb：已连接 " + host);
                        Toast.makeText(this, "✅ ADB 连接成功！", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "❌ 连接失败，请确认设备在线且端口正确", Toast.LENGTH_LONG).show();
                    }
                }));
            }
        });

        dialog.show();

        // 弹窗宽度 400dp
        if (dialog.getWindow() != null) {
            int w = (int) (400 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        // 自动聚焦 IP 输入框
        etHost.requestFocus();
        etHost.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etHost, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    /** 执行配对，连接端口需用户单独输入 */
    private void doWirelessPairAndConnect(String host, int port, String code) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("⏳ 正在配对...\n请等待");
        pd.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        pd.setCancelable(false);
        pd.show();

        AdbHelper.get().pair(host, port, code, pairOk -> {
            if (!pairOk) {
                pd.dismiss();
                runOnUiThread(() -> {
                    Toast.makeText(this, "❌ 配对失败，请检查配对码和端口", Toast.LENGTH_LONG).show();
                    updateAdbStatusText("🔌 adb：配对失败");
                });
                return;
            }

            pd.dismiss();
            runOnUiThread(() -> {
                updateAdbStatusText("🔑 adb：已配对");
                Toast.makeText(this,
                    "✅ 配对成功，请使用无线调试页面显示的连接端口继续连接",
                    Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 更新ADB状态指示文字 */
    private void updateAdbStatusText(String text) {
        if (tvAdbStatus != null) {
            tvAdbStatus.setText(text);
        }
    }
}
