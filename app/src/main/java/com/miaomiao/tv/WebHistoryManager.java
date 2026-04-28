package com.miaomiao.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 网页浏览历史管理器
 */
public class WebHistoryManager {

    private static final String PREF_NAME = "web_history";
    private static final String KEY_HISTORY = "history_list";
    private static final int MAX_HISTORY = 100;

    private final SharedPreferences prefs;

    public WebHistoryManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 添加浏览历史 */
    public void addHistory(String url, String title) {
        if (url == null || url.isEmpty()) return;

        List<HistoryItem> items = getHistoryList();

        // 移除相同URL的记录（去重）- Android 6 兼容：使用传统循环替代 removeIf
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).url.equals(url)) {
                items.remove(i);
            }
        }

        // 添加到最前面
        HistoryItem newItem = new HistoryItem();
        newItem.url = url;
        newItem.title = (title != null && !title.isEmpty()) ? title : url;
        newItem.timestamp = System.currentTimeMillis();
        items.add(0, newItem);

        // 限制最大数量
        while (items.size() > MAX_HISTORY) {
            items.remove(items.size() - 1);
        }

        saveHistory(items);
    }

    /** 获取历史记录列表 */
    public List<HistoryItem> getHistoryList() {
        List<HistoryItem> items = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_HISTORY, "[]");
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                HistoryItem item = new HistoryItem();
                item.url = obj.getString("url");
                item.title = obj.optString("title", item.url);
                item.timestamp = obj.getLong("timestamp");
                items.add(item);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return items;
    }

    /** 清空历史记录 */
    public void clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply();
    }

    /** 删除单条历史 */
    public void deleteHistory(int position) {
        List<HistoryItem> items = getHistoryList();
        if (position >= 0 && position < items.size()) {
            items.remove(position);
            saveHistory(items);
        }
    }

    private void saveHistory(List<HistoryItem> items) {
        try {
            JSONArray array = new JSONArray();
            for (HistoryItem item : items) {
                JSONObject obj = new JSONObject();
                obj.put("url", item.url);
                obj.put("title", item.title);
                obj.put("timestamp", item.timestamp);
                array.put(obj);
            }
            prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 历史记录项 */
    public static class HistoryItem {
        public String url;
        public String title;
        public long timestamp;
    }
}
