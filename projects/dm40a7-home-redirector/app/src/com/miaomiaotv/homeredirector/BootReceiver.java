package com.miaomiaotv.homeredirector;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机后自动把目标桌面拉起来。
 *
 * 因为原厂桌面已被隐藏（pm hide），系统启动 HOME 会失败，所以这里要尽快接管；
 * 间隔一小段时间再拉一次做二次保险（幂等，只是把目标桌面带到前台）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = (intent == null) ? null : intent.getAction();
        if (action == null) {
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        final Context ctx = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(3000L);
                    launch(ctx, "boot#1");
                    Thread.sleep(4000L);
                    launch(ctx, "boot#2");
                } catch (Throwable t) {
                    Log.e(HomeWatcher.TAG, "boot launch failed", t);
                } finally {
                    if (pending != null) {
                        pending.finish();
                    }
                }
            }
        }).start();
    }

    private void launch(Context ctx, String from) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(HomeWatcher.TARGET_PKG, HomeWatcher.TARGET_CLS));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            ctx.startActivity(i);
            Log.i(HomeWatcher.TAG, "boot launch(" + from + ") -> " + HomeWatcher.TARGET_PKG);
        } catch (Throwable t) {
            Log.e(HomeWatcher.TAG, "boot launch(" + from + ") failed", t);
        }
    }
}
