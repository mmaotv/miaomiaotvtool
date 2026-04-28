package com.miaomiao.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import android.view.View;
import android.widget.LinearLayout;

import java.util.regex.Pattern;

/**
 * 统一风格的圆角弹窗工具类 v2.0
 * - 底部取消/确认按钮，水平居中等间距
 * - 柔和半透明遮罩背景
 * - 点击遮罩/返回键关闭弹窗
 * - 按钮柔和外发光反馈
 * - 输入框自动聚焦唤起键盘
 * - IP格式校验、重命名非法字符过滤
 * - 统一对齐首页大按钮视觉风格
 */
public class DialogHelper {

    // IP地址正则
    private static final Pattern IP_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");

    // 重命名非法字符（Windows/ADB兼容）
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    public static void showBackendGuide(Activity activity, BackendState state,
                                        Runnable onPrimary, Runnable onSecondary) {
        String title = state.usesShizuku ? "Shizuku 状态" : "ADB 状态";
        String icon = state.usesShizuku ? "🧩" : "🔌";
        String positive = state.status == BackendStatus.SHIZUKU_PERMISSION_REQUIRED ? "去授权" : "去连接";
        String negative = "取消";
        show(activity, icon, title,
            state.summary + "\n\n" + state.detail,
            positive, negative, onPrimary, onSecondary);
    }

    public static String buildBackendStatusText(BackendState state) {
        if (state == null) return "未检测到可用后端";
        String prefix = state.usesShizuku ? "Shizuku" : "ADB";
        return prefix + "：" + state.summary;
    }

    /**
     * =============================================
     * 标准确认弹窗（纯文本消息）
     * =============================================
     */
    public static void showConfirm(Activity activity, String icon, String title, String message,
                                   Runnable onConfirm) {
        showConfirm(activity, icon, title, message, "取消", "确认", null, onConfirm);
    }

    public static void showConfirm(Activity activity, String icon, String title, String message,
                                   String positiveText, Runnable onConfirm) {
        showConfirm(activity, icon, title, message, "取消", positiveText, null, onConfirm);
    }

    public static void showConfirm(Activity activity, String icon, String title, String message,
                                   String negativeText, String positiveText, Runnable onConfirm) {
        showConfirm(activity, icon, title, message, negativeText, positiveText, null, onConfirm);
    }

    /**
     * =============================================
     * 旧版兼容方法（6参数show）
     * =============================================
     */
    public static void show(Activity activity, String icon, String title, String message,
                           String positiveText, String negativeText,
                           Runnable onConfirm, Runnable onCancel) {
        showConfirm(activity, icon, title, message, negativeText, positiveText, onCancel, onConfirm);
    }

    /** 旧版4参数show */
    public static void show(Activity activity, String icon, String title, String message,
                           Runnable onConfirm) {
        showConfirm(activity, icon, title, message, onConfirm);
    }

    /** 旧版5参数show (positiveText, null callback) */
    public static void show(Activity activity, String icon, String title, String message,
                           String positiveText, Runnable onConfirm) {
        showConfirm(activity, icon, title, message, "取消", positiveText, onConfirm);
    }

    /** 旧版6参数show (icon, title, msg, pos, neg, onConfirm) */
    public static void show(Activity activity, String icon, String title, String message,
                           String positiveText, String negativeText, Runnable onConfirm) {
        showConfirm(activity, icon, title, message, negativeText, positiveText, null, onConfirm);
    }

    public static void showConfirm(Activity activity, String icon, String title, String message,
                                   String negativeText, String positiveText,
                                   Runnable onCancel, Runnable onConfirm) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_standard, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout dialogInputContainer = dialogView.findViewById(R.id.dialogInputContainer);
        LinearLayout dialogContentContainer = dialogView.findViewById(R.id.dialogContentContainer);

        // 隐藏输入容器，显示消息
        dialogInputContainer.setVisibility(View.GONE);
        dialogContentContainer.setVisibility(View.VISIBLE);
        tvMessage.setVisibility(View.VISIBLE);

        tvIcon.setText(icon != null ? icon : "📍");
        tvTitle.setText(title);
        tvMessage.setText(message);
        tvPositive.setText(positiveText != null ? positiveText : "确认");

        AlertDialog dialog = createDialog(activity, dialogView);

        // 设置取消按钮
        if (negativeText != null && !negativeText.isEmpty()) {
            btnNegative.setVisibility(View.VISIBLE);
            tvNegative.setText(negativeText);
            btnNegative.setOnClickListener(v -> {
                dialog.dismiss();
                if (onCancel != null) onCancel.run();
            });
        }

        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        dialog.show();
    }

    /**
     * =============================================
     * 危险操作确认弹窗（红色警告）
     * =============================================
     */
    public static void showDangerConfirm(Activity activity, String title, String message,
                                         Runnable onConfirm) {
        showDangerConfirm(activity, "⚠️", title, message, onConfirm);
    }

    public static void showDangerConfirm(Activity activity, String icon, String title,
                                         String message, Runnable onConfirm) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_standard, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout dialogInputContainer = dialogView.findViewById(R.id.dialogInputContainer);
        LinearLayout dialogContentContainer = dialogView.findViewById(R.id.dialogContentContainer);

        dialogInputContainer.setVisibility(View.GONE);
        dialogContentContainer.setVisibility(View.VISIBLE);
        tvMessage.setVisibility(View.VISIBLE);

        tvIcon.setText(icon != null ? icon : "⚠️");
        tvTitle.setText(title);
        tvMessage.setText(message);
        tvPositive.setText("确认删除");

        // 危险按钮改为红色
        btnPositive.setBackgroundResource(R.drawable.dialog_danger_confirm_bg);

        AlertDialog dialog = createDialog(activity, dialogView);
        btnPositive.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) onConfirm.run();
        });
        btnNegative.setVisibility(View.VISIBLE);
        tvNegative.setText("取消");
        btnNegative.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * =============================================
     * 单输入弹窗（通用）
     * =============================================
     */
    public static void showInput(Activity activity, String title, String hint,
                                 DialogCallback callback) {
        showInput(activity, null, title, hint, null, "取消", "确认", false, null, callback);
    }

    public static void showInput(Activity activity, String icon, String title, String hint,
                                 String defaultValue, String negativeText, String positiveText,
                                 boolean autoFocus, String inputType, DialogCallback callback) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_standard, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout dialogInputContainer = dialogView.findViewById(R.id.dialogInputContainer);
        LinearLayout dialogContentContainer = dialogView.findViewById(R.id.dialogContentContainer);
        EditText dialogInput1 = dialogView.findViewById(R.id.dialogInput1);
        EditText dialogInput2 = dialogView.findViewById(R.id.dialogInput2);

        // 隐藏消息容器，显示输入框
        dialogContentContainer.setVisibility(View.GONE);
        dialogInputContainer.setVisibility(View.VISIBLE);
        tvMessage.setVisibility(View.GONE);
        dialogInput1.setVisibility(View.VISIBLE);
        dialogInput2.setVisibility(View.GONE);

        tvIcon.setText(icon != null ? icon : "📝");
        tvTitle.setText(title);
        tvPositive.setText(positiveText != null ? positiveText : "确认");
        tvNegative.setText(negativeText != null ? negativeText : "取消");
        btnNegative.setVisibility(View.VISIBLE);

        // 设置hint和默认值
        if (hint != null) dialogInput1.setHint(hint);
        if (defaultValue != null) dialogInput1.setText(defaultValue);

        // 设置输入类型
        if (inputType != null) {
            switch (inputType) {
                case "number":
                    dialogInput1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                    break;
                case "textUri":
                    dialogInput1.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
                    break;
                case "ip":
                    dialogInput1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    break;
            }
        }

        // 自动聚焦并唤起键盘
        if (autoFocus || defaultValue == null || defaultValue.isEmpty()) {
            dialogInput1.requestFocus();
            dialogInput1.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(dialogInput1, InputMethodManager.SHOW_IMPLICIT);
            }, 200);
        }

        AlertDialog dialog = createDialog(activity, dialogView);
        btnPositive.setOnClickListener(v -> {
            String input = dialogInput1.getText().toString().trim();
            dialog.dismiss();
            if (callback != null) callback.onConfirm(input);
        });
        btnNegative.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onCancel();
        });
        dialog.show();
    }

    /**
     * =============================================
     * IP输入弹窗（带格式校验）
     * =============================================
     */
    public static void showIpInput(Activity activity, String title, String defaultIp, String defaultPort,
                                   DialogIpCallback callback) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_standard, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout dialogInputContainer = dialogView.findViewById(R.id.dialogInputContainer);
        LinearLayout dialogContentContainer = dialogView.findViewById(R.id.dialogContentContainer);
        EditText dialogInput1 = dialogView.findViewById(R.id.dialogInput1);
        EditText dialogInput2 = dialogView.findViewById(R.id.dialogInput2);

        // 隐藏消息容器，显示双输入框
        dialogContentContainer.setVisibility(View.GONE);
        dialogInputContainer.setVisibility(View.VISIBLE);
        tvMessage.setVisibility(View.GONE);
        dialogInput1.setVisibility(View.VISIBLE);
        dialogInput2.setVisibility(View.VISIBLE);

        tvIcon.setText("🔗");
        tvTitle.setText(title != null ? title : "输入设备IP地址和端口进行连接：");
        tvPositive.setText("连接");
        tvNegative.setText("取消");
        btnNegative.setVisibility(View.VISIBLE);

        // IP输入框
        dialogInput1.setHint("IP 地址，如 192.168.1.100");
        dialogInput1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (defaultIp != null) dialogInput1.setText(defaultIp);

        // 端口输入框
        dialogInput2.setHint("端口（默认 5555）");
        dialogInput2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (defaultPort != null) dialogInput2.setText(defaultPort);
        else dialogInput2.setText("5555");

        // 移除多余字符过滤器（IP只需要数字和点）
        dialogInput1.setFilters(new InputFilter[]{new InputFilter() {
            @Override
            public CharSequence filter(CharSequence source, int start, int end, android.text.Spanned dest, int dstart, int dend) {
                // 只允许数字和点
                StringBuilder filtered = new StringBuilder();
                for (int i = start; i < end; i++) {
                    char c = source.charAt(i);
                    if (Character.isDigit(c) || c == '.') {
                        filtered.append(c);
                    }
                }
                return filtered.toString();
            }
        }});

        AlertDialog dialog = createDialog(activity, dialogView);

        // IP格式校验
        TextWatcher ipWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                String ip = s.toString().trim();
                if (!ip.isEmpty() && !IP_PATTERN.matcher(ip).matches()) {
                    dialogInput1.setTextColor(ContextCompat.getColor(activity, R.color.atv_red)); // 红色警告
                } else {
                    dialogInput1.setTextColor(ContextCompat.getColor(activity, R.color.atv_text_primary)); // 恢复正常
                }
            }
        };
        dialogInput1.addTextChangedListener(ipWatcher);

        btnPositive.setOnClickListener(v -> {
            String ip = dialogInput1.getText().toString().trim();
            String portStr = dialogInput2.getText().toString().trim();
            int port = 5555;
            try { port = Integer.parseInt(portStr); } catch (Exception e) {}

            // 校验IP格式
            if (ip.isEmpty() || !IP_PATTERN.matcher(ip).matches()) {
                Toast.makeText(activity, "⚠️ 请输入正确的IP地址格式", Toast.LENGTH_SHORT).show();
                return;
            }

            dialog.dismiss();
            if (callback != null) callback.onConfirm(ip, port);
        });

        btnNegative.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onCancel();
        });

        // 自动聚焦IP输入框
        dialogInput1.requestFocus();
        dialogInput1.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(dialogInput1, InputMethodManager.SHOW_IMPLICIT);
        }, 200);

        dialog.show();
    }

    /**
     * =============================================
     * 重命名弹窗（过滤非法字符）
     * =============================================
     */
    public static void showRenameInput(Activity activity, String currentName, DialogCallback callback) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_standard, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout dialogInputContainer = dialogView.findViewById(R.id.dialogInputContainer);
        LinearLayout dialogContentContainer = dialogView.findViewById(R.id.dialogContentContainer);
        EditText dialogInput1 = dialogView.findViewById(R.id.dialogInput1);
        EditText dialogInput2 = dialogView.findViewById(R.id.dialogInput2);

        dialogContentContainer.setVisibility(View.GONE);
        dialogInputContainer.setVisibility(View.VISIBLE);
        tvMessage.setVisibility(View.GONE);
        dialogInput1.setVisibility(View.VISIBLE);
        dialogInput2.setVisibility(View.GONE);

        tvIcon.setText("✏️");
        tvTitle.setText("重命名");
        tvPositive.setText("确认");
        tvNegative.setText("取消");
        btnNegative.setVisibility(View.VISIBLE);

        if (currentName != null) {
            // 分离文件名和扩展名
            int lastDot = currentName.lastIndexOf('.');
            String name = lastDot > 0 ? currentName.substring(0, lastDot) : currentName;
            dialogInput1.setText(name);
            dialogInput1.setSelection(name.length());
        }

        // 过滤非法字符
        dialogInput1.setFilters(new InputFilter[]{new InputFilter() {
            @Override
            public CharSequence filter(CharSequence source, int start, int end, android.text.Spanned dest, int dstart, int dend) {
                String filtered = ILLEGAL_CHARS.matcher(source.subSequence(start, end)).replaceAll("");
                return filtered;
            }
        }});

        AlertDialog dialog = createDialog(activity, dialogView);
        btnPositive.setOnClickListener(v -> {
            String name = dialogInput1.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(activity, "⚠️ 文件名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.length() > 255) {
                Toast.makeText(activity, "⚠️ 文件名过长", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            if (callback != null) callback.onConfirm(name);
        });
        btnNegative.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onCancel();
        });

        dialogInput1.requestFocus();
        dialogInput1.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(dialogInput1, InputMethodManager.SHOW_IMPLICIT);
        }, 200);

        dialog.show();
    }

    /**
     * =============================================
     * 关于弹窗
     * =============================================
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

        showConfirm(activity, "ℹ️", aboutTitle != null ? aboutTitle : "关于",
                message.toString(), "知道了", "打开GitHub",
                null, onOpenGithub);
    }

    // ==================== ADB 连接对话框 ====================

    /**
     * ADB 连接弹窗（IP + 端口 + 配对码三输入框）
     */
    public static void showAdbConnect(Activity activity, String defaultIp, int defaultPort,
                                      AdbConnectCallback callback) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_standard, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        TextView tvMessage = dialogView.findViewById(R.id.dialogMessage);
        TextView tvPositive = dialogView.findViewById(R.id.tvPositive);
        TextView tvNegative = dialogView.findViewById(R.id.tvNegative);
        LinearLayout btnNegative = dialogView.findViewById(R.id.btnNegative);
        LinearLayout btnPositive = dialogView.findViewById(R.id.btnPositive);
        LinearLayout dialogInputContainer = dialogView.findViewById(R.id.dialogInputContainer);
        LinearLayout dialogContentContainer = dialogView.findViewById(R.id.dialogContentContainer);
        EditText dialogInput1 = dialogView.findViewById(R.id.dialogInput1);
        EditText dialogInput2 = dialogView.findViewById(R.id.dialogInput2);

        // 隐藏消息容器，显示三个输入框
        dialogContentContainer.setVisibility(View.GONE);
        dialogInputContainer.setVisibility(View.VISIBLE);
        tvMessage.setVisibility(View.GONE);
        dialogInput1.setVisibility(View.VISIBLE);
        dialogInput2.setVisibility(View.VISIBLE);

        // 添加第三输入框（配对码）标签
        TextView label3 = new TextView(activity);
        label3.setText("\u914d\u5bf9\u7801\uff1a");
        label3.setTextSize(13f);
        label3.setTextColor(0xFF7F8C8D);
        LinearLayout.LayoutParams lpLabel3 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpLabel3.setMargins(0, (int)(8 * activity.getResources().getDisplayMetrics().density), 0, (int)(4 * activity.getResources().getDisplayMetrics().density));
        label3.setLayoutParams(lpLabel3);
        dialogInputContainer.addView(label3);

        EditText dialogInput3 = new EditText(activity);
        dialogInput3.setVisibility(View.VISIBLE);
        dialogInput3.setHint("6\u4f4d\u914d\u5bf9\u7801\uff08\u5982 123456\uff09");
        dialogInput3.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dialogInput3.setTextSize(16f);
        dialogInput3.setTextColor(0xFF1D1D1F);
        dialogInput3.setHintTextColor(0xFF95A5A6);
        dialogInput3.setBackgroundResource(R.drawable.dialog_standard_edit_bg);
        int padPx = (int)(14 * activity.getResources().getDisplayMetrics().density);
        dialogInput3.setPadding(padPx, padPx, padPx, padPx);
        dialogInput3.setMinimumHeight((int)(52 * activity.getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp3.setMargins(0, 0, 0, (int)(8 * activity.getResources().getDisplayMetrics().density));
        dialogInput3.setLayoutParams(lp3);
        dialogInputContainer.addView(dialogInput3);

        tvIcon.setText("🔗");
        tvTitle.setText("ADB 连接");
        tvPositive.setText("连接");
        tvNegative.setText("取消");
        btnNegative.setVisibility(View.VISIBLE);

        // IP 输入
        dialogInput1.setHint("IP 地址，如 192.168.1.100");
        dialogInput1.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (defaultIp != null) dialogInput1.setText(defaultIp);

        // 端口输入
        dialogInput2.setHint("端口（默认 5555）");
        dialogInput2.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dialogInput2.setText(defaultPort > 0 ? String.valueOf(defaultPort) : "5555");

        // IP 过滤
        dialogInput1.setFilters(new InputFilter[]{new InputFilter() {
            @Override
            public CharSequence filter(CharSequence source, int start, int end, android.text.Spanned dest, int dstart, int dend) {
                StringBuilder filtered = new StringBuilder();
                for (int i = start; i < end; i++) {
                    char c = source.charAt(i);
                    if (Character.isDigit(c) || c == '.') filtered.append(c);
                }
                return filtered.toString();
            }
        }});

        // IP 格式校验变色
        TextWatcher ipWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String ip = s.toString().trim();
                dialogInput1.setTextColor(
                    !ip.isEmpty() && !IP_PATTERN.matcher(ip).matches()
                    ? ContextCompat.getColor(activity, R.color.atv_red) : ContextCompat.getColor(activity, R.color.atv_text_primary)
                );
            }
        };
        dialogInput1.addTextChangedListener(ipWatcher);

        AlertDialog dialog = createDialog(activity, dialogView);

        btnPositive.setOnClickListener(v -> {
            String ip = dialogInput1.getText().toString().trim();
            String portStr = dialogInput2.getText().toString().trim();
            String code = dialogInput3.getText().toString().trim();
            int port = 5555;
            try { port = Integer.parseInt(portStr.isEmpty() ? "5555" : portStr); } catch (Exception ignored) {}

            if (ip.isEmpty() || !IP_PATTERN.matcher(ip).matches()) {
                Toast.makeText(activity, "⚠️ 请输入正确的IP地址", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            if (callback != null) callback.onConfirm(ip, port, code);
        });

        btnNegative.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) callback.onCancel();
        });

        dialogInput1.requestFocus();
        dialogInput1.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(dialogInput1, InputMethodManager.SHOW_IMPLICIT);
        }, 200);

        dialog.show();
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建统一风格的对话框
     */
    private static AlertDialog createDialog(Activity activity, View dialogView) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.DialogStandardStyle);
        builder.setView(dialogView);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setGravity(Gravity.CENTER);

            // 统一弹窗宽度：最小600dp，最大屏幕80%
            int minWidth = (int) (600 * activity.getResources().getDisplayMetrics().density);
            int maxWidth = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.8);
            dialog.getWindow().setLayout(
                Math.max(minWidth, maxWidth),
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        return dialog;
    }

    // ==================== 列表弹窗（替代系统AlertDialog.Builder().setItems） ====================

    /**
     * 显示自定义列表弹窗（白底圆角，永不黑屏）
     * @param activity       上下文
     * @param icon           标题前图标，可为 null
     * @param title          弹窗标题
     * @param items          列表项文字数组
     * @param onItemClick    点击回调，参数为 item index
     * @param onCancel       取消回调，可为 null
     */
    public static void showList(Activity activity, String icon, String title,
                                String[] items, OnItemClickListener onItemClick,
                                Runnable onCancel) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_list, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        LinearLayout listContainer = dialogView.findViewById(R.id.listItemsContainer);

        tvIcon.setText(icon != null ? icon : "📋");
        tvTitle.setText(title);

        AlertDialog dialog = createDialog(activity, dialogView);

        // 动态创建列表项（两列布局）
        LayoutInflater inflater = LayoutInflater.from(activity);
        android.widget.LinearLayout rowLayout = null;
        int margin6 = (int) (6 * activity.getResources().getDisplayMetrics().density);

        for (int i = 0; i < items.length; i++) {
            final int index = i;

            // 每两个项创建一行
            if (i % 2 == 0) {
                rowLayout = new android.widget.LinearLayout(activity);
                rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                rowLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                listContainer.addView(rowLayout);
            }

            View itemView = inflater.inflate(R.layout.item_dialog_list, rowLayout, false);
            TextView tvItem = itemView.findViewById(R.id.itemText);
            tvItem.setText(items[i]);

            // 设置两列布局参数
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.0f
            );
            if (i % 2 == 0) {
                lp.setMargins(0, 0, margin6, margin6);
            } else {
                lp.setMargins(margin6, 0, 0, margin6);
            }
            itemView.setLayoutParams(lp);
            itemView.setMinimumHeight((int) (48 * activity.getResources().getDisplayMetrics().density));

            itemView.setOnClickListener(v -> {
                dialog.dismiss();
                if (onItemClick != null) onItemClick.onClick(index);
            });
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.setScaleX(hasFocus ? 1.02f : 1.0f);
                v.setScaleY(hasFocus ? 1.02f : 1.0f);
            });
            rowLayout.addView(itemView);
        }

        // 点击取消按钮关闭
        //（列表弹窗不需要固定取消按钮，改为点击外部或返回键取消）
        dialog.setOnCancelListener(d -> {
            if (onCancel != null) onCancel.run();
        });

        dialog.show();
    }

    /**
     * 简化的列表弹窗（无标题图标）
     */
    public static void showList(Activity activity, String title, String[] items,
                                OnItemClickListener onItemClick) {
        showList(activity, null, title, items, onItemClick, null);
    }

    /**
     * 带底部按钮的列表弹窗（文件操作菜单：打开/删除/复制... + 粘贴 + 取消）
     */
    public static void showListWithButtons(Activity activity, String title,
                                           String[] items, OnItemClickListener onItemClick,
                                           String positiveText, Runnable onPositive,
                                           String negativeText, Runnable onNegative) {
        // 先显示列表弹窗，顶部会有一个额外选项用于「粘贴到此处」
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_list, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        LinearLayout listContainer = dialogView.findViewById(R.id.listItemsContainer);

        tvIcon.setText("📁");
        tvTitle.setText(title);

        AlertDialog dialog = createDialog(activity, dialogView);

        // 两列布局容器
        LayoutInflater inflater = LayoutInflater.from(activity);
        android.widget.LinearLayout rowLayout = null;
        int margin6 = (int) (6 * activity.getResources().getDisplayMetrics().density);

        // 添加主列表项（两列布局）
        for (int i = 0; i < items.length; i++) {
            final int index = i;

            // 每两个项创建一行
            if (i % 2 == 0) {
                rowLayout = new android.widget.LinearLayout(activity);
                rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                rowLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                listContainer.addView(rowLayout);
            }

            View itemView = inflater.inflate(R.layout.item_dialog_list, rowLayout, false);
            TextView tvItem = itemView.findViewById(R.id.itemText);
            tvItem.setText(items[i]);

            // 设置两列布局参数
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.0f
            );
            if (i % 2 == 0) {
                lp.setMargins(0, 0, margin6, margin6);
            } else {
                lp.setMargins(margin6, 0, 0, margin6);
            }
            itemView.setLayoutParams(lp);
            itemView.setMinimumHeight((int) (48 * activity.getResources().getDisplayMetrics().density));

            itemView.setOnClickListener(v -> {
                dialog.dismiss();
                if (onItemClick != null) onItemClick.onClick(index);
            });
            rowLayout.addView(itemView);
        }

        // 分隔线
        View divider = new View(activity);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(0, margin6, 0, margin6);
        divider.setLayoutParams(divLp);
        divider.setBackgroundColor(ContextCompat.getColor(activity, R.color.atv_separator_light));
        listContainer.addView(divider);

        // 底部按钮行（两列）
        android.widget.LinearLayout bottomRow = new android.widget.LinearLayout(activity);
        bottomRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bottomRow.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        listContainer.addView(bottomRow);

        // 取消按钮
        if (negativeText != null && !negativeText.isEmpty()) {
            View cancelView = inflater.inflate(R.layout.item_dialog_list, bottomRow, false);
            TextView tvCancel = cancelView.findViewById(R.id.itemText);
            tvCancel.setText(negativeText);
            tvCancel.setTextColor(ContextCompat.getColor(activity, R.color.atv_text_secondary));
            android.widget.LinearLayout.LayoutParams cancelLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.0f
            );
            cancelLp.setMargins(0, 0, margin6, 0);
            cancelView.setLayoutParams(cancelLp);
            cancelView.setMinimumHeight((int) (48 * activity.getResources().getDisplayMetrics().density));
            cancelView.setOnClickListener(v -> {
                dialog.dismiss();
                if (onNegative != null) onNegative.run();
            });
            bottomRow.addView(cancelView);
        }

        // 粘贴按钮
        if (positiveText != null && !positiveText.isEmpty()) {
            View pasteView = inflater.inflate(R.layout.item_dialog_list, bottomRow, false);
            TextView tvPaste = pasteView.findViewById(R.id.itemText);
            tvPaste.setText(positiveText);
            android.widget.LinearLayout.LayoutParams pasteLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.0f
            );
            pasteLp.setMargins(negativeText != null ? margin6 : 0, 0, 0, 0);
            pasteView.setLayoutParams(pasteLp);
            pasteView.setMinimumHeight((int) (48 * activity.getResources().getDisplayMetrics().density));
            pasteView.setOnClickListener(v -> {
                dialog.dismiss();
                if (onPositive != null) onPositive.run();
            });
            bottomRow.addView(pasteView);
        }

        dialog.setOnCancelListener(d -> {
            if (onNegative != null) onNegative.run();
        });

        dialog.show();
    }

    /**
     * 显示自定义列表弹窗（支持取消回调）
     * @param activity       上下文
     * @param icon           标题前图标，可为 null
     * @param title          弹窗标题
     * @param items          列表项文字数组
     * @param onItemClick    点击回调（包含 onCancel）
     */
    public static void showList(Activity activity, String icon, String title,
                                String[] items, OnItemClickListenerWithCancel onItemClick) {
        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_list, null);

        TextView tvIcon = dialogView.findViewById(R.id.dialogIcon);
        TextView tvTitle = dialogView.findViewById(R.id.dialogTitle);
        LinearLayout listContainer = dialogView.findViewById(R.id.listItemsContainer);

        tvIcon.setText(icon != null ? icon : "📋");
        tvTitle.setText(title);

        AlertDialog dialog = createDialog(activity, dialogView);

        // 动态创建列表项（两列布局）
        LayoutInflater inflater = LayoutInflater.from(activity);
        android.widget.LinearLayout rowLayout = null;
        int margin6 = (int) (6 * activity.getResources().getDisplayMetrics().density);

        for (int i = 0; i < items.length; i++) {
            final int index = i;

            if (i % 2 == 0) {
                rowLayout = new android.widget.LinearLayout(activity);
                rowLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                rowLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ));
                listContainer.addView(rowLayout);
            }

            View itemView = inflater.inflate(R.layout.item_dialog_list, rowLayout, false);
            TextView tvItem = itemView.findViewById(R.id.itemText);
            tvItem.setText(items[i]);

            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1.0f
            );
            if (i % 2 == 0) {
                lp.setMargins(0, 0, margin6, margin6);
            } else {
                lp.setMargins(margin6, 0, 0, margin6);
            }
            itemView.setLayoutParams(lp);
            itemView.setMinimumHeight((int) (48 * activity.getResources().getDisplayMetrics().density));

            itemView.setOnClickListener(v -> {
                dialog.dismiss();
                if (onItemClick != null) onItemClick.onClick(index);
            });
            rowLayout.addView(itemView);
        }

        // 分隔线
        View divider = new View(activity);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divLp.setMargins(0, margin6, 0, margin6);
        divider.setLayoutParams(divLp);
        divider.setBackgroundColor(ContextCompat.getColor(activity, R.color.atv_separator_light));
        listContainer.addView(divider);

        // 底部按钮行（取消 + 粘贴）
        android.widget.LinearLayout bottomRow = new android.widget.LinearLayout(activity);
        bottomRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bottomRow.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        listContainer.addView(bottomRow);

        // 取消按钮
        View cancelView = inflater.inflate(R.layout.item_dialog_list, bottomRow, false);
        TextView tvCancel = cancelView.findViewById(R.id.itemText);
        tvCancel.setText("取消");
        android.widget.LinearLayout.LayoutParams cancelLp = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f
        );
        cancelLp.setMargins(0, 0, margin6, 0);
        cancelView.setLayoutParams(cancelLp);
        cancelView.setMinimumHeight((int) (48 * activity.getResources().getDisplayMetrics().density));
        cancelView.setOnClickListener(v -> {
            dialog.dismiss();
            if (onItemClick != null) onItemClick.onCancel();
        });
        bottomRow.addView(cancelView);

        dialog.setOnCancelListener(d -> {
            if (onItemClick != null) onItemClick.onCancel();
        });

        dialog.show();
    }

    // ==================== 回调接口 ====================

    /** 列表项点击监听器 */
    public interface OnItemClickListener {
        void onClick(int position);
    }

    public interface OnItemClickListenerWithCancel {
        void onClick(int position);
        default void onCancel() {}
    }

    public interface AdbConnectCallback {
        void onConfirm(String ip, int port, String pairingCode);
        default void onCancel() {}
    }

    public interface DialogCallback {
        void onConfirm(String input);
        default void onCancel() {}
    }

    public interface DialogIpCallback {
        void onConfirm(String ip, int port);
        default void onCancel() {}
    }
}