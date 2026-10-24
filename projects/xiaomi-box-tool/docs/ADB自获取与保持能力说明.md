# ADB 自获取与调试保持

> 小米盒子工具箱（TV 版）新增能力说明
> 适用：Android 5.x ~ 9.x 的电视盒子（小米盒子 4 / 4S Pro 等）

---

## 一、要解决的两个问题

### 问题 1：换桌面必须依赖另一台电脑/手机

以往给电视盒子换桌面，需要用电脑连上盒子的 ADB，一步步敲命令：
装桌面、设默认、卸原厂桌面……盒子不在手边、或者没有电脑，就做不了。

**本工具自带 ADB 客户端**，直接在电视上运行，连的是它自己的调试端口
（`127.0.0.1:5555`），自己给自己授权、自己执行整套流程。

### 问题 2：盒子一关机，ADB 就再也连不上

小米盒子、极光盒子这类设备在关机时会顺手把 ADB 关掉，
表现就是「昨天还能连，今天连不上了」，得去设置里重新打开一次开发者选项。

**本工具会把调试开关写进持久化设置**，并在开机后自动巡检、被关掉就自动打开。

---

## 二、用户需要做的操作

只有一步：

> 打开工具 → 设备上弹出「是否允许 USB 调试」→ **点「允许」**

之后全部自动完成，**不需要任何电脑或手机**。

授权之所以只需要点一次，是因为本工具的 RSA 私钥持久化在应用私有目录，
设备记住的是这把钥匙，之后每次连接都是「已授权」状态。

---

## 三、能力构成

```
主界面
  释放内置安装包 → 连接本机调试端口 → 授权 → 一键处理
                     ↓
处理流程（6 步）
  1 准备：装上替代桌面
  2 配置：设为默认桌面（记录系统 HOME 是否可用）
  3 清理：卸掉原厂桌面（带防变砖校验）
  4 精简：清掉广告推送等预装
  5 配套：安装应用管家/小草助手/当贝市场
  6 自守护：★ 本次新增 ★
      · 自举：settings put secure enabled_accessibility_services
        <本工具自己的服务>  → 自己给自己开无障碍
      · 保持：settings put global adb_enabled 1 等
                     ↓ 第 6 步之后由系统长期绑定
自守护无障碍服务（开机自动绑定，常驻）
  1 HOME 键接管：仅在系统 HOME 被写死时才出手
  2 开机救援：连续看不到任何界面就把桌面拉起来
  3 ADB 巡检：每分钟确认通道与开关，异常自动修复
```

---

## 四、关键实现

### 4.1 自举：自己给自己开无障碍

拿到 shell 权限后执行：

```bash
settings put secure accessibility_enabled 1
settings put secure enabled_accessibility_services <本工具>/<服务类名>
```

执行者是 adb shell（uid 2000），该身份有 WRITE_SECURE_SETTINGS 权限，
所以这一句是**静默生效**的，不会再弹系统确认框。

实现时先读回已启用的服务列表再追加，避免把用户自己装的无障碍服务关掉。

### 4.2 保持：把调试开关写成常开

```bash
settings put global development_settings_enabled 1
settings put global adb_enabled 1
settings put global adb_wifi_enabled 1
setprop service.adb.tcp.port 5555
setprop persist.adb.tcp.port 5555
setprop persist.sys.usb.config adb
```

- Settings.Global 写进 /data/data/system.xml，**重启后依然保留**；
  真正决定「开发者选项 → USB 调试」开关的就是 adb_enabled 这个值。
- service.adb.tcp.port 是运行时属性（改完立刻生效、重启失效），
  persist.adb.tcp.port 才是跨重启保留的那一份，两个都写才稳。
- 全部可逆：把值改回 0 即可恢复原状（工具内也提供了关闭入口）。
### 4.3 开机后的自动恢复

两条腿走路：

| 通道 | 可靠性 | 说明 |
|---|---|---|
| 无障碍服务 | 高 | 系统开机时**一定会**主动绑定已登记的无障碍服务 |
| BOOT_COMPLETED 广播 | 低 | 定制 ROM 常常直接拦掉（实测海看 ROM 就收不到） |

因此主力是无障碍服务里的巡检：

- 每 60 秒静默探测一次调试通道；
- 开关被关 → 重新写回开启；
- 端口干脆没监听 → 借 su 把 service.adb.tcp.port 拉起来并重启 adbd
  （小米盒子/极光盒子通常带 root，这条兜底才有机会生效）。

### 4.4 静默探测：绝不打扰用户

后台巡检时如果设备还没授权过本工具，一旦出示公钥就会在电视上弹出
「是否允许 USB 调试」，每分钟弹一次会让人崩溃。

因此 AdbChannel 增加了 probeOnly 模式：**只验证是否已授权，绝不出示公钥**，
未授权就安静放弃，设备那边不会有任何弹窗。

### 4.5 HOME 键接管：只在必要时出手

处理流程里已经知道 set-home-activity 是否生效：

- **生效**（普通 ROM）：系统 HOME 一切正常，自守护服务完全不插手；
- **不生效**（把桌面写死在框架里的定制 ROM）：服务收到 HOME 键后
  直接把真正的桌面拉起来。

之所以必须用按键事件而不是窗口事件：这类 ROM 下按 HOME 时
系统 goHome 会静默失败，前台窗口**根本不会变化**，
窗口事件收不到，只有按键事件收得到。

### 4.6 开机救援

原厂桌面被卸掉后，系统开机时找不到任何 HOME 组件，
会一直停在黑屏（前台没有任何 Activity，焦点卡在 KeyguardScrim）。

自守护服务的看门狗盯着这个状态：连续 9 秒看不到任何窗口，
就主动把目标桌面拉起来。阈值远大于普通应用冷启动的窗口空白时间（最长约 3 秒），
不会把用户刚打开的应用顶掉。

---

## 五、改动清单

| 文件 | 类型 | 说明 |
|---|---|---|
| core/AdbGuard.kt | 新增 | ADB 保持、自举、root 兜底、状态读取 |
| core/DesktopState.kt | 新增 | 目标桌面与「系统 HOME 是否可用」的持久化 |
| accessibility/AgentAccessibilityService.kt | 新增 | 自守护服务：HOME 接管 + 开机救援 + ADB 巡检 |
| receiver/AdbBootReceiver.kt | 新增 | 开机自启自检（双保险之一） |
| adb/AdbChannel.kt | 修改 | 增加 probeOnly 静默探测模式 |
| core/XiaomiRunner.kt | 修改 | 新增第 6 步「启用自守护与调试保持」；记录桌面归属 |
| MainActivity.kt | 修改 | 状态栏显示自守护与调试保持状态 |
| AndroidManifest.xml | 修改 | 无障碍服务声明、开机广播接收器、相关权限 |
| res/xml/accessibility_service_config.xml | 新增 | 无障碍服务能力声明 |
| res/values/strings.xml | 修改 | 相关文案 |

---

## 六、已知限制

1. **首次仍需点一次「允许」**。设备上的调试授权弹窗是系统行为，无法绕过；
   之后本工具的私钥被设备记住，不再需要。
2. **端口彻底没监听时，无 root 设备无法自救**。这时只能借 su；
   没有 root 的盒子需要人工进设置打开一次开发者选项。
3. **部分 ROM 会在开机时主动把 adb_enabled 清零**。
   本工具的巡检会在服务起来后（开机约 30 秒内）重新写回，
   但「从开机到服务起来」这段时间内 ADB 是不可用的。
4. **不处理 Android 11+ 的无线调试（pairing 流程）**，仅覆盖老式的 5555 端口方式。
5. **开启调试开关是系统级改动**，介意的话可在工具内关闭，或手动把
   adb_enabled 改回 0。

---

## 七、相关文档

- 桌面处理与防变砖设计：见工程内 XiaomiRunner.kt 的分段注释
- ADB 协议实现细节：见 adb/AdbChannel.kt
- 硬绑定桌面的应对（另一条技术路线）：见 home-redirector 项目