package com.miaomiao.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 投屏历史记录Activity
 */
public class CastHistoryActivity extends Activity {

    private RecyclerView rvHistory;
    private TextView tvEmpty;
    private LinearLayout btnBack;
    private LinearLayout btnClear;

    private RecyclerView.Adapter<HistoryViewHolder> historyAdapter;
    private final List<CastReceiverService.CastHistory> historyList = new ArrayList<>();
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_cast_history);

        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        loadHistory();
    }

    private void initViews() {
        rvHistory = findViewById(R.id.rvHistory);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnBack = findViewById(R.id.btnBack);
        btnClear = findViewById(R.id.btnClear);

        btnBack.setOnClickListener(v -> finish());
        btnClear.setOnClickListener(v -> clearHistory());

        setFocusEffect(btnBack);
        setFocusEffect(btnClear);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        setupAdapter();
    }

    private void setFocusEffect(View view) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? 1.08f : 1.0f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    private void setupAdapter() {
        historyAdapter = new RecyclerView.Adapter<HistoryViewHolder>() {
            @NonNull
            @Override
            public HistoryViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_cast_history, parent, false);
                return new HistoryViewHolder(v);
            }

            @Override
            public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
                CastReceiverService.CastHistory history = historyList.get(position);
                holder.bind(history);

                holder.itemView.setOnClickListener(v -> {
                    // 重新播放
                    Intent result = new Intent();
                    result.putExtra("url", history.url);
                    result.putExtra("type", history.type);
                    setResult(RESULT_OK, result);
                    finish();
                });

                holder.itemView.setOnLongClickListener(v -> {
                    // 长按删除
                    new AlertDialog.Builder(CastHistoryActivity.this)
                        .setTitle("删除记录")
                        .setMessage("确定删除这条记录？")
                        .setPositiveButton("删除", (d, w) -> {
                            historyList.remove(position);
                            historyAdapter.notifyDataSetChanged();
                            if (historyList.isEmpty()) {
                                tvEmpty.setVisibility(View.VISIBLE);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                    return true;
                });
            }

            @Override
            public int getItemCount() {
                return historyList.size();
            }
        };

        rvHistory.setAdapter(historyAdapter);
    }

    private void loadHistory() {
        historyList.clear();
        historyList.addAll(CastReceiverService.getHistory());
        historyAdapter.notifyDataSetChanged();

        if (historyList.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void clearHistory() {
        if (historyList.isEmpty()) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("清空历史")
            .setMessage("确定清空所有投屏记录？")
            .setPositiveButton("清空", (d, w) -> {
                CastReceiverService.getHistory().clear();
                historyList.clear();
                historyAdapter.notifyDataSetChanged();
                tvEmpty.setVisibility(View.VISIBLE);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 历史记录ViewHolder */
    class HistoryViewHolder extends RecyclerView.ViewHolder {
        LinearLayout itemLayout;
        TextView tvDevice;
        TextView tvUrl;
        TextView tvTime;
        TextView tvType;

        HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            itemLayout = itemView.findViewById(R.id.itemLayout);
            tvDevice = itemView.findViewById(R.id.tvDevice);
            tvUrl = itemView.findViewById(R.id.tvUrl);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvType = itemView.findViewById(R.id.tvType);
        }

        void bind(CastReceiverService.CastHistory history) {
            tvDevice.setText("📱 " + history.deviceName);

            // 截取URL显示
            String url = history.url;
            if (url.length() > 40) {
                url = url.substring(0, 40) + "...";
            }
            tvUrl.setText(url);

            // 格式化时间
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            tvTime.setText(sdf.format(new Date(history.timestamp)));

            // 类型标签
            String typeEmoji = history.type.equals("photo") ? "🖼️" : "🎬";
            tvType.setText(typeEmoji);

            // 焦点效果
            itemLayout.setOnFocusChangeListener((v, hasFocus) -> {
                float scale = hasFocus ? 1.02f : 1.0f;
                v.animate().scaleX(scale).scaleY(scale).setDuration(100).start();
                if (hasFocus) {
                    itemLayout.setBackgroundResource(R.drawable.btn_focused_bg);
                } else {
                    itemLayout.setBackgroundResource(R.drawable.btn_device_bg);
                }
            });
        }
    }
}
