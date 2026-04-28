package com.miaomiao.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏夹管理器
 * 使用 SharedPreferences + JSON 存储书签列表
 */
public class BookmarkManager {

    private static final String PREF_NAME = "bookmarks";
    private static final String KEY_BOOKMARKS = "bookmark_list";

    private final SharedPreferences prefs;

    public BookmarkManager(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 获取所有书签 */
    public List<Bookmark> getAll() {
        List<Bookmark> list = new ArrayList<>();
        String json = prefs.getString(KEY_BOOKMARKS, null);
        if (json == null) return list;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                list.add(new Bookmark(
                    obj.optString("id", ""),
                    obj.optString("title", ""),
                    obj.optString("url", ""),
                    obj.optLong("addTime", 0)
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    /** 添加书签（自动去重） */
    public boolean add(Bookmark bookmark) {
        List<Bookmark> list = getAll();
        // URL 去重
        for (Bookmark b : list) {
            if (b.url != null && b.url.equals(bookmark.url)) {
                return false; // 已存在
            }
        }
        if (bookmark.id == null || bookmark.id.isEmpty()) {
            bookmark.id = String.valueOf(System.currentTimeMillis());
        }
        bookmark.addTime = System.currentTimeMillis();
        list.add(0, bookmark);
        return save(list);
    }

    /** 删除书签 */
    public boolean delete(String id) {
        List<Bookmark> list = getAll();
        boolean removed = list.removeIf(b -> b.id.equals(id));
        if (removed) return save(list);
        return false;
    }

    /** 更新书签名称 */
    public boolean rename(String id, String newTitle) {
        List<Bookmark> list = getAll();
        for (Bookmark b : list) {
            if (b.id.equals(id)) {
                b.title = newTitle;
                return save(list);
            }
        }
        return false;
    }

    /** 书签是否存在 */
    public boolean exists(String url) {
        if (TextUtils.isEmpty(url)) return false;
        for (Bookmark b : getAll()) {
            if (b.url != null && b.url.equals(url)) return true;
        }
        return false;
    }

    private boolean save(List<Bookmark> list) {
        try {
            JSONArray array = new JSONArray();
            for (Bookmark b : list) {
                JSONObject obj = new JSONObject();
                obj.put("id", b.id);
                obj.put("title", b.title);
                obj.put("url", b.url);
                obj.put("addTime", b.addTime);
                array.put(obj);
            }
            prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /** 书签数据类 */
    public static class Bookmark {
        public String id;
        public String title;
        public String url;
        public long addTime;

        public Bookmark(String id, String title, String url, long addTime) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.addTime = addTime;
        }
    }
}
