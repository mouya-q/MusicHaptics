package com.mouya.musichaptics

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle 
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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

        setContentView(uiBuilder.buildView())

        // 异步检测目标应用状态与 Root 审计流，防止阻塞主线程物理冷启动
        Thread {
            if (isTargetAppRunning(TARGET_PACKAGE) && isAppUpdated()) {
                runOnUiThread { executeRootAuditFlow() }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_WALLPAPER && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            try {
                // 仅在支持持久化的系统层 URI 下尝试锁定
                val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (takeFlags != 0) {
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                }
            } catch (e: Exception) {
                // ACTION_PICK 产生的普通媒体库 URI 无法持久化是正常现象，捕获不闪退
                e.printStackTrace()
            }
            prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
            uiBuilder.updateBackground(uri.toString())
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.mouya.musichaptics.ACTION_LOG")
        // 🟢 修复：只有 API 33 (TIRAMISU) 以上才支持并强制要求带上 RECEIVER_EXPORTED 标志位
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(4), 0, dpToPx(14))
        }
        container.addView(titleView)

        val descView = TextView(context).apply {
            text = if (hasRoot) {
                "杂鱼老师，宿主音频参数流已经升级！为了防止你的爪机阻抗失调，现在必须强杀酷狗概念版来重新载入！"
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

        // 🟢 修复：将原本死板的 116px 换算为标准响应式高斯阻尼 DP 适配
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

    /**
     * 🟢 修复：利用底层 Root 命令行突破 Android 应用进程沙盒机制
     */
    private fun isTargetAppRunning(packageName: String): Boolean {
        var process: Process? = null
        var reader: BufferedReader? = null
        return try {
            // 利用 pidof 直接去内核查找目标包名进程号
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
