package com.miaomiaotv.xiaomi.core

import android.util.Log
import com.miaomiaotv.xiaomi.accessibility.AgentAccessibilityService
import com.miaomiaotv.xiaomi.adb.AdbChannel
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ADB 保持与自举。
 *
 * 本工具拿到 adb shell 权限后，真正值钱的两件事：
 *
 * 一、【自举】用自己的 shell 权限给自己开无障碍服务
 *   `settings put secure enabled_accessibility_services <自己的服务>`
 *   —— 于是工具不再依赖用户再去系统设置里手动点一次「允许」，
 *   一次授权（设备上的调试授权弹窗）之后，全流程可以自动闭环。
 *
 * 二、【保持】把 ADB 开关写进持久化设置，让它在关机重启后依然有效
 *   小米盒子 / 极光盒子这类设备在关机时会顺手把 ADB 关掉，
 *   表现就是「昨天还能连，今天连不上了」。
 *   真正的开关是 Settings.Global 里的几个键（重启后依然保留），
 *   这里统一把它们写成开启状态，并用 persist. 属性把调试端口也固定住。
 *
 * 全部改动都是可逆的：把对应值改回 0 即可恢复原状。
 */
object AdbGuard {

    private const val TAG = "AdbGuard"

    /**
     * 需要常开的全局设置。
     *
     *  - adb_enabled：调试总开关，就是系统「开发者选项 → USB 调试」背后的那个值
     *  - development_settings_enabled：开发者选项总开关，关掉它 adb_enabled 会被连带清零
     *  - adb_wifi_enabled：允许走网络调试（较新版本才有，老版本写进去也无副作用）
     *
     * 注意：这些都是 Global 段设置，写入 /data/data/system.xml，重启后依然保留。
     */
    private val GLOBAL_SETTINGS = listOf(
        "development_settings_enabled" to "1",
        "adb_enabled" to "1",
        "adb_wifi_enabled" to "1"
    )

    /**
     * 调试端口与 USB 形态的属性。
     *
     *  - service.adb.tcp.port 是运行时属性，改完立刻生效但重启即失效；
     *  - persist.adb.tcp.port 才是跨重启保留的那一份；
     *  - persist.sys.usb.config 让盒子重启时仍按「调试模式」初始化 USB。
     */
    private val PROPERTIES = listOf(
        "service.adb.tcp.port" to "5555",
        "persist.adb.tcp.port" to "5555",
        "persist.sys.usb.config" to "adb"
    )

    /**
     * 把 ADB 相关开关全部写成开启状态。
     *
     * 顺序有讲究：先写开发者选项总开关（它会连带影响 adb_enabled），
     * 再写 adb_enabled，最后才动属性。写完 adb_enabled 后系统可能重启 adbd，
     * 因此调用方若要继续用这条连接，应当重新连一次。
     */
    fun harden(channel: AdbChannel, onDetail: (String) -> Unit = {}): Boolean {
        var ok = true

        GLOBAL_SETTINGS.forEach { (key, value) ->
            val output = runCatching { channel.shell("settings put global $key $value") }
                .getOrDefault("")
            if (output.contains("Exception", ignoreCase = true) ||
                output.contains("Error", ignoreCase = true)
            ) {
                onDetail("写入全局设置 $key 失败：${output.trim().take(60)}")
                ok = false
            }
        }

        PROPERTIES.forEach { (key, value) ->
            val output = runCatching { channel.shell("setprop $key $value") }.getOrDefault("")
            // setprop 对某些属性会返回失败信息，但不致命：属性只是"锦上添花"，
            // 真正决定 ADB 是否可用的是上面的 Global 设置。
            if (output.contains("failed", ignoreCase = true)) {
                onDetail("设置属性 $key 未生效：${output.trim().take(60)}")
            }
        }

        val state = readGlobal(channel, "adb_enabled")
        onDetail("ADB 保持：adb_enabled=$state")
        if (state != "1") ok = false
        return ok
    }

    /**
     * 自举：用 shell 权限给自己的无障碍服务开权限。
     *
     * 为什么不直接写死：设备上可能已经启用了别的无障碍服务
     * （比如用户自己装的服务），直接覆盖会把它们关掉。这里先读回来再追加。
     */
    fun selfEnableAccessibility(
        channel: AdbChannel,
        onDetail: (String) -> Unit = {}
    ): Boolean {
        val self = AgentAccessibilityService.componentName()
        val current = runCatching {
            channel.shell("settings get secure enabled_accessibility_services")
        }.getOrDefault("").trim()

        val enabled = if (current.isBlank() || current == "null") {
            mutableListOf()
        } else {
            current.split(':').filter { it.isNotBlank() }.toMutableList()
        }

        if (enabled.contains(self)) {
            onDetail("自守护服务已处于启用状态")
            return true
        }
        enabled.add(self)
        val merged = enabled.joinToString(":")

        runCatching { channel.shell("settings put secure accessibility_enabled 1") }
        val output = runCatching {
            channel.shell("settings put secure enabled_accessibility_services '$merged'")
        }.getOrDefault("")
        if (output.contains("Exception", ignoreCase = true)) {
            onDetail("开启自守护服务失败：${output.trim().take(80)}")
            return false
        }

        val after = runCatching {
            channel.shell("settings get secure enabled_accessibility_services")
        }.getOrDefault("").trim()
        val ok = after.contains(AgentAccessibilityService.SERVICE_SIMPLE_NAME)
        onDetail(if (ok) "自守护服务已开启" else "自守护服务开启未生效：$after")
        return ok
    }

    /** 读取一个 Global 设置值 */
    fun readGlobal(channel: AdbChannel, key: String): String =
        runCatching { channel.shell("settings get global $key") }
            .getOrDefault("")
            .trim()

    /** 设备当前是否处于「调试已开启」状态 */
    fun isAdbEnabled(channel: AdbChannel): Boolean = readGlobal(channel, "adb_enabled") == "1"

    /**
     * 设备上是否存在 root 通道（su）。
     *
     * 小米盒子、极光盒子这类设备通常都带 su，
     * 有 root 就能在 adbd 端口没开的情况下强行把端口拉起来。
     */
    fun hasRoot(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val out = reader.readText()
        reader.close()
        process.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    /**
     * root 兜底：把调试端口强行拉起来。
     *
     * 适用场景：盒子重启后 adbd 干脆没监听 5555（不只是没授权），
     * 这时没有 shell 通道可用，只能靠 root 直接改属性并重启 adbd。
     */
    fun recoverAdbPortByRoot(onDetail: (String) -> Unit = {}): Boolean {
        if (!hasRoot()) {
            onDetail("设备无 root，无法用属性方式恢复调试端口")
            return false
        }
        var ok = true
        listOf(
            "setprop service.adb.tcp.port 5555",
            "setprop persist.adb.tcp.port 5555",
            "settings put global development_settings_enabled 1",
            "settings put global adb_enabled 1",
            "stop adbd",
            "start adbd"
        ).forEach { cmd ->
            val output = runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val text = reader.readText()
                reader.close()
                process.waitFor()
                text
            }.getOrDefault("")
            if (output.contains("Permission denied", ignoreCase = true) ||
                output.contains("not found", ignoreCase = true)
            ) {
                onDetail("root 执行 $cmd 失败：${output.trim().take(60)}")
                ok = false
            }
        }
        onDetail(if (ok) "已通过 root 恢复调试端口" else "root 恢复调试端口未完全成功")
        return ok
    }

    /**
     * 关闭 ADB（把设置恢复原状）。
     *
     * 用户不想留后门时用这个：把开关写回 0 即可。
     */
    fun disable(channel: AdbChannel, onDetail: (String) -> Unit = {}): Boolean {
        listOf("adb_enabled" to "0", "adb_wifi_enabled" to "0").forEach { (key, value) ->
            runCatching { channel.shell("settings put global $key $value") }
        }
        val state = readGlobal(channel, "adb_enabled")
        onDetail("ADB 已关闭：adb_enabled=$state")
        return state == "0"
    }
}
