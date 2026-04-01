# 喵喵嗷影视

Android TV WebView 应用，支持遥控器操作，专为大屏设备优化。

## 功能特性

- **4 大功能入口**：直播 / 点播 / 工具 / 扫码输入网址
- **遥控器全支持**：D-pad 方向键导航 + 确认/返回键操作
- **二维码扫码**：手机扫码即可输入网址，无需遥控器打字
- **下载管理**：内置下载功能，扫码盒内影视资源
- **地址栏**：支持手动输入任意网址

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
│   ├── java/com/miaomiao/tv/    # Java 源码
│   └── res/                     # 布局、图片等资源
├── build_helper.py               # 构建脚本（读取 CONFIG.txt）
├── CONFIG.txt                    # 快速配置文件
└── gradle/                      # Gradle Wrapper
```

## 技术栈

- Android SDK 36
- Gradle 8.13
- JDK 17
- WebView + Java

## 注意事项

- `local.properties` 包含本机 SDK 路径（不随仓库同步，首次克隆后需手动配置）
- `gradlew` / `gradlew.bat` 首次运行会自动下载 Gradle，无需手动安装
