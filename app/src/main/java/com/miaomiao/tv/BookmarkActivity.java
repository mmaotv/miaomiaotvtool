package com.miaomiao.tv;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 收藏夹界面
 * - 显示所有收藏的书签
 * - 点击跳转，遥控器长按/确认键删除
 * - 手动添加书签
 */
public class BookmarkActivity extends AppCompatActivity {

    private LinearLayout listContainer;
    private ScrollView scrollView;
    private View emptyView;
    private TextView tvCount;
    private LinearLayout btnAdd;
    private BookmarkManager manager;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 启动此 Activity 并指定跳转的 URL */
    public static void startWithUrl(android.content.Context ctx, String url) {
        Intent intent = new Intent(ctx, BookmarkActivity.class);
        intent.putExtra("jump_url", url);
        ctx.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 沉浸式：隐藏系统UI
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        setContentView(R.layout.activity_bookmark);

        manager = new BookmarkManager(this);

        listContainer = findViewById(R.id.listContainer);
        scrollView    = findViewById(R.id.scrollView);
        emptyView     = findViewById(R.id.emptyView);
        tvCount       = findViewById(R.id.tvCount);
        btnAdd        = findViewById(R.id.btnAdd);

        // 添加按钮：手动添加书签
        btnAdd.setOnClickListener(v -> showAddDialog(null, null));

        // 焦点动画
        attachFocusScale(btnAdd, 1.12f);

        // 检查是否有传入的 URL（扫码推送的地址直接跳转）
        String jumpUrl = getIntent().getStringExtra("jump_url");
        if (jumpUrl != null && !jumpUrl.isEmpty()) {
            // 直接跳转，不显示列表
            navigateTo(jumpUrl, jumpUrl);
            return;
        }

        loadBookmarks();
    }

    private void loadBookmarks() {
        listContainer.removeAllViews();
        List<BookmarkManager.Bookmark> bookmarks = manager.getAll();

        if (bookmarks.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            scrollView.setVisibility(View.GONE);
            tvCount.setText("0 个收藏");
            return;
        }

        emptyView.setVisibility(View.GONE);
        scrollView.setVisibility(View.VISIBLE);
        tvCount.setText(bookmarks.size() + " 个收藏");

        View firstFocus = null;
        for (int i = 0; i < bookmarks.size(); i++) {
            BookmarkManager.Bookmark b = bookmarks.get(i);
            View item = makeItem(b, i);
            listContainer.addView(item);
            if (firstFocus == null) firstFocus = item;
        }

        // 默认聚焦第一个
        final View finalFirstFocus = firstFocus;
        mainHandler.postDelayed(() -> {
            if (finalFirstFocus != null) finalFirstFocus.requestFocus();
        }, 100);
    }

    private View makeItem(BookmarkManager.Bookmark bookmark, int position) {
        LayoutInflater inflater = LayoutInflater.from(this);
        View item = inflater.inflate(R.layout.item_bookmark, listContainer, false);

        TextView tvTitle  = item.findViewById(R.id.tvTitle);
        TextView tvUrl    = item.findViewById(R.id.tvUrl);
        TextView btnDel   = item.findViewById(R.id.btnDelete);

        tvTitle.setText(bookmark.title.isEmpty() ? "未命名" : bookmark.title);
        tvUrl.setText(bookmark.url);

        // 聚焦动画
        attachFocusScale(item, 1.04f);

        // 点击跳转
        item.setOnClickListener(v -> navigateTo(bookmark.title, bookmark.url));

        // 删除按钮：弹出确认对话框
        btnDel.setOnClickListener(v -> showDeleteConfirm(bookmark));

        // 遥控器长按删除
        item.setOnLongClickListener(v -> {
            showDeleteConfirm(bookmark);
            return true;
        });

        return item;
    }

    private void navigateTo(String title, String url) {
        // 将 URL 通过 Intent 返回给 MainActivity
        Intent result = new Intent();
        result.putExtra("bookmark_url", url);
        result.putExtra("bookmark_title", title);
        setResult(RESULT_OK, result);
        finish();
    }

    private void showDeleteConfirm(BookmarkManager.Bookmark bookmark) {
        new AlertDialog.Builder(this)
            .setTitle("删除收藏")
            .setMessage("确定删除「" + bookmark.title + "」？")
            .setPositiveButton("删除", (d, w) -> {
                if (manager.delete(bookmark.id)) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    loadBookmarks();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 手动添加书签对话框 */
    private void showAddDialog(String prefilledUrl, String prefilledTitle) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_add_bookmark);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.CENTER);
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
        }

        EditText etTitle = dialog.findViewById(R.id.etTitle);
        EditText etUrl   = dialog.findViewById(R.id.etUrl);
        LinearLayout btnConfirm = dialog.findViewById(R.id.btnConfirm);
        LinearLayout btnCancel  = dialog.findViewById(R.id.btnCancel);

        if (prefilledTitle != null) etTitle.setText(prefilledTitle);
        if (prefilledUrl != null)    etUrl.setText(prefilledUrl);

        attachFocusScale(btnConfirm, 1.08f);
        attachFocusScale(btnCancel, 1.08f);

        btnConfirm.setOnClickListener(v -> {
            String title = etTitle.getText().toString().trim();
            String url   = etUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            if (title.isEmpty()) {
                title = url;
            }
            BookmarkManager.Bookmark b = new BookmarkManager.Bookmark(
                null, title, url, System.currentTimeMillis()
            );
            if (manager.add(b)) {
                Toast.makeText(this, "已添加收藏", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                loadBookmarks();
            } else {
                Toast.makeText(this, "该网址已收藏", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /** 焦点放大/缩小动画 */
    private void attachFocusScale(View v, float scale) {
        v.setOnFocusChangeListener((view, hasFocus) -> {
            float target = hasFocus ? scale : 1.0f;
            AnimatorSet set = new AnimatorSet();
            set.playTogether(
                ObjectAnimator.ofFloat(view, "scaleX", target),
                ObjectAnimator.ofFloat(view, "scaleY", target)
            );
            set.setDuration(150);
            set.start();
        });
    }
}
