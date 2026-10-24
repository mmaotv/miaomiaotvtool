package com.miaomiaotv.xiaomi.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.miaomiaotv.xiaomi.adb.AdbChannel
import com.miaomiaotv.xiaomi.adb.AdbIdentity
import com.miaomiaotv.xiaomi.core.AdbGuard
import com.miaomiaotv.xiaomi.core.DesktopState
import java.io.File
import java.util.concurrent.Executors

/**
 * 自守护无障碍服务：本工具「一次授权、之后不用再管」的支点，兼三职：
 *
 * 一、HOME 键重定向：只在系统自己的 HOME 不管用时才出手。
 *    定制 ROM 把桌面写死在框架里，set-home-activity 改了也不生效，
 *    按 HOME 只会把那个空壳桌面拉起来。此时由本服务直接把真正的桌面拉起来；
 *    系统 HOME 正常时本服务完全不插手，不影响原生体验。
 *
 * 二、开机救援：原厂桌面被卸掉后，系统开机找不到任何 HOME 组件，
 *    会一直停在黑屏（焦点卡在 KeyguardScrim）。看门狗盯着，
 *    连续一段时间看不到任何窗口就主动把桌面拉起来。
 *
 * 三、ADB 保持：定期确认调试通道还在、调试开关还开着；
 *    被关掉了就重新打开，端口干脆没监听就借 root 把端口拉起来。
 */
class AgentAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var watchdog: Runnable? = null
    private var adbKeeper: Runnable? = null
    private var noWindowStreak = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "自守护服务已连接")
        startWatchdog()
        startAdbKeeper()
    }

    override fun onInterrupt() {
        Log.w(TAG, "自守护服务被中断")
    }

    /**
     * 无障碍事件本身用不上：判断「当前有没有界面」用 rootInActiveWindow 更直接，
     * 感知界面切换用 typeWindowStateChanged 事件反而更准。
     * 这里保留空实现以满足抽象方法要求。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 预留：将来若要做界面级联动（如识别投屏应用）可在此扩展
    }

    override fun onDestroy() {
        watchdog?.let { handler.removeCallbacks(it) }
        adbKeeper?.let { handler.removeCallbacks(it) }
        worker.shutdownNow()
        super.onDestroy()
    }

    /** 只观察、不拦截（始终返回 false），系统原本的 HOME 行为不受影响 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_HOME && event.action == KeyEvent.ACTION_DOWN) {
            onHomeKeyDown()
        }
        return false
    }

    private fun onHomeKeyDown() {
        val state = DesktopState.read(this)
        if (!state.homeRedirectRequired) return
        if (state.targetComponent() == null) {
            Log.w(TAG, "未记录目标桌面，忽略 HOME 键")
            return
        }
        if (state.targetPackage == currentForegroundPackage()) return
        Log.i(TAG, "系统 HOME 无效，直接拉起目标桌面：${state.targetPackage}")
        launchTarget("homekey")
    }

    private fun startWatchdog() {
        val task = object : Runnable {
            override fun run() {
                try {
                    checkForMissingDesktop()
                } catch (e: Throwable) {
                    Log.w(TAG, "看门狗异常：${e.message}")
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        watchdog = task
        handler.postDelayed(task, WATCHDOG_INTERVAL_MS)
    }

    /**
     * 连续一段时间看不到任何窗口，就认为系统没有桌面可显示。
     * 阈值必须远大于普通应用冷启动的窗口空白时间（最长约 3 秒），
     * 否则会把用户刚打开的应用顶掉。
     */
    private fun checkForMissingDesktop() {
        if (currentForegroundPackage() != null) {
            noWindowStreak = 0
            return
        }
        noWindowStreak++
        if (noWindowStreak >= NO_WINDOW_STREAK) {
            noWindowStreak = 0
            if (DesktopState.read(this).targetComponent() == null) return
            Log.i(TAG, "连续无窗口，判定系统没有桌面，执行救援")
            launchTarget("no-window-rescue")
        }
    }

    private fun currentForegroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (e: Throwable) {
        null
    }

    private fun launchTarget(reason: String) {
        val state = DesktopState.read(this)
        val component = state.targetComponent() ?: return
        try {
            val intent = Intent()
                .setComponent(ComponentName(state.targetPackage, component))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            startActivity(intent)
            Log.i(TAG, "拉起目标桌面 ${state.targetPackage}（$reason）")
        } catch (e: Throwable) {
            Log.w(TAG, "拉起目标桌面失败：${e.message}")
        }
    }

    private fun startAdbKeeper() {
        val task = object : Runnable {
            override fun run() {
                handler.postDelayed(this, ADB_KEEP_INTERVAL_MS)
                worker.execute { checkAdbHealth() }
            }
        }
        adbKeeper = task
        handler.postDelayed(task, ADB_KEEP_INTERVAL_MS)
    }

    /**
     * 确认调试通道还活着、调试开关还开着：
     *  1. 通道正常且开关开着 —— 不用管；
     *  2. 通道正常但开关被关 —— 重新写回开启；
     *  3. 通道连不上 —— 可能 adbd 没监听端口，借 root 把端口拉起来。
     */
    private fun checkAdbHealth() {
        val channel = AdbChannel()
        // 静默探测：未授权时直接放弃，不在设备上弹出授权窗口打扰用户
        channel.probeOnly = true
        try {
            val identity = AdbIdentity(File(filesDir, KEYS_DIR))
            channel.attachIdentity(identity)
            channel.connect(ADB_HOST, ADB_PORT, ADB_CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "调试通道不可用，尝试恢复端口：${e.message}")
            AdbGuard.recoverAdbPortByRoot { Log.i(TAG, it) }
            runCatching { channel.disconnect() }
            return
        }
        try {
            if (!AdbGuard.isAdbEnabled(channel)) {
                Log.w(TAG, "调试开关已被关闭，重新写回")
                AdbGuard.harden(channel) { Log.i(TAG, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查调试开关失败：${e.message}")
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    companion object {
        private const val TAG = "AgentGuard"
        private const val KEYS_DIR = "keys"
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555
        private const val ADB_CONNECT_TIMEOUT_MS = 4000

        /** 看门狗轮询间隔 */
        private const val WATCHDOG_INTERVAL_MS = 1500L

        /**
         * 连续无窗口多少次才判定为「系统没有桌面可显示」。
         * 1500ms × 6 ≈ 9 秒，远大于普通应用冷启动的窗口空白时间，不会误伤。
         */
        private const val NO_WINDOW_STREAK = 6

        /** ADB 巡检间隔：本地 socket 很轻，一分钟一次足够 */
        private const val ADB_KEEP_INTERVAL_MS = 60_000L

        /** 本工具自身包名 */
        const val PACKAGE_NAME = "com.miaomiaotv.xiaomi"

        /** 无障碍服务类名，settings 里登记用的就是它 */
        const val SERVICE_SIMPLE_NAME =
            "com.miaomiaotv.xiaomi.accessibility.AgentAccessibilityService"

        /** 完整组件名，格式为 包名/类名 */
        fun componentName() = "$PACKAGE_NAME/$SERVICE_SIMPLE_NAME"
    }
}
