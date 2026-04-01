# 保留 WebView 接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# 保留 Leanback 相关
-keep class androidx.leanback.** { *; }
# 保留 FileProvider
-keep class androidx.core.content.FileProvider { *; }
