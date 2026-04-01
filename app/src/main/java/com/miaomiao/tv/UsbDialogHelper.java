package com.miaomiao.tv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Environment;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * USB 插入弹窗提示工具
 * 当检测到 U 盘插入时弹出对话框，询问用户是否打开 U 盘
 */
public class UsbDialogHelper {

    /** 显示 U 盘插入提示对话框 */
    public static void showUsbMountedDialog(Context context) {
        File usbRoot = findUsbDrive(context);
        if (usbRoot == null) return;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(40, 30, 40, 20);

        TextView tvIcon = new TextView(context);
        tvIcon.setText("\uD83D\uDCBB");
        tvIcon.setTextSize(56);
        tvIcon.setGravity(Gravity.CENTER);
        layout.addView(tvIcon);

        TextView tvTitle = new TextView(context);
        tvTitle.setText("U \u76D8\u5DF2\u63D2\u5165");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 20, 0, 10);
        layout.addView(tvTitle);

        TextView tvPath = new TextView(context);
        tvPath.setText("\u8BBE\u5907\u8DEF\u5F84\uFF1A" + usbRoot.getAbsolutePath());
        tvPath.setTextSize(13);
        tvPath.setTextColor(Color.parseColor("#88AABBCC"));
        tvPath.setGravity(Gravity.CENTER);
        tvPath.setPadding(0, 0, 0, 20);
        layout.addView(tvPath);

        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle("")
            .setView(layout)
            .setPositiveButton("\u6253\u5F00 U \u76D8", (d, w) -> {
                Intent intent = new Intent(context, UsbManagerActivity.class);
                context.startActivity(intent);
            })
            .setNegativeButton("\u5FFD\u7565", null)
            .create();

        // 让 Dialog 在屏幕中间弹出（TV 场景）
        if (dialog.getWindow() != null) {
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
        dialog.show();
    }

    /**
     * 查找 U 盘挂载路径（逻辑与 UsbManagerActivity 保持一致）
     */
    private static File findUsbDrive(Context context) {
        File storageRoot = new File("/storage");
        if (!storageRoot.exists()) return null;

        File[] mounts = storageRoot.listFiles();
        if (mounts == null) return null;

        String internalPath = Environment.getExternalStorageDirectory().getAbsolutePath();

        for (File mount : mounts) {
            if (!mount.isDirectory() || !mount.canRead()) continue;
            String path = mount.getAbsolutePath();
            if (path.equals(internalPath)) continue;
            if (path.contains("emulated")) continue;
            if (mount.getName().equals("self")) continue;

            String name = mount.getName().toLowerCase();
            if (name.contains("sd") || name.contains("usb") || name.contains("external")) {
                File[] test = mount.listFiles();
                if (test != null || mount.canRead()) return mount;
            }
        }

        // fallback: 第一个非内部存储
        for (File mount : mounts) {
            if (!mount.isDirectory() || !mount.canRead()) continue;
            String path = mount.getAbsolutePath();
            if (path.equals(internalPath) || path.contains("emulated") || mount.getName().equals("self")) continue;
            File[] test = mount.listFiles();
            if (test != null || mount.canRead()) return mount;
        }

        return null;
    }
}
