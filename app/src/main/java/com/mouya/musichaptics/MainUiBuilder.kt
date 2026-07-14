package com.mouya.musichaptics

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ⚡ MusicHapticsX 极致拟物动效 UI 渲染核（视觉清爽优化版） ⚡
 */
class MainUiBuilder(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val onSelectWallpaper: () -> Unit,
    private val onClearWallpaper: () -> Unit,
    private val onConfigChanged: () -> Unit
) {

    private var lastSliderHapticTime = 0L
    private lateinit var logTextView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var bgImageView: ImageView
    private lateinit var bgOverlayView: View
    
    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // 核心物理硬件振动反馈通道
    private val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private fun dpToPx(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }

    private fun dpToPxF(dp: Float): Float {
        return dp * activity.resources.displayMetrics.density
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            activity.resources.getDimensionPixelSize(resourceId)
        } else {
            dpToPx(24)
        }
    }

    /**
     * 直接在审计日志面板追加日志，自带炫酷时间戳，自动滚到底
     */
    fun appendLog(msg: String) {
        activity.runOnUiThread {
            if (::logTextView.isInitialized && ::logScroll.isInitialized) {
                val timeStr = logTimeFormat.format(Date())
                if (logTextView.text.isEmpty()) {
                    logTextView.text = "[$timeStr] $msg"
                } else {
                    logTextView.append("\n[$timeStr] $msg")
                }
                logScroll.post {
                    logScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    /**
     * 刷新背景图：降低遮罩暗度，还给大杂鱼老师一个明亮通透的香奈！
     */
    fun updateBackground(uriString: String?) {
        activity.runOnUiThread {
            if (::bgImageView.isInitialized && ::bgOverlayView.isInitialized) {
                // ⚡ 视觉调整 1：从 #90000000 降到 #25000000，拒绝死黑，轻薄通透
                bgOverlayView.setBackgroundColor(Color.parseColor("#25000000"))
                bgImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                
                if (!uriString.isNullOrEmpty()) {
                    try {
                        bgImageView.setImageURI(Uri.parse(uriString))
                    } catch (e: Exception) {
                        e.printStackTrace()
                        val resId = activity.resources.getIdentifier("default_bg", "drawable", activity.packageName)
                        if (resId > 0) bgImageView.setImageResource(resId)
                    }
                } else {
                    val resId = activity.resources.getIdentifier("default_bg", "drawable", activity.packageName)
                    if (resId > 0) {
                        bgImageView.setImageResource(resId)
                    } else {
                        bgImageView.setImageDrawable(null)
                        bgOverlayView.setBackgroundColor(Color.parseColor("#F2F2F7"))
                    }
                }
            }
        }
    }

    private fun vibrateClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createOneShot(12, 180)) 
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun vibrateTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createOneShot(6, 110)) 
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerSafeSliderHaptic() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSliderHapticTime >= 40) {
            vibrateTick() 
            lastSliderHapticTime = currentTime
        }
    }

    private fun applySpringAndLiquidClick(view: View, onClick: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val rippleColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#15000000"))
            val mask = android.graphics.drawable.ColorDrawable(Color.BLACK)
            view.foreground = android.graphics.drawable.RippleDrawable(rippleColor, null, mask)
        }

        view.setOnTouchListener { v, event ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                v.foreground?.setHotspot(event.x, event.y)
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.pivotX = v.width / 2f
                    v.pivotY = v.height / 2f
                    v.animate().scaleX(0.93f).scaleY(0.95f).setDuration(120).setInterpolator(DecelerateInterpolator()).start()
                    vibrateClick() 
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.animate().cancel()
                    val reboundX = android.animation.ObjectAnimator.ofFloat(v, "scaleX", 0.93f, 1.04f, 0.98f, 1.0f)
                    val reboundY = android.animation.ObjectAnimator.ofFloat(v, "scaleY", 0.95f, 1.03f, 0.99f, 1.0f)
                    android.animation.AnimatorSet().apply {
                        playTogether(reboundX, reboundY)
                        duration = 420
                        interpolator = PathInterpolator(0.15f, 1.65f, 0.30f, 1.0f)
                        start()
                    }
                    vibrateTick() 
                    onClick()
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            true
        }
    }

    private fun toggleLogWithSpring(container: View, isExpanding: Boolean) {
        container.clearAnimation()
        if (isExpanding) {
            container.visibility = View.VISIBLE
            container.alpha = 0f
            val matchParentMeasureSpec = View.MeasureSpec.makeMeasureSpec((container.parent as View).width, View.MeasureSpec.EXACTLY)
            val wrapContentMeasureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            container.measure(matchParentMeasureSpec, wrapContentMeasureSpec)
            val targetHeight = dpToPx(180)

            container.layoutParams.height = 0
            container.requestLayout()

            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 450
                interpolator = PathInterpolator(0.15f, 1.55f, 0.35f, 1.0f)
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    container.layoutParams.height = (targetHeight * value).toInt()
                    container.alpha = value
                    container.requestLayout()
                }
                start()
            }
        } else {
            val startHeight = container.height
            android.animation.ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 300
                interpolator = PathInterpolator(0.3f, 1.0f, 0.5f, 1.0f)
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    container.layoutParams.height = (startHeight * value).toInt()
                    container.alpha = value
                    container.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        container.visibility = View.GONE
                    }
                })
                start()
            }
        }
    }

    private fun createStyledGroup(title: String?, vararg items: View): LinearLayout {
        val groupLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(18))
            }
        }

        if (!title.isNullOrEmpty()) {
            val titleView = TextView(activity).apply {
                text = title
                textSize = 13f
                // ⚡ 视觉调整 2：既然整体背景变浅了，分组副标题的标签字改成深灰色，防止看不见
                setTextColor(Color.parseColor("#555555")) 
                setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(6))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            groupLayout.addView(titleView)
        }

        val cardLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(14).toFloat()
                setColor(Color.argb(230, 255, 255, 255)) 
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(1).toFloat()
                clipToOutline = true
            }
        }

        for (i in items.indices) {
            val item = items[i]
            cardLayout.addView(item)

            if (i < items.size - 1) {
                val divider = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(1)
                    ).apply {
                        setMargins(dpToPx(16), 0, 0, 0)
                    }
                    // 分隔线调整为淡淡的浅灰，契合白色全局流
                    setBackgroundColor(Color.parseColor("#E5E5EA"))
                }
                cardLayout.addView(divider)
            }
        }

        groupLayout.addView(cardLayout)
        return groupLayout
    }

    private fun createStyledTextItem(
        title: String, 
        subtitle: String? = null, 
        actionView: View? = null, 
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val finalOnClick = onClick ?: if (actionView is SpringSwitch) {
            { actionView.isChecked = !actionView.isChecked }
        } else {
            null
        }

        val itemLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            
            if (finalOnClick != null) {
                applySpringAndLiquidClick(this, finalOnClick)
            }
        }

        val textLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#1C1C1E"))
        }
        textLayout.addView(titleView)

        if (!subtitle.isNullOrEmpty()) {
            val subView = TextView(activity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#636366"))
                setPadding(0, dpToPx(2), 0, 0)
            }
            textLayout.addView(subView)
        }

        itemLayout.addView(textLayout)

        if (actionView != null) {
            actionView.isClickable = false
            actionView.isFocusable = false
            itemLayout.addView(actionView)
        } else if (finalOnClick != null) {
            val arrow = TextView(activity).apply {
                text = "〉"
                textSize = 14f
                setTextColor(Color.parseColor("#C4C4C6"))
            }
            itemLayout.addView(arrow)
        }

        return itemLayout
    }

    private fun createStyledSliderItem(
        title: String, 
        currentVal: Float, 
        isFloat: Boolean,
        maxProgress: Int, 
        minProgress: Int,
        valueSuffix: String = "",
        onValChanged: (Float) -> Unit
    ): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
        }

        val titleText = if (isFloat) {
            "$title：${String.format("%.1f", currentVal)}$valueSuffix"
        } else {
            "$title：${currentVal.toInt()}$valueSuffix"
        }

        val titleView = TextView(activity).apply {
            text = titleText
            textSize = 15f
            setTextColor(Color.parseColor("#1C1C1E"))
            setPadding(0, 0, 0, dpToPx(8))
        }
        container.addView(titleView)

        val seekBar = SeekBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(28))
            max = maxProgress - minProgress
            progress = if (isFloat) {
                ((currentVal - (minProgress / 100f)) * 100).toInt().coerceIn(0, max)
            } else {
                (currentVal.toInt() - minProgress).coerceIn(0, max)
            }

            val trackGrad = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#E5E5EA"))
            }
            val progressGrad = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                setColor(Color.parseColor("#007AFF"))
            }
            val progressClip = ClipDrawable(progressGrad, Gravity.START, ClipDrawable.HORIZONTAL)
            progressDrawable = LayerDrawable(arrayOf(trackGrad, progressClip)).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }

            thumb = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setSize(dpToPx(38), dpToPx(20))
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dpToPx(1), Color.parseColor("#D1D1D6"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dpToPx(2).toFloat()
                }
            }
            thumbOffset = dpToPx(8)
            splitTrack = false

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                    val finalVal = if (isFloat) {
                        (minProgress / 100f) + (prog / 100f)
                    } else {
                        (minProgress + prog).toFloat()
                    }

                    titleView.text = if (isFloat) {
                        "$title：${String.format("%.1f", finalVal)}$valueSuffix"
                    } else {
                        "$title：${finalVal.toInt()}$valueSuffix"
                    }

                    if (isFloat) {
                        prefs.edit().putFloat(title.lowercase().replace(" ", "_"), finalVal).apply()
                    } else {
                        prefs.edit().putInt(title.lowercase().replace(" ", "_"), finalVal.toInt()).apply()
                    }

                    if (fromUser) {
                        triggerSafeSliderHaptic() 
                        onConfigChanged()
                    }
                }
                
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    sb?.animate()?.scaleX(1.02f)?.scaleY(1.18f)?.setDuration(220)?.setInterpolator(PathInterpolator(0.2f, 1.4f, 0.4f, 1.0f))?.start()
                    vibrateClick()
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(300)?.setInterpolator(OvershootInterpolator(1.8f))?.start()
                    vibrateTick()
                }
            })
        }

        container.addView(seekBar)
        return container
    }

    fun buildView(): View {
        val rootLayout = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        bgImageView = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        rootLayout.addView(bgImageView)

        bgOverlayView = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        rootLayout.addView(bgOverlayView)

        val savedWallpaper = prefs.getString("wallpaper_uri", null)
        updateBackground(savedWallpaper)

        val rootScrollView = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(24))
        }

        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val topPadding = getStatusBarHeight() + dpToPx(16)
            setPadding(dpToPx(8), topPadding, dpToPx(8), dpToPx(20))
        }
        val mainTitle = TextView(activity).apply {
            text = "MusicHapticsX"
            textSize = 34f
            // ⚡ 视觉调整 3：背景变浅后，大标题用深沉庄严的黑灰色（#1C1C1E），对比度拉满，极为醒目
            setTextColor(Color.parseColor("#1C1C1E")) 
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        }
        val subTitle = TextView(activity).apply {
            val verCode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
                }
            } catch (e: Exception) { 79L }
            text = "大杂鱼 调校( ⩌⤚⩌) Build $verCode"
            textSize = 13f
            setTextColor(Color.parseColor("#34C759")) 
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(4), 0, 0)
        }
        headerLayout.addView(mainTitle)
        headerLayout.addView(subTitle)
        mainLayout.addView(headerLayout)

        val masterSwitch = SpringSwitch(activity).apply {
            isChecked = prefs.getBoolean("master_switch", true)
            setOnCheckedChangeListener { checked ->
                prefs.edit().putBoolean("master_switch", checked).apply()
                onConfigChanged()
            }
        }
        val groupMaster = createStyledGroup(
            "CORE SYSTEM / 核心系统",
            createStyledTextItem("高频低延迟触感引擎", "当前状态：已强行注入系统参数流", masterSwitch)
        )
        mainLayout.addView(groupMaster)

        val gainSlider = createStyledSliderItem("Haptic Gain", prefs.getFloat("haptic_gain", 1.0f), true, 200, 50, "x") { onConfigChanged() }
        val sizeSlider = createStyledSliderItem("Haptic Frame Size", prefs.getInt("haptic_frame_size", 240).toFloat(), false, 512, 32, " samples") { onConfigChanged() }
        val intervalSlider = createStyledSliderItem("Haptic Interval", prefs.getInt("haptic_interval", 0).toFloat(), false, 100, 0, " ms") { onConfigChanged() }
        val thresholdSlider = createStyledSliderItem("Haptic Threshold", prefs.getInt("haptic_threshold", 1200).toFloat(), false, 5000, 500, "") { onConfigChanged() }

        val groupSliders = createStyledGroup(
            "AUDIOPHILE HAPTICS / 音频振动控制参数",
            gainSlider, sizeSlider, intervalSlider, thresholdSlider
        )
        mainLayout.addView(groupSliders)

        val selectWallpaperItem = createStyledTextItem("从相册选择自定义背景壁纸", "支持任意高清图片，自动匹配卡片磨砂透光") {
            onSelectWallpaper()
        }
        val clearWallpaperItem = createStyledTextItem("清除当前自定义背景", "恢复默认") {
            onClearWallpaper()
        }
        val groupWallpaper = createStyledGroup(
            "PERSONALIZATION / 视觉个性化",
            selectWallpaperItem, clearWallpaperItem
        )
        mainLayout.addView(groupWallpaper)

        // 🌟 视觉调整 4：重构审计日志面板！拿掉黑框，改用高阶浅灰卡片底（#F2F2F7）
        logScroll = ScrollView(activity).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#F2F2F7")) // 顺应白色大流的浅系统灰底
            }
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(180)
            ).apply {
                setMargins(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            }
        }
        
        logTextView = TextView(activity).apply {
            text = "" 
            textSize = 11f // ⚡ 彻底锁死 Float 类型，绝不引发任何编译崩溃
            setTextColor(Color.parseColor("#2C2C2E")) // 优雅的深灰黑字体，极度清晰易读
            typeface = android.graphics.Typeface.MONOSPACE 
        }
        logScroll.addView(logTextView)
        
        appendLog("【系统就绪】跨进程共享通道就绪。大杂鱼老师，开始你的电音秀！")

        val logTriggerItem = createStyledTextItem("⚙️ 查看核心审计数据流", "点击展开 / 折叠系统实时同步日志", logScroll) {
            val isExpanding = logScroll.visibility == View.GONE
            toggleLogWithSpring(logScroll, isExpanding)
        }
        val groupLog = createStyledGroup("SYSTEM LOG / 审计日志", logTriggerItem)
        mainLayout.addView(groupLog)

        val devQqItem = createStyledTextItem(
            "开发者：もうや", 
            "QQ：3695088119\nQQ交流群：1047262325"
        ) {
            Toast.makeText(activity, "不要再点了啦～₍ᐢ⸝⸝› ̫‹⸝⸝ᐢ₎", Toast.LENGTH_SHORT).show()
        }
        val groupDev = createStyledGroup("DEVELOPER / 关于作者", devQqItem)
        mainLayout.addView(groupDev)

        rootScrollView.addView(mainLayout)
        rootLayout.addView(rootScrollView)

        mainLayout.post {
            val count = mainLayout.childCount
            for (i in 0 until count) {
                val child = mainLayout.getChildAt(i)
                if (child == logScroll) continue
                child.alpha = 0f
                child.translationY = dpToPxF(40f)
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(600)
                    .setStartDelay(i * 90L)
                    .setInterpolator(PathInterpolator(0.15f, 1.45f, 0.30f, 1.0f))
                    .start()
            }
        }

        return rootLayout
    }

    inner class SpringSwitch(context: Context) : FrameLayout(context) {
        var isChecked = false
            set(value) {
                if (field != value) {
                    field = value
                    updateState(animate = true)
                }
            }

        private val trackDrawable = GradientDrawable().apply {
            cornerRadius = dpToPxF(16f)
            setColor(Color.parseColor("#E9E9EB"))
        }

        private val thumbView = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dpToPxF(1.5f)
                }
            }
        }

        private var onCheckedChangeListener: ((Boolean) -> Unit)? = null

        init {
            background = trackDrawable
            val widthPx = dpToPxF(51f).toInt()
            val heightPx = dpToPxF(31f).toInt()
            layoutParams = LayoutParams(widthPx, heightPx)

            val thumbSize = dpToPxF(27f).toInt()
            val thumbParams = LayoutParams(thumbSize, thumbSize).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                leftMargin = dpToPxF(2f).toInt()
            }
            addView(thumbView, thumbParams)

            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.90f).scaleY(0.90f).setDuration(120).start()
                        vibrateClick() 
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
                        vibrateTick() 
                        toggleCheckedState()
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                }
                true
            }
        }

        fun setOnCheckedChangeListener(listener: (Boolean) -> Unit) {
            onCheckedChangeListener = listener
        }

        private fun toggleCheckedState() {
            isChecked = !isChecked
            onCheckedChangeListener?.invoke(isChecked)
        }

        private fun updateState(animate: Boolean) {
            val targetColor = if (isChecked) Color.parseColor("#34C759") else Color.parseColor("#E9E9EB")
            val targetTranslation = if (isChecked) dpToPxF(20f) else 0f

            if (animate) {
                android.animation.ValueAnimator.ofArgb(
                    if (isChecked) Color.parseColor("#E9E9EB") else Color.parseColor("#34C759"),
                    targetColor
                ).apply {
                    duration = 250
                    addUpdateListener { animator -> trackDrawable.setColor(animator.animatedValue as Int) }
                }.start()

                val startTranslation = thumbView.translationX
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 380
                    interpolator = PathInterpolator(0.25f, 1.45f, 0.40f, 1.0f)
                    addUpdateListener { animator ->
                        val fraction = animator.animatedValue as Float
                        thumbView.translationX = startTranslation + (targetTranslation - startTranslation) * fraction
                        val stretchFactor = 1.0f - Math.abs(fraction - 0.5f) * 2.0f
                        thumbView.pivotX = if (isChecked) 0f else thumbView.width.toFloat()
                        thumbView.scaleX = 1.0f + (0.32f * stretchFactor)
                        thumbView.scaleY = 1.0f - (0.15f * stretchFactor)
                    }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            thumbView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).setInterpolator(OvershootInterpolator(1.5f)).start()
                        }
                    })
                }.start()
            } else {
                trackDrawable.setColor(targetColor)
                thumbView.translationX = targetTranslation
                thumbView.scaleX = 1.0f
                thumbView.scaleY = 1.0f
            }
        }

        private fun dpToPxF(dp: Float): Float {
            return dp * context.resources.displayMetrics.density
        }
    }
}
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
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
            佛祖保佑       永无BUG
*/