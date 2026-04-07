package com.miaomiao.tv;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 应用管理器Activity - 管理已安装的应用
 */
public class AppManagerActivity extends Activity {

    private RecyclerView rvApps;
    private EditText etSearch;
    private TextView tvStats;
    private LinearLayout btnBack;
    private LinearLayout btnRefresh;

    private RecyclerView.Adapter<AppViewHolder> appAdapter;
    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> filteredApps = new ArrayList<>();
    private Handler mainHandler;
    private PackageManager pm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_app_manager);

        mainHandler = new Handler(Looper.getMainLooper());
        pm = getPackageManager();

        initViews();
        loadApps();
    }

    private void initViews() {
        rvApps = findViewById(R.id.rvApps);
        etSearch = findViewById(R.id.etSearch);
        tvStats = findViewById(R.id.tvStats);
        btnBack = findViewById(R.id.btnBack);
        btnRefresh = findViewById(R.id.btnRefresh);

        btnBack.setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> loadApps());

        setFocusEffect(btnBack);
        setFocusEffect(btnRefresh);

        // 搜索监听
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        rvApps.setLayoutManager(new LinearLayoutManager(this));
        setupAdapter();
    }

    private void setFocusEffect(View view) {
        view.setOnFocusChangeListener((v, hasFocus) -> {
            float scale = hasFocus ? 1.08f : 1.0f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
        });
    }

    private void setupAdapter() {
        appAdapter = new RecyclerView.Adapter<AppViewHolder>() {
            @NonNull
            @Override
            public AppViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
                View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_app, parent, false);
                return new AppViewHolder(v);
            }

            @Override
            public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
                AppInfo app = filteredApps.get(position);
                holder.bind(app);

                holder.itemView.setOnClickListener(v -> {
                    openApp(app);
                });

                holder.itemView.setOnLongClickListener(v -> {
                    showAppOptions(app);
                    return true;
                });
            }

            @Override
            public int getItemCount() {
                return filteredApps.size();
            }
        };

        rvApps.setAdapter(appAdapter);
    }

    private void loadApps() {
        ProgressDialog pd = ProgressDialog.show(this, "", "正在加载应用列表...", true);

        new Thread(() -> {
            allApps.clear();

            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

            for (ApplicationInfo appInfo : apps) {
                // 过滤系统应用（可选）
                // if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

                AppInfo app = new AppInfo();
                app.packageName = appInfo.packageName;
                app.appName = pm.getApplicationLabel(appInfo).toString();
                app.icon = pm.getApplicationIcon(appInfo);
                app.isSystem = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                // 获取版本号
                try {
                    app.version = pm.getPackageInfo(appInfo.packageName, 0).versionName;
                } catch (NameNotFoundException e) {
                    app.version = "";
                }

                allApps.add(app);
            }

            // 按名称排序
            Collections.sort(allApps, (a, b) -> a.appName.compareToIgnoreCase(b.appName));

            mainHandler.post(() -> {
                pd.dismiss();
                filterApps(etSearch.getText().toString());
                tvStats.setText("共 " + allApps.size() + " 个应用");
            });
        }).start();
    }

    private void filterApps(String keyword) {
        filteredApps.clear();

        if (keyword.isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            String lower = keyword.toLowerCase();
            for (AppInfo app : allApps) {
                if (app.appName.toLowerCase().contains(lower) ||
                    app.packageName.toLowerCase().contains(lower)) {
                    filteredApps.add(app);
                }
            }
        }

        appAdapter.notifyDataSetChanged();
        tvStats.setText("共 " + allApps.size() + " 个应用，显示 " + filteredApps.size() + " 个");
    }

    private void openApp(AppInfo app) {
        try {
            Intent intent = pm.getLaunchIntentForPackage(app.packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "无法打开此应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppOptions(AppInfo app) {
        String[] options;

        if (app.packageName.equals(getPackageName())) {
            // 本应用不显示卸载选项
            options = new String[]{"打开", "应用信息"};
        } else {
            options = new String[]{"打开", "应用信息", "卸载"};
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle(app.appName)
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: // 打开
                        openApp(app);
                        break;
                    case 1: // 应用信息
                        showAppInfo(app);
                        break;
                    case 2: // 卸载
                        uninstallApp(app);
                        break;
                }
            })
            .show();
    }

    private void showAppInfo(AppInfo app) {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + app.packageName));
        startActivity(intent);
    }

    private void uninstallApp(AppInfo app) {
        Intent intent = new Intent(Intent.ACTION_DELETE);
        intent.setData(Uri.parse("package:" + app.packageName));
        startActivity(intent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 应用信息 */
    static class AppInfo {
        String packageName;
        String appName;
        String version;
        Drawable icon;
        boolean isSystem;
    }

    /** 应用列表ViewHolder */
    class AppViewHolder extends RecyclerView.ViewHolder {
        LinearLayout itemLayout;
        ImageView ivIcon;
        TextView tvName;
        TextView tvPackage;
        TextView tvVersion;

        AppViewHolder(@NonNull View itemView) {
            super(itemView);
            itemLayout = itemView.findViewById(R.id.itemLayout);
            ivIcon = itemView.findViewById(R.id.ivIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvPackage = itemView.findViewById(R.id.tvPackage);
            tvVersion = itemView.findViewById(R.id.tvVersion);
        }

        void bind(AppInfo app) {
            ivIcon.setImageDrawable(app.icon);
            tvName.setText(app.appName);
            tvPackage.setText(app.packageName);
            tvVersion.setText("v" + app.version);

            // 系统应用标记
            if (app.isSystem) {
                tvName.setText(app.appName + " 🔒");
            }

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
