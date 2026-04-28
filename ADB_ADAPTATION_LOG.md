# ADB 无线调试版本兼容适配记录

## 适配日期
2026-04-26

## 适配目标
使 ADB 无线调试功能支持 Android 5 (API 21) 到 Android 16+ 的所有版本。

## 参考项目
- `D:\apkbuild_yunpan` - 云盘 TV 应用

## 新增文件

### 1. AndroidVersionCompat.java
**路径**: `app/src/main/java/com/miaomiao/tv/AndroidVersionCompat.java`

**功能**: Android 版本兼容工具类

**主要功能**:
- 版本判断方法（`isAndroid11OrAbove()`, `isOreoOrAbove()` 等）
- 通知渠道管理（Android 8.0+ 需要）
- 连接超时配置（根据版本调整）
- 协议版本获取

### 2. AdbPairService.java
**路径**: `app/src/main/java/com/miaomiao/tv/AdbPairService.java`

**功能**: ADB 配对服务（支持 Android 5-16）

**主要功能**:
- 前台服务处理配对通知
- RemoteInput API 支持（Android 7.0+）
- 剪贴板备用方案（Android 5-6）
- 配对结果回调

### 3. AdbConnectionHelper.java
**路径**: `app/src/main/java/com/miaomiao/tv/AdbConnectionHelper.java`

**功能**: ADB 连接帮助类

**主要功能**:
- 跨版本连接管理
- Shell 命令执行
- 设备信息获取
- 应用管理操作

### 4. ADB_VERSION_COMPAT.md
**路径**: 项目根目录

**功能**: 版本兼容适配文档

**内容**:
- 版本差异分析
- 适配实现方案
- 使用说明
- 注意事项

## 修改文件

### 1. AdbHelper.java
**修改内容**:
- 添加版本兼容信息方法
- 更新 `pair()` 方法添加版本检查
- 更新 `connect()` 方法添加版本提示
- 添加 `checkIfPairingRequired()` 方法
- 添加更友好的错误信息

### 2. AdbCommandActivity.java
**修改内容**:
- `checkAdbStatus()` 添加版本信息显示
- `showConnectDialog()` 添加版本兼容提示

### 3. dialog_adb_connect.xml
**修改内容**:
- 添加 `tvHint` 提示文本视图

### 4. AndroidManifest.xml
**修改内容**:
- 添加 `AdbPairService` 服务声明

## 版本兼容矩阵

| 功能 | Android 5-6 | Android 7-10 | Android 11-12 | Android 13+ |
|-----|-------------|--------------|---------------|-------------|
| 通知渠道 | ❌ 不需要 | ❌ 不需要 | ✅ 需要 | ✅ 需要 |
| RemoteInput | ❌ 不支持 | ✅ 支持 | ✅ 支持 | ✅ 支持 |
| 配对码 | ❌ 不需要 | ❌ 不需要 | ✅ 需要 | ✅ 需要 |
| TLS | ❌ 不支持 | ❌ 不支持 | ✅ 支持 | ✅ 支持 |
| 协议版本 | v1 | v1 | v2 | v2/v3 |
| 连接超时 | 10秒 | 10秒 | 12秒 | 15秒 |

## 使用说明

### Android 5-10 使用流程
1. 用 USB 连接电脑
2. 执行 `adb tcpip 5555`
3. 断开 USB
4. 打开应用，连接 `127.0.0.1:5555`

### Android 11+ 使用流程
1. 用 USB 连接电脑
2. 执行 `adb pair 127.0.0.1:5555`
3. 输入设备显示的 6 位配对码
4. 执行 `adb connect 127.0.0.1:5555`
5. 断开 USB
6. 打开应用，连接 `127.0.0.1:5555`

## 待完善功能

1. **QR 码配对**: Android 13+ 支持 QR 码配对，尚未实现
2. **配对码自动获取**: 通过通知自动获取配对码
3. **设备发现**: mDNS 广播发现局域网内的 ADB 设备
4. **连接历史**: 保存和管理连接过的设备

## 依赖库

使用 `libadb-android` 库：
```gradle
implementation 'io.github.muntashirakon:libadb-android:1.3.3'
```

## 注意事项

1. **WRITE_SECURE_SETTINGS 权限**: 部分功能需要此权限，需要 ROOT
2. **网络权限**: 确保应用有 INTERNET 权限
3. **防火墙**: 确保目标设备的 5555 端口未被防火墙阻止
4. **同一网络**: 无线连接时设备需要在同一局域网内
