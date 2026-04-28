package com.miaomiao.tv;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.RemoteInput;

/**
 * ADB 配对服务
 *
 * 支持 Android 5-16：
 * - Android 5-10 (API 21-29): adb tcpip 后直连，无需配对码
 * - Android 11+ (API 30+):   需要 6 位配对码
 *
 * 配对码获取来源优先级：
 * 1. RemoteInput 回复（Android 7+ 通知直接输入）
 * 2. 剪贴板（用户复制配对码后粘贴）
 * 3. 用户在弹窗中输入
 */
public class AdbPairService extends Service {

    private static final String TAG = "AdbPairService";

    private static final int NOTIFICATION_ID_PAIR = 4;

    // Intent Action & Extra 常量
    public static final String ACTION_REPLY        = "com.miaomiao.ACTION_REPLY";
    public static final String ACTION_START_PAIR    = "com.miaomiao.ACTION_START_PAIR";
    public static final String EXTRA_KEY_TEXT_REPLY = "key_text_reply";
    public static final String EXTRA_HOST          = "extra_host";
    public static final String EXTRA_PORT          = "extra_port";
    public static final String EXTRA_PAIRING_CODE  = "extra_pairing_code";

    // 配对结果广播（供 Activity 接收）
    public static final String ACTION_PAIR_RESULT    = "com.miaomiao.ACTION_PAIR_RESULT";
    public static final String EXTRA_PAIR_SUCCESS    = "extra_pair_success";
    public static final String EXTRA_PAIR_MESSAGE   = "extra_pair_message";

    private static PairingCallback sPairingCallback;

    public interface PairingCallback {
        /** 配对开始，通知 UI */
        void onPairingRequested(String host, int port);
        /** 收到配对码，通知 UI */
        void onPairingCodeReceived(String code);
        /** 配对完成（成功/失败） */
        void onPairingComplete(boolean success, String message);
    }

    public static void setPairingCallback(PairingCallback callback) {
        sPairingCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AdbPairService created, API: " + AndroidVersionCompat.getApiLevel());
        AndroidVersionCompat.createNotificationChannels(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand action: " + action);

        if (ACTION_START_PAIR.equals(action)) {
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, 5555);
            startPairingNotification(host, port);
        } else if (ACTION_REPLY.equals(action)) {
            handlePairingResponse(intent);
        }

        return START_NOT_STICKY;
    }

    // ===================== 通知构建 =====================

    private void startPairingNotification(String host, int port) {
        Notification notification = createPairingNotification(host, port);
        if (AndroidVersionCompat.isOreoOrAbove()) {
            startForeground(NOTIFICATION_ID_PAIR, notification);
        } else {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_PAIR, notification);
        }
    }

    private Notification createPairingNotification(String host, int port) {
        Intent replyIntent = new Intent(this, AdbPairService.class);
        replyIntent.setAction(ACTION_REPLY);
        replyIntent.putExtra(EXTRA_HOST, host);
        replyIntent.putExtra(EXTRA_PORT, port);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent replyPendingIntent = PendingIntent.getService(this, 0, replyIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                AndroidVersionCompat.CHANNEL_ID_ADB_PAIR);

        builder.setSmallIcon(R.drawable.ic_adb)
               .setContentTitle("ADB 无线配对")
               .setContentText("等待输入配对码...")
               .setPriority(NotificationCompat.PRIORITY_HIGH)
               .setAutoCancel(false)
               .setOngoing(true);

        if (AndroidVersionCompat.supportsRemoteInput()) {
            RemoteInput remoteInput = new RemoteInput.Builder(EXTRA_KEY_TEXT_REPLY)
                    .setLabel("输入6位配对码")
                    .setChoices(new String[]{"000000", "111111", "222222"})
                    .build();

            NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                    R.drawable.ic_send, "确认", replyPendingIntent)
                    .addRemoteInput(remoteInput)
                    .setShowsUserInterface(true)
                    .build();

            builder.addAction(action);
        }

        return builder.build();
    }

    // ===================== 配对响应处理 =====================

    /**
     * 处理配对响应（从 RemoteInput 或剪贴板获取配对码）
     */
    private void handlePairingResponse(Intent intent) {
        String pairingCode = null;
        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, 5555);

        // 1. 优先从 RemoteInput 获取（用户直接在通知中输入）
        if (AndroidVersionCompat.supportsRemoteInput()) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results != null) {
                pairingCode = results.getString(EXTRA_KEY_TEXT_REPLY);
            }
        }

        // 2. 备用：检查剪贴板（用户复制了 6 位配对码）
        if (pairingCode == null || pairingCode.isEmpty()) {
            pairingCode = getPairingCodeFromClipboard();
        }

        // 3. 验证配对码格式
        if (pairingCode != null && pairingCode.matches("\\d{6}")) {
            Log.d(TAG, "Got valid pairing code: " + pairingCode.charAt(0) + "***");
            if (sPairingCallback != null) {
                sPairingCallback.onPairingCodeReceived(pairingCode);
            }
            stopForeground(true);
            performPairing(host, port, pairingCode);
        } else {
            Log.w(TAG, "Invalid or missing pairing code");
            if (sPairingCallback != null) {
                sPairingCallback.onPairingComplete(false, "配对码无效，请输入6位数字配对码");
            }
            showErrorNotification("配对码无效，请重新输入6位数字配对码");
            stopForeground(true);
        }
    }

    /**
     * 从系统剪贴板获取配对码（用户复制配对码后粘贴）
     */
    private String getPairingCodeFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clipData = clipboard.getPrimaryClip();
                if (clipData != null && clipData.getItemCount() > 0) {
                    CharSequence text = clipData.getItemAt(0).getText();
                    if (text != null) {
                        String trimmed = text.toString().trim();
                        if (trimmed.matches("\\d{6}")) {
                            return trimmed;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading clipboard", e);
        }
        return null;
    }

    // ===================== 执行配对（核心修复）=====================

    /**
     * 真正执行 ADB 配对操作
     *
     * 使用 AdbHelper.pair() 调用 libadb-android 执行配对协议。
     * Android 11+ 的配对端口和连接端口通常不同，因此这里只做配对。
     */
    private void performPairing(String host, int port, String code) {
        Log.d(TAG, "Performing pairing to " + host + ":" + port);

        if (sPairingCallback != null) {
            sPairingCallback.onPairingRequested(host, port);
        }

        // Android 11+ 的 pairing port 与 connect port 往往不同，避免错误地复用同一端口。
        AdbHelper.get().pair(host, port, code, pairSuccess -> {
            if (pairSuccess) {
                if (sPairingCallback != null) {
                    sPairingCallback.onPairingComplete(true, "配对成功，请使用连接端口手动连接");
                }
                broadcastPairResult(true, "配对成功，请使用连接端口手动连接");
                showSuccessNotification();
            } else {
                if (sPairingCallback != null) {
                    sPairingCallback.onPairingComplete(false, "配对失败，请检查配对码是否正确");
                }
                broadcastPairResult(false, "配对失败，请检查配对码是否正确");
                showErrorNotification("配对失败，请检查配对码是否正确");
            }
        });
    }

    // ===================== 通知辅助 =====================

    private void showErrorNotification(String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                AndroidVersionCompat.CHANNEL_ID_ADB_PAIR);
        builder.setSmallIcon(R.drawable.ic_adb)
               .setContentTitle("ADB 配对失败")
               .setContentText(message)
               .setPriority(NotificationCompat.PRIORITY_HIGH)
               .setAutoCancel(true);
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_PAIR, builder.build());
    }

    private void showSuccessNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                AndroidVersionCompat.CHANNEL_ID_ADB_STATUS);
        builder.setSmallIcon(R.drawable.ic_adb)
               .setContentTitle("ADB 配对成功")
               .setContentText("可以使用无线 ADB 连接了")
               .setPriority(NotificationCompat.PRIORITY_LOW)
               .setAutoCancel(true);
        NotificationManagerCompat.from(this).notify(1001, builder.build());
    }

    /**
     * 广播配对结果，供 Activity 接收
     */
    private void broadcastPairResult(boolean success, String message) {
        Intent resultIntent = new Intent(ACTION_PAIR_RESULT);
        resultIntent.putExtra(EXTRA_PAIR_SUCCESS, success);
        resultIntent.putExtra(EXTRA_PAIR_MESSAGE, message);
        sendBroadcast(resultIntent);
    }

    /**
     * 构建"需要配对"的提示通知（由 Activity 调用）
     */
    public static Notification buildPairingRequiredNotification(Context context, String host, int port) {
        Intent startPairIntent = new Intent(context, AdbPairService.class);
        startPairIntent.setAction(ACTION_START_PAIR);
        startPairIntent.putExtra(EXTRA_HOST, host);
        startPairIntent.putExtra(EXTRA_PORT, port);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getService(context, 1, startPairIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context,
                AndroidVersionCompat.CHANNEL_ID_ADB_PAIR);
        builder.setSmallIcon(R.drawable.ic_adb)
               .setContentTitle("ADB 需要配对")
               .setContentText("点击此处输入配对码")
               .setPriority(NotificationCompat.PRIORITY_HIGH)
               .setContentIntent(pendingIntent)
               .setAutoCancel(true);

        return builder.build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AdbPairService destroyed");
        sPairingCallback = null;
    }
}
