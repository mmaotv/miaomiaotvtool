# 喵喵嗷影视

Android TV WebView 应用，支持遥控器操作，专为大屏设备优化。

## 功能特性

- **8 大功能入口**：直播 / 点播 / 工具 / 历史记录 / 扫码输入 / 文件管理 / U盘 / 关于
- **遥控器全支持**：D-pad 方向键导航 + 确认/返回键操作
- **扫码推送**：手机扫码后可推送网址或上传文件到设备，无需遥控器打字
- **局域网直接访问**：二维码下方显示 IP 地址和端口，浏览器直接输入也可推送网址和上传文件
- **文件管理器**：内置文件浏览/重命名/删除/分享等操作，支持图片/视频缩略图
- **U盘管理**：自动检测 USB 存储设备，支持文件复制与浏览
- **下载管理**：内置下载功能，管理已下载的影视资源
- **地址栏**：支持手动输入任意网址，含前进/后退/刷新/首页
- **收藏与历史**：支持收藏网址、查看浏览历史
- **关于页面**：点击关于按钮可直接在应用内打开 GitHub 仓库页面

## 扫码 / 局域网推送

打开「扫码输入」功能后，设备会启动一个局域网 HTTP 服务器（默认端口 `18765`）。

**两种使用方式：**

1. **扫二维码**：手机扫码直接打开控制页面
2. **浏览器输入地址**：二维码下方显示当前 IP 和端口（如 `192.168.1.x:18765`），局域网内任何设备打开浏览器输入该地址均可访问

**控制页面支持：**
- 📡 输入网址并推送到 TV 端立即跳转
- 📂 从手机/电脑上传文件到 TV 设备本地存储

## 快速配置

修改 `CONFIG.txt` 后运行 `build.bat` 即可打包。

```ini
# 应用信息
APP_NAME=喵喵嗷影视
VERSION_NAME=1.0
VERSION_CODE=1

# 首页标题（留空则与 APP_NAME 相同）
HOME_TITLE=
SUBTITLE_TEXT=高清影视 · 随时随地

# 链接配置
LIVE_URL=https://...
VOD_URL=https://...
TOOLS_URL=https://...

# 提示文字
INFO_TEXT=密码：miao

# 图标（留空使用默认图标，填写路径则自动生成各密度图标）
ICON_PATH=
```

## 构建

```bash
# 安装依赖（首次）
pip install Pillow

# 打包
build.bat
```

输出的 APK 文件在项目根目录：`miaomiaotv.apk`

## 项目结构

```
TVapp/
├── app/src/main/
│   ├── java/com/miaomiao/tv/
│   │   ├── MainActivity.java         # 主界面 + WebView + 遥控器
│   │   ├── QrInputHelper.java        # 扫码推送 + 文件上传 HTTP 服务
│   │   ├── DownloadsActivity.java    # 下载管理
│   │   ├── FileManagerActivity.java  # 文件管理器
│   │   ├── UsbManagerActivity.java   # U 盘管理
│   │   ├── HistoryActivity.java      # 历史记录
│   │   ├── BookmarkActivity.java     # 收藏
│   │   ├── BookmarkManager.java      # 收藏管理
│   │   ├── WebHistoryManager.java    # 历史记录管理
│   │   ├── DownloadService.java      # 后台下载服务
│   │   └── DialogHelper.java         # 弹窗工具
│   └── res/                          # 布局、图片等资源
├── build_helper.py                   # 构建脚本（读取 CONFIG.txt）
├── CONFIG.txt                        # 快速配置文件
└── gradle/                           # Gradle Wrapper
```

## 技术栈

- Android SDK 36
- Gradle 8.13
- JDK 17
- WebView + Java
- ZXing（二维码生成）

## GitHub 仓库

- 仓库地址：https://github.com/mmaotv/miaomiaotvtool
- 最新 APK 下载：https://github.com/mmaotv/miaomiaotvtool/releases

## 注意事项

- `local.properties` 包含本机 SDK 路径（不随仓库同步，首次克隆后需手动配置）
- `gradlew` / `gradlew.bat` 首次运行会自动下载 Gradle，无需手动安装
- 扫码推送功能需要手机和 TV 在同一局域网（Wi-Fi）下