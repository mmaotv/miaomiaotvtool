package com.miaomiao.tv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.ScaleAnimation;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 浏览历史记录Activity
 */
public class HistoryActivity extends Activity {

    private RecyclerView rvHistory;
    private View emptyView;
    private TextView tvClear;
    private HistoryAdapter adapter;
    private WebHistoryManager historyManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        historyManager = new WebHistoryManager(this);

        initViews();
        loadHistory();
    }

    private void initViews() {
        rvHistory = findViewById(R.id.rvHistory);
        emptyView = findViewById(R.id.emptyView);
        tvClear = findViewById(R.id.tvClear);

        rvHistory.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
        rvHistory.setAdapter(adapter);

        adapter.setOnItemClickListener((position, item) -> {
            // 返回URL给MainActivity
            Intent result = new Intent();
            result.putExtra("url", item.url);
            setResult(RESULT_OK, result);
            finish();
        });

        adapter.setOnDeleteClickListener((position, item) -> {
            historyManager.deleteHistory(position);
            loadHistory();
        });

        // 清空按钮
        tvClear.setOnClickListener(v -> {
            historyManager.clearHistory();
            loadHistory();
            Toast.makeText(this, "已清空历史记录", Toast.LENGTH_SHORT).show();
        });

        // 焦点动画
        attachFocusAnim(tvClear);
    }

    private void loadHistory() {
        List<WebHistoryManager.HistoryItem> items = historyManager.getHistoryList();

        if (items.isEmpty()) {
            rvHistory.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            rvHistory.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
            adapter.setData(items);
        }
    }

    private void attachFocusAnim(View view) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                ScaleAnimation scale = new ScaleAnimation(1f, 1.1f, 1f, 1.1f,
                    v.getWidth()/2f, v.getHeight()/2f);
                scale.setDuration(150);
                v.startAnimation(scale);
            } else {
                ScaleAnimation scale = new ScaleAnimation(1.1f, 1f, 1.1f, 1f,
                    v.getWidth()/2f, v.getHeight()/2f);
                scale.setDuration(150);
                v.startAnimation(scale);
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // 适配器
    private static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.ViewHolder> {

        private List<WebHistoryManager.HistoryItem> data;
        private SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        private OnItemClickListener listener;
        private OnDeleteClickListener deleteListener;

        interface OnItemClickListener { void onClick(int position, WebHistoryManager.HistoryItem item); }
        interface OnDeleteClickListener { void onClick(int position, WebHistoryManager.HistoryItem item); }

        void setData(List<WebHistoryManager.HistoryItem> data) {
            this.data = data;
            notifyDataSetChanged();
        }

        void setOnItemClickListener(OnItemClickListener listener) { this.listener = listener; }
        void setOnDeleteClickListener(OnDeleteClickListener listener) { this.deleteListener = listener; }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            WebHistoryManager.HistoryItem item = data.get(position);
            holder.tvTitle.setText(item.title);
            holder.tvUrl.setText(item.url);
            holder.tvTime.setText(dateFormat.format(new Date(item.timestamp)));

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(holder.getAdapterPosition(), item);
            });

            holder.tvDelete.setOnClickListener(v -> {
                if (deleteListener != null) deleteListener.onClick(holder.getAdapterPosition(), item);
            });

            holder.tvDelete.setOnFocusChangeListener((v, hasFocus) -> {
                v.setAlpha(hasFocus ? 1f : 0.5f);
            });
        }

        @Override
        public int getItemCount() { return data == null ? 0 : data.size(); }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvTitle, tvUrl, tvTime, tvDelete;
            ViewHolder(View view) {
                super(view);
                tvTitle = view.findViewById(R.id.tvTitle);
                tvUrl = view.findViewById(R.id.tvUrl);
                tvTime = view.findViewById(R.id.tvTime);
                tvDelete = view.findViewById(R.id.tvDelete);
            }
        }
    }
}
