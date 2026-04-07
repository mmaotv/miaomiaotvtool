# 喵喵嗷影视项目工作记忆

## 项目概述
- 位置：f:\APPS\TVapp
- Android TV WebView 应用，支持遥控器操作、横屏
- 打包APK名称：miaomiaotv.apk（ASCII名）或 喵喵嗷影视.apk

## 技术栈
- Java + AndroidManifest + Gradle
- JDK：C:\temp\jdk17\jdk-17.0.12+7
- SDK：C:\Users\1\AppData\Local\Android\Sdk（build-tools 36.1.0，platform android-36）
- Gradle：8.13（缓存于 C:\Users\1\.gradle\wrapper\dists\gradle-8.13-bin）
- Python：C:\Users\1\AppData\Local\Programs\Python\Python314\python.exe

## 快速打包
1. 修改 f:\APPS\TVapp\CONFIG.txt（支持 APP_NAME / VERSION_NAME / VERSION_CODE / LIVE_URL / VOD_URL / TOOLS_URL / INFO_TEXT / ICON_PATH）
2. 运行：python f:\APPS\TVapp\build_helper.py
3. 或直接修改 strings.xml 再运行构建脚本
4. 图标：在 ICON_PATH 填写本地图片路径，需安装 Pillow（pip install Pillow）

## APP 结构（2026-04-07 最新版）
- 首页：4个按钮（2行2列）+ 底部提示栏
  - 第一行：直播（蓝绿渐变）+ 点播（粉橙渐变）
  - 第二行：工具（绿青渐变）+ 投屏（红粉渐变）
  - 底部提示：扫码输入 · 文件管理 · U盘
- WebView 工具栏：后退/前进/刷新 + 可编辑地址栏 + GO + 🏠首页 + 📥下载 + ⭐收藏 + 📜历史 + 📺投屏
- 下载管理 → DownloadsActivity（扫描 Download/喵喵嗷影视/ 目录）
- 扫码输入：局域网 HTTP Server（端口18765）+ ZXing 二维码，手机扫码输入网址
- 投屏功能：CastReceiverService（端口18766）+ NSD/mDNS广播
- 遥控器：D-pad 移动光标，确认键点击，BACK 返回首页
- 配色：纯白背景 #FFFFFF，主色调 #00D9FF
- 按钮焦点效果：scaleX/Y 放大动画（150ms），遥控器选中时放大明显

## 配置文件
- 快速配置：CONFIG.txt（APP_NAME / VERSION_NAME / VERSION_CODE / LIVE_URL / VOD_URL / TOOLS_URL / INFO_TEXT / HOME_TITLE / SUBTITLE_TEXT / ICON_PATH）
- 代码配置：app/src/main/res/values/strings.xml
- strings.xml 包含 app_name / home_title / subtitle_text / live_url / vod_url / tools_url / info_text 等 key
- build.gradle 版本号由 build_helper.py 动态写入（versionCode / versionName）
- 投屏界面（CastReceiverActivity）：极简设计，只显示接收器名称和IP地址
  - 接收器名称："喵喵嗷投屏接收器"
  - IP地址：显示局域网IP（如192.168.x.x）
  - 白色背景，无二维码，无使用说明

## 依赖
- build.gradle 新增：com.google.zxing:core:3.5.2（用于二维码生成）

## 注意事项
- Windows 中文路径编码问题：APK 输出时用 ASCII 文件名（miaomiaotv.apk）更可靠
- build_helper.py 里用 ASCII 转义中文字符避免 GBK 编码错误
- DownloadsActivity 已在 AndroidManifest.xml 注册

## GitHub 协作
- 仓库：https://github.com/mmaotv/miaomiaotvtool.git（私有）
- 每次完成代码修改后自动 commit + push
- Git 代理配置：`git config --global http.proxy http://127.0.0.1:7897`（Clash，需开启 VPN）
- .gitignore：排除 .apk / build/ / .gradle/ / .workbuddy/
