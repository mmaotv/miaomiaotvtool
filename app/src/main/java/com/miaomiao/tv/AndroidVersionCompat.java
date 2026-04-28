package com.miaomiao.tv;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * Android 版本兼容工具类
 * 用于处理不同 Android 版本的 API 差异
 * 
 * 支持 Android 5 (API 21) 到 Android 16+
 */
public class AndroidVersionCompat {
    
    // Android 版本常量
    public static final int API_5 = 21;  // Android 5.0
    public static final int API_6 = 23;  // Android 6.0
    public static final int API_7 = 24;  // Android 7.0
    public static final int API_8 = 26;  // Android 8.0 (Oreo)
    public static final int API_9 = 28;  // Android 9.0
    public static final int API_10 = 29; // Android 10
    public static final int API_11 = 30; // Android 11
    public static final int API_12 = 31; // Android 12
    public static final int API_13 = 33; // Android 13
    public static final int API_14 = 34; // Android 14
    public static final int API_15 = 35; // Android 15
    public static final int API_16 = 36; // Android 16
    
    // 通知渠道 ID
    public static final String CHANNEL_ID_ADB_PAIR = "adb_pair_service_channel";
    public static final String CHANNEL_ID_ADB_STATUS = "adb_status_channel";
    
    /**
     * 获取当前设备 API Level
     */
    public static int getApiLevel() {
        return Build.VERSION.SDK_INT;
    }
    
    /**
     * 获取当前 Android 版本名称
     */
    public static String getVersionName() {
        switch (Build.VERSION.SDK_INT) {
            case API_5: return "Android 5.0 (Lollipop)";
            case API_6: return "Android 6.0 (Marshmallow)";
            case API_7: return "Android 7.0 (Nougat)";
            case API_8: return "Android 8.0 (Oreo)";
            case API_9: return "Android 9.0 (Pie)";
            case API_10: return "Android 10";
            case API_11: return "Android 11";
            case API_12: return "Android 12";
            case API_13: return "Android 13";
            case API_14: return "Android 14";
            case API_15: return "Android 15";
            case API_16: return "Android 16";
            default:
                return "Android " + Build.VERSION.SDK_INT;
        }
    }
    
    // ==================== 版本判断方法 ====================
    
    /**
     * 检查是否为 Android 5.0 (API 21) 及以上
     */
    public static boolean isLollipopOrAbove() {
        return Build.VERSION.SDK_INT >= API_5;
    }
    
    /**
     * 检查是否为 Android 6.0 (API 23) 及以上
     */
    public static boolean isMarshmallowOrAbove() {
        return Build.VERSION.SDK_INT >= API_6;
    }
    
    /**
     * 检查是否为 Android 7.0 (API 24) 及以上
     */
    public static boolean isAndroid7OrAbove() {
        return Build.VERSION.SDK_INT >= API_7;
    }
    
    /**
     * 检查是否为 Android 8.0 (API 26) 及以上
     * Android 8.0+ 需要创建通知渠道
     */
    public static boolean isOreoOrAbove() {
        return Build.VERSION.SDK_INT >= API_8;
    }
    
    /**
     * 检查是否为 Android 10 (API 29) 及以上
     */
    public static boolean isAndroid10OrAbove() {
        return Build.VERSION.SDK_INT >= API_10;
    }
    
    /**
     * 检查是否为 Android 11 (API 30) 及以上
     * Android 11+ 支持无线配对码功能
     */
    public static boolean isAndroid11OrAbove() {
        return Build.VERSION.SDK_INT >= API_11;
    }
    
    /**
     * 检查是否为 Android 12 (API 31) 及以上
     */
    public static boolean isAndroid12OrAbove() {
        return Build.VERSION.SDK_INT >= API_12;
    }
    
    /**
     * 检查是否为 Android 13 (API 33) 及以上
     */
    public static boolean isAndroid13OrAbove() {
        return Build.VERSION.SDK_INT >= API_13;
    }
    
    /**
     * 检查是否为 Android 14 (API 34) 及以上
     */
    public static boolean isAndroid14OrAbove() {
        return Build.VERSION.SDK_INT >= API_14;
    }
    
    /**
     * 检查是否为 Android 15 (API 35) 及以上
     */
    public static boolean isAndroid15OrAbove() {
        return Build.VERSION.SDK_INT >= API_15;
    }
    
    // ==================== 功能判断方法 ====================
    
    /**
     * 检查是否需要配对
     * Android 11+ 首次连接需要配对码
     */
    public static boolean requiresPairing() {
        return Build.VERSION.SDK_INT >= API_11;
    }
    
    /**
     * 检查是否支持 RemoteInput API
     * Android 7.0+ 支持
     */
    public static boolean supportsRemoteInput() {
        return Build.VERSION.SDK_INT >= API_7;
    }
    
    /**
     * 检查是否需要创建通知渠道
     * Android 8.0+ 需要
     */
    public static boolean requiresNotificationChannel() {
        return Build.VERSION.SDK_INT >= API_8;
    }
    
    /**
     * 检查是否支持 TLS 连接 (STLS)
     * Android 11+ 支持
     */
    public static boolean supportsTlsConnection() {
        return Build.VERSION.SDK_INT >= API_11;
    }
    
    /**
     * 检查是否需要运行时权限检查
     * Android 6.0+ 需要
     */
    public static boolean requiresRuntimePermissions() {
        return Build.VERSION.SDK_INT >= API_6;
    }
    
    // ==================== 通知渠道管理 ====================
    
    /**
     * 创建 ADB 相关通知渠道
     * Android 8.0+ 系统要求
     */
    public static void createNotificationChannels(Context context) {
        if (!requiresNotificationChannel()) {
            return;
        }
        
        NotificationManager manager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        
        // 配对服务通知渠道
        NotificationChannel pairChannel = new NotificationChannel(
            CHANNEL_ID_ADB_PAIR,
            "ADB 无线配对",
            NotificationManager.IMPORTANCE_HIGH
        );
        pairChannel.setDescription("用于 ADB 无线配对通知");
        pairChannel.enableVibration(true);
        pairChannel.setShowBadge(true);
        manager.createNotificationChannel(pairChannel);
        
        // ADB 状态通知渠道
        NotificationChannel statusChannel = new NotificationChannel(
            CHANNEL_ID_ADB_STATUS,
            "ADB 连接状态",
            NotificationManager.IMPORTANCE_LOW
        );
        statusChannel.setDescription("显示 ADB 连接状态");
        statusChannel.setShowBadge(false);
        manager.createNotificationChannel(statusChannel);
    }
    
    /**
     * 删除所有 ADB 通知渠道
     */
    public static void deleteNotificationChannels(Context context) {
        if (!requiresNotificationChannel()) {
            return;
        }
        
        NotificationManager manager = 
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.deleteNotificationChannel(CHANNEL_ID_ADB_PAIR);
            manager.deleteNotificationChannel(CHANNEL_ID_ADB_STATUS);
        }
    }
    
    // ==================== 超时配置 ====================
    
    /**
     * 获取连接超时时间（毫秒）
     */
    public static int getConnectionTimeout() {
        if (isAndroid13OrAbove()) {
            return 15000; // Android 13+ 需要更长的超时时间
        } else if (isAndroid11OrAbove()) {
            return 12000; // Android 11-12
        } else {
            return 10000; // Android 5-10
        }
    }
    
    /**
     * 获取配对超时时间（毫秒）
     */
    public static int getPairingTimeout() {
        if (isAndroid11OrAbove()) {
            return 20000; // 需要等待用户输入配对码，增加超时
        } else {
            return 10000;
        }
    }
    
    /**
     * 获取 ADB 协议版本
     */
    public static int getProtocolVersion() {
        if (isAndroid14OrAbove()) {
            return 3; // 最新协议版本
        } else if (isAndroid11OrAbove()) {
            return 2;
        } else {
            return 1;
        }
    }
    
    /**
     * 获取最大数据 payload 大小
     */
    public static int getMaxPayload() {
        if (isAndroid14OrAbove()) {
            return 0x100000; // 1MB for protocol v3
        } else if (isAndroid11OrAbove()) {
            return 0x40000; // 256KB for protocol v2
        } else {
            return 0x1000; // 4KB for protocol v1
        }
    }
    
    // ==================== 默认端口配置 ====================
    
    /**
     * 获取默认 ADB 端口
     */
    public static int getDefaultAdbPort() {
        return 5555;
    }
    
    /**
     * 获取默认 ADB Server 端口
     */
    public static int getDefaultAdbServerPort() {
        return 5037;
    }
    
    /**
     * 获取默认配对端口
     * Android 11+ 通常与 ADB 端口相同
     */
    public static int getDefaultPairingPort() {
        return 5555;
    }
}
