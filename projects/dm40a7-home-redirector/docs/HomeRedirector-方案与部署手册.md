# HomeRedirector —— 免 root 接管第三方桌面

> 归档日期：2026-10-17
> 目标设备：大麦盒子 DM40A7（海看 Hiveview 定制 ROM）
> 场景：原厂桌面是云端门户且服务器已停运，界面空白、连启动应用都做不到，需要换成第三方桌面
> 最终形态：`dist/HomeRedirector-v1.5.apk`（无障碍 HOME 重定向 + 开机救援 + 可开关遮罩）

---

## 一、结论速览

| 问题 | 结论 |
|---|---|
| 能否直接设置第三方桌面为默认 HOME | **不能**。ROM 在框架层硬绑原厂桌面，系统不走标准 HOME 解析 |
| `pm disable-user` 原厂桌面 | **被拒**（`SecurityException: uid 2000` 无权改系统包组件状态） |
| `pm hide` 原厂桌面 | 命令能成功，但**没用**（HOME 解析依旧 NPE，系统是硬启动原厂组件） |
| `pm uninstall --user 0` 原厂桌面 | **能成功**，但卸载后系统 goHome 抛 NPE，无桌面可显示 |
| Device Owner（`dpm set-device-owner`） | **不可用**。ROM 裁掉了 `android.software.device_admin` 特性 |
| 最终可行方案 | **无障碍服务拦截 HOME 键 + 拉起目标桌面**（免 root） |

**关键取舍**：原厂桌面**建议卸载**。装回它的话每次按 HOME 会先启动那个要联网 9 秒的空壳；卸载后由 HomeRedirector 直接拉起目标桌面，快得多。代价是重启后系统没有可启动的 HOME，必须靠 HomeRedirector 的开机救援兜底（v1.3 起已内置）。

---

## 二、设备基线（换设备前先对齐）

```
型号         DM40A7（大麦盒子）
平台         Amlogic p212 / S905X
系统         Android 6.0.1（SDK 23）
ROM          T-REX.FF.70.0808（海看 Hiveview / DomyUI4.2）
内存         838MB（MemFree 常年在 50MB 上下，很吃紧）
原厂桌面     com.hiveview.cloudscreen.launcher/.LauncherMainActivity
备份桌面     com.hiveview.smalllauncher（同样声明 HOME，是 ROM 的兜底桌面）
引导组件     com.android.provision（也声明 HOME）
adb 权限     shell（uid 2000），无 root，`adb root` 被拒
```

采集命令：

```bash
adb connect <IP>:5555
adb shell "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk"
adb shell "getprop ro.product.hiveviewrom"
adb shell "pm list packages | grep -iE 'launcher|desktop|home'"
adb shell "ls /system/app"
```

---

## 三、为什么常规换桌面全部失败（实测证据）

### 3.1 第三方桌面本身完全合法

解析两个 APK 的二进制 Manifest 对比：

| 应用 | Activity | 声明的 category |
|---|---|---|
| 原厂 DomyUI4.2 | `com.hiveview.domyui4.launcher.LauncherMainActivity` | MAIN + **HOME** + DEFAULT + LAUNCHER + LEANBACK_LAUNCHER |
| Emotn UI | `com.oversea.aslauncher.ui.main.MainActivity` | MAIN + **HOME** + DEFAULT + LAUNCHER + LEANBACK_LAUNCHER |

声明完全一致，安装后也确实进了系统 Activity 解析表，但系统 HOME 依旧给原厂桌面。

### 3.2 ROM 硬绑 HOME，不走标准解析

- `pm hide` 掉原厂桌面（shell 有权限）后按 HOME，**依然 NPE** —— 证明 AMS 是直接启动写死的组件，不做候选解析。
- 厂商属性 `hiveview.app.defaultactivity = com.hiveview.cloudscreen.launcher.NewHomeActivity`，而这个 Activity **在原厂 APK 里根本不存在**（真实声明 HOME 的是 `LauncherMainActivity`）。把它改成目标桌面组件后行为不变 —— 属性只是记录，不是真正驱动。

### 3.3 卸载原厂桌面后的真实表现

```
pm uninstall --user 0 com.hiveview.cloudscreen.launcher      → Success
am start -a android.intent.action.MAIN -c android.intent.category.HOME
  → java.lang.NullPointerException: Attempt to read from field
    'java.lang.String android.content.pm.PackageItemInfo.name' on a null object reference
```

屏幕全黑，第三方桌面完全不被采用；此时 `smalllauncher`、`provision` 这些同样声明 HOME 的候选，系统一个都不选。

### 3.4 Device Owner 为什么不行

```
dpm set-device-owner com.miaomiaotv.homesetter/.AdminReceiver
→ Can't set package as device owner

pm list features
→ 只剩 app_widgets / backup / print / webview
```

`android.software.device_admin` 特性被 ROM 整个裁掉，`DevicePolicyManager.mHasFeature=false`，所有 Device Owner API 直接不可用。要补回来得改 `/system/etc/permissions`，需要 root。

> 工具源码仍保留在 `tools/HomeSetter/`，在**没裁这个特性**的盒子上依然可用（它能用官方 API `addPersistentPreferredActivity` 直接把 HOME 指向目标桌面，那是更干净的方案，优先级更高）。

---

## 四、最终方案架构

```
┌──────────────────────────────────────────────────────────┐
│ HomeRedirector（无障碍服务，常驻）                        │
│                                                          │
│  ① onKeyEvent(HOME DOWN)                                 │
│       └→ 立即 startActivity(目标桌面)  ← 实测 60ms 内拉起  │
│                                                          │
│  ② 看门狗（每 120ms 轮询当前前台包名）                     │
│       ├→ 原厂桌面包出现 → 拉起目标桌面                     │
│       ├→ 目标桌面出现   → 撤遮罩 / 关闸                    │
│       └→ 连续 6 秒无任何窗口 → 开机救援，拉起目标桌面       │
│                                                          │
│  ③ 遮罩（可开关，SharedPreferences 持久化）               │
│       └→ "正在进入桌面…"，目标桌面出现自动消失（8 秒兜底）  │
└──────────────────────────────────────────────────────────┘
```

**为什么必须用无障碍**：本 ROM 下按 HOME 时前台**完全不变**（goHome 静默失败），没有任何可观测的窗口变化信号 —— 唯一的可靠信号就是 HOME 按键事件本身。

源码位置：`tools/HomeRedirector/src/com/miaomiaotv/homeredirector/`

| 文件 | 作用 |
|---|---|
| `HomeWatcher.java` | 无障碍服务：按键拦截、看门狗、开机救援、遮罩 |
| `BootReceiver.java` | BOOT_COMPLETED 双保险（可能被 ROM 自启动管控挡掉，不能只靠它） |
| `MainActivity.java` | 设置页：遮罩开关 + 状态显示 |
| `build.ps1` | 一键构建（aapt → javac → d8 → zipalign → apksigner） |

---

## 五、部署步骤（新设备通用）

### 5.1 前置检查

```bash
adb connect <IP>:5555
adb shell "getprop ro.build.version.sdk"          # 确认 ≥ 21
adb shell "pm list features | grep device_admin"   # 有则优先走 Device Owner 方案
```

### 5.2 安装目标桌面 + HomeRedirector

```bash
adb install -r -d emotnui.apk                       # 或当贝桌面
adb install -r -d HomeRedirector-v1.5.apk
```

### 5.3 启用无障碍服务（无需进设置页手工点）

```bash
adb shell "settings put secure accessibility_enabled 1"
adb shell "settings put secure enabled_accessibility_services \
  com.miaomiaotv.homeredirector/com.miaomiaotv.homeredirector.HomeWatcher"
```

验证（关键）：

```bash
adb shell "dumpsys accessibility | head -5"
# 应看到 services:{Service[label=HomeRedirector, ... capabilities=9, ...]}
# capabilities=9 = CAN_RETRIEVE_WINDOW_CONTENT(1) + CAN_FILTER_KEY_EVENTS(8)
adb logcat -d | grep HomeRedirector     # 应看到 "service connected"
```

### 5.4 换目标桌面（可选）

改 `HomeWatcher.java` 顶部两个常量，重编：

```java
public static final String TARGET_PKG = "com.oversea.aslauncher";
public static final String TARGET_CLS = "com.oversea.aslauncher.ui.main.MainActivity";
```

当贝桌面的值是：

```java
TARGET_PKG = "com.dangbei.tvlauncher"
TARGET_CLS = "com.dangbei.launcher.ui.main.MainActivity"
```

### 5.5 卸载原厂桌面（提速关键，但有前提）

```bash
adb shell "pm uninstall --user 0 com.hiveview.cloudscreen.launcher"
```

**前提**：HomeRedirector 已装好并启用（否则重启/按 HOME 会黑屏）。卸载后 HomeRedirector 的开机救援负责让系统有界面。

### 5.6 验证清单

```bash
# 1) 按 HOME 回桌面（用真实按键链路，见 6.1）
# 2) 打开应用不被顶掉：进设置等 7 秒，前台应仍是设置
adb shell "am start -n com.hiveview.cloudtv.settings/.SettingActivity; sleep 7; \
  dumpsys activity activities | grep -m1 mResumedActivity"
# 3) 重启后自动进桌面
adb reboot && sleep 120
adb shell "dumpsys activity activities | grep -m1 mResumedActivity"
```

---

## 六、必须知道的坑（血泪记录）

### 6.1 `input keyevent` 测不出按键事件，必须用 `sendevent`

`input keyevent 3` 是**注入事件**，不走无障碍的按键过滤链路，所以 `onKeyEvent` 永远收不到 —— 会误判成"功能失效"。**遥控器真按键和 `sendevent` 才走真实链路。**

```bash
# aml_keypad 支持 KEY_HOME(=102)；shell 属于 input 组，可直接写设备节点
adb shell "getevent -pl /dev/input/event0 | grep -i KEY_HOME"
adb shell "sendevent /dev/input/event0 1 102 1; sendevent /dev/input/event0 0 0 0; \
           sendevent /dev/input/event0 1 102 0; sendevent /dev/input/event0 0 0 0"
```

### 6.2 空窗口判定过激 → "打开任意应用立刻闪退回桌面"

应用冷启动瞬间 `getRootInActiveWindow()` 返回 null。如果把"无窗口"判定压到 2×120ms，**任何应用启动都会被误判成桌面丢失**，240ms 后就被拉回桌面，表现就是"打开应用闪退"。

修复：加**"期待窗口"闸门** —— 只有**刚按下 HOME 之后的 8 秒内**，空窗才允许触发兜底（`EXPECT_WINDOW_MS`）。普通应用启动的空窗不再触发。

### 6.3 不能只靠 BOOT_COMPLETED

`BootReceiver` 实测**有时完全收不到开机广播**（ROM 自启动管控），日志里一条记录都没有 —— 这就是"重启后彻底失效"的直接原因。

修复：把开机救援塞进**无障碍服务**里。无障碍服务是系统在 boot 时主动绑定的（实测开机 26 秒就连上），比任何广播都可靠。

### 6.4 卸载原厂桌面后重启 = 黑屏（最严重）

实测现象：uptime 205 秒时前台**没有任何 Activity**，焦点卡在 `KeyguardScrim`，遥控器完全没反应。

因为系统 goHome 找不到 HOME 组件，起不来任何界面。

修复：看门狗检测"**连续 6 秒完全没有窗口**"就主动拉起目标桌面，并且**持续重试**直到成功。实测开机时间线：

```
26s  service connected          ← 无障碍服务自动绑定
26s  foreground=android         ← 系统无桌面
33s  launch target from no-window-rescue   ← 救援触发
37s  foreground=com.oversea.aslauncher     ← 桌面显示
```

### 6.5 黑屏 ≠ 系统挂了（先查 HDMI）

用户反馈"桌面没出来，一直黑屏"时，实测：

```
截图 = 目标桌面应用列表页（渲染完全正常）
mWakefulness=Awake / Display Power=ON / mScreenState=ON
hpd_state=1（电视在线）/ EDID 校验通过 / mode=720p60hz
```

系统在正常输出，是**电视端没同步信号**（Amlogic 盒子重启后常见）。

排查顺序（从快到慢）：

1. 切电视输入源（切走再切回）
2. 重插 HDMI 线
3. 按遥控器方向键，有反应就说明系统正常，纯显示问题
4. 最后才重启盒子

### 6.6 这个 ROM 里电源键是"关机/重启"，不是"熄屏"

`input keyevent 26` 会让盒子**直接重启**（uptime 归零），不是简单的屏幕熄灭。想触发 HDMI 重新握手别用这招。

### 6.7 `ro.` 属性改不了

想用平台保活白名单 `ro.platform.keepbackground.app` 让目标桌面不被杀 —— `setprop` 静默失败（`ro.` 前缀只写一次）。

### 6.8 恢复原厂桌面必须带 `--user 0`

`pm uninstall --user 0` 卸载系统包后，普通 `adb install -r` **报 Success 但实际不生效**（`installed` 仍为 false、`pm path` 为空）。必须：

```bash
adb install -r -d --user 0 hiveview_launcher.apk
```

### 6.9 目标桌面无后台 Service，保活无门

Emotn UI 没有可绑定的 Service，无法通过 bindService 提升其进程优先级；它又是"云桌面"，退出后 Activity 很快被回收，每次回桌面都要重建（实测 3.0 秒）。当贝桌面重建更快（1.9 秒）。这是无 root 条件下的硬上限，只能靠换更轻的桌面缓解。

---

## 七、参数速查（`HomeWatcher.java`）

| 常量 | 当前值 | 含义 / 调整建议 |
|---|---|---|
| `TARGET_PKG` / `TARGET_CLS` | Emotn | 目标桌面组件 |
| `WATCHDOG_INTERVAL_MS` | 120 | 轮询间隔。再小收益有限，耗电上升 |
| `BLANK_THRESHOLD` | 2 | 期待窗口内的空窗判定次数（2×120ms≈240ms） |
| `EXPECT_WINDOW_MS` | 8000 | 按 HOME 后允许兜底介入的时长。**勿调太小**，否则应用冷启动会被误伤 |
| `NO_WINDOW_RESCUE_STREAK` | 50 | 连续无窗口次数判定（50×120ms≈6 秒）。**勿调太小**，否则慢速应用冷启动会被误伤 |
| `LAUNCH_COOLDOWN_MS` | 3500 | 重复拉起冷却期。太小会打断目标桌面冷启动 |
| `COVER_MAX_MS` | 8000 | 遮罩最长显示时间（兜底） |
| `KEY_SHOW_COVER` | 设置页可改 | 遮罩开关，SharedPreferences 持久化，默认开启 |
| `STOCK_PKGS` | 原厂桌面 + guide | 需要拦截/遮盖的原厂包名 |

---

## 八、恢复与后悔药

### 8.1 只想暂时关掉重定向

```bash
adb shell "settings put secure accessibility_enabled 0"
# 或者进 HomeRedirector 应用把遮罩关掉（重定向仍生效）
```

### 8.2 恢复原厂桌面（误卸载后救砖）

```bash
adb pull /system/app/DomyUI4.2_Launcher_4.0.98_201903131910/DomyUI4.2_Launcher_4.0.98_201903131910.apk
adb install -r -d --user 0 DomyUI4.2_Launcher_4.0.98_201903131910.apk
# 注意 --user 0 不能省
```

本地备份：`backups/hiveview_launcher.apk`

### 8.3 完全卸载 HomeRedirector

```bash
adb shell "settings put secure enabled_accessibility_services ''"
adb uninstall com.miaomiaotv.homeredirector
# 若原厂桌面已卸载，务必先按 8.2 装回来，否则重启黑屏
```

### 8.4 恢复出厂设置后

所有配置都会没有，重装 APK + 重新写无障碍设置即可，原厂桌面不受影响。

---

## 九、移植到其它设备的检查清单

- [ ] 确认 SDK ≥ 21，无 root 可用
- [ ] 检查 `pm list features` 是否有 `device_admin` —— **有就优先用 `tools/HomeSetter/`（Device Owner 方案更干净）**
- [ ] 确认原厂桌面包名/主 Activity，改 `STOCK_PKGS`
- [ ] 确认目标桌面组件名，改 `TARGET_PKG` / `TARGET_CLS`
- [ ] 用 `sendevent`（不是 `input keyevent`）验证按键链路是否通
- [ ] 确认 `capabilities` 包含 CAN_FILTER_KEY_EVENTS（值含 8）
- [ ] 测试"打开慢应用 7 秒不被顶掉"
- [ ] 测试"重启后能自动进桌面"
- [ ] 若该系统 `BootReceiver` 不可靠，确认开机救援（`NO_WINDOW_RESCUE_STREAK`）能兜住
- [ ] 记录该机型的 HDMI 特性（是否重启后需要重新握手）

---

## 十、交付物清单

```
dist/
  HomeRedirector-v1.5.apk          最终成品（当前设备在跑的就是它）
  HomeRedirector-v1.2.apk          历史版本（遮罩方案早期版）

tools/HomeRedirector/              正式方案源码
  AndroidManifest.xml
  src/com/miaomiaotv/homeredirector/
    HomeWatcher.java               核心：按键拦截 + 看门狗 + 开机救援 + 遮罩
    BootReceiver.java              开机广播双保险
    MainActivity.java              设置页（遮罩开关）
  res/values/strings.xml
  res/xml/accessibility_service_config.xml
  build.ps1                        一键构建（PowerShell）

tools/HomeSetter/                  Device Owner 方案（本机因缺 device_admin 特性未成功，其它机型可用）
  src/.../AdminReceiver.java
  src/.../MainActivity.java
  build.ps1

backups/hiveview_launcher.apk      原厂桌面备份（救砖用）
docs/HomeRedirector-方案与部署手册.md   本文档
```

### 构建说明

依赖：JDK 8+（实测 JDK 21 可用）、Android SDK build-tools **35.0.0**、platforms android-34、debug.keystore。

> 注意：build-tools **34.0.0 的 d8 有 bug**（R8 开发版），会出现内部异常；33.0.1 又与 JDK 21 不兼容。**用 35.0.0**。

```powershell
cd tools\HomeRedirector
powershell -ExecutionPolicy Bypass -File .\build.ps1
# 产物：build\HomeRedirector.apk
```

---

## 十一、版本历史

| 版本 | 变更 |
|---|---|
| 1.0 | 初版：检测原厂桌面出现 → 遮罩 + 拉起目标桌面 |
| 1.2 | 遮罩超时兜底、目标桌面稳定判定 |
| 1.3 | **修复重启后黑屏**：新增"连续无窗口 6 秒"开机救援（不再依赖 BOOT_COMPLETED）；修复空窗口误判导致"打开应用闪退"（引入 EXPECT_WINDOW 闸门） |
| 1.4 | 遮罩改为可配置（默认关闭） |
| 1.5 | 遮罩开关做进设置页（遥控器可操作、SharedPreferences 持久化）；开机救援时也显示遮罩 |

---

## 十二、实测性能数据（DM40A7）

| 指标 | 数值 |
|---|---|
| 按下 HOME → 发起拉起 | **63ms** |
| 拉起 → 桌面可见（Emotn 重建） | 2.8~3.1 秒 |
| 拉起 → 桌面可见（当贝重建） | ~1.9 秒 |
| 两者"任务仍是热的"时 | **0ms** |
| 开机 → 服务绑定 | 26 秒 |
| 开机 → 桌面可见 | 33~37 秒 |
