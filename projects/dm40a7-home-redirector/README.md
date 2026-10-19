# DM40A7 第三方桌面接管（HomeRedirector）

免 root 解决「海看 Hiveview 定制 ROM 硬绑原厂桌面、无法更换默认桌面」的问题。

- **适用**：大麦盒子 DM40A7（Amlogic p212 / Android 6.0.1 / ROM `T-REX.FF.70.0808`）
- **同样适用**：其它「系统只认原厂桌面、不允许禁用/替换」的定制 TV 盒子
- **成品**：[`apk/HomeRedirector-v1.5.apk`](apk/HomeRedirector-v1.5.apk)

---

## 背景

原厂桌面 `com.hiveview.cloudscreen.launcher`（DomyUI4.2）是**云端门户**式桌面，内容依赖海看服务器。服务器早已停运，桌面只剩一个空壳——连"列出并启动本机应用"这个最基本的功能都没有，设备基本不可用。

换成第三方桌面（Emotn UI / 当贝桌面）本身完全正常，但**系统不认它当主屏幕**。

---

## 为什么常规办法全部失败（实测结论）

| 尝试 | 结果 |
|---|---|
| 装第三方桌面（已声明 `CATEGORY_HOME`） | 安装成功、进入解析表，但系统 HOME 仍给原厂桌面 |
| `pm disable-user` 原厂桌面 | `SecurityException`：uid 2000 无权改系统包组件状态 |
| `pm hide` 原厂桌面 | 命令成功，但按 HOME **依旧 NPE**——系统是硬启动写死的组件，不做候选解析 |
| `pm uninstall --user 0` 原厂桌面 | 成功，但 `goHome` 抛 `NullPointerException`，第三方桌面完全不被采用，界面全黑 |
| Device Owner（`dpm set-device-owner`） | 不可用：ROM 裁掉了 `android.software.device_admin` 特性 |

结论：**这个 ROM 在框架层把桌面写死了**，所有"改默认 HOME"的正规路径都被堵死。

---

## 方案

用**无障碍服务**在系统之外接管 HOME 键：

```
按下 HOME
   ↓
AccessibilityService.onKeyEvent(HOME DOWN)      ← 唯一可靠信号
   ↓  ~60ms
startActivity(目标桌面)
   ↓
看门狗（120ms）确认目标桌面已在前台 → 撤遮罩
```

三条兜底：

1. **原厂桌面冒头** → 遮罩 + 拉起目标桌面（原厂桌面若还装着才需要）
2. **连续 6 秒完全没有窗口** → 开机救援（卸载原厂桌面后，重启时系统没有 HOME 可启动，会黑屏）
3. **`BOOT_COMPLETED`** → 双保险（该 ROM 会挡这个广播，**不能只靠它**）

遮罩（"正在进入桌面…"）可在应用内开关，状态持久化。

---

## 目录结构

```
projects/dm40a7-home-redirector/
├── README.md                     本文件
├── docs/
│   └── HomeRedirector-方案与部署手册.md    完整技术文档（含全部踩坑记录）
├── apk/
│   └── HomeRedirector-v1.5.apk   成品
└── app/                          源码（非 Gradle 工程，用 build.ps1 直接构建）
    ├── AndroidManifest.xml
    ├── build.ps1                 一键构建：aapt → javac → d8 → zipalign → apksigner
    ├── res/
    │   ├── values/strings.xml
    │   └── xml/accessibility_service_config.xml
    └── src/com/miaomiaotv/homeredirector/
        ├── HomeWatcher.java      核心：按键拦截 + 看门狗 + 开机救援 + 遮罩
        ├── BootReceiver.java     开机广播双保险
        └── MainActivity.java     设置页（遮罩开关）
```

---

## 快速部署

```bash
# 1. 装目标桌面 + 本工具
adb install -r -d emotnui.apk
adb install -r -d HomeRedirector-v1.5.apk

# 2. 启用无障碍（无需进设置页手工点）
adb shell "settings put secure accessibility_enabled 1"
adb shell "settings put secure enabled_accessibility_services \
  com.miaomiaotv.homeredirector/com.miaomiaotv.homeredirector.HomeWatcher"

# 3. 确认服务已连上（capabilities 含 8 = CAN_FILTER_KEY_EVENTS）
adb shell "dumpsys accessibility | head -5"

# 4. 卸载原厂桌面（提速；前提是第 2 步已生效，否则重启会黑屏）
adb shell "pm uninstall --user 0 com.hiveview.cloudscreen.launcher"
```

换目标桌面：改 `HomeWatcher.java` 里的 `TARGET_PKG` / `TARGET_CLS` 后重新构建。

构建依赖：JDK 8+、Android SDK build-tools **35.0.0**（34 的 d8 有 bug，33 与 JDK 21 不兼容）、platforms android-34。

```powershell
cd app
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

---

## 五个最关键的坑

1. **`input keyevent 3` 测不出按键** —— 注入事件不走无障碍按键过滤。必须用真按键或 `sendevent /dev/input/event0 1 102 1`（KEY_HOME=102）。
2. **无窗口判定不能太激进** —— 应用冷启动瞬间窗口也是空的；判定压到 240ms 会把用户刚打开的应用顶掉，表现为"打开任意应用立刻闪退回桌面"。
3. **不能只靠 `BOOT_COMPLETED`** —— 该 ROM 会挡掉开机广播；开机救援必须放进无障碍服务（系统开机时一定会绑定它）。
4. **卸载原厂桌面后重启会黑屏** —— 系统没有 HOME 组件可启动，前台没有任何 Activity，焦点停在 `KeyguardScrim`。靠 6 秒无窗口救援兜住。
5. **"黑屏"未必是系统挂了** —— 先确认是不是 HDMI 没同步（截图仍有画面、`hpd_state=1`、`Display Power: ON` 就说明系统正常）。

---

## 实测数据

| 指标 | 数值 |
|---|---|
| 按下 HOME → 发起拉起 | **63ms** |
| 拉起 → 桌面可见（Emotn 重建） | 2.8~3.1 秒 |
| 拉起 → 桌面可见（当贝重建） | ~1.9 秒 |
| 目标桌面"任务仍是热的"时 | **0ms** |
| 开机 → 服务绑定 → 桌面可见 | 26s → 37s |

> 剩余等待全部来自目标桌面自身的 Activity 重建（无 root 无法保活：目标桌面没有可绑定的后台 Service，平台保活白名单 `ro.platform.keepbackground.app` 是只读属性）。

---

## 回退

```bash
# 关掉重定向
adb shell "settings put secure accessibility_enabled 0"

# 恢复原厂桌面（注意 --user 0 不能省，否则会"Success 但不生效"）
adb pull /system/app/DomyUI4.2_Launcher_4.0.98_201903131910/DomyUI4.2_Launcher_4.0.98_201903131910.apk
adb install -r -d --user 0 DomyUI4.2_Launcher_4.0.98_201903131910.apk
```

---

## 版本

| 版本 | 变更 |
|---|---|
| 1.0 | 初版：检测原厂桌面 → 遮罩 + 拉起目标桌面 |
| 1.2 | 遮罩超时兜底、目标桌面稳定判定 |
| 1.3 | 修复重启后黑屏（新增开机救援）；修复"打开应用闪退"（引入期待窗口闸门） |
| 1.4 | 遮罩改为可配置 |
| 1.5 | 遮罩开关做进设置页（遥控器可操作、持久化）；开机救援时也显示遮罩 |

详细技术细节、全部踩坑记录、移植检查清单见 [`docs/HomeRedirector-方案与部署手册.md`](docs/HomeRedirector-方案与部署手册.md)。
