package com.miaomiaotv.homeredirector;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设置页：目前提供一个“进入桌面的遮罩”开关，其余是状态信息。
 * 遥控器方向键移动焦点，OK 键切换；开关写入 SharedPreferences，
 * HomeWatcher 每次显示遮罩前都会重新读取，改完立即生效。
 */
public class MainActivity extends Activity {

    private static final String SELF_SERVICE =
            "com.miaomiaotv.homeredirector/com.miaomiaotv.homeredirector.HomeWatcher";

    private TextView statusView;
    private Button coverBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(70, 50, 70, 40);
        root.setBackgroundColor(0xFF12161A);

        TextView title = new TextView(this);
        title.setText("HomeRedirector");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(0xFFB0BEC5);
        statusView.setPadding(0, 18, 0, 30);
        root.addView(statusView);

        coverBtn = new Button(this);
        coverBtn.setTextSize(18);
        coverBtn.setFocusable(true);
        coverBtn.setFocusableInTouchMode(true);
        coverBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !isCoverEnabled();
                setCoverEnabled(next);
                refresh();
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.START;
        root.addView(coverBtn, btnLp);

        TextView hint = new TextView(this);
        hint.setText("\n遥控器：方向键移动焦点，OK 键切换\n\n"
                + "· 遮罩开启：按主页键后先显示“正在进入桌面…”，\n"
                + "  目标桌面出现后自动消失（等桌面起来时不会看到黑屏闪烁）。\n"
                + "· 遮罩关闭：按主页键后直接进入目标桌面。");
        hint.setTextSize(13);
        hint.setTextColor(0xFF78909C);
        root.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        refresh();
        coverBtn.requestFocus();
        Log.i(HomeWatcher.TAG, "MainActivity opened");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = isCoverEnabled();
        coverBtn.setText(on
                ? "进入桌面的遮罩：已开启    （按 OK 关闭）"
                : "进入桌面的遮罩：已关闭    （按 OK 开启）");
        statusView.setText(
                "目标桌面     : " + HomeWatcher.TARGET_PKG + "\n"
                        + "被替换桌面 : " + HomeWatcher.STOCK_PKG + "\n"
                        + "无障碍服务 : "
                        + (isServiceEnabled() ? "已启用" : "未启用（需在系统设置里开启）"));
    }

    private boolean isCoverEnabled() {
        try {
            return HomeWatcher.prefs(this)
                    .getBoolean(HomeWatcher.KEY_SHOW_COVER, HomeWatcher.DEFAULT_SHOW_COVER);
        } catch (Throwable t) {
            return HomeWatcher.DEFAULT_SHOW_COVER;
        }
    }

    private void setCoverEnabled(boolean on) {
        try {
            HomeWatcher.prefs(this).edit()
                    .putBoolean(HomeWatcher.KEY_SHOW_COVER, on).apply();
            Log.i(HomeWatcher.TAG, "cover preference -> " + on);
        } catch (Throwable t) {
            Log.w(HomeWatcher.TAG, "set cover preference failed", t);
        }
    }

    private boolean isServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains("com.miaomiaotv.homeredirector");
        } catch (Throwable t) {
            return false;
        }
    }
}
