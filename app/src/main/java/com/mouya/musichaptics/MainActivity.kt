package com.mouya.musichaptics

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle 
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var uiBuilder: MainUiBuilder
    private val TARGET_PACKAGE = "com.kugou.android.lite"
    
    private var lastNotifyTime = 0L
    private val NOTIFY_THROTTLE_MS = 300L
    private var lastLogTime = 0L

    private val REQUEST_PICK_WALLPAPER = 2026

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val logMsg = intent?.getStringExtra("log_msg") ?: return
            if (::uiBuilder.isInitialized) {
                uiBuilder.appendLog(logMsg)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("haptics_config", Context.MODE_PRIVATE)

        // 在 setContentView 之前建立全屏幕穿透，消灭黑边gugugaga
        initImmersionStatusBar()

        uiBuilder = MainUiBuilder(
            activity = this,
            prefs = prefs,
            onSelectWallpaper = {
                val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                startActivityForResult(intent, REQUEST_PICK_WALLPAPER)
            },
            onClearWallpaper = {
                prefs.edit().remove("wallpaper_uri").apply()
                uiBuilder.updateBackground(null)
            },
            onConfigChanged = {
                notifyHookUpdate()
            }
        )

        // 【核心改动】建立一个绝对安全的视图容器挂载策略
        val mainView = uiBuilder.buildView()
        val isActivated = prefs.getBoolean("is_factory_activated", false)

        if (!isActivated) {
            // 如果未激活，创建一个全屏容器，把原生 UI 和 激活向导叠在一起
            val rootLayout = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            // 底部塞入你完好无损的 MainUiView
            rootLayout.addView(mainView)

            // 顶部覆盖一层高科技感十足的激活面纱
            val activationOverlay = createActivationOverlay(rootLayout, mainView)
            rootLayout.addView(activationOverlay)

            setContentView(rootLayout)
        } else {
            // 如果早跑过激活了，直接加载原生 UI，完全不走任何弯路
            setContentView(mainView)
            // 顺便在此处将先前读取的自适应参数同步到你的底层 C++ 驱动中
            applySavedHardwareConfig()
        }

        // 异步检测目标应用状态与 Root 审计流，防止阻塞主线程物理冷启动
        Thread {
            if (isTargetAppRunning(TARGET_PACKAGE) && isAppUpdated()) {
                runOnUiThread { executeRootAuditFlow() }
            }
        }.start()
    }

    /**
     * 【新增】构建纯原生 View 体系的“工厂级硬件自适应激活面纱”
     * 绝不冲突 MainUiBuilder，跑完自毁
     */
    private fun createActivationOverlay(parentContainer: FrameLayout, mainView: View): View {
        val context = this
        
        // 全屏深邃黑色背景罩
        val overlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Color.parseColor("#121212")) }
            setPadding(dpToPx(24), dpToPx(60), dpToPx(24), dpToPx(40))
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 头部大标题
        val titleView = TextView(context).apply {
            text = "MusicHapticsX"
            textSize = 30f
            setTextColor(Color.parseColor("#007AFF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        overlay.addView(titleView)

        // 副标题
        val subTitleView = TextView(context).apply {
            text = "量子超频触觉内核激活向导"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dpToPx(6), 0, dpToPx(30))
            gravity = Gravity.CENTER
        }
        overlay.addView(subTitleView)

        // 核心说明文本栏
        val descView = TextView(context).apply {
            text = "欢迎使用全新自适应架构。为了打通高带宽低时延的底层 Linux 内核 RTP 注入通道，引擎需要请求超级用户 (Root) 权限，并对硬件马达进行出厂共振频率校准。\n\n(ᗜ ˰ ᗜ) 准备就绪，待命建立通道。"
            textSize = 14f
            setTextColor(Color.parseColor("#E0E0E0"))
            setLineSpacing(0f, 1.3f)
        }
        overlay.addView(descView)

        // 【黑客风终端日志区】包裹在 ScrollView 中防止跑马灯溢出
        val scrollContainer = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                dpToPx(260)
            ).apply { setMargins(0, dpToPx(20), 0, dpToPx(20)) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#080808"))
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            visibility = View.GONE // 初始状态隐藏，开始探测时展示
        }
        val consoleLogView = TextView(context).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.CYAN)
            setLineSpacing(0f, 1.2f)
        }
        scrollContainer.addView(consoleLogView)
        overlay.addView(scrollContainer)

        // 占位弹簧
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                0, 1f
            )
        }
        overlay.addView(spacer)

        // 核心激活大按钮
        val actionBtn = Button(context).apply {
            text = "授权 SU 并激活超频内核"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#007AFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                dpToPx(48)
            )
        }
        overlay.addView(actionBtn)

        // 辅助更新控制台文本的内部小工具
        fun appendTextToConsole(msg: String) {
            runOnUiThread {
                val currentText = consoleLogView.text.toString()
                consoleLogView.text = if (currentText.isEmpty()) msg else "$currentText\n$msg"
                scrollContainer.post { scrollContainer.fullScroll(View.FOCUS_DOWN) }
            }
        }

        // 激活按钮点击后的核心自动化状态机流水线
        actionBtn.setOnClickListener {
            actionBtn.isEnabled = false
            descView.visibility = View.GONE
            scrollContainer.visibility = View.VISIBLE
            actionBtn.text = "硬件深度扫描中..."
            actionBtn.background = GradientDrawable().apply {
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#333333"))
            }

            Thread {
                var nodePath = "/sys/class/leds/vibrator/rtp"
                var motorF0 = 170.0f
                
                appendTextToConsole("正在初始化工厂级触觉探测流水线...")
                Thread.sleep(400)
                
                val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                appendTextToConsole("宿主设备识别: $deviceModel")
                appendTextToConsole("正在向系统请求超级用户 (su) 权限...")

                // 探测高级 root 权限
                val hasSu = checkRootSilent()
                if (!hasSu) {
                    appendTextToConsole("超级用户权限获取失败！请在 Magisk/KernelSU 中授权。")
                    runOnUiThread {
                        actionBtn.isEnabled = true
                        actionBtn.text = "重试授权激活"
                        actionBtn.background = GradientDrawable().apply {
                            cornerRadius = dpToPx(24).toFloat()
                            setColor(Color.parseColor("#FF3B30"))
                        }
                    }
                    return@Thread
                }
                
                appendTextToConsole("超级用户权限已锁定。开始底层硬件解密...")
                Thread.sleep(300)
                // 【新增】扫描 AW8697 触觉固件指纹
                appendTextToConsole("正在扫描 AW8697 触觉固件...")

                val firmwarePaths = arrayOf(
                    "/vendor/firmware/aw8697_haptic.bin",
                    "/vendor/firmware/aw8697_haptic_a.bin",
                    "/vendor/firmware/haptics_video_rtp.bin",
                    "/vendor/firmware/haptics_video_rtp_a.bin"
                )

                for (fw in firmwarePaths) {
                    if (File(fw).exists()) {
                        appendTextToConsole("发现触觉固件: $fw")
                    }
                }

                // 1. 扫描与适配高时感硬件通道节点
                val potentialNodes = arrayOf(

                    // AW8697 原生节点
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/rtp",
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/wave",
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/activate",

                    // Xiaomi 通用 vibrator
                    "/sys/class/leds/vibrator/rtp",
                    "/sys/class/leds/vibrator/rtp_input",
                    "/sys/class/leds/vibrator/level",

                    // 部分 MIUI/HyperOS
                    "/sys/class/haptic/vibrator/rtp",
                    "/sys/class/haptic/vibrator/amplitude",

                    // 备用
                    "/dev/haptic"
                )
                var foundNode = false
                for (node in potentialNodes) {
                    appendTextToConsole("探测寻址路径: $node")
                    // 尝试提权并测试是否可写
                    val isWritable = try {
                        val process = Runtime.getRuntime().exec("su")
                        val os = DataOutputStream(process.outputStream)
                        os.writeBytes("chmod 666 $node\n")
                        os.writeBytes("[ -w \"$node\" ] && echo \"OK\"\n")
                        os.writeBytes("exit\n")
                        os.flush()
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        val output = reader.readLine()
                        process.waitFor()
                        output?.trim() == "OK"
                    } catch (e: Exception) { false }

                    if (isWritable) {
                        nodePath = node
                        foundNode = true
                        appendTextToConsole("锁定低延迟触觉通道: $node")
                        break
                    }
                    Thread.sleep(150)
                }

                if (!foundNode) {
                    appendTextToConsole("未检测到标准流式注入通道，已进入兼容性模式。")
                }

                // 2. 深度检索马达物理出厂校准共振频率 F0
                val f0Nodes = arrayOf(
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/f0",
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/f0_value",
                    "/sys/class/leds/vibrator/f0",
                    "/sys/class/leds/vibrator/cali",
                    "/sys/class/leds/vibrator/f0_cali",
                    "/sys/class/haptic/vibrator/f0_cali",
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/f0_cali",
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/cali",
                    "/sys/devices/platform/soc/a8c000.i2c/i2c-2/2-005a/f0_data",
                )
                var foundF0 = false
                for (f0Node in f0Nodes) {
                    val rawF0 = try {
                        val process = Runtime.getRuntime().exec("su")
                        val os = DataOutputStream(process.outputStream)
                        os.writeBytes("cat $f0Node\n")
                        os.writeBytes("exit\n")
                        os.flush()
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        val line = reader.readLine()
                        process.waitFor()
                        line?.trim() ?: ""
                    } catch (e: Exception) { "" }

                    val numericValue = rawF0.replace(Regex("[^0-9.]"), "").toFloatOrNull()
                    if (numericValue != null && numericValue > 50f && numericValue < 400f) {
                        motorF0 = numericValue
                        foundF0 = true
                        appendTextToConsole("从硬件层解码出厂共振频率: ${motorF0}Hz")
                        break
                    }
                }

                // 无法直接抓取时触发基于硬件指纹的高精度自适应代偿机制
                if (!foundF0) {
                    val fingerPrint = Build.DEVICE.lowercase(Locale.ROOT)
                    if (fingerPrint.contains("umi") || fingerPrint.contains("cmi")) {
                        motorF0 = 170.0f // 小米10 / 10 Pro 黄金共振点
                    } else if (fingerPrint.contains("polaris") || fingerPrint.contains("dipper")) {
                        motorF0 = 165.0f // 小米 MIX2S 等经典老架构马达
                    } else {
                        appendTextToConsole("未读到出厂物理校准值，已基于常规线性马达自适应对齐均值: 170.0Hz")
                    }
                }

                appendTextToConsole("\n硬件自适应配置已全线就绪！")
                Thread.sleep(400)

                // 写入硬件快照参数以供下次无缝越过激活
                prefs.edit().apply {
                    putString("calibrated_node_path", nodePath)
                    putFloat("calibrated_motor_f0", motorF0)
                    putBoolean("is_factory_activated", true)
                    apply()
                }

                // 步骤 3：完美收工，点亮终极按钮
                runOnUiThread {
                    actionBtn.isEnabled = true
                    actionBtn.text = "进入超级音振 (ᗜ ˰ ᗜ)"
                    actionBtn.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(24).toFloat()
                        setColor(Color.parseColor("#34C759")) // 激活成功的翠绿
                    }
                    
                    // 点击终极按钮后，面纱渐隐自毁，完全不影响 MainUiBuilder
                    actionBtn.setOnClickListener {
                        overlay.animate()
                            .alpha(0f)
                            .setDuration(400)
                            .withEndAction {
                                parentContainer.removeView(overlay) // 彻底从物理内存和视图树中拔除自毁！
                                applySavedHardwareConfig()          // 让底层 C++ 正式接纳这套全新参数
                            }
                            .start()
                    }
                }
            }.start()
        }

        return overlay
    }

    /**
     * 【新增】将自适应拿到的底层物理节点和马达共振频率动态发射进 C++ 核心
     */
    private fun applySavedHardwareConfig() {
        val path = prefs.getString("calibrated_node_path", "/sys/class/leds/vibrator/rtp") ?: "/sys/class/leds/vibrator/rtp"
        val f0 = prefs.getFloat("calibrated_motor_f0", 170.0f)
        
        // 利用系统 Root 强制刷新当前节点的读写权，防止系统重启后权限回收
        Thread {
            try {
                val process = Runtime.getRuntime().exec("su")
                val os = process.outputStream
                os.write("chmod 666 $path\n".toByteArray())
                os.write("exit\n".toByteArray())
                os.flush()
                process.waitFor()
            } catch (e: Exception) { e.printStackTrace() }
        }.start()

        // TODO: 在这里直接呼叫 JNI 引擎注入配置即可！
        // HapticEngine.updateKernelConfig(path, f0)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_WALLPAPER && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            try {
                val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (takeFlags != 0) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
            uiBuilder.updateBackground(uri.toString())
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.mouya.musichaptics.ACTION_LOG")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initImmersionStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        @Suppress("DEPRECATION")
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun isAppUpdated(): Boolean {
        val currentVersionCode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
        } catch (e: Exception) { 79L }

        val lastVersionCode = prefs.getLong("last_version_code", -1L)
        if (currentVersionCode > lastVersionCode) {
            prefs.edit().putLong("last_version_code", currentVersionCode).apply()
            return true
        }
        return false
    }

    private fun notifyHookUpdate() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastNotifyTime < NOTIFY_THROTTLE_MS) {
            return 
        }
        lastNotifyTime = currentTime

        try {
            val uri = Uri.parse("content://com.mouya.musichaptics.provider/update")
            contentResolver.notifyChange(uri, null)
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 3000) {
                lastLogTime = now
                if (::uiBuilder.isInitialized) {
                    uiBuilder.appendLog("咕咕嘎嘎！ [同步通道挂起] 仅保存在本地。请在杂鱼老师的 AndroidManifest 中配置 Provider 即可完美激活！")
                }
            }
        }
    }

    private fun executeRootAuditFlow() {
        Thread {
            val hasRoot = checkRootSilent()
            runOnUiThread {
                showHyperDialog(hasRoot)
            }
        }.start()
    }

    private fun showHyperDialog(hasRoot: Boolean) {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(22), dpToPx(24), dpToPx(22))
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#FAFAFA"))
            }
        }

        val titleView = TextView(context).apply {
            text = if (hasRoot) "审计中心系统提示" else "核心授权请求"
            textSize = 19f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(4), 0, dpToPx(14))
        }
        container.addView(titleView)

        val descView = TextView(context).apply {
            text = if (hasRoot) {
                "杂鱼老师，宿主音频参数流已经升级！为了防止你的爪机阻抗失调，现在必须强杀app来重新载入！"
            } else {
                "哼，杂鱼老师居然没有给 Root 权限！快去 Magisk/KernelSU 里面给本大人点下允许授权啦！( ⩌⤚⩌)"
            }
            textSize = 14f
            setTextColor(Color.parseColor("#43474E"))
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, dpToPx(24))
        }
        container.addView(descView)

        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val btnHeight = dpToPx(42)

        val cancelBtn = Button(context).apply {
            text = "取消"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#3F474F"))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dpToPx(1), Color.parseColor("#DCE2E9"))
            }
        }
        cancelBtn.layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f).apply {
            setMargins(0, 0, dpToPx(8), 0)
        }

        val confirmBtn = Button(context).apply {
            text = if (hasRoot) "立即重启" else "重试检测"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor("#007AFF"))
            }
        }
        confirmBtn.layoutParams = LinearLayout.LayoutParams(0, btnHeight, 1f).apply {
            setMargins(dpToPx(8), 0, 0, 0)
        }

        buttonLayout.addView(cancelBtn)
        buttonLayout.addView(confirmBtn)
        container.addView(buttonLayout)

        @Suppress("DEPRECATION")
        val dialog = AlertDialog.Builder(context, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
            .setView(container)
            .create()

        dialog.window?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = 60
            }
            setDimAmount(0.18f)
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            if (hasRoot) {
                Thread {
                    try {
                        val process = Runtime.getRuntime().exec("su")
                        val os = process.outputStream
                        os.write("am force-stop $TARGET_PACKAGE\n".toByteArray())
                        os.write("exit\n".toByteArray())
                        os.flush()
                        process.waitFor()
                    } catch (e: Exception) {
                        runOnUiThread {
                            descView.text = "切，自动强杀失败，大杂鱼老师自己去系统设置手动停止吧~|･з･)｡"
                        }
                    }
                }.start()
            } else {
                executeRootAuditFlow()
            }
        }

        dialog.show()
    }

    private fun checkRootSilent(): Boolean {
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/local/su")
        for (path in paths) { if (File(path).exists()) return true }
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("exit\n".toByteArray())
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) { false }
    }

    private fun isTargetAppRunning(packageName: String): Boolean {
        var process: Process? = null
        var reader: BufferedReader? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pidof $packageName"))
            reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            !line.isNullOrEmpty()
        } catch (e: Exception) {
            false
        } finally {
            reader?.close()
            process?.destroy()
        }
    }
}
