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
- **ADB 无线调试**：支持 Android 5-16 全版本无线 ADB 连接（含配对码支持）
- **关于页面**：点击关于按钮可直接在应用内打开 GitHub 仓库页面

## 扫码 / 局域网推送

打开「扫码输入」功能后，设备会启动一个局域网 HTTP 服务器（默认端口 `18765`）。

**两种使用方式：**

1. **扫二维码**：手机扫码直接打开控制页面
2. **浏览器输入地址**：二维码下方显示当前 IP 和端口（如 `192.168.1.x:18765`），局域网内任何设备打开浏览器输入该地址均可访问

**控制页面支持：**
- 📡 输入网址并推送到 TV 端立即跳转
- 📂 从手机/电脑上传文件到 TV 设备本地存储

## ADB 无线调试

内置 ADB 无线调试功能，支持 Android 5 到 Android 16 全版本：

| Android 版本 | 配对方式 | 说明 |
|-------------|---------|------|
| Android 5-10 | 无需配对 | 直接连接，端口 5555 |
| Android 11-12 | 6位配对码 | 通知输入或剪贴板 |
| Android 13+ | 6位配对码 | 支持 TLS 加密连接 |

**使用方式：**
1. 打开「ADB 调试」功能
2. 输入目标设备 IP 地址
3. Android 11+ 需要输入 6 位配对码
4. 连接成功后可在 PC 端通过 `adb shell` 操作设备

## 快速配置

修改 `CONFIG.txt` 后运行 `build.bat` 即可打包。

```ini
# 应用信息
APP_NAME=喵喵嗷影视
VERSION_NAME=1.6
VERSION_CODE=7

# 首页标题
HOME_TITLE=喵喵嗷影视
SUBTITLE_TEXT=高清影视 · 随时随地

# 链接配置
LIVE_URL=https://...
VOD_URL=https://...
TOOLS_URL=https://...

# 提示文字
INFO_TEXT=密码：miao

# 图标路径（留空使用默认图标）
ICON_PATH=

# 签名配置（可选，用于 Release 发布）
KEYSTORE_PATH=
KEYSTORE_PASSWORD=
KEY_ALIAS=
KEY_PASSWORD=

# 自动版本递增
AUTO_INCREMENT_VERSION=true
```

**详细配置说明**见 `CONFIG.txt` 文件内注释。

## 构建

### 环境要求

- Android SDK 36
- Gradle 8.13
- JDK 17+
- Python 3.x（用于构建脚本）

### 快速构建

```bash
# 安装依赖（首次）
pip install Pillow

# 配置 CONFIG.txt（复制模板并修改）
copy CONFIG.txt.template CONFIG.txt

# 打包
build.bat
```

输出的 APK 文件在项目根目录：`喵喵嗷影视.apk`

### 签名发布

如需发布到应用商店，需要创建签名密钥：

```bash
# 生成签名密钥
keytool -genkey -v -keystore mykey.jks -keyalg RSA -keysize 2048 -validity 10000 -alias myalias

# 在 CONFIG.txt 中填写签名信息
KEYSTORE_PATH=C:\path\to\mykey.jks
KEYSTORE_PASSWORD=your_password
KEY_ALIAS=myalias
KEY_PASSWORD=your_password
```

填写后运行 `build.bat` 会自动构建 Release 签名版 APK。

## 项目结构

```
miaomiaotvtool/
├── app/src/main/
│   ├── java/com/miaomiao/tv/
│   │   ├── MainActivity.java           # 主界面 + WebView + 遥控器
│   │   ├── QrInputHelper.java          # 扫码推送 + 文件上传 HTTP 服务
│   │   ├── DownloadsActivity.java      # 下载管理
│   │   ├── FileManagerActivity.java    # 文件管理器
│   │   ├── UsbManagerActivity.java     # U 盘管理
│   │   ├── HistoryActivity.java        # 历史记录
│   │   ├── BookmarkActivity.java       # 收藏
│   │   ├── AdbHelper.java              # ADB 无线调试核心
│   │   ├── AdbPairService.java         # ADB 配对服务
│   │   ├── AndroidVersionCompat.java   # 版本兼容工具
│   │   └── AndroidPubkeyCodec.java     # RSA 公钥编码
│   └── res/                            # 布局、图片等资源
├── build.py                            # 构建脚本（读取 CONFIG.txt）
├── build.bat                           # Windows 构建入口
├── CONFIG.txt                          # 快速配置文件（本地使用）
├── CONFIG.txt.template                 # 配置模板（GitHub）
├── settings.gradle                     # Gradle 项目配置
└── gradle/                             # Gradle Wrapper
```

## 技术栈

- **Android SDK**: 36 (minSdk 21, 支持 Android 5.0+)
- **构建工具**: Gradle 8.13
- **编程语言**: Java
- **核心库**:
  - libadb-android: ADB 无线调试
  - ZXing: 二维码生成
  - Conscrypt: TLS 加密支持
- **脚本**: Python 3 (构建自动化)

## 版本历史

| 版本 | 日期 | 更新内容 |
|-----|------|---------|
| 1.6 | 2026-04-28 | 新增 ADB 无线调试功能，支持 Android 5-16 |
| 1.5 | - | 优化文件管理器，修复 WebView 兼容性问题 |
| 1.0 | - | 初始版本，基础功能完成 |

## GitHub 仓库

- **仓库地址**: https://github.com/mmaotv/miaomiaotvtool
- **最新 APK**: https://github.com/mmaotv/miaomiaotvtool/releases
- **问题反馈**: https://github.com/mmaotv/miaomiaotvtool/issues

## 开发文档

- [ADB 版本兼容说明](ADB_VERSION_COMPAT.md)
- [ADB 适配日志](ADB_ADAPTATION_LOG.md)

## 注意事项

- `local.properties` 包含本机 SDK 路径（已加入 `.gitignore`，首次克隆后需自动或手动配置）
- `CONFIG.txt` 包含本地配置（已加入 `.gitignore`，从 `CONFIG.txt.template` 复制）
- `gradlew` / `gradlew.bat` 首次运行会自动下载 Gradle，无需手动安装
- 扫码推送功能需要手机和 TV 在同一局域网（Wi-Fi）下
- ADB 无线调试需要设备和电脑在同一网络下

## License

MIT License

---

**开发者**: mmaotv  
**联系邮箱**: mma0215@qq.com
