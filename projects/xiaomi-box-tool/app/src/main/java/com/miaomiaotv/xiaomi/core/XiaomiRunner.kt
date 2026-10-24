package com.miaomiaotv.xiaomi.core

import android.content.Context
import com.miaomiaotv.xiaomi.adb.AdbChannel
import java.io.File

/** 步骤名称（对外只展示这些中性名称，不含任何包名或命令） */
enum class Step(val label: String) {
    PREPARE("准备运行环境"),
    CONFIG("应用系统配置"),
    CLEAN_HOME("清理原装桌面"),
    SLIM("处理预装应用"),
    /** 收尾：安装配套应用 */
    EXTRAS("安装配套应用"),
    /** 最后：自举无障碍服务 + 把调试开关写成常开 */
    GUARD("启用自守护与调试保持")
}

/** 一次处理的最终结论 */
data class FlowResult(
    val totalSteps: Int,
    val okSteps: Int,
    val failedSteps: List<String>
) {
    val succeeded: Boolean get() = failedSteps.isEmpty()
}

/**
 * 处理流程执行器。
 *
 * 对外只通报「当前步骤」与「进度百分比」和最终结论，
 * 包名、命令、设备返回等信息一律走 [onDetail]（记到 logcat，供售后排查）。
 *
 * 安全边界（防变砖）：
 *  先把替代桌面（当贝）装好并设为默认，确认生效后才处理原厂桌面；
 *  若发现「处理完后设备将没有可用桌面」，该步会自动放弃；
 *  凡具备桌面能力的包在「处理预装应用」阶段一律跳过。
 *
 * 最后一步是安装配套应用。
 */
class XiaomiRunner(
    private val context: Context,
    private val channel: AdbChannel,
    private val targets: List<TargetPackage>,
    /** 本次型号使用的替代桌面定义（包名 + 入口组件） */
    private val desktop: XiaomiTargets.DesktopApp,
    /** 已释放到设备上的桌面安装包 */
    private val desktopApk: File?,
    private val companionApps: List<XiaomiTargets.PendingInstall>,
    private val onProgress: (label: String, percent: Int) -> Unit,
    private val onDetail: (String) -> Unit = {}
) {
    private val okSteps = mutableListOf<Step>()
    private val failedSteps = mutableListOf<Step>()
    private val totalSteps = 6
    private var completedSteps = 0

    /** 设备是否支持直接用命令设置默认桌面（安卓 6.0 起才有） */
    private var explicitSetHomeSupported = false

    fun run(model: BoxModel): FlowResult {
        return try {
            onDetail("处理对象：${model.label}")
            runWithDesktopReplacement(model)
            // 收尾统一拉到 100%，避免用户看到 99% 卡住
            onProgress(if (failedSteps.isEmpty()) "处理完成" else "处理结束", 100)
            FlowResult(totalSteps, okSteps.size, failedSteps.map { it.label })
        } catch (e: Exception) {
            onDetail("流程异常：${e.message}")
            markFailed(Step.PREPARE)
            onProgress("处理结束", 100)
            FlowResult(totalSteps, okSteps.size, failedSteps.map { it.label })
        }
    }

    // ===================== 一键处理（含更换桌面） =====================

    private fun runWithDesktopReplacement(model: BoxModel) {
        val installed = installedPackages()
        onDetail("设备当前安装应用数：${installed.size}")

        // 1) 先把替代桌面装好 —— 这是后面一切操作的前提
        begin(Step.PREPARE)
        if (!ensureDesktopInstalled(installed)) {
            markFailed(Step.PREPARE)
            onDetail("桌面未能就绪，已中止以避免设备无桌面可用")
            return
        }
        markOk(Step.PREPARE)

        // 2) 设为默认桌面
        begin(Step.CONFIG)
        if (configureDefaultHome()) markOk(Step.CONFIG) else markFailed(Step.CONFIG)

        // 3) 提前自举无障碍服务。
        //    卸掉原厂桌面后，系统可能再也找不到任何 HOME 组件（定制 ROM 尤其如此，
        //    开机时 goHome 直接抛异常、界面起不来）。先把救援能力装上再动桌面，
        //    万一后面出问题也有兜底。这一步失败不阻断主流程。
        AdbGuard.selfEnableAccessibility(channel) { onDetail(it) }

        // 4) 在确认安全的前提下处理原厂桌面
        begin(Step.CLEAN_HOME)
        if (cleanOriginalHomes()) markOk(Step.CLEAN_HOME) else markFailed(Step.CLEAN_HOME)

        // 4) 其余预装：桌面类能力项一律跳过，保留刚装好的桌面
        //    通用档（极光/海看等非小米机型）不套用小米清单：
        //    包名在别的品牌上本就不存在，而各家的「出厂桌面 / 复原推手」都不一样，
        //    拿小米清单去卸别家的包存在误删风险。
        if (XiaomiTargets.usesXiaomiSlimList(model)) {
            val homes = homePackages()
            val slimTargets = targets
                .filter { it.pkg != XiaomiTargets.SELF_PACKAGE }
                .filter { it.pkg != desktop.pkg }
                .filter { it.pkg !in XiaomiTargets.ORIGINAL_LAUNCHERS }
                .filter { it.pkg !in homes }

            slimAll(slimTargets, installedPackages())

            // 防复原加固：会话内复核（复活项补卸 + 默认桌面归属复查）+ 清理已下载的 OTA 升级残留
            reviewAndRepair()
            clearStagedOta()
        } else {
            begin(Step.SLIM)
            markOk(Step.SLIM)
            onDetail("通用机型：跳过预装清理，只做桌面接管与自守护")
        }

        // 5) 收尾：安装配套应用。
        //    这里刻意不拉起新桌面：桌面一被拉起，本工具就退到后台，
        //    用户既看不到完成弹窗，剩余步骤也可能因内存不足被系统回收。
        //    新桌面已在第 2 步设为默认，等用户点「知道了」时再跳转。
        if (XiaomiTargets.hasCompanions(model)) {
            installCompanions()
        } else {
            begin(Step.EXTRAS)
            markOk(Step.EXTRAS)
            onDetail("通用机型：无配套应用需要安装")
        }

        // 6) 最后：自举无障碍 + 把调试开关写成常开。
        //    放在最后是因为写 adb_enabled 可能触发 adbd 重启、当前连接会断；
        //    而自举成功后本工具由系统长期绑定，此后不再依赖这条连接。
        enableGuard()
    }

    /**
     * 自举 + 保持调试通道。
     *
     * 自举：用刚拿到的 shell 权限给自己的无障碍服务开权限，
     *      于是用户不必再去系统设置里手动点一次「允许」；
     *      服务起来后兼做 HOME 接管、开机救援与调试通道巡检。
     * 保持：把调试总开关写进 Global 设置（重启后依然保留），
     *      解决「盒子关机一次，ADB 就再也连不上」的问题。
     */
    private fun enableGuard(): Boolean {
        begin(Step.GUARD)

        val selfEnabled = AdbGuard.selfEnableAccessibility(channel) { onDetail(it) }
        val hardened = AdbGuard.harden(channel) { onDetail(it) }

        val ok = selfEnabled && hardened
        if (ok) {
            onDetail("自守护已接管：主页键与开机救援就绪，调试开关已常开")
        } else {
            onDetail(
                "自守护未完全就绪：自举=$selfEnabled 调试保持=$hardened"
            )
        }
        if (ok) markOk(Step.GUARD) else markFailed(Step.GUARD)
        return ok
    }

    // ===================== 核心处理 =====================

    /**
     * 按清单处理预装内容。
     *
     * 采用「批量下发命令，最后统一核对」的方式：
     * 老盒子上 pm 的返回文本格式并不统一，逐条解析容易误判，
     * 统一看最终状态最可靠，也把命令往返次数压到最低。
     *
     * 小米对 shell 的 disable-user 做了系统级拦截（PMS canBeDisabled：
     * 系统包 + callingUid==2000 直接抛 SecurityException），但
     * 「对用户卸载」走另一条代码路径、没有这层校验 —— 因此三轮策略：
     *  1) pm uninstall -k --user 0（保留数据，可用 install-existing 恢复）；
     *  2) 仍在的：pm uninstall --user 0（不保留数据再试）；
     *  3) 仍在的 FREEZE 目标：pm disable-user（非小米 ROM 有效，小米会拒绝）。
     */
    private fun slimAll(candidates: List<TargetPackage>, installed: Set<String>): Boolean {
        begin(Step.SLIM)
        val present = candidates.filter { it.pkg in installed }
        if (present.isEmpty()) {
            markOk(Step.SLIM)
            onDetail("没有需要处理的内容")
            return true
        }
        onDetail("需处理 ${present.size} 项")

        // ★ 清除安装限制单独处理，绝不进入下面的通用卸载链。
        //   通用链的兜底是 hide → disable-user，一旦作用在安装器上，
        //   纯净工厂版（安装器本就在 /system、从未被更新过）会被整包移除或停用，
        //   用户插 U 盘点安装就会直接失败 —— 比「有名单限制」更糟。
        val (installFixers, regular) = present.partition { it.action == Action.CLEAR_INSTALL }
        var clearOk = 0
        installFixers.forEach { if (clearInstallRestriction(it.pkg)) clearOk++ }

        // 复原推手直接连数据卸载（不带 -k）：其内部排队的"恢复预装/桌面"任务随数据一起消失；
        // 其余普通预装先带 -k 卸载（保留数据、可用 install-existing 恢复，更可逆）
        val (agents, normal) = regular.partition { it.pkg in XiaomiTargets.RESTORE_AGENTS }
        batchRun(agents.map { "pm uninstall --user 0 ${it.pkg}" })
        batchRun(normal.map { "pm uninstall -k --user 0 ${it.pkg}" })

        var stillThere = installedPackages()
        val remaining = regular.filter { it.pkg in stillThere }
        if (remaining.isNotEmpty()) {
            onDetail("第一轮未移除 ${remaining.size} 项，改用不保留数据方式重试")
            batchRun(remaining.map { "pm uninstall --user 0 ${it.pkg}" })
            stillThere = installedPackages()
        }

        // 卸载被拒的兜底尝试 hide（安卓 6 起可用，比禁用更彻底，命令不存在会自动失败）
        val hideable = regular.filter { it.pkg in stillThere }
        if (hideable.isNotEmpty()) {
            batchRun(hideable.map { "pm hide --user 0 ${it.pkg}" })
            stillThere = installedPackages()
        }

        val freezable = regular.filter { it.action == Action.FREEZE && it.pkg in stillThere }
        if (freezable.isNotEmpty()) {
            batchRun(freezable.map { "pm disable-user --user 0 ${it.pkg}" })
        }

        val finalInstalled = installedPackages()
        val disabled = disabledPackages()
        var removed = 0
        var frozen = 0
        var failed = 0
        // 注意：安装器回退原厂后依然「装着」（/system 原厂版在位），属正常，不计入失败。
        regular.forEach { target ->
            when {
                target.pkg !in finalInstalled -> removed++
                target.pkg in disabled -> frozen++
                else -> failed++
            }
        }
        onDetail("结果：清除安装限制 $clearOk/${installFixers.size}，卸载 $removed，停用 $frozen，未成功 $failed（共 ${present.size}）")

        val ok = failed == 0 && clearOk == installFixers.size
        if (ok) markOk(Step.SLIM) else markFailed(Step.SLIM)
        return ok
    }

    /**
     * 清除 APP 安装器的「安装名单限制」。
     *
     * 原理（小米盒子 4 / 安卓 6 实测）：
     *  小米 APP 安装器 com.android.packageinstaller（小定制为 MiTVPackageInstaller）位于
     *  /system/priv-app，是带 INSTALL_PACKAGES 权限的特权应用；它会被应用商店自动更新，
     *  新版（带安装名单）作为覆盖包装在 /data/app，原厂版（无名单）仍留在 /system/priv-app。
     *  只卸掉 /data 那层覆盖包，系统会自动回退到 /system 原厂版 —— 无需恢复出厂设置，
     *  且安装器仍在（特权身份保留），插 U 盘点安装照常工作。
     *
     * 安全边界：
     *  - 只有当安装器当前路径落在 /data（=存在更新覆盖包）时才卸载；
     *    纯净工厂版路径在 /system，本来就没有限制，此时绝不能卸载。
     *  - 卸载后【不】补 pm install 重新注册：那会把特权系统应用降级成普通 /data 应用，
     *    丢掉 INSTALL_PACKAGES 权限，用户点安装直接闪退（已实测踩坑）。
     */
    private fun clearInstallRestriction(pkg: String): Boolean {
        val path = runCatching { channel.shell("pm path $pkg") }.getOrDefault("")
        val hasUpdateOverlay = path.contains("/data/") || path.trim().startsWith("package:/data")
        if (!hasUpdateOverlay) {
            onDetail("安装器为原厂版（无名单限制），无需处理")
            return true
        }
        val output = runCatching { channel.shell("pm uninstall $pkg") }.getOrDefault("")
        val ok = output.contains("Success", ignoreCase = true)
        onDetail("清除安装限制${if (ok) "已回退原厂安装器" else "失败"}：${output.trim().take(60)}")
        return ok
    }

    /**
     * 会话内复核：部分系统服务在运行中会把刚卸载的包再装回去（install-existing），
     * 处理结束后再查两轮，发现原厂桌面或复原推手"复活"就立刻补卸（连数据）；
     * 顺带复查默认桌面归属，被抢回去就重新设置。
     */
    private fun reviewAndRepair() {
        for (round in 1..2) {
            val installed = installedPackages()
            val revived = (XiaomiTargets.ORIGINAL_LAUNCHERS + XiaomiTargets.RESTORE_AGENTS)
                .filter { it in installed }
            if (revived.isEmpty()) {
                onDetail("复核通过，无复活项（第 $round 轮）")
                break
            }
            onDetail("复核发现 ${revived.size} 项复活，立即补卸（第 $round 轮）")
            batchRun(revived.map { "pm uninstall --user 0 $it" })
        }
        if (!defaultHomeIsDesktop()) {
            onDetail("复核发现默认桌面被改回，重新设置")
            val resolved = resolveHomeComponent()
            runCatching {
                channel.shell("cmd package set-home-activity ${resolved ?: desktop.component}")
            }
        }
    }

    /**
     * 清理已下载的 OTA 升级残留。
     *
     * 升级通道（upgrade）已卸载，但之前可能已把整包升级下载到本地；
     * 这种包在 recovery 阶段直接刷入，不看升级 App 是否还在 —— 必须删掉。
     * 尽力而为：目录权限不足时命令会失败，不影响流程。
     */
    private fun clearStagedOta() {
        val commands = listOf(
            "rm -f /cache/*.zip /cache/fota*",
            "rm -rf /cache/download",
            "rm -rf /data/ota_package"
        )
        commands.forEach { cmd ->
            val output = runCatching { channel.shell("$cmd 2>/dev/null") }.getOrDefault("")
            onDetail("清理升级残留 $cmd：${output.trim().take(60).ifEmpty { "完成" }}")
        }
    }

    /**
     * 最后一步：安装配套应用（应用管家、助手类应用）。
     * 安装包已随本工具一起释放到设备上，直接用安装命令调用即可。
     */
    private fun installCompanions(): Boolean {
        begin(Step.EXTRAS)
        if (companionApps.isEmpty()) {
            markOk(Step.EXTRAS)
            return true
        }
        var installedCount = 0
        val before = installedPackages()
        for (app in companionApps) {
            if (app.pkg in before) {
                installedCount++
                continue
            }
            if (installApk(app.file, app.pkg)) installedCount++
        }
        onDetail("配套应用已就位 $installedCount/${companionApps.size}")

        val ok = installedCount == companionApps.size
        if (ok) markOk(Step.EXTRAS) else markFailed(Step.EXTRAS)
        return ok
    }

    /**
     * 处理原厂桌面。
     *
     * 清理候选 = 运行时探测到的桌面 + 已知原厂桌面清单。
     * 这样定制桌面壳、换过名的原厂桌面也不会漏掉。
     *
     * 边界：
     *  - 只清原厂与第三方桌面，系统级兜底桌面（com.android.* 等）永远保留；
     *  - 只有当「清掉之后设备上仍然留有可用桌面」时才真正执行：
     *    新桌面已装好可用，或设备上还有系统兜底桌面，二者满足其一才动手。
     */
    private fun cleanOriginalHomes(): Boolean {
        val installed = installedPackages()
        val homes = homePackages()

        // 关键校验：清理前确认系统认定的默认桌面已经是新桌面（resolve-activity 实查，
        // 比命令回显可靠）。安卓 6 老系统上桌面列表识别可能失败，这条是第二道安全通道。
        val defaultIsDesktop = defaultHomeIsDesktop()

        // 不支持直接设置默认桌面的设备（安卓 5.x）：先把其它第三方桌面停用，
        // 新桌面才能成为唯一可用桌面（系统兜底桌面保留不动）。
        // 这一步必须放在这里——即便后面没有可清理的原厂桌面，也不能跳过，
        // 否则老设备上会出现「桌面换了但开机还是进旧桌面」的情况。
        if (!explicitSetHomeSupported) disableCompetingHomes()

        val candidates = (homes + XiaomiTargets.ORIGINAL_LAUNCHERS)
            .filter { it != desktop.pkg }
            .filter { !isSystemFallbackHome(it) }
            .filter { it in installed }

        if (candidates.isEmpty()) {
            onDetail("未发现可清理的桌面，跳过")
            return true
        }
        onDetail("待清理桌面 ${candidates.size} 项")

        // 安全前提（满足其一才动手）：
        //  1) 清掉后仍有其它桌面存活；
        //  2) 新桌面已安装，且系统认定的默认桌面就是它（含桌面列表识别失败的情况）。
        val survivors = homes.filter { it !in candidates }
        val hasNewHome = desktop.pkg in survivors
        val hasSystemHome = survivors.any { isSystemFallbackHome(it) }
        val safe = hasNewHome || hasSystemHome || (defaultIsDesktop && desktop.pkg in installed)
        if (!safe) {
            onDetail("清理后设备将没有可用桌面，已放弃本次清理")
            return false
        }

        // 小米屏蔽了 shell 对系统包的 disable-user，但卸载路径没拦；
        // 原厂桌面直接连数据卸载，卸不掉再试 hide，最后才是 disable
        batchRun(candidates.map { "pm uninstall --user 0 $it" })

        var stillThere = installedPackages()
        val remaining = candidates.filter { it in stillThere }
        if (remaining.isNotEmpty()) {
            batchRun(remaining.map { "pm hide --user 0 $it" })
            stillThere = installedPackages()
        }
        val lastLeft = candidates.filter { it in stillThere }
        if (lastLeft.isNotEmpty()) {
            batchRun(lastLeft.map { "pm disable-user --user 0 $it" })
        }

        // 以最终状态为准统计：卸载消失的、或已处于停用状态的，都算处理到位
        val finalInstalled = installedPackages()
        val disabled = disabledPackages()
        val handled = candidates.count { it !in finalInstalled || it in disabled }
        onDetail("原装桌面处理 $handled/${candidates.size} 项")
        return handled > 0
    }

    /**
     * 停用除新桌面与系统兜底桌面之外的其它桌面。
     * 仅在没有「设置默认桌面」命令的老设备上使用，让新桌面自然成为唯一桌面。
     */
    private fun disableCompetingHomes() {
        val homes = homePackages()
        if (desktop.pkg !in homes) {
            onDetail("新桌面不在可用桌面之列，跳过停用其它桌面")
            return
        }
        val competitors = homes.filter {
            it != desktop.pkg && !isSystemFallbackHome(it)
        }
        if (competitors.isEmpty()) {
            onDetail("除新桌面外已无其它第三方桌面")
            return
        }
        onDetail("停用其它桌面 ${competitors.size} 个，确保新桌面成为唯一桌面")
        batchRun(competitors.map { "pm disable-user --user 0 $it" })
    }

    /** 系统级兜底桌面：任何时候都不清理、不停用 */
    private fun isSystemFallbackHome(pkg: String): Boolean =
        XiaomiTargets.SYSTEM_FALLBACK_HOME_PREFIXES.any { pkg.startsWith(it) }

    /**
     * 把默认桌面切到新桌面并确认结果。
     *
     * 版本差异很大：
     *  - 安卓 6.0 起有明确的设置命令（cmd package set-home-activity）；
     *  - 安卓 5.x 连 cmd 命令都没有，设置默认桌面只能靠
     *    「把所有其它桌面停用，让新桌面成为唯一可用桌面」来实现
     *    （这也是同类工具一贯的做法）。
     *
     * 因此这里只要求「新桌面确实是一个合法桌面」就算通过，
     * 真正的默认归属由下一步清理其它桌面来保证。
     */
    private fun configureDefaultHome(): Boolean {
        // 各版本桌面的入口 Activity 名不一致，写死容易落空；
        // 先用 resolve-activity 查系统认定的 HOME 组件，查到是新桌面就用查到的。
        val resolved = resolveHomeComponent()
        val component = resolved ?: desktop.component
        onDetail("使用桌面组件：$component")

        val output = runCatching {
            channel.shell("cmd package set-home-activity $component")
        }.onFailure {
            onDetail("设置默认桌面的命令未执行成功：${it.message}")
        }.getOrDefault("")
        onDetail("设置默认桌面返回：${output.trim().take(80)}")

        // 命令不存在时会回显 not found / Unknown command，
        // 命令存在但执行失败会回显 Error —— 这两种情况都要改用停用其它桌面的方式兜底。
        explicitSetHomeSupported = output.isNotBlank() &&
            !isCommandMissing(output) &&
            !output.contains("Error", ignoreCase = true)
        if (!explicitSetHomeSupported) {
            onDetail("该设备不支持直接设置默认桌面，改为停用其它桌面来确保新桌面生效")
        }

        // 记录桌面归属与「系统 HOME 是否可用」，供自守护服务判断要不要接管主页键。
        val className = desktop.component.substringAfterLast('/')
        DesktopState.save(context, desktop.pkg, className, !explicitSetHomeSupported)

        return desktop.pkg in homePackages()
    }

    /**
     * 查询系统当前认定的 HOME 组件（resolve-activity --brief，安卓 6 起可用）。
     * 只认新桌面的组件：查到的不是新桌面（例如还是原厂桌面）时返回 null，
     * 由调用方退回内置组件名。
     */
    private fun resolveHomeComponent(): String? {
        val output = runCatching {
            channel.shell("cmd package resolve-activity --brief -c android.intent.category.HOME")
        }.getOrDefault("")
        if (output.isBlank() || isCommandMissing(output)) return null
        val match = output.lineSequence()
            .map { it.trim() }
            .mapNotNull { HOME_COMPONENT.find(it) }
            .firstOrNull { it.groupValues[1] == desktop.pkg }
        return match?.value
    }

    /** 系统当前认定的默认桌面是否就是新桌面（用于清理原厂桌面前的安全校验） */
    private fun defaultHomeIsDesktop(): Boolean {
        val output = runCatching {
            channel.shell("cmd package resolve-activity --brief -c android.intent.category.HOME")
        }.getOrDefault("")
        val isDesktop = output.lineSequence()
            .map { it.trim() }
            .any { it.startsWith("${desktop.pkg}/") }
        onDetail("系统当前默认桌面为新桌面：$isDesktop")
        return isDesktop
    }

    /** 命令不存在时设备会回显这些字眼，不能当成正常的执行结果 */
    private fun isCommandMissing(output: String): Boolean {
        val text = output.trim()
        return text.contains("not found", ignoreCase = true) ||
            text.contains("Unknown command", ignoreCase = true) ||
            text.contains("inaccessible", ignoreCase = true) ||
            text.contains("No such file", ignoreCase = true)
    }

    /** 确认新桌面已安装；没有就用随本工具一起释放到设备上的安装包装上 */
    private fun ensureDesktopInstalled(installed: Set<String>): Boolean {
        if (desktop.pkg in installed) {
            onDetail("设备上已装有新桌面")
            return true
        }
        return installApk(desktopApk, desktop.pkg)
    }

    /**
     * 安装随工具分发的安装包。
     *
     * 为什么不直接安装外部存储上的路径：负责安装的系统进程在较新安卓上
     * 无权读取外部存储（会报 fuse 上下文无权限），安装必然失败。
     * 因此先让 shell 把安装包搬进它自己的目录（该目录系统进程可读），再执行安装；
     * 万一该目录不可用，再退回直接安装，兼容老设备。
     */
    private fun installApk(apk: File?, pkg: String): Boolean {
        if (apk == null || !apk.exists()) {
            onDetail("缺少安装包：$pkg")
            return false
        }
        val staged = "$STAGING_DIR/${apk.name}"
        runCatching { channel.shell("cp \"${apk.absolutePath}\" $staged") }
            .onFailure { onDetail("复制安装包到中间目录失败：${it.message}") }

        var output = runCatching { channel.shell("pm install -r $staged") }.getOrDefault("")
        onDetail("安装 $pkg 返回：${output.trim().take(80)}")

        if (!installedPackages().contains(pkg)) {
            output = runCatching {
                channel.shell("pm install -r \"${apk.absolutePath}\"")
            }.getOrDefault("")
            onDetail("改用直接路径安装 $pkg：${output.trim().take(80)}")
        }
        runCatching { channel.shell("rm -f $staged") }
        return installedPackages().contains(pkg)
    }

    /**
     * 多条命令合成一次往返，减少等待时间。
     *
     * 老设备上 adb 单次报文上限可能只有 4096 字节，
     * 因此这里按实际字符数切分，而不是简单按条数切分，避免超长被拒绝。
     */
    private fun batchRun(commands: List<String>) {
        if (commands.isEmpty()) return
        var batch = mutableListOf<String>()
        var length = 0
        for (command in commands) {
            val cost = command.length + 1
            if (batch.isNotEmpty() && length + cost > MAX_SHELL_COMMAND) {
                flushBatch(batch)
                batch = mutableListOf()
                length = 0
            }
            batch.add(command)
            length += cost
        }
        flushBatch(batch)
    }

    private fun flushBatch(batch: List<String>) {
        if (batch.isEmpty()) return
        runCatching { channel.shell(batch.joinToString(";")) }
            .onFailure { onDetail("部分命令中断：${it.message}") }
    }

    // ===================== 设备信息查询 =====================

    private fun parsePackages(command: String): Set<String> = runCatching {
        val output = channel.shell(command)
        if (isCommandMissing(output)) emptySet()
        else output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .toSet()
    }.getOrDefault(emptySet())

    private fun installedPackages(): Set<String> = parsePackages("pm list packages")

    /**
     * 已停用的包。老设备上 -d 参数可能不被支持，
     * 这时改用「全部包 - 已启用包」反推，保证停用状态能正确统计。
     */
    private fun disabledPackages(): Set<String> {
        val direct = parsePackages("pm list packages -d")
        if (direct.isNotEmpty()) return direct
        val enabled = parsePackages("pm list packages -e")
        if (enabled.isEmpty()) return emptySet()
        return installedPackages() - enabled
    }

    /**
     * 列出所有具备桌面（HOME）能力的包名。
     *
     * 命令随安卓版本变化：6.0 起用 cmd package query-activities，
     * 安卓 5.x 没有 cmd，只能用 pm query-intent-activities。
     * 两条都试，取第一个能拿到结果的；都拿不到就返回空集（调用方会走保守策略）。
     */
    private fun homePackages(): Set<String> {
        for (command in HOME_QUERIES) {
            val output = runCatching { channel.shell(command) }.getOrDefault("")
            if (output.isBlank() || isCommandMissing(output)) continue
            val packages = LinkedHashSet<String>()
            for (line in output.lineSequence()) {
                PACKAGE_FIELD.find(line)?.let { packages.add(it.groupValues[1]) }
                COMPONENT_LINE.find(line)?.let { packages.add(it.groupValues[1]) }
            }
            if (packages.isNotEmpty()) {
                onDetail("识别到桌面 ${packages.size} 个，来源：$command")
                return packages
            }
        }

        // 最后兜底：dumpsys 是所有安卓版本都有的命令，输出里能找到桌面过滤条件。
        // 老设备上前两条命令都不存在时，靠它保证桌面一定被识别出来。
        val dump = runCatching { channel.shell("dumpsys package") }.getOrDefault("")
        val fromDump = parseHomeFromDump(dump)
        if (fromDump.isNotEmpty()) {
            onDetail("识别到桌面 ${fromDump.size} 个，来源：dumpsys")
            return fromDump
        }
        return emptySet()
    }

    /**
     * 从 dumpsys package 的输出里找桌面。
     *
     * 输出结构（组件行在前，随后是过滤条件）：
     *     com.xxx/.Launcher
     *     Action: "android.intent.action.MAIN"
     *     Category: "android.intent.category.HOME"
     * 老版本安卓的组件行前面还带一串哈希值和 filter 字样，这里一并兼容。
     */
    private fun parseHomeFromDump(output: String): Set<String> {
        if (output.isBlank()) return emptySet()
        val packages = LinkedHashSet<String>()
        var pending = mutableListOf<String>()
        for (line in output.lineSequence()) {
            val text = line.trim()
            when {
                DUMP_COMPONENT.matches(text) -> {
                    DUMP_COMPONENT.matchEntire(text)?.let { pending.add(it.groupValues[1]) }
                }
                text.startsWith("Category:") -> {
                    if (text.contains("android.intent.category.HOME")) packages.addAll(pending)
                    pending = mutableListOf()
                }
            }
        }
        return packages
    }

    // ===================== 步骤记账与进度 =====================

    private fun begin(step: Step) {
        onProgress(step.label, percent())
    }

    private fun markOk(step: Step) {
        if (step !in okSteps) okSteps.add(step)
        completedSteps = okSteps.size + failedSteps.size
        onProgress(step.label, percent())
    }

    private fun markFailed(step: Step) {
        if (step !in failedSteps) failedSteps.add(step)
        completedSteps = okSteps.size + failedSteps.size
        onProgress(step.label, percent())
    }

    private fun percent(): Int = (completedSteps * 100 / totalSteps).coerceIn(0, 100)

    companion object {
        /** shell 自己的目录：系统安装进程可以读取这里的文件 */
        private const val STAGING_DIR = "/data/local/tmp"

        /** 单次下发的命令总长度上限：老设备的 adb 报文上限可能只有 4096 字节 */
        private const val MAX_SHELL_COMMAND = 3000

        /**
         * 查询具备桌面（HOME）能力的包。
         * 第一条是安卓 6.0 起的写法，第二条是安卓 5.x 也能用的老命令。
         */
        private val HOME_QUERIES = listOf(
            "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME",
            "pm query-intent-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
        )

        private val PACKAGE_FIELD = Regex("packageName=([A-Za-z0-9._]+)")
        private val COMPONENT_LINE = Regex("^\\s*([A-Za-z0-9._]+)/[.A-Za-z0-9_$]+")

        /** resolve-activity --brief 输出里的组件行：pkg/cls */
        private val HOME_COMPONENT = Regex("([A-Za-z0-9._]+)/([.A-Za-z0-9_$]+)")

        /** dumpsys 里的组件行：兼容新版纯组件写法与老版带哈希/filter 的写法 */
        private val DUMP_COMPONENT =
            Regex("""^\s*(?:[0-9a-f]{8}\s+)?([A-Za-z0-9._]+)/([.A-Za-z0-9_$]+)(?:\s+filter.*)?$""")
    }
}
