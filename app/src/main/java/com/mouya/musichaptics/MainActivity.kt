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
import android.os.Bundle // 就是这里    雑魚库不自带     凑库
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/*
                   _ooOoo_
                  o8888888o
                  88" . "88
                  (| -_- |)
                  O\  =  /O
               ____/`---'\____
             .'  \\|     |//  `.
            /  \\|||  :  |||//  \
           /  _||||| -:- |||||-  \
           |   | \\\  -  /// |   |
           | \_|  ''\---/''  |   |
           \  .-\__  `-`  ___/-. /
         ___`. .'  /--.--\  `. . __
      ."" '<  `.___\_<|>_/___.'  >'"".
     | | :  `- \`.;`\ _ /`;.`/ - ` : | |
     \  \ `-.   \_ __\ /__ _/   .-` /  /
======`-.____`-.___\_____/___.-`____.-'======
                   `=---='
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
*/


class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var uiBuilder: MainUiBuilder
    private val TARGET_PACKAGE = "com.kugou.android.lite"
    
    // 省流：保证一秒内拖动几十次，最多触发一次跨进程传输♪(′ε′‧̣̥̇)
    private var lastNotifyTime = 0L
    private val NOTIFY_THROTTLE_MS = 300L

    // 省流：保证审计日志控制台不会因为同步警告而刷屏爆满（每 3 秒最多输出一次）૮₍ ˊᯅˋ₎ა
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("haptics_config", Context.MODE_PRIVATE)

        //  在 setContentView 之前建立全屏幕穿透，消灭黑边gugugaga
        initImmersionStatusBar()

        uiBuilder = MainUiBuilder(
            activity = this,
            prefs = prefs,
            onSelectWallpaper = {
                // 【核心修复】：丢弃 ACTION_OPEN_DOCUMENT（文件管理），
                // 采用外部图像媒体库 ACTION_PICK，彻底强制唤醒系统自带相册！
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

        if (isTargetAppRunning(TARGET_PACKAGE) && isAppUpdated()) {
            executeRootAuditFlow()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_WALLPAPER && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            try {
                // ⚡ 申请持久化 URI 访问权限，即使模块重启，依然能看大老婆壁纸
                val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(logReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(logReceiver)
    }

    // 好像没鸟用终极修复：“黑边”消灭器！开启全屏 Edge-to-Edge 渲染
    private fun initImmersionStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        // 让系统内容区域（包括壁纸）无视状态栏与导航栏高度，彻底占满屏幕(ᗜ ˰ ᗜ) ​
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
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
        } catch (e: Exception) { 79L } // ㅎㅅㅎ 版本迭代升级：Build 79！

        val lastVersionCode = prefs.getLong("last_version_code", -1L)
        if (currentVersionCode > lastVersionCode) {
            prefs.edit().putLong("last_version_code", currentVersionCode).apply()
            return true
        }
        return false
    }

    // (´ཫ`)雑魚雑魚 极致防洪：不仅限制同步频率，更锁死警告日志输出频率，终结刷屏！
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
            e.printStackTrace()
            // ⚡ 漏斗：限制警告每 3000ms 最多在日志区打印一次，防止疯狂刷屏导致闪退或迟钝
            val now = System.currentTimeMillis()
            if (now - lastLogTime > 3000) {
                lastLogTime = now
                if (::uiBuilder.isInitialized) {
                    uiBuilder.appendLog("⚠️ [同步通道挂起] 仅保存在本地。请在大杂鱼老师的 AndroidManifest 中配置 Provider 即可完美激活！")
                }
            }
        }
    }

    private fun executeRootAuditFlow() {
        if (checkRootSilent()) {
            showHyperDialog(hasRoot = true)
        } else {
            showHyperDialog(hasRoot = false)
        }
    }

    private fun showHyperDialog(hasRoot: Boolean) {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 60, 64, 60)
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 68f
                setColor(Color.parseColor("#FAFAFA"))
            }
        }

        val titleView = TextView(context).apply {
            text = if (hasRoot) "审计中心系统提示" else "核心授权请求"
            textSize = 20f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 12, 0, 32)
        }
        container.addView(titleView)

        val descView = TextView(context).apply {
            text = if (hasRoot) {
                "大杂鱼老师，宿主音频参数流已经升级！为了防止你的爪机阻抗失调，现在必须强杀酷狗概念版来重新载入！"
            } else {
                "哼，大杂鱼老师居然没有给 Root 权限！快去 Magisk/KernelSU 里面给本大人点下允许授权啦！"
            }
            textSize = 15f
            setTextColor(Color.parseColor("#43474E"))
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, 56)
        }
        container.addView(descView)

        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttonLayout.setLayoutParams(LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val cancelBtn = Button(context).apply {
            text = "取消"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor("#3F474F"))
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.WHITE)
                setStroke(2, Color.parseColor("#DCE2E9"))
            }
        }
        cancelBtn.setLayoutParams(LinearLayout.LayoutParams(0, 116, 1f).apply {
            setMargins(0, 0, 16, 0)
        })

        val confirmBtn = Button(context).apply {
            text = if (hasRoot) "立即重启" else "重试检测"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 36f
                setColor(Color.parseColor("#FF0078FA"))
            }
        }
        confirmBtn.setLayoutParams(LinearLayout.LayoutParams(0, 116, 1f).apply {
            setMargins(16, 0, 0, 0)
        })

        buttonLayout.addView(cancelBtn)
        buttonLayout.addView(confirmBtn)
        container.addView(buttonLayout)

        val dialog = AlertDialog.Builder(context, AlertDialog.THEME_DEVICE_DEFAULT_LIGHT)
            .setView(container)
            .create()

        dialog.window?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = 60
            }
            setDimAmount(0.16f)
        }

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            if (hasRoot) {
                try {
                    val process = Runtime.getRuntime().exec("su")
                    val os = process.outputStream
                    os.write("am force-stop $TARGET_PACKAGE\n".toByteArray())
                    os.write("exit\n".toByteArray())
                    os.flush()
                    process.waitFor()
                } catch (e: Exception) {
                    descView.text = "切，自动强杀失败，大杂鱼老师自己去系统设置手动停止吧~|･з･)｡"
                }
            } else {
                executeRootAuditFlow()
            }
        }

        dialog.show()
    }

    private fun checkRootSilent(): Boolean {
        return try {
            val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/local/su")
            for (path in paths) { if (File(path).exists()) return true }
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("exit\n".toByteArray())
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) { false }
    }

    private fun isTargetAppRunning(packageName: String): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningProcesses = am.runningAppProcesses ?: return false
            for (processInfo in runningProcesses) {
                if (processInfo.processName == packageName) return true
            }
            false
        } catch (e: Exception) { false }
    }
}
