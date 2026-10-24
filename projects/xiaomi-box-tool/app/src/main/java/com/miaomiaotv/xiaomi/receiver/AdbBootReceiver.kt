package com.miaomiaotv.xiaomi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.miaomiaotv.xiaomi.adb.AdbChannel
import com.miaomiaotv.xiaomi.adb.AdbIdentity
import com.miaomiaotv.xiaomi.core.AdbGuard
import java.io.File

/**
 * 开机自启：设备启动后立刻确认一次调试通道与调试开关。
 *
 * 这是「双保险」中的第一重 —— 真正长期可靠的是自守护无障碍服务
 * （系统开机一定会主动绑定它，比开机广播可靠得多，
 *  定制 ROM 常常直接把 BOOT_COMPLETED 拦掉）。
 *
 * 这里刻意采用静默探测：只验证「设备是否已经授权本工具」，
 * 不会出示公钥，因此不会在开机时莫名其妙弹出「是否允许 USB 调试」。
 */
class AdbBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "收到 ${intent.action}，开始自检调试通道")
        val appContext = context.applicationContext
        Thread { ensureAdb(appContext) }.start()
    }

    companion object {
        private const val TAG = "AdbBoot"

        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val KEYS_DIR = "keys"

        /** 静默确认调试通道；已授权就把调试开关重新写成常开 */
        fun ensureAdb(context: Context) {
            val channel = AdbChannel()
            // 静默模式：未授权时直接放弃，绝不触发设备上的授权弹窗
            channel.probeOnly = true
            try {
                val identity = AdbIdentity(File(context.filesDir, KEYS_DIR))
                channel.attachIdentity(identity)
                channel.connect(ADB_HOST, ADB_PORT, CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "调试通道未就绪：${e.message}")
                // 端口可能干脆没监听，借 root 把端口拉起来
                AdbGuard.recoverAdbPortByRoot { Log.i(TAG, it) }
                runCatching { channel.disconnect() }
                return
            }

            try {
                if (!AdbGuard.isAdbEnabled(channel)) {
                    Log.w(TAG, "调试开关被关，重新写回常开")
                    AdbGuard.harden(channel) { Log.i(TAG, it) }
                } else {
                    Log.i(TAG, "调试通道与开关均正常")
                }
            } catch (e: Exception) {
                Log.w(TAG, "自检失败：${e.message}")
            } finally {
                runCatching { channel.disconnect() }
            }
        }
    }
}