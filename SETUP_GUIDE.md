# 喵喵嗷影视 - 新电脑快速部署指南

## 目录结构

```
miaomiaotvtool-main/
├── build.bat              # 双击打包脚本
├── build_helper.py        # Python 打包辅助脚本
├── CONFIG.txt             # 应用配置（URL、名称等）
├── keystore.jks           # 签名密钥
├── local.properties       # SDK 路径配置
├── gradle/                # Gradle Wrapper
├── app/                   # 应用源码
└── tools/                 # 【新】便携工具目录
    ├── jdk/               # JDK 21（已包含）
    ├── android-sdk/       # Android SDK（已包含）
    └── gradle-8.4/        # Gradle 8.4（已包含）
```

## 快速开始

### 方式一：使用现有环境（推荐）

如果电脑已安装 Android Studio：

1. 打开 `local.properties`，修改 SDK 路径：
   ```properties
   sdk.dir=C\\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
   ```

2. 双击 `build.bat` 即可打包

### 方式二：完全便携版（无需安装 Android Studio）

#### 步骤 1：下载必要工具

1. **JDK 17**（必须）
   - 下载地址：https://adoptium.net/temurin/releases/?version=17
   - 下载 `OpenJDK17U-jdk_x64_windows_hotspot_17.0.12_7.zip`
   - 解压到 `tools/jdk/`

2. **Android SDK 命令行工具**（必须）
   - 下载地址：https://developer.android.com/studio#command-tools
   - 下载 `commandlinetools-win-11076708_latest.zip`
   - 解压到 `tools/android-sdk/cmdline-tools/latest/`

3. **Gradle 8.4**（已包含）
   - 已解压到 `tools/gradle-8.4/`
   - 如需重新下载：https://services.gradle.org/distributions/gradle-8.4-all.zip

#### 步骤 2：配置环境

编辑 `tools\env.bat`，设置你的路径：

```bat
set "JDK_PATH=%~dp0jdk"
set "SDK_PATH=%~dp0android-sdk"
set "GRADLE_PATH=%~dp0gradle-8.4"
```

#### 步骤 3：安装 SDK 组件

双击运行 `tools\setup-sdk.bat`，自动安装：
- Android SDK Platform 36
- Android SDK Build-Tools 33.0.1

#### 步骤 4：打包

双击 `build.bat`，生成 APK。

## 文件说明

| 文件 | 用途 |
|------|------|
| `CONFIG.txt` | 配置应用名称、URL、版本号等 |
| `build.bat` | 双击打包，默认生成 Release 版本 |
| `build.bat debug` | 生成 Debug 版本 |
| `tools/setup-sdk.bat` | 初始化 SDK（首次使用运行） |
| `tools/env.bat` | 环境变量配置 |

## 配置说明

编辑 `CONFIG.txt`：

```ini
# 应用名称
APP_NAME=喵喵嗷影视

# 版本号
VERSION_NAME=1.4
VERSION_CODE=5

# 首页按钮链接
LIVE_URL=https://example.com/live
VOD_URL=https://example.com/vod
TOOLS_URL=https://example.com/tools

# 提示文字
INFO_TEXT=密码：miao
SUBTITLE_TEXT=高清影视 · 随时随地

# 关于信息
ABOUT_CONTACT=mmaotv@outlook.com
ABOUT_GITHUB=github.com/mmaotv/miaomiaotvtool

# 应用图标路径（可选）
ICON_PATH=
```

## 常见问题

**Q: 打包失败，提示找不到 JAVA_HOME**
A: 运行 `tools\env.bat` 设置 JDK 路径，或安装 JDK 并设置环境变量。

**Q: 打包失败，提示找不到 Android SDK**
A: 编辑 `local.properties`，确保 `sdk.dir` 指向正确的 SDK 路径。

**Q: 如何修改签名密钥？**
A: 替换 `keystore.jks` 文件，并在 `app/build.gradle` 中更新签名配置。

**Q: 如何升级 Gradle 版本？**
A: 修改 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl`。

## 技术栈

- **语言**: Java + Android SDK
- **构建**: Gradle 8.4
- **最低版本**: Android 5.0 (API 21)
- **目标版本**: Android 16 (API 36)
- **依赖**: AndroidX, Material Design, ZXing

## 许可证

私有项目，仅供个人使用。
