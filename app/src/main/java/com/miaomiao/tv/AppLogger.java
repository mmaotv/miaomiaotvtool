package com.miaomiao.tv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用日志记录器 v1.0
 * 功能：
 * 1. 记录工作日志（Info/Debug/Warn/Error）
 * 2. 自动捕获并记录崩溃日志
 * 3. 导出日志到存储根目录
 * 4. 查看/清除日志
 */
public class AppLogger {
    private static final String TAG = "AppLogger";
    private static final String LOG_DIR = "SimpleMiaoMiaoTV_Logs";
    private static final String APP_LOG_FILE = "app.log";
    private static final String CRASH_LOG_FILE = "crash.log";
    private static final int MAX_LOG_LINES = 3000;

    private static AppLogger instance;
    private Context appContext;
    private File logDir;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private SimpleDateFormat fileNameFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());

    public static final int LEVEL_VERBOSE = 0;
    public static final int LEVEL_DEBUG = 1;
    public static final int LEVEL_INFO = 2;
    public static final int LEVEL_WARN = 3;
    public static final int LEVEL_ERROR = 4;

    private AppLogger() {}

    public static synchronized AppLogger get() {
        if (instance == null) {
            instance = new AppLogger();
        }
        return instance;
    }

    /**
     * 初始化日志系统（应在 Application 或 MainActivity.onCreate 中调用）
     */
    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        createLogDirectory();
        Log.i(TAG, "AppLogger 初始化成功");
        Log.i(TAG, "设备：" + Build.MANUFACTURER + " " + Build.MODEL);
        Log.i(TAG, "Android：" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        i(TAG, "应用启动");
    }

    private void createLogDirectory() {
        if (appContext != null) {
            File appFilesDir = appContext.getExternalFilesDir(null);
            if (appFilesDir != null) {
                logDir = new File(appFilesDir, LOG_DIR);
            }
        }
        if (logDir == null || !logDir.exists()) {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            logDir = new File(downloadDir, LOG_DIR);
        }
        if (logDir != null && !logDir.exists()) {
            logDir.mkdirs();
        }
        Log.d(TAG, "日志目录：" + (logDir != null ? logDir.getAbsolutePath() : "null"));
    }

    // ==================== 日志写入 ====================

    public void i(String tag, String message) { writeLog(LEVEL_INFO, tag, message); }
    public void d(String tag, String message) { writeLog(LEVEL_DEBUG, tag, message); }
    public void w(String tag, String message) { writeLog(LEVEL_WARN, tag, message); }
    public void e(String tag, String message) { writeLog(LEVEL_ERROR, tag, message); }
    public void e(String tag, String message, Throwable t) {
        writeLog(LEVEL_ERROR, tag, message + "\n" + getStackTrace(t));
    }
    public void v(String tag, String message) { writeLog(LEVEL_VERBOSE, tag, message); }

    private void writeLog(int level, String tag, String message) {
        if (appContext == null) return;

        String levelStr = getLevelString(level);
        String timestamp = dateFormat.format(new Date());
        String logLine = String.format("[%s] [%s] [%s] %s", timestamp, levelStr, tag, message);

        switch (level) {
            case LEVEL_VERBOSE: Log.v(tag, message); break;
            case LEVEL_DEBUG:   Log.d(tag, message); break;
            case LEVEL_INFO:   Log.i(tag, message); break;
            case LEVEL_WARN:   Log.w(tag, message); break;
            case LEVEL_ERROR:  Log.e(tag, message); break;
        }

        writeToFile(logLine, APP_LOG_FILE);
    }

    // ==================== 崩溃日志 ====================

    public void logCrash(Thread thread, Throwable throwable) {
        String timestamp = dateFormat.format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("=== 崩溃报告 ===\n");
        sb.append("时间：").append(timestamp).append("\n");
        sb.append("线程：").append(thread.getName()).append("\n");
        sb.append("异常：").append(throwable.getClass().getName()).append("\n");
        sb.append("信息：").append(throwable.getMessage()).append("\n");
        sb.append("\n--- 堆栈跟踪 ---\n");
        sb.append(getStackTrace(throwable));
        sb.append("\n--- 设备信息 ---\n");
        sb.append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Android：").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("================\n\n");

        writeToFile(sb.toString(), CRASH_LOG_FILE);
        Log.e(TAG, "崩溃已记录：" + throwable.getClass().getSimpleName());
    }

    private String getStackTrace(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : t.getStackTrace()) {
            sb.append("  at ").append(e.toString()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 文件操作 ====================

    private synchronized void writeToFile(String content, String fileName) {
        if (logDir == null) return;
        try {
            File file = new File(logDir, fileName);
            FileWriter writer = new FileWriter(file, true);
            writer.write(content + "\n");
            writer.flush();
            writer.close();
            trimLogFile(file);
        } catch (IOException e) {
            Log.e(TAG, "写入日志失败：" + e.getMessage());
        }
    }

    private void trimLogFile(File file) {
        if (!file.exists() || file.length() < 512 * 1024) return; // 小于512KB不截断
        try {
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file)));
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            reader.close();
            if (lines.size() > MAX_LOG_LINES) {
                FileWriter writer = new FileWriter(file, false);
                int start = lines.size() - MAX_LOG_LINES;
                for (int i = start; i < lines.size(); i++) writer.write(lines.get(i) + "\n");
                writer.flush();
                writer.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "截断日志失败：" + e.getMessage());
        }
    }

    // ==================== 公共接口 ====================

    /** 获取日志文件路径 */
    public String getLogFilePath() {
        return logDir != null ? new File(logDir, APP_LOG_FILE).getAbsolutePath() : null;
    }

    /** 获取崩溃日志文件路径 */
    public String getCrashLogFilePath() {
        return logDir != null ? new File(logDir, CRASH_LOG_FILE).getAbsolutePath() : null;
    }

    /** 获取日志目录路径 */
    public String getLogDirPath() {
        return logDir != null ? logDir.getAbsolutePath() : null;
    }

    /** 获取应用日志内容 */
    public String getLogContent() {
        if (logDir == null) return null;
        StringBuilder sb = new StringBuilder();
        File file = new File(logDir, APP_LOG_FILE);
        if (file.exists()) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file)));
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                r.close();
            } catch (IOException e) {
                Log.e(TAG, "读取日志失败：" + e.getMessage());
            }
        }
        return sb.toString();
    }

    /** 获取崩溃日志内容 */
    public String getCrashLogContent() {
        if (logDir == null) return null;
        StringBuilder sb = new StringBuilder();
        File file = new File(logDir, CRASH_LOG_FILE);
        if (file.exists()) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file)));
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                r.close();
            } catch (IOException e) {
                Log.e(TAG, "读取崩溃日志失败：" + e.getMessage());
            }
        }
        return sb.toString();
    }

    /** 导出日志（返回导出文件路径） */
    public String exportLogs() {
        if (logDir == null) return null;
        try {
            String timestamp = fileNameFormat.format(new Date());
            String exportFileName = "SimpleMiaoMiaoTV_log_" + timestamp + ".txt";
            File exportFile = new File(logDir, exportFileName);

            StringBuilder content = new StringBuilder();
            content.append("=== SimpleMiaoMiaoTV 日志导出 ===\n");
            content.append("导出时间：").append(dateFormat.format(new Date())).append("\n");
            content.append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            content.append("Android：").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            content.append("================================\n\n");

            content.append("=== 应用日志 ===\n\n");
            File appLog = new File(logDir, APP_LOG_FILE);
            if (appLog.exists()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(appLog)));
                String line;
                while ((line = r.readLine()) != null) content.append(line).append("\n");
                r.close();
            } else {
                content.append("(无日志)\n");
            }

            content.append("\n\n=== 崩溃日志 ===\n\n");
            File crashLog = new File(logDir, CRASH_LOG_FILE);
            if (crashLog.exists()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(crashLog)));
                String line;
                while ((line = r.readLine()) != null) content.append(line).append("\n");
                r.close();
            } else {
                content.append("(无崩溃日志)\n");
            }

            FileWriter writer = new FileWriter(exportFile);
            writer.write(content.toString());
            writer.flush();
            writer.close();

            Log.i(TAG, "日志已导出：" + exportFile.getAbsolutePath());
            return exportFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "导出日志失败：" + e.getMessage());
            return null;
        }
    }

    /** 清除所有日志 */
    public void clearLogs() {
        if (logDir == null) return;
        File appLog = new File(logDir, APP_LOG_FILE);
        File crashLog = new File(logDir, CRASH_LOG_FILE);
        if (appLog.exists()) appLog.delete();
        if (crashLog.exists()) crashLog.delete();
        i(TAG, "日志已清除");
    }

    private String getLevelString(int level) {
        switch (level) {
            case LEVEL_VERBOSE: return "V";
            case LEVEL_DEBUG:   return "D";
            case LEVEL_INFO:   return "I";
            case LEVEL_WARN:   return "W";
            case LEVEL_ERROR:  return "E";
            default:           return "?";
        }
    }

    /**
     * 安装全局异常处理器（崩溃自动记录）
     * 应在 Application.onCreate 中调用
     */
    public void installGlobalExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "捕获未处理异常！");
            logCrash(thread, throwable);
            if (appContext != null) {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
        });
    }
}
