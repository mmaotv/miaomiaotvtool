package com.miaomiaotv.homeredirector;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import java.util.List;

/**
 * 无 root 保桌面。
 *
 * 背景（实测结论）：
 *   - 该 ROM 把系统 HOME 硬绑到原厂桌面，按 HOME 一定先起原厂桌面；
 *   - 原厂桌面启动时很吃资源，会把目标桌面的启动拖慢到 9 秒左右；
 *   - 而且这个 ROM 的无障碍服务收不到按键事件，没法拦 HOME 键；
 *   - 若让原厂桌面不可用，系统 goHome 会静默失败（画面不动），主页键等于失灵。
 *
 * 所以策略是：不阻止它启动，但立刻用一层系统级遮罩盖住它，让用户看不到那几秒；
 * 同时把目标桌面拉起来，目标桌面显示后遮罩自动撤掉。
 */
public class HomeWatcher extends AccessibilityService {

    public static final String TAG = "HomeRedirector";

    /** 原厂桌面（海看 DomyUI4） */
    public static final String STOCK_PKG = "com.hiveview.cloudscreen.launcher";
    /** 目标桌面：Emotn UI */
    public static final String TARGET_PKG = "com.oversea.aslauncher";
    public static final String TARGET_CLS = "com.oversea.aslauncher.ui.main.MainActivity";

    /** 需要被遮掉的原厂前台（原厂桌面本身 + 它拉起的新手引导） */
    private static final String[] STOCK_PKGS = {
            "com.hiveview.cloudscreen.launcher",
            "com.hiveview.cloudscreen.guide",
    };

    /** 拉起目标桌面后的冷却期：期内不再重复拉起，避免打断它正在进行的冷启动 */
    private static final long LAUNCH_COOLDOWN_MS = 3500L;
    /** 看门狗轮询间隔（越小响应越快；120ms 兼顾灵敏度和开销） */
    private static final long WATCHDOG_INTERVAL_MS = 120L;
    /** 遮罩最长显示时间（兜底） */
    private static final long COVER_MAX_MS = 8000L;
    /** 连续多少次"没有任何有效窗口"才判定为无桌面黑屏 */
    private static final int BLANK_THRESHOLD = 2;
    /**
     * “期待窗口”：只有刚按下 HOME 键之后的这段时间里，空窗口/原厂桌面
     * 才被当作“桌面没回来”而去拉起目标桌面。
     * 这非常关键——普通应用冷启动的瞬间窗口也是空的，
     * 没有这道闸门就会把用户刚打开的应用顶掉（表现为“打开就闪退回桌面”）。
     */
    private static final long EXPECT_WINDOW_MS = 8000L;
    /** 遮罩开关在设置页里可改，存这里 */
    private static final String PREFS_NAME = "home_redirector";
    public static final String KEY_SHOW_COVER = "show_cover";
    /** 遮罩默认开启 */
    public static final boolean DEFAULT_SHOW_COVER = true;

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 当前是否要显示遮罩（设置页可随时切换，立即生效） */
    private boolean isCoverEnabled() {
        try {
            return prefs(this).getBoolean(KEY_SHOW_COVER, DEFAULT_SHOW_COVER);
        } catch (Throwable t) {
            return DEFAULT_SHOW_COVER;
        }
    }
    /**
     * 连续多少次“完全没有窗口”就认定系统没有桌面，主动拉起目标桌面。
     * 最典型场景：开机后原厂桌面缺失，HOME 起不来，系统一直停在黑屏
     * （实测焦点停在 KeyguardScrim、前台没有任何 Activity）。
     * 50 × 120ms ≈ 6 秒，远大于普通应用冷启动的窗口空白时间（最长约 3 秒），
     * 所以不会把用户刚打开的应用顶掉。
     */
    private static final int NO_WINDOW_RESCUE_STREAK = 50;

    private Handler handler;
    private Runnable watchdog;
    private long cooldownUntil = 0L;
    private int blankStreak = 0;
    /** “完全没有窗口”的连续计数，用于开机救援 */
    private int noWindowStreak = 0;
    /** 目标桌面连续在前台的次数（用于确认它真的稳定了） */
    private int targetStreak = 0;
    private String lastLoggedPkg = "\u0000";
    /** 是否处于“等目标桌面回来”的状态，以及该状态从何时开始 */
    private boolean expectingTarget = false;
    private long expectingSince = 0L;

    private WindowManager windowManager;
    private View coverView;
    private final Runnable coverTimeoutTask = new Runnable() {
        @Override
        public void run() {
            hideCover("timeout");
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        applyConfig();
        startWatchdog();
        Log.i(TAG, "service connected, uptime=" + SystemClock.elapsedRealtime());
        if (handler != null) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        tick();
                    } catch (Throwable t) {
                        Log.e(TAG, "initial tick failed", t);
                    }
                }
            }, 2000L);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopWatchdog();
        hideCover("unbind");
        return super.onUnbind(intent);
    }

    private void applyConfig() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                info = new AccessibilityServiceInfo();
            }
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 0;
            info.packageNames = null;
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        } catch (Throwable t) {
            Log.w(TAG, "applyConfig failed", t);
        }
    }

    // ---------------- 事件路径 ----------------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (isStock(pkg == null ? null : pkg.toString())) {
            onStockForeground("event");
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            // 按下就响应：比等抬起早，更比等看门狗轮询快得多
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                onHomeKeyDown();
            }
            return false;
        }
        return false;
    }

    /**
     * 收到 HOME 键按下：若当前不在目标桌面，立刻盖住并把它拉到前台。
     * （该 ROM 的 goHome 会抛 NPE 静默失败、前台不变，所以这里必须自己拉起。）
     */
    private void onHomeKeyDown() {
        if (TARGET_PKG.equals(foregroundPackage())) {
            expectingTarget = false;
            return;
        }
        expectingTarget = true;
        expectingSince = System.currentTimeMillis();
        showCover();
        launchTarget("homekey");
    }

    /** 是否处于开闸状态：刚按过 HOME，允许兜底逻辑介入 */
    private boolean inExpectWindow() {
        if (!expectingTarget) {
            return false;
        }
        if (System.currentTimeMillis() - expectingSince > EXPECT_WINDOW_MS) {
            expectingTarget = false;
            return false;
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    // ---------------- 看门狗 ----------------

    private void startWatchdog() {
        if (handler == null) {
            handler = new Handler();
        }
        if (watchdog != null) {
            handler.removeCallbacks(watchdog);
        }
        watchdog = new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Throwable t) {
                    Log.e(TAG, "watchdog failed", t);
                }
                if (handler != null) {
                    handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                }
            }
        };
        handler.postDelayed(watchdog, 150L);
    }

    private void stopWatchdog() {
        if (handler != null && watchdog != null) {
            handler.removeCallbacks(watchdog);
        }
        watchdog = null;
    }

    private void tick() {
        String pkg = foregroundPackage();

        if (!same(pkg, lastLoggedPkg)) {
            lastLoggedPkg = pkg;
            Log.i(TAG, "foreground=" + pkg + " (uptime="
                    + SystemClock.elapsedRealtime() + ")");
        }

        // 原厂桌面冒头（只有它还在这台机器上才可能发生）：盖住它并拉起目标桌面
        if (isStock(pkg)) {
            blankStreak = 0;
            targetStreak = 0;
            expectingTarget = true;
            expectingSince = System.currentTimeMillis();
            onStockForeground("watchdog:stock");
            return;
        }

        // 目标桌面已经在前台：关闸、撤遮罩，不再干预
        if (TARGET_PKG.equals(pkg)) {
            expectingTarget = false;
            targetStreak = 0;
            hideCover("target-stable");
            return;
        }

        targetStreak = 0;

        // 空窗口兜底，分两种情形：
        if (isBlank(pkg)) {
            // 1) 刚按过 HOME（期待窗口内）：短促判定即可，立刻把桌面拉回来
            if (inExpectWindow()) {
                blankStreak++;
                if (blankStreak >= BLANK_THRESHOLD) {
                    blankStreak = 0;
                    launchTarget("no-window");
                }
            } else {
                blankStreak = 0;
            }

            // 2) 长时间完全没有窗口：系统压根没有桌面可显示
            //    （开机后原厂桌面缺失、HOME 起不来，一直黑屏就是这种情况）。
            //    阈值远大于普通应用冷启动的窗口空白时间，不会误伤刚打开的应用。
            noWindowStreak++;
            if (noWindowStreak >= NO_WINDOW_RESCUE_STREAK) {
                noWindowStreak = 0;
                // 开机黑屏时也盖一层，避免用户对着纯黑等
                showCover();
                launchTarget("no-window-rescue");
            }
        } else {
            blankStreak = 0;
            noWindowStreak = 0;
        }
    }

    /** 没有任何有效窗口，或只剩一个没有包名的系统占位窗口 */
    private boolean isBlank(String pkg) {
        return pkg == null || "android".equals(pkg);
    }

    private boolean same(String a, String b) {
        return (a == null) ? (b == null) : a.equals(b);
    }

    private boolean isStock(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String s : STOCK_PKGS) {
            if (s.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前前台包名；没有任何有效窗口时返回 null。
     */
    private String foregroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                CharSequence p = root.getPackageName();
                root.recycle();
                if (p != null) {
                    return p.toString();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getRootInActiveWindow failed", t);
        }
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w == null || !w.isActive()) {
                        continue;
                    }
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null) {
                        CharSequence p = r.getPackageName();
                        r.recycle();
                        if (p != null) {
                            return p.toString();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getWindows failed", t);
        }
        return null;
    }

    // ---------------- 遮罩 ----------------

    /** 原厂桌面/引导出现在前台：盖住它，并把目标桌面拉起来 */
    private void onStockForeground(String from) {
        if (System.currentTimeMillis() >= cooldownUntil) {
            showCover();
        }
        launchTarget(from);
    }

    private void showCover() {
        if (!isCoverEnabled()) {
            return;
        }
        if (coverView != null) {
            return;
        }
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        try {
            TextView tv = new TextView(this);
            tv.setBackgroundColor(Color.BLACK);
            tv.setText("正在进入桌面…");
            tv.setTextColor(0xFF9E9E9E);
            tv.setTextSize(20);
            tv.setGravity(Gravity.CENTER);

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            windowManager.addView(tv, p);
            coverView = tv;
            if (handler != null) {
                handler.removeCallbacks(coverTimeoutTask);
                handler.postDelayed(coverTimeoutTask, COVER_MAX_MS);
            }
            Log.i(TAG, "cover shown");
        } catch (Throwable t) {
            Log.e(TAG, "showCover failed", t);
            coverView = null;
        }
    }

    private void hideCover(String why) {
        if (handler != null) {
            handler.removeCallbacks(coverTimeoutTask);
        }
        if (coverView == null) {
            return;
        }
        try {
            windowManager.removeView(coverView);
            Log.i(TAG, "cover hidden (" + why + ")");
        } catch (Throwable t) {
            Log.w(TAG, "hideCover failed", t);
        }
        coverView = null;
    }

    // ---------------- 拉起目标桌面 ----------------

    private void launchTarget(String from) {
        long now = System.currentTimeMillis();
        if (now < cooldownUntil) {
            return;
        }
        cooldownUntil = now + LAUNCH_COOLDOWN_MS;
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(TARGET_PKG, TARGET_CLS));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            Log.i(TAG, "launch target from " + from);
        } catch (Throwable t) {
            Log.e(TAG, "launch failed", t);
        }
    }
}
