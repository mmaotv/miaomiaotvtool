# ADB 无线调试 Android 5-16 版本兼容适配方案

## 一、背景说明

当前项目已使用 `libadb-android` 库 (`io.github.muntashirakon.adb`) 实现 ADB 无线调试功能。本文档基于参考项目 `D:\apkbuild_yunpan` 的实现，总结 Android 5-16 版本兼容性适配方案。

## 二、Android 版本差异分析

### 2.1 关键版本节点

| 版本范围 | API Level | ADB 无线调试支持 | 配对方式 |
|---------|-----------|-----------------|---------|
| Android 5.0-5.1 | 21-22 | `adb tcpip 5555` | 无需配对 |
| Android 6.0 | 23 | `adb tcpip 5555` | 无需配对 |
| Android 7.0-10 | 24-29 | `adb tcpip 5555` | 无需配对 |
| Android 11-12 | 30-31 | 完整支持 | 6位配对码 |
| Android 13-14 | 32-34 | 完整支持 | 6位配对码 |
| Android 15+ | 35+ | 完整支持 | 6位配对码 |

### 2.2 技术差异

1. **Notification API 差异**
   - Android 8.0+ (API 26+) 需要创建 NotificationChannel
   - Android 7.1 (API 25) 及以下直接使用 Notification

2. **RemoteInput API 差异**
   - Android 7.0+ (API 24+) 支持 RemoteInput
   - Android 5-6 (API 21-23) 需要使用 Intent.EXTRA_CLIP_DATA 方式

3. **权限差异**
   - 不同版本对 WRITE_SECURE_SETTINGS 权限的处理

## 三、适配实现方案

### 3.1 创建版本兼容工具类

```java
package com.miaomiao.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

/**
 * Android 版本兼容工具类
 * 用于处理不同 Android 版本的 API 差异
 */
public class AndroidVersionCompat {
    
    // Android 版本常量
    public static final int API_5 = 21;  // Android 5.0
    public static final int API_6 = 23;  // Android 6.0
    public static final int API_7 = 24;  // Android 7.0
    public static final int API_8 = 26;  // Android 8.0
    public static final int API_11 = 30; // Android 11
    public static final int API_13 = 33; // Android 13
    public static final int API_15 = 35; // Android 15
    
    /**
     * 获取当前设备 API Level
     */
    public static int getApiLevel() {
        return Build.VERSION.SDK_INT;
    }
    
    /**
     * 检查是否为 Android 8.0 (API 26) 及以上
     */
    public static boolean isOreoOrAbove() {
        return Build.VERSION.SDK_INT >= API_8;
    }
    
    /**
     * 检查是否为 Android 11 (API 30) 及以上
     * Android 11+ 支持无线配对码
     */
    public static boolean isAndroid11OrAbove() {
        return Build.VERSION.SDK_INT >= API_11;
    }
    
    /**
     * 检查是否为 Android 13 (API 33) 及以上
     */
    public static boolean isAndroid13OrAbove() {
        return Build.VERSION.SDK_INT >= API_13;
    }
    
    /**
     * 检查是否需要配对
     * Android 11+ 首次连接需要配对码
     */
    public static boolean requiresPairing() {
        return Build.VERSION.SDK_INT >= API_11;
    }
    
    /**
     * 创建通知渠道（Android 8.0+ 需要）
     */
    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= API_8) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ADB 无线配对",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("用于 ADB 无线配对通知");
            channel.enableVibration(true);
            
            NotificationManager manager = 
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    // 通知渠道 ID
    public static final String CHANNEL_ID = "adb_pair_service_channel";
}
```

### 3.2 配对服务适配

```java
package com.miaomiao.tv;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;

/**
 * ADB 配对服务 - 支持 Android 5-16
 * 
 * Android 5-6 (API 21-23): 使用 Intent.EXTRA_CLIP_DATA 获取配对码
 * Android 7+ (API 24+): 使用 RemoteInput 获取配对码
 */
public class AdbPairService extends Service {
    
    private static final String TAG = "AdbPairService";
    private static final int NOTIFICATION_ID = 4;
    
    // Action 常量
    public static final String ACTION_REPLY = "com.yunpan.appmanage.ACTION_REPLY";
    public static final String EXTRA_KEY_TEXT_REPLY = "key_text_reply";
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Android 8.0+ 需要创建通知渠道
        AndroidVersionCompat.createNotificationChannel(this);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        
        String action = intent.getAction();
        if (ACTION_REPLY.equals(action)) {
            handlePairingResponse(intent);
        }
        
        return START_NOT_STICKY;
    }
    
    /**
     * 处理配对响应
     * 根据 Android 版本使用不同的方式获取配对码
     */
    private void handlePairingResponse(Intent intent) {
        String pairingCode = null;
        
        if (AndroidVersionCompat.isAndroid7OrAbove()) {
            // Android 7.0+ 使用 RemoteInput
            Bundle resultsFromIntent = RemoteInput.getResultsFromIntent(intent);
            if (resultsFromIntent != null) {
                pairingCode = resultsFromIntent.getString(EXTRA_KEY_TEXT_REPLY);
            }
        } else {
            // Android 5-6 使用 ClipData
            ClipData clipData = intent.getClipData();
            if (clipData != null) {
                ClipData.Item item = clipData.getItemAt(0);
                if (item != null && item.getIntent() != null) {
                    Bundle extras = item.getIntent().getExtras();
                    if (extras != null) {
                        pairingCode = extras.getString(EXTRA_KEY_TEXT_REPLY);
                    }
                }
            }
        }
        
        if (pairingCode != null && pairingCode.length() == 6) {
            // 验证配对码格式并执行配对
            performPairing(pairingCode);
        } else {
            // 配对码无效
            showPairingErrorNotification("配对码错误，请重新尝试");
        }
    }
    
    /**
     * 执行配对
     */
    private void performPairing(String code) {
        // 调用 AdbHelper 执行配对
        // ...
    }
    
    /**
     * 创建配对通知
     */
    private Notification createPairingNotification(String code) {
        // 创建用于接收配对码的 PendingIntent
        Intent replyIntent = new Intent(this, AdbPairService.class);
        replyIntent.setAction(ACTION_REPLY);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent replyPendingIntent = PendingIntent.getService(
            this, 0, replyIntent, flags
        );
        
        // 根据 Android 版本构建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, 
            AndroidVersionCompat.CHANNEL_ID);
        
        builder.setSmallIcon(R.drawable.ic_adb)
               .setContentTitle("ADB 无线配对")
               .setContentText("请输入配对码")
               .setPriority(NotificationCompat.PRIORITY_HIGH)
               .setAutoCancel(true);
        
        if (AndroidVersionCompat.isAndroid7OrAbove()) {
            // Android 7.0+ 使用 RemoteInput
            RemoteInput remoteInput = new RemoteInput.Builder(EXTRA_KEY_TEXT_REPLY)
                .setLabel("输入6位配对码")
                .build();
            
            NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                R.drawable.ic_send, "确认", replyPendingIntent
            ).addRemoteInput(remoteInput).build();
            
            builder.addAction(action);
        }
        
        return builder.build();
    }
    
    /**
     * 显示配对错误通知
     */
    private void showPairingErrorNotification(String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
            AndroidVersionCompat.CHANNEL_ID);
        
        builder.setSmallIcon(R.drawable.ic_adb)
               .setContentTitle("配对失败")
               .setContentText(message)
               .setPriority(NotificationCompat.PRIORITY_HIGH)
               .setAutoCancel(true);
        
        NotificationManager manager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
```

### 3.3 AdbHelper 版本适配

```java
package com.miaomiao.tv;

import android.os.Build;

/**
 * ADB 连接版本适配器
 * 处理不同 Android 版本的连接差异
 */
public class AdbConnectionAdapter {
    
    /**
     * 获取连接超时时间（毫秒）
     * 根据 Android 版本调整
     */
    public static int getConnectionTimeout() {
        if (AndroidVersionCompat.isAndroid13OrAbove()) {
            return 15000; // Android 13+ 需要更长的超时时间
        } else if (AndroidVersionCompat.isAndroid11OrAbove()) {
            return 12000; // Android 11-12
        } else {
            return 10000; // Android 5-10
        }
    }
    
    /**
     * 获取配对超时时间（毫秒）
     */
    public static int getPairingTimeout() {
        if (AndroidVersionCompat.isAndroid11OrAbove()) {
            return 15000; // 需要等待用户输入配对码
        } else {
            return 10000;
        }
    }
    
    /**
     * 检查是否支持 TLS 连接
     * Android 11+ 支持 STLS
     */
    public static boolean supportsTlsConnection() {
        return AndroidVersionCompat.isAndroid11OrAbove();
    }
    
    /**
     * 获取默认 ADB 端口
     */
    public static int getDefaultAdbPort() {
        return 5555;
    }
    
    /**
     * 获取配对端口
     * Android 11+ 使用 5555 作为配对端口
     */
    public static int getDefaultPairingPort() {
        if (AndroidVersionCompat.isAndroid11OrAbove()) {
            return 5555;
        } else {
            return 5555; // 旧版本也使用 5555
        }
    }
    
    /**
     * 判断是否需要用户授权
     * Android 4.2+ 需要用户授权，但不同版本授权方式不同
     */
    public static boolean requiresUserAuthorization() {
        // 所有版本首次连接都需要用户确认授权
        return true;
    }
    
    /**
     * 获取协议版本
     */
    public static int getProtocolVersion() {
        if (AndroidVersionCompat.isAndroid13OrAbove()) {
            return 3; // 最新协议
        } else if (AndroidVersionCompat.isAndroid11OrAbove()) {
            return 2;
        } else {
            return 1;
        }
    }
}
```

### 3.4 连接对话框版本适配

```java
/**
 * ADB 连接对话框 - 版本适配
 */
public class AdbConnectDialogHelper {
    
    /**
     * 根据 Android 版本显示合适的连接对话框
     */
    public static void showConnectDialog(Activity activity, AdbConnectCallback callback) {
        int apiLevel = AndroidVersionCompat.getApiLevel();
        
        if (apiLevel >= AndroidVersionCompat.API_11) {
            // Android 11+ 显示完整对话框（包含配对码输入）
            showFullConnectDialog(activity, callback);
        } else {
            // Android 5-10 简化对话框
            showSimpleConnectDialog(activity, callback);
        }
    }
    
    /**
     * 显示完整对话框（Android 11+）
     */
    private static void showFullConnectDialog(Activity activity, AdbConnectCallback callback) {
        View dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_adb_connect, null);
        
        EditText etHost = dialogView.findViewById(R.id.etHost);
        EditText etPort = dialogView.findViewById(R.id.etPort);
        EditText etCode = dialogView.findViewById(R.id.etCode);
        
        // 默认值
        etHost.setText("127.0.0.1");
        etPort.setText("5555");
        
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(dialogView).create();
        
        dialogView.findViewById(R.id.btnPositive).setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            
            if (host.isEmpty()) {
                Toast.makeText(activity, "请输入 IP 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            
            int port = 5555;
            try { port = Integer.parseInt(portStr); } catch (Exception ignored) {}
            
            dialog.dismiss();
            callback.onConfirm(host, port, code);
        });
        
        dialog.show();
    }
    
    /**
     * 显示简化对话框（Android 5-10）
     * 这些版本不需要配对码
     */
    private static void showSimpleConnectDialog(Activity activity, AdbConnectCallback callback) {
        View dialogView = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_adb_connect_simple, null);
        
        EditText etHost = dialogView.findViewById(R.id.etHost);
        EditText etPort = dialogView.findViewById(R.id.etPort);
        
        etHost.setText("127.0.0.1");
        etPort.setText("5555");
        
        AlertDialog dialog = new AlertDialog.Builder(activity).setView(dialogView).create();
        
        dialogView.findViewById(R.id.btnPositive).setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            
            if (host.isEmpty()) {
                Toast.makeText(activity, "请输入 IP 地址", Toast.LENGTH_SHORT).show();
                return;
            }
            
            int port = 5555;
            try { port = Integer.parseInt(portStr); } catch (Exception ignored) {}
            
            dialog.dismiss();
            callback.onConfirm(host, port, null); // 不需要配对码
        });
        
        dialog.show();
    }
    
    public interface AdbConnectCallback {
        void onConfirm(String host, int port, String pairingCode);
    }
}
```

### 3.5 简化版连接布局文件

创建 `res/layout/dialog_adb_connect_simple.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="24dp"
    android:background="@drawable/dialog_background">

    <!-- 标题 -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="🔗 ADB 连接"
        android:textSize="20sp"
        android:textStyle="bold"
        android:textColor="@color/text_primary"
        android:layout_marginBottom="16dp"/>

    <!-- IP 地址输入 -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="IP 地址："
        android:textSize="14sp"
        android:textColor="@color/text_secondary"/>
    
    <EditText
        android:id="@+id/etHost"
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:hint="如 192.168.1.100"
        android:inputType="numberDecimal"
        android:paddingHorizontal="12dp"
        android:layout_marginBottom="12dp"/>

    <!-- 端口输入 -->
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="端口："
        android:textSize="14sp"
        android:textColor="@color/text_secondary"/>
    
    <EditText
        android:id="@+id/etPort"
        android:layout_width="match_parent"
        android:layout_height="48dp"
        android:hint="5555"
        android:inputType="number"
        android:paddingHorizontal="12dp"
        android:layout_marginBottom="20dp"/>

    <!-- 提示文字 -->
    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="💡 提示：Android 5-10 版本无需配对码"
        android:textSize="12sp"
        android:textColor="@color/text_hint"
        android:layout_marginBottom="16dp"/>

    <!-- 按钮 -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="end">

        <LinearLayout
            android:id="@+id/btnNegative"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingHorizontal="24dp"
            android:paddingVertical="12dp"
            android:layout_marginEnd="12dp"
            android:background="@drawable/btn_secondary_bg">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="取消"
                android:textColor="@color/text_primary"/>
        </LinearLayout>

        <LinearLayout
            android:id="@+id/btnPositive"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:paddingHorizontal="24dp"
            android:paddingVertical="12dp"
            android:background="@drawable/btn_primary_bg">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="连接"
                android:textColor="@android:color/white"/>
        </LinearLayout>
    </LinearLayout>
</LinearLayout>
```

## 四、AndroidManifest.xml 配置

```xml
<!-- ADB 配对服务 -->
<service
    android:name=".AdbPairService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync" />

<!-- 权限声明 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />
```

## 五、版本兼容检查清单

| 检查项 | Android 5-6 | Android 7-10 | Android 11-12 | Android 13+ |
|-------|-------------|--------------|---------------|-------------|
| 通知渠道 | ❌ 不需要 | ❌ 不需要 | ✅ 需要 | ✅ 需要 |
| RemoteInput | ❌ 不支持 | ✅ 支持 | ✅ 支持 | ✅ 支持 |
| 配对码 | ❌ 不需要 | ❌ 不需要 | ✅ 需要 | ✅ 需要 |
| TLS | ❌ 不支持 | ❌ 不支持 | ✅ 支持 | ✅ 支持 |
| 协议版本 | v1 | v1 | v2 | v2/v3 |
| 超时时间 | 10秒 | 10秒 | 12秒 | 15秒 |

## 六、使用说明

### Android 5-10 使用流程
1. 用 USB 连接电脑
2. 执行 `adb tcpip 5555`
3. 断开 USB
4. 打开应用，连接 127.0.0.1:5555

### Android 11+ 使用流程
1. 用 USB 连接电脑
2. 执行 `adb pair 127.0.0.1:5555`
3. 输入设备显示的 6 位配对码
4. 执行 `adb connect 127.0.0.1:5555`
5. 断开 USB
6. 打开应用，连接 127.0.0.1:5555（可能需要再次输入配对码）

## 七、注意事项

1. **WRITE_SECURE_SETTINGS 权限**：部分功能需要此权限，需要 ROOT 或特殊授权
2. **网络权限**：确保应用有 INTERNET 权限
3. **防火墙**：确保目标设备的 5555 端口未被防火墙阻止
4. **同一网络**：无线连接时设备需要在同一局域网内
