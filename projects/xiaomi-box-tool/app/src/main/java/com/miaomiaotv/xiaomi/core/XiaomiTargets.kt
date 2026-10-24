package com.miaomiaotv.xiaomi.core

import java.io.File

/**
 * ADB 层面的两种处理方式。
 *
 * 小米在系统层（PMS canBeDisabled）屏蔽了 shell 对系统包的 disable-user，
 * 但「对用户卸载」路径没有这层限制，因此两种方式的实现主力都是卸载：
 *
 * REMOVE：pm uninstall -k --user 0（保留数据、可恢复），失败再补一发不带 -k 的卸载。
 * FREEZE：同上先卸载；实在卸不掉的（极少数）最后才试 disable-user（小米会拒绝，属正常）。
 *         卸载与冻结都不会删除系统分区文件，均为可逆操作。
 */
enum class Action {
    REMOVE,
    FREEZE,

    /**
     * 清除安装限制：处理 APP 安装器（com.android.packageinstaller）的「安装名单限制」。
     *
     * 小米 APP 安装器会被应用商店自动更新成带名单限制的版本（覆盖包装在 /data/app），
     * 原厂版（无名单限制、且是带 INSTALL_PACKAGES 权限的特权应用）留在 /system/priv-app。
     * 这一项只卸掉 /data 那层更新覆盖包，系统即自动回退到原厂版 —— 用户无需恢复出厂设置，
     * 且安装器仍在（特权身份保留），插 U 盘点安装照常工作。
     *
     * 与 REMOVE/FREEZE 完全不同：它【不能】走通用的「卸载 → hide → disable」兜底链。
     * 兜底一旦作用在安装器上，在从未被更新过的纯净工厂版上会把安装器整包移除或停用，
     * 用户从此再也装不了软件 —— 比"有名单限制"更糟。
     */
    CLEAR_INSTALL
}

data class TargetPackage(val pkg: String, val name: String, val action: Action)

/** 工具开放给用户的两个处理对象（对应主界面两张卡片） */
enum class BoxModel(val label: String) {
    /** 安卓 9 系列：小米盒子 4S Pro / 4S Pro Max，桌面替换为 Emotn UI，另装原生系统设置 */
    MIBOX_4SPRO("小米盒子4SPro"),

    /** 安卓 6 系列：小米盒子 4 / 4 SE / 4C / 4S，桌面替换为当贝桌面，配套小草助手 + 当贝市场 */
    MIBOX_4("小米盒子4"),

    /**
     * 通用档：极光盒子、海看（大麦）等非小米机型。
     *
     * 只做三件事：换桌面 + 自守护（HOME 接管 / 开机救援）+ 调试通道保持。
     * 不套用小米的预装清理清单 —— 各家「出厂桌面 / 复原推手」完全不同，
     * 拿小米清单去卸别家的包有误删风险，而清单里的包名在别的品牌上本就不存在。
     */
    GENERIC("其他盒子")
}

/**
 * 小米盒子的处理清单。
 *
 * 清单来源：实机验证过的小米盒子批处理命令（小米3 / 4spro 批处理 + 长期维护积累）。
 * 运行时只会处理设备上真实存在的包，清单里没有的项会自动跳过，不做无意义的尝试。
 */
object XiaomiTargets {

    /**
     * 一、小米通用清单（安卓 6 / 9 两代盒子共有的预装）。
     * 广告、推送、商城、支付、统计上报类为主，全部为实机验证过的处理目标。
     */
    val COMMON_APPS: List<TargetPackage> = listOf(
        TargetPackage("com.mitv.gallery", "小米电视相册/画报", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.tvmanager", "小米电视管家", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.upgrade", "小米系统升级（复活源头）", Action.REMOVE),
        TargetPackage("mitv.service", "MITV 基础服务（重装桌面推手，两型号必卸）", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.advertise", "小米电视广告服务（复活源头）", Action.REMOVE),
        TargetPackage("com.xiaomi.mibox.lockscreen", "小米盒子锁屏", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.advertise", "小米电视广告服务", Action.REMOVE),
        TargetPackage("com.duokan.airkan.tvbox", "多看投送(TV)", Action.REMOVE),
        TargetPackage("com.xiaomi.devicereport", "小米设备报告(上报)", Action.REMOVE),
        TargetPackage("com.xiaomi.mimusic2", "小米音乐2", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.pay", "小米电视支付", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.payment", "小米电视支付服务", Action.REMOVE),
        TargetPackage("com.mipay.wallet.tv", "小米钱包(电视版)", Action.REMOVE),
        TargetPackage("com.xiaomi.screenrecorder", "小米屏幕录制", Action.REMOVE),
        TargetPackage("com.miui.daemon", "MIUI 守护进程", Action.REMOVE),
        TargetPackage("com.xiaomi.tv.nameservice", "小米名称服务", Action.REMOVE),
        TargetPackage("com.xiaomi.tweather", "小米天气(TV)", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.calendar", "小米电视日历", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.shop", "小米商城(TV)", Action.REMOVE),
        TargetPackage("com.svox.pico", "TTS 语音引擎", Action.REMOVE),
        TargetPackage("com.droidlogic", "Amlogic 平台组件", Action.REMOVE),
        TargetPackage("com.mitv.appstore", "小米应用商店(旧)", Action.REMOVE),
        TargetPackage("com.mitv.screensaver", "小米屏保", Action.REMOVE),
        TargetPackage("com.xiaomi.tv.appupgrade", "小米应用升级", Action.REMOVE),
        TargetPackage("com.xiaomi.mibox.gamecenter", "小米盒子游戏中心", Action.REMOVE),
        TargetPackage("com.xiaomi.gamecenter.sdk.service.mibox", "小米游戏SDK服务(盒子)", Action.REMOVE),
        TargetPackage("com.mi.miplay.mitvupnpsink", "UPnP 投屏接收", Action.REMOVE),
        TargetPackage("com.mitv.alarmcenter", "小米电视闹钟中心", Action.REMOVE),
        TargetPackage("com.xiaomi.tv.gallery", "小米电视相册", Action.REMOVE),
        TargetPackage("com.miui.systemAdSolution", "MIUI 系统广告方案（复活源头）", Action.REMOVE),
        TargetPackage("com.duokan.videodaily", "多看视频日报", Action.REMOVE),
        TargetPackage("com.jiajia.yundonghui.mitv", "运动汇(TV)", Action.REMOVE),
        TargetPackage("com.miui.cloudservice", "小米云服务", Action.REMOVE),
        TargetPackage("com.baidu.tv.download", "百度电视下载", Action.REMOVE),
        TargetPackage("com.duokan.application.photoviewer", "多看照片查看器", Action.REMOVE),

        // ---- 小米广告 / 推送 / 统计补充（来源：手机端 AdPackageDatabase 2026-10-01 版）----
        // 与上面既有项互补的包名变体；设备上不存在会自动跳过
        TargetPackage("com.xiaomi.ad", "小米系统广告", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.ad", "小米电视广告", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.system_ad", "小米电视系统广告", Action.REMOVE),
        TargetPackage("com.xiaomi.analytics", "小米数据统计", Action.REMOVE),
        TargetPackage("com.miui.analytics", "小米数据统计", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.stat", "小米电视统计", Action.REMOVE),
        TargetPackage("com.xiaomi.stat", "小米数据统计", Action.REMOVE),
        TargetPackage("com.xiaomi.mipush", "小米推送", Action.REMOVE),
        TargetPackage("com.xiaomi.push", "小米推送", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.tvpush", "小米电视推送", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.tvpushservice", "小米电视推送服务（远程指令通道）", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.services", "小米电视服务（安卓6 复原推手变体）", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.commentservice", "小米评论服务(上报)", Action.REMOVE),
        TargetPackage("com.miui.tv.analytics", "MIUI TV 统计分析", Action.REMOVE),
        TargetPackage("com.xiaomi.account.auth", "小米账号认证（云备份恢复依赖）", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.companion", "小米电视伴侣(推广)", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.tvshare", "小米电视投屏(推广)", Action.REMOVE),
        TargetPackage("com.xiaomi.gamecenter", "小米游戏中心(推广)", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.gamecenter", "小米电视游戏中心(推广)", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.gallery", "小米电视相册(含广告)", Action.REMOVE),
        TargetPackage("com.xiaomi.vipaccount", "小米会员(推广)", Action.REMOVE),

        // ---- 合并：小米盒子3s(MDZ19AA) 精简清单 v2（tier A/B 级，2026-10-22）----
        // 来源：F:\纯净包\小米\小米盒子3s MDZ19AA\queenchristina-upgrade-r253\精简清单_v2.csv
        // 只采纳 A/B 级；C 级（看需求）与 D 级（禁止）不进通用清单。
        TargetPackage("com.xiaomi.statistic", "小米行为统计上报(A)", Action.REMOVE),
        TargetPackage("com.xiaomi.smarthome.tv", "米家智能家居(B)", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.download", "小米远程下载(B)", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.searchcenter", "小米全局搜索(B)", Action.REMOVE),
        TargetPackage("com.cleanmaster.sdk", "猎豹清理SDK(第三方预装,B)", Action.REMOVE),
        // 百度输入法：可卸（3s 固件里与搜狗并存，卸掉不影响输入）
        TargetPackage("com.baidu.input", "百度输入法(第三方预装,B)", Action.REMOVE),
        // ⚠ 搜狗输入法 com.sohu.inputmethod.sogou.tv 于 2026-10-22 从清单移除：
        //   MiBOX4 原厂固件里它是【唯一输入法】，删了无法输入 WiFi 密码（该固件判 D 级，必须保留）
        TargetPackage("com.xiaomi.mitv.tvpush.tvpushservice", "小米电视推送服务(变体推手,C)", Action.REMOVE),
        // 应用商店(新) 提到通用清单：MiBOX4 固件（安卓6）也带，
        // 持 INSTALL_PACKAGES+DELETE_PACKAGES，是最强的自动恢复通道
        TargetPackage("com.xiaomi.mitv.appstore", "小米应用商店(新,A)", Action.REMOVE),

        // ---- 主流广告 / 推送 / 统计 SDK（各厂商盒子普遍预置，置为不可用可去掉大部分内嵌广告）----
        TargetPackage("com.qq.e.ads", "广点通广告 SDK", Action.FREEZE),
        TargetPackage("com.qq.ads", "广点通广告 SDK", Action.FREEZE),
        TargetPackage("com.tencent.ads", "腾讯广告 SDK", Action.FREEZE),
        TargetPackage("com.baidu.mobads", "百度广告 SDK", Action.FREEZE),
        TargetPackage("com.baidu.ad", "百度广告 SDK", Action.FREEZE),
        TargetPackage("com.umeng.analytics", "友盟统计", Action.FREEZE),
        TargetPackage("com.umeng.message", "友盟推送", Action.FREEZE),
        TargetPackage("cn.jpush.android", "极光推送 SDK", Action.FREEZE),
        TargetPackage("com.igexin.sdk", "个推推送 SDK", Action.FREEZE),
        TargetPackage("com.igexin", "个推推送 SDK", Action.FREEZE),
        TargetPackage("com.getui", "个推推送 SDK", Action.FREEZE),
        TargetPackage("com.tencent.bugly", "腾讯 Bugly(崩溃/统计)", Action.FREEZE),
        TargetPackage("com.tencent.stat", "腾讯统计", Action.FREEZE),
        TargetPackage("com.google.android.gms.ads", "Google 广告 SDK", Action.FREEZE)
    )

    /**
     * 二、小米盒子 4S Pro / 4S Pro Max 独有（安卓 9 系列实机清单）。
     * 原厂桌面 com.xiaomi.tv.desktop 不在此列 —— 桌面由流程的「清理原装桌面」步骤统一处理。
     */
    val SPRO_EXTRA_APPS: List<TargetPackage> = listOf(
        TargetPackage("com.xiaomi.mitv.appstore", "小米应用商店(新)", Action.REMOVE),
        TargetPackage("com.android.vpndialogs", "VPN 对话框", Action.FREEZE)
    )

    /**
     * 三、小米盒子 4 / 4 SE / 4C / 4S 独有（安卓 6 系列实机清单，与小米 3 同平台）。
     * 原厂桌面 com.xiaomi.tv.desktop 同样由「清理原装桌面」步骤统一处理。
     */
    val FOUR_EXTRA_APPS: List<TargetPackage> = listOf(
        // ★ 清除安装限制：仅小米盒子 4 / 4 SE / 4C / 4S（安卓 6 系列）。
        //   4S Pro（安卓 9）不加 —— 未实测过，不冒险。
        //   小米 APP 安装器会被应用商店自动更新成带「安装名单限制」的版本（覆盖包在 /data/app），
        //   原厂版（无名单限制）留在 /system/priv-app。卸掉 /data 覆盖包即回退原厂版，
        //   用户不用恢复出厂设置，点「一键处理」就能去掉限制，U 盘装软件照常。
        TargetPackage("com.android.packageinstaller", "清除安装限制（回退原厂安装器）", Action.CLEAR_INSTALL),
        TargetPackage("com.android.music", "系统音乐", Action.REMOVE),
        TargetPackage("com.xiaomi.miplay", "小米 MiPlay", Action.REMOVE),
        TargetPackage("com.xiaomi.tv.mitvuser", "MITV 用户服务", Action.REMOVE),
        TargetPackage("com.mitv.video", "小米电视视频", Action.REMOVE),
        TargetPackage("com.xiaomi.mimusic", "小米音乐", Action.REMOVE),
        TargetPackage("com.xiaomi.mitv.assistant.manual", "小米电视助手(手册)", Action.REMOVE),

        // ---- 合并：小米盒子3s(MDZ19AA) 精简清单 v2（tier C 级，2026-10-22）----
        // 安卓 6 平台的厂商预装（投屏/互联/播放器/语音/账号），4S Pro(安卓9) 上基本不存在，
        // 因此只挂在「小米盒子4」清单里；设备上不存在会自动跳过。
        TargetPackage("com.xiaomi.mitv.smartshare", "小米无线投屏 MiTVMultiCast(C)", Action.REMOVE),
        TargetPackage("com.xiaomi.milink.udt", "米联设备互联(C)", Action.REMOVE),
        TargetPackage("com.xiaomi.dlnatvservice", "DLNA 投屏服务(C)", Action.REMOVE),
        // ⚠ 播放器类自 2026-10-22 起一律不动（用户要求保留本地播放能力）：
        //   com.mitv.videoplayer / com.xiaomi.mitv.mediaexplorer /
        //   com.xiaomi.mitv.media（播放器守护）/ com.xiaomi.mitv.player
        TargetPackage("com.xiaomi.voicecontrol", "小米语音控制(C)", Action.REMOVE),
        TargetPackage("com.xiaomi.account", "小米账号(C)", Action.REMOVE)
    )

    /** 按型号取对应处理清单（通用 + 型号独有，按包名去重） */
    fun targetsFor(model: BoxModel): List<TargetPackage> = when (model) {
        BoxModel.MIBOX_4SPRO -> COMMON_APPS + SPRO_EXTRA_APPS
        BoxModel.MIBOX_4 -> COMMON_APPS + FOUR_EXTRA_APPS
        BoxModel.GENERIC -> emptyList()
    }.distinctBy { it.pkg }

    /**
     * 复原推手清单：这些服务以 system 身份运行，能把任何用户级状态逆转回来
     * （install-existing / unhide / enable 都有权限），是设备"变回原厂"的直接推手。
     *
     * 处理策略与普通预装不同：必须「连数据一起卸载」（pm uninstall --user 0，不带 -k），
     * 让其内部排队的"恢复预装/恢复桌面"任务随数据一起消失。
     */
    val RESTORE_AGENTS: List<String> = listOf(
        "mitv.service",
        "com.xiaomi.mitv.services",
        "com.xiaomi.mitv.upgrade",
        "com.xiaomi.mitv.advertise",
        "com.miui.systemAdSolution",
        "com.xiaomi.mitv.tvpushservice",
        // 小米盒子3s(MDZ19AA) 精简清单 v2 里的推送服务变体，同属远程指令通道，一并连数据卸载
        "com.xiaomi.mitv.tvpush.tvpushservice",
        "com.xiaomi.mitv.tvpush",
        "com.xiaomi.mipush",
        "com.xiaomi.push",
        "com.xiaomi.devicereport",
        "com.xiaomi.tv.appupgrade",
        "com.mitv.appstore",
        "com.xiaomi.mitv.appstore",
        "com.miui.tv.analytics",
        "com.miui.cloudservice"
    )

    // ===================== 替代桌面（按型号区分） =====================

    /**
     * 替代桌面定义：包名 + 首页组件（用于 set-home-activity，必须是真实入口）+ 内置安装包。
     *
     * 当贝桌面 3.2.5 的入口是 com.dangbei.launcher.ui.main.MainActivity（与 2.x 的
     * SplashActivity 不同，以 aapt 实测为准）；Emotn UI 入口为 .ui.main.MainActivity。
     */
    data class DesktopApp(
        val pkg: String,
        val component: String,
        val assetPath: String,
        val fileName: String
    )

    /** 当贝桌面 3.2.5（安卓 6 系列使用） */
    val DANGBEI_DESKTOP = DesktopApp(
        pkg = "com.dangbei.tvlauncher",
        component = "com.dangbei.tvlauncher/com.dangbei.launcher.ui.main.MainActivity",
        assetPath = "Dangbei/dangbei_desktop.apk",
        fileName = "box_desktop_dangbei.apk"
    )

    /** Emotn UI（安卓 9 系列 4S Pro 使用） */
    val EMOTN_DESKTOP = DesktopApp(
        pkg = "com.oversea.aslauncher",
        component = "com.oversea.aslauncher/com.oversea.aslauncher.ui.main.MainActivity",
        assetPath = "EmotnUI/emotnui.apk",
        fileName = "box_desktop_emotn.apk"
    )

    /** 按型号取替代桌面：4S Pro 与通用档 → Emotn UI；小米盒子4 → 当贝桌面 */
    fun desktopFor(model: BoxModel): DesktopApp = when (model) {
        BoxModel.MIBOX_4SPRO -> EMOTN_DESKTOP
        BoxModel.MIBOX_4 -> DANGBEI_DESKTOP
        BoxModel.GENERIC -> EMOTN_DESKTOP
    }

    /**
     * 该型号是否套用小米的预装清理清单。
     *
     * 通用档一律不套用：清单里的包名在别的品牌上本就不存在，
     * 硬套只会做无用功；而各家的「出厂桌面 / 复原推手」都不一样，
     * 拿小米清单去卸别家的包存在误删风险。
     */
    fun usesXiaomiSlimList(model: BoxModel): Boolean = model != BoxModel.GENERIC

    /** 该型号是否有配套应用需要安装（通用档没有） */
    fun hasCompanions(model: BoxModel): Boolean = model != BoxModel.GENERIC

    // ===================== 配套应用（按型号区分） =====================

    /** 随工具一起分发、处理完成后安装的配套应用 */
    data class CompanionApp(
        val pkg: String,
        /** 安装包在 APK 内的路径 */
        val assetPath: String,
        /** 释放到设备上的文件名 */
        val fileName: String
    )

    private val GUANJIA = CompanionApp(
        pkg = "com.yunpan.appmanage",
        assetPath = "Guanjia/guanjia.apk",
        fileName = "box_guanjia.apk"
    )

    private val XIAOCAO = CompanionApp(
        pkg = "app.yecao", // 小草助手TV版（实测包名如此）
        assetPath = "Xiaocao/xiaocao.apk",
        fileName = "box_xiaocao.apk"
    )

    private val DANGBEI_MARKET = CompanionApp(
        pkg = "com.dangbeimarket",
        assetPath = "DangbeiMarket/dangbeimarket.apk",
        fileName = "box_dangbeimarket.apk"
    )

    /** 原生系统设置（安卓 9 的 4S Pro 精简后需要它进设置） */
    private val TV_SETTINGS = CompanionApp(
        pkg = "com.android.tv.settings",
        assetPath = "TvSettings/TvSettings.apk",
        fileName = "box_tvsettings.apk"
    )

    /** 按型号取配套应用清单：
     *  两个型号统一安装：应用管家 + 小草助手 + 当贝市场；
     *  4S Pro（安卓9）额外多装一个原生系统设置。
     */
    fun companionsFor(model: BoxModel): List<CompanionApp> = when (model) {
        BoxModel.MIBOX_4SPRO -> listOf(GUANJIA, XIAOCAO, DANGBEI_MARKET, TV_SETTINGS)
        BoxModel.MIBOX_4 -> listOf(GUANJIA, XIAOCAO, DANGBEI_MARKET)
        BoxModel.GENERIC -> emptyList()
    }

    /** 已释放到设备上、待安装的配套应用 */
    data class PendingInstall(
        val pkg: String,
        val file: File?
    )

    // ===================== 桌面安全边界 =====================

    /**
     * 小米原厂桌面候选（不同机型/批次可能不同，这里逐一尝试）。
     * 此外运行时还会探测设备上真实已装的桌面一并合并进来。
     */
    val ORIGINAL_LAUNCHERS: List<String> = listOf(
        "com.mitv.tvhome",
        "com.xiaomi.tv.desktop"
    )

    /**
     * 系统级兜底桌面的包名前缀。
     *
     * 这些是系统自带的桌面（含开机引导用的兜底桌面），任何时候都不清理、不停用：
     * 就算新桌面出了问题，设备也还有系统桌面可以进，不会真的变砖。
     */
    val SYSTEM_FALLBACK_HOME_PREFIXES: List<String> = listOf(
        "com.android.",
        "com.google.android."
    )

    /** 本工具自身的包名，任何情况下都不会被处理，防止把工具自己删掉 */
    const val SELF_PACKAGE = "com.miaomiaotv.xiaomi"
}
