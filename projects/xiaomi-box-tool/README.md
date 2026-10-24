# 小米盒子工具箱 TV 版

Android 电视盒子一键处理工具。**在电视上自己获取 ADB 权限**，不需要电脑或手机配合。

- 包名：`com.miaomiaotv.xiaomi`
- 适用：Android 5.x ~ 9.x 的电视盒子（小米盒子 4 / 4S Pro、极光盒子、海看等）
- 成品：[`apk/xiaomi-box-tool-release.apk`](apk/xiaomi-box-tool-release.apk)

---

## 一、这个工具解决什么

| 痛点 | 做法 |
|---|---|
| 换桌面要找电脑连 ADB 敲命令 | 工具自带 ADB 客户端，直连本机 `127.0.0.1:5555`，**自己给自己授权** |
| 盒子一关机，ADB 就再也连不上 | 把调试开关写进**持久化设置**，开机后自动巡检、被关掉自动打开 |
| 定制 ROM 把桌面写死，换不了 | 自守护无障碍服务接管 HOME 键（仅在系统 HOME 失效时出手） |
| 卸掉原厂桌面后重启黑屏 | 无障碍服务做开机救援：连续看不到界面就把桌面拉起来 |

**用户只需要做一步**：打开工具 → 设备上弹出「是否允许 USB 调试」→ 点「允许」。

---

## 二、三张机型卡片

| 卡片 | 适用 | 做什么 |
|---|---|---|
| 小米盒子4SPro | 安卓 9 系列 | 装 Emotn UI + 换桌面 + 清预装 + 装配套应用 + 自守护 |
| 小米盒子4 | 安卓 6 系列 | 装当贝桌面 + 换桌面 + 清预装 + 装配套应用 + 自守护 |
| **其他盒子** | 极光 / 海看等非小米机型 | 装 Emotn UI + 换桌面 + **自守护**（不清理预装） |

> 通用档刻意**不套用**小米的预装清理清单：清单里的包名在别的品牌上本就不存在，
> 而各家的「出厂桌面 / 复原推手」都不一样，拿小米清单去卸别家的包有误删风险。

---

## 三、新增能力：自获取与调试保持

详见 [`docs/ADB自获取与保持能力说明.md`](docs/ADB自获取与保持能力说明.md)。

### 3.1 自举：自己给自己开无障碍

拿到 shell 权限后执行（shell 身份有 `WRITE_SECURE_SETTINGS`，静默生效不弹框）：

```bash
settings put secure accessibility_enabled 1
settings put secure enabled_accessibility_services <本工具>/<服务类名>
```

### 3.2 保持：把调试开关写成常开

```bash
settings put global development_settings_enabled 1
settings put global adb_enabled 1
settings put global adb_wifi_enabled 1
setprop service.adb.tcp.port 5555
setprop persist.adb.tcp.port 5555
setprop persist.sys.usb.config adb
```

`Settings.Global` 写进 `/data/data/system.xml`，**重启后依然保留**；
`persist.` 前缀的属性同样跨重启保留。全部可逆（改回 0 即可）。

### 3.3 静默探测

后台巡检用 `probeOnly` 模式：只验证是否已授权，**绝不出示公钥**，
因此未授权时不会在电视上反复弹「是否允许 USB 调试」。
---

## 四、目录结构

```
projects/xiaomi-box-tool/
├── README.md
├── docs/
│   └── ADB自获取与保持能力说明.md     能力详解
├── apk/
│   └── xiaomi-box-tool-release.apk    成品（含 6 个内置桌面安装包）
└── app/                                源码（Gradle KTS，Kotlin）
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/miaomiaotv/xiaomi/
        │   ├── MainActivity.kt                     主界面（三张机型卡片）
        │   ├── adb/
        │   │   ├── AdbChannel.kt                   ADB 协议客户端（零依赖 Socket 实现）
        │   │   ├── AdbIdentity.kt                  RSA 身份与 AOSP 公钥
        │   │   └── AndroidPubkey.kt                AOSP 公钥编码
        │   ├── core/
        │   │   ├── AdbGuard.kt                     ADB 保持 / 自举 / root 兜底
        │   │   ├── DesktopState.kt                 桌面状态持久化
        │   │   ├── XiaomiRunner.kt                 处理流程执行器
        │   │   └── XiaomiTargets.kt                各机型清单与目标定义
        │   ├── accessibility/
        │   │   └── AgentAccessibilityService.kt    自守护服务
        │   └── receiver/
        │       └── AdbBootReceiver.kt              开机自启自检
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml
            ├── drawable/（含通用卡片图标 ic_generic）
            └── xml/accessibility_service_config.xml
```

---

## 五、安装与使用

```bash
adb install -r -d apk/xiaomi-box-tool-release.apk
```

电视上打开「小米盒子工具箱」：

1. 工具自动连接本机调试端口，设备弹出「是否允许 USB 调试」→ **点允许**；
2. 用方向键选机型卡片，OK 键执行；
3. 处理完成后点「知道了」，设备重启进入新桌面。

---

## 六、自行构建

依赖：JDK 8+、Android SDK（compileSdk 36）、Gradle 8.14.4（wrapper 已附带）。

```powershell
.\gradlew.bat assembleRelease
# 产物：app\build\outputs\apk\release\app-release.apk
```

> **注意**：`app/src/main/assets/` 下的 6 个桌面安装包（当贝桌面 / 当贝市场 / Emotn UI /
> 管家 / 原生系统设置 / 小草助手）体积共约 63MB，**未随本仓库分发**，
> 需要自行放入对应目录后构建。仓库里已提供构建好的成品 APK，可直接安装使用。

---

## 七、与 home-redirector 项目的关系

两者解决的是同一个问题的两条不同路线，可独立使用：

| | 小米盒子工具箱（本项目） | home-redirector |
|---|---|---|
| 定位 | 通用电视盒子处理工具 | 单用途的桌面接管工具 |
| 接管方式 | 优先用系统标准 HOME 解析；失败才用无障碍接管 | 一律用无障碍接管 HOME 键 |
| 授权 | 工具自举（adb shell 给自己开无障碍） | 需要用电脑 ADB 手动开一次 |

详见仓库内 [`projects/home-redirector/`](../home-redirector/)。

---

## 八、已知限制

1. 首次仍需在设备上点一次「允许」——调试授权弹窗是系统行为，无法绕过。
2. 调试端口彻底没监听时，**无 root 设备无法自救**，需人工进设置开一次。
3. 部分 ROM 开机时会主动把 `adb_enabled` 清零；工具会在服务起来后（开机约 30 秒内）
   重新写回，但这段时间内 ADB 不可用。
4. 不支持 Android 11+ 的无线调试（pairing 流程），仅覆盖老式 5555 端口。
5. 开启调试开关是系统级改动，介意的话把 `adb_enabled` 改回 0 即可。