package com.miaomiaotv.xiaomi

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.miaomiaotv.xiaomi.adb.AdbChannel
import com.miaomiaotv.xiaomi.adb.AdbIdentity
import com.miaomiaotv.xiaomi.adb.UnauthorizedException
import com.miaomiaotv.xiaomi.accessibility.AgentAccessibilityService
import com.miaomiaotv.xiaomi.core.BoxModel
import com.miaomiaotv.xiaomi.core.FlowResult
import com.miaomiaotv.xiaomi.core.XiaomiRunner
import com.miaomiaotv.xiaomi.core.XiaomiTargets
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * 主界面（TV 风格）。
 *
 * 一屏展示两张型号卡片（小米盒子4SPro / 小米盒子4），遥控器方向键切换焦点、确认键执行；
 * 执行期间弹出处理中弹窗（带百分比），完成后切换为「处理完成 100%」并提供「知道了」。
 *
 * 界面只呈现步骤与进度，包名、命令、设备返回等技术信息一律只写入 logcat。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var cardSpro: LinearLayout
    private lateinit var cardFour: LinearLayout
    private lateinit var cardGeneric: LinearLayout
    private lateinit var sproHint: TextView
    private lateinit var fourHint: TextView
    private lateinit var genericHint: TextView

    private lateinit var overlay: FrameLayout
    private lateinit var blockProgress: LinearLayout
    private lateinit var blockDone: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var stepText: TextView
    private lateinit var percentText: TextView
    private lateinit var doneTitle: TextView
    private lateinit var donePercent: TextView
    private lateinit var doneDetail: TextView
    private lateinit var okButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var channel: AdbChannel? = null

    @Volatile
    private var busy = false

    /** 随工具一起释放到设备上的桌面安装包（两个型号各一份，按型号选用） */
    @Volatile
    private var dangbeiApk: File? = null

    @Volatile
    private var emotnApk: File? = null

    /** 已释放到设备上的配套应用安装包（按包名索引，两个型号共用一套释放结果） */
    private val companionFiles = mutableMapOf<String, File?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.tvStatus)
        retryButton = findViewById(R.id.btnRetry)
        cardSpro = findViewById(R.id.cardSpro)
        cardFour = findViewById(R.id.cardFour)
        cardGeneric = findViewById(R.id.cardGeneric)
        sproHint = findViewById(R.id.tvSproHint)
        fourHint = findViewById(R.id.tvFourHint)
        genericHint = findViewById(R.id.tvGenericHint)

        overlay = findViewById(R.id.overlay)
        blockProgress = findViewById(R.id.blockProgress)
        blockDone = findViewById(R.id.blockDone)
        progressBar = findViewById(R.id.progressBar)
        stepText = findViewById(R.id.tvStep)
        percentText = findViewById(R.id.tvPercent)
        doneTitle = findViewById(R.id.tvDoneTitle)
        donePercent = findViewById(R.id.tvDonePercent)
        doneDetail = findViewById(R.id.tvDoneDetail)
        okButton = findViewById(R.id.btnOk)

        bindFocusZoom(cardSpro)
        bindFocusZoom(cardFour)
        bindFocusZoom(cardGeneric)
        bindFocusZoom(okButton)

        retryButton.setOnClickListener { startWork() }
        okButton.setOnClickListener {
            // 处理完成 → 重启设备。桌面相关的全部保障（安装、设默认、清理原厂桌面）
            // 都已在流程内执行完毕，重启后系统会直接进入新桌面。
            hideOverlay(lastFocusedCard)
            rebootDevice()
        }
        cardSpro.setOnClickListener { runFlow(BoxModel.MIBOX_4SPRO, cardSpro) }
        cardFour.setOnClickListener { runFlow(BoxModel.MIBOX_4, cardFour) }
        cardGeneric.setOnClickListener { runFlow(BoxModel.GENERIC, cardGeneric) }

        cardSpro.requestFocus()

        requestStoragePermissionIfNeeded()
        startWork()
    }

    // ===================== 启动：准备文件 + 连接 =====================

    private fun startWork() {
        status(getString(R.string.prepare_file))
        executor.execute {
            prepareFiles()
            connectChannel()
        }
    }

    /** 把内置的安装包释放到设备上，后续直接用安装命令调用 */
    private fun prepareFiles() {
        dangbeiApk = extractAsset(XiaomiTargets.DANGBEI_DESKTOP.assetPath, XiaomiTargets.DANGBEI_DESKTOP.fileName)
        emotnApk = extractAsset(XiaomiTargets.EMOTN_DESKTOP.assetPath, XiaomiTargets.EMOTN_DESKTOP.fileName)
        companionFiles.clear()
        (XiaomiTargets.companionsFor(BoxModel.MIBOX_4SPRO) + XiaomiTargets.companionsFor(BoxModel.MIBOX_4))
            .distinctBy { it.pkg }
            .forEach { app -> companionFiles[app.pkg] = extractAsset(app.assetPath, app.fileName) }
    }

    /**
     * 连接本机调试通道。
     * 软件的 IP 是固定的：就是设备自己。
     */
    private fun connectChannel() {
        status(getString(R.string.connecting))
        val identity = runCatching { AdbIdentity(File(filesDir, KEYS_DIR)) }
            .onFailure { Log.e(TAG, "密钥初始化失败", it) }
            .getOrNull()
        if (identity == null) {
            status(getString(R.string.not_found))
            refreshCards()
            return
        }

        val candidate = AdbChannel()
        candidate.attachIdentity(identity)
        // 设备索要公钥、授权弹窗弹出时，界面提示用户去设备上确认
        candidate.onNeedAuthorization = {
            status(getString(R.string.waiting_authorization))
        }
        try {
            candidate.connect(TARGET_HOST, TARGET_PORT, CONNECT_TIMEOUT_MS)
            channel = candidate
            Log.i(TAG, "已连接本机调试通道 $TARGET_HOST:$TARGET_PORT")
            status(getString(R.string.connected))
            showGuardState()
        } catch (e: UnauthorizedException) {
            candidate.disconnect()
            Log.w(TAG, "设备未给本工具授权：${e.message}")
            status("设备尚未给本工具授权")
        } catch (e: Exception) {
            candidate.disconnect()
            Log.e(TAG, "连接失败：${e.message}")
            status(getString(R.string.not_found))
        }
        refreshCards()
    }

    private fun extractAsset(assetPath: String, fileName: String): File? {
        runCatching {
            val publicFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            publicFile.parentFile?.mkdirs()
            if (!publicFile.exists() || publicFile.length() == 0L) {
                assets.open(assetPath).use { input ->
                    FileOutputStream(publicFile).use { output -> input.copyTo(output) }
                }
            }
            @Suppress("DEPRECATION")
            runCatching { publicFile.setReadable(true, false) }
            if (publicFile.exists() && publicFile.length() > 0L) {
                Log.i(TAG, "安装包已就绪：$fileName")
                return publicFile
            }
        }.onFailure { Log.w(TAG, "释放 $fileName 到公共目录失败：${it.message}") }

        runCatching {
            val privateFile = File(filesDir, fileName)
            if (!privateFile.exists() || privateFile.length() == 0L) {
                assets.open(assetPath).use { input ->
                    FileOutputStream(privateFile).use { output -> input.copyTo(output) }
                }
            }
            @Suppress("DEPRECATION")
            runCatching { privateFile.setReadable(true, false) }
            return privateFile
        }.onFailure { Log.w(TAG, "释放 $fileName 失败：${it.message}") }
        return null
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
            val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            }
        }
    }

    // ===================== 执行处理 =====================

    private var lastFocusedCard: View? = null

    private fun runFlow(model: BoxModel, card: View) {
        val current = channel
        if (current == null) {
            showNotice(getString(R.string.not_connected))
            return
        }
        if (busy) return
        busy = true
        lastFocusedCard = card
        refreshCards()
        showProgress()

        executor.execute {
            val desktopSpec = XiaomiTargets.desktopFor(model)
            val desktopFile = when (model) {
                BoxModel.MIBOX_4SPRO, BoxModel.GENERIC -> emotnApk
                BoxModel.MIBOX_4 -> dangbeiApk
            }
            val companions = XiaomiTargets.companionsFor(model).map {
                XiaomiTargets.PendingInstall(pkg = it.pkg, file = companionFiles[it.pkg])
            }
            val result = XiaomiRunner(
                context = this,
                channel = current,
                targets = XiaomiTargets.targetsFor(model),
                desktop = desktopSpec,
                desktopApk = desktopFile,
                companionApps = companions,
                onProgress = { label, percent -> updateProgress(label, percent) },
                onDetail = { text -> Log.i(TAG, text) }
            ).run(model)
            handler.post {
                busy = false
                showResult(result)
                refreshCards()
            }
        }
    }

    /**
     * 重启设备（用户点「知道了」后执行）。
     *
     * 桌面相关的一切保障在流程内已完成：
     * 新桌面已安装、已设为默认（不支持的设备上已停用其它桌面使其成为唯一桌面）、
     * 原厂桌面已清理、系统兜底桌面始终保留，因此重启后设备一定能正常进入桌面。
     */
    private fun rebootDevice() {
        val current = channel ?: return
        executor.execute {
            runCatching { current.shell("reboot") }
                .onSuccess { Log.i(TAG, "已下发重启命令") }
                .onFailure { Log.w(TAG, "重启命令失败：${it.message}") }
        }
    }

    private fun updateProgress(label: String, percent: Int) {
        handler.post {
            progressBar.progress = percent
            percentText.text = "$percent%"
            stepText.text = getString(R.string.stepping, label)
        }
    }

    private fun showProgress() {
        overlay.visibility = View.VISIBLE
        blockProgress.visibility = View.VISIBLE
        blockDone.visibility = View.GONE
        progressBar.progress = 0
        percentText.text = getString(R.string.percent_zero)
        stepText.text = getString(R.string.preparing)
    }

    private fun showResult(result: FlowResult) {
        blockProgress.visibility = View.GONE
        blockDone.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE

        // 完成后一律展示 100%
        donePercent.text = getString(R.string.percent_full)
        if (result.succeeded) {
            doneTitle.text = getString(R.string.done_title)
            doneTitle.setTextColor(resources.getColor(android.R.color.holo_green_light))
            doneDetail.text = getString(R.string.done_reboot) + "\n" + getString(R.string.done_reboot_warning)
        } else {
            doneTitle.text = getString(R.string.done_partial_title)
            doneTitle.setTextColor(resources.getColor(android.R.color.holo_orange_light))
            val list = result.failedSteps.joinToString(separator = "\n") { "• $it" }
            doneDetail.text = buildString {
                append(getString(R.string.done_partial))
                append('\n')
                append(list)
                append('\n')
                append(getString(R.string.done_reboot_warning))
            }
        }
        okButton.requestFocus()
        Log.i(TAG, "处理结论：成功=${result.succeeded}，共 ${result.totalSteps} 步，失败 ${result.failedSteps.size} 步")
    }

    /** 未连接等情况下的提示弹窗 */
    private fun showNotice(message: String) {
        blockProgress.visibility = View.GONE
        blockDone.visibility = View.VISIBLE
        overlay.visibility = View.VISIBLE
        doneTitle.text = message
        doneTitle.setTextColor(resources.getColor(android.R.color.holo_orange_light))
        donePercent.text = "!"
        doneDetail.text = ""
        okButton.requestFocus()
    }

    private fun hideOverlay(focusTarget: View?) {
        overlay.visibility = View.GONE
        lastFocusedCard = null
        (focusTarget ?: cardSpro).requestFocus()
    }

    // ===================== 界面辅助 =====================

    /**
     * 显示自守护与调试保持的状态。
     *
     * 两项都是"读得到"的状态：
     *  - 自守护服务是否已登记进系统的无障碍服务列表；
     *  - 调试总开关（adb_enabled）是否处于开启状态。
     */
    private fun showGuardState() {
        val services = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrDefault("").orEmpty()
        val guardOn = services.contains(AgentAccessibilityService.SERVICE_SIMPLE_NAME)

        val adbOn = runCatching {
            Settings.Global.getInt(contentResolver, "adb_enabled", 0)
        }.getOrDefault(0) == 1

        val text = buildString {
            append(getString(if (guardOn) R.string.guard_state_on else R.string.guard_state_off))
            append("  |  ")
            append(getString(if (adbOn) R.string.adb_state_on else R.string.adb_state_off))
        }
        handler.post { statusText.text = text }
    }

    private fun refreshCards() {
        val enabled = !busy && channel != null
        handler.post {
            cardSpro.isFocusable = enabled
            cardFour.isFocusable = enabled
            cardGeneric.isFocusable = enabled
            cardSpro.alpha = if (enabled) 1f else 0.45f
            cardFour.alpha = if (enabled) 1f else 0.45f
            cardGeneric.alpha = if (enabled) 1f else 0.45f
            val hint = if (enabled) getString(R.string.press_to_start) else getString(R.string.not_connected)
            sproHint.text = hint
            fourHint.text = hint
            genericHint.text = hint
        }
    }

    /** 遥控器焦点放大效果，TV 上必须有明显的焦点反馈 */
    private fun bindFocusZoom(view: View) {
        view.setOnFocusChangeListener { target, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            target.animate()
                .scaleX(scale)
                .scaleY(scale)
                .setDuration(150)
                .start()
        }
    }

    private fun status(text: String) {
        handler.post { statusText.text = text }
    }

    companion object {
        private const val TAG = "XiaomiBoxTool"

        /** 处理对象就是设备自身，地址固定为本机 */
        private const val TARGET_HOST = "127.0.0.1"
        private const val TARGET_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 5000

        private const val KEYS_DIR = "keys"
        /** 选中放大倍数：过大会让两张卡片互相压住，1.03 已足够明显 */
        private const val FOCUS_SCALE = 1.03f
        private const val REQUEST_STORAGE = 1001
    }
}
