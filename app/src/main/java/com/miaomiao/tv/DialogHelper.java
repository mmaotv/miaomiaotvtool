package com.miaomiao.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 统一风格的圆角弹窗工具类
 * 适配 Android 6-16
 */
public class DialogHelper {

    /**
     * 显示提示弹窗
     * @param activity 上下文
     * @param icon 图标emoji，如 "📍"
     * @param title 标题
     * @param message 内容
     * @param positiveText 确认按钮文字
     * @param onConfirm 点击确认的回调
     */
    public static void show(Activity activity, String icon, String title, String message,
                           String positiveText, Runnable onConfirm) {
        show(activity, icon, title, message, positiveText, null, onConfirm, null);
    }

    /**
     * 显示带取消按钮的弹窗
     */
    public static void show(Activity activity, String icon, String title, String message,
                           String positiveText, String negativeText,
                           Runnable onConfirm, Runnable onCancel) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_custom, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);

        tvIcon.setText(icon);
        tvTitle.setText(title);
        tvMessage.setText(message);
        tvPositive.setText(positiveText);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.DialogRoundedStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();

        // 设置对话框背景透明+圆角
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        // 确认按钮
        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });

        // 取消按钮
        if (negativeText != null && !negativeText.isEmpty()) {
            btnNegative.setVisibility(View.VISIBLE);
            tvNegative.setText(negativeText);
            btnNegative.setOnClickListener(v -> {
                dialog.dismiss();
                if (onCancel != null) onCancel.run();
            });
        }

        dialog.show();
    }

    /**
     * 显示关于弹窗（使用本软件WebView打开GitHub）
     * @param activity 上下文
     * @param onOpenGithub 点击打开GitHub的回调（会在本软件WebView中打开）
     */
    public static void showAbout(Activity activity, Runnable onOpenGithub) {
        String appName = activity.getString(R.string.app_name);
        String aboutTitle = activity.getString(R.string.about_title);
        String aboutMessage = activity.getString(R.string.about_message);
        String aboutContact = activity.getString(R.string.about_contact);
        String aboutGithub = activity.getString(R.string.about_github);

        StringBuilder message = new StringBuilder();
        message.append("📺 ").append(appName).append("\n\n");
        if (aboutMessage != null && !aboutMessage.isEmpty()) {
            message.append(aboutMessage).append("\n\n");
        }
        message.append("📧 邮箱：").append(aboutContact != null ? aboutContact : "").append("\n");
        message.append("🔗 GitHub：\n").append(aboutGithub != null ? aboutGithub : "");

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_custom, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);

        tvIcon.setText("ℹ️");
        tvTitle.setText(aboutTitle != null ? aboutTitle : "关于");
        tvMessage.setText(message.toString());
        tvPositive.setText("打开GitHub");
        tvNegative.setText("知道了");

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.DialogRoundedStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();

        // 设置对话框背景透明+圆角
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.CENTER);
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        // 打开GitHub按钮 - 调用回调在本软件打开
        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onOpenGithub != null) {
                onOpenGithub.run();
            }
        });

        // 知道按钮
        btnNegative.setVisibility(View.VISIBLE);
        btnNegative.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }
}