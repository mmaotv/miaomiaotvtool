package com.miaomiaotv.xiaomi.core

import android.content.Context

/**
 * 桌面状态：把「这台设备最后被换成了什么桌面」「系统自己的 HOME 还能不能用」
 * 持久化下来，供自守护服务读取。
 *
 * 无障碍服务可能在设备重启后才被系统拉起，那时界面的处理流程早跑完了，
 * 服务必须能自己知道该把用户带到哪个桌面。
 */
data class DesktopState(
    /** 目标桌面包名 */
    val targetPackage: String,
    /** 目标桌面入口组件的类名 */
    val targetClass: String,
    /**
     * 系统自己的 HOME 是否被写死（true 时必须由无障碍服务代劳）。
     *
     * 判定依据是处理流程里 set-home-activity 的执行结果：
     * 命令不存在或报错，就说明这个 ROM 不认标准 HOME 解析。
     */
    val homeRedirectRequired: Boolean
) {
    /** 完整组件名 */
    fun targetComponent(): String? =
        if (targetPackage.isBlank() || targetClass.isBlank()) null else targetClass

    companion object {
        private const val PREFS = "agent_state"
        private const val KEY_PKG = "target_desktop_pkg"
        private const val KEY_CLS = "target_desktop_cls"
        private const val KEY_REDIRECT = "home_redirect_required"

        /** 写入状态（处理流程结束时调用） */
        fun save(
            context: Context,
            targetPackage: String,
            targetClass: String,
            homeRedirectRequired: Boolean
        ) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PKG, targetPackage)
                .putString(KEY_CLS, targetClass)
                .putBoolean(KEY_REDIRECT, homeRedirectRequired)
                .apply()
        }

        /** 读取状态；没有记录时返回空状态，服务据此保持沉默 */
        fun read(context: Context): DesktopState {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DesktopState(
                targetPackage = prefs.getString(KEY_PKG, "") ?: "",
                targetClass = prefs.getString(KEY_CLS, "") ?: "",
                homeRedirectRequired = prefs.getBoolean(KEY_REDIRECT, false)
            )
        }

        /** 是否已经记录过目标桌面 */
        fun hasTarget(context: Context): Boolean = read(context).targetComponent() != null
    }
}
