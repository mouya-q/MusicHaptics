package com.mouya.musichaptics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.TimeInterpolator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.*
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.*
import android.view.animation.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT

class LiquidGlassBuilder(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val onSelectWallpaper: () -> Unit,
    private val onClearWallpaper: () -> Unit,
    private val onConfigChanged: () -> Unit
) {
    companion object {
        private const val CARD_RADIUS_DP = 24
        private const val SHEEN_MAX_OFFSET_DP = 60
        private const val SHEEN_ALPHA_PRESS = 0.15f 
        private const val SHEEN_ALPHA_PEAK = 0.45f
        private const val SHEEN_PULSE_ALPHA = 0.50f
        private const val GLOSS_ALPHA = 0.25f      
        private const val SPRING_COMPRESS = 0.95f 
        private const val SHEEN_FOLLOW_MS = 180L
        private const val SHEEN_RETURN_MS = 1200L
        private const val SHEEN_FADE_MS = 400L
        private const val SLIDER_HAPTIC_THROTTLE_MS = 40L

        private val BODY_GRADIENT = intArrayOf(
            Color.argb(0x33, 0xFF, 0xFF, 0xFF), 
            Color.argb(0x1A, 0xFF, 0xFF, 0xFF), 
            Color.argb(0x10, 0xFF, 0xFF, 0xFF)  
        )
        
        private val RIM_LIGHT_COLOR = Color.argb(0x33, 0xFF, 0xFF, 0xFF)
        private val RIPPLE_COLOR = Color.argb(0x14, 0xFF, 0xFF, 0xFF)
    }

    private var lastSliderHapticTime = 0L
    private lateinit var logTextView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var bgImageView: ImageView
    private lateinit var bgOverlayView: View
    private lateinit var rootLayout: FrameLayout

    // ── 预设档位系统 ──
    private var currentPresetLevel = prefs.getInt("haptic_preset_level", 1) // 0-3,默认Standard

    private val presetDefs = listOf(
        PresetDef("Light",  "轻柔", 0.70f, 0.50f, 15, 256, 30,  3500, 0.55f),
        PresetDef("Std",    "标准", 1.20f, 1.00f, 40, 192, 18,  2000, 1.00f),
        PresetDef("Strong", "强劲", 1.80f, 1.50f, 60, 160, 10,  1000, 1.70f),
        PresetDef("Extreme","狂暴", 2.50f, 2.00f, 80, 128, 5,    400, 2.50f)
    )

    data class PresetDef(
        val label: String, val labelCN: String,
        val gain: Float, val amplitude: Float, val bassPurity: Int,
        val frameSize: Int, val interval: Int, val threshold: Int,
        val boostLevel: Float
    )

    private fun applyPreset(level: Int) {
        if (level !in 0..3) return
        currentPresetLevel = level
        val p = presetDefs[level]
        prefs.edit()
            .putInt("haptic_preset_level", level)
            .putFloat("haptic_gain", p.gain)
            .putFloat("haptic_amplitude", p.amplitude)
            .putInt("haptic_bass_purity", p.bassPurity)
            .putInt("haptic_frame_size", p.frameSize)
            .putInt("haptic_interval", p.interval)
            .putInt("haptic_threshold", p.threshold)
            .putFloat("haptic_boost_level", p.boostLevel)
            .apply()
        onConfigChanged()
    }

    private val logTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val density: Float get() = activity.resources.displayMetrics.density
    private fun dp(i: Int): Int = (i * density).toInt()
    private fun dpf(f: Float): Float = f * density

    private fun getStatusBarHeight(): Int {
        val resourceId = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    // Underdamped harmonic oscillator spring interpolation
    class QuantumSpringInterpolator(
        private val damping: Float = 0.45f,     
        private val stiffness: Float = 16.0f    
    ) : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            if (t == 0f) return 0f
            if (t == 1f) return 1f
            
            val omegaN = stiffness
            val zeta = damping
            val omegaD = omegaN * Math.sqrt(1.0 - zeta * zeta)
            val envelope = Math.exp(-zeta * omegaN * t.toDouble())
            val cosPart = Math.cos(omegaD * t.toDouble())
            val sinPart = (zeta / Math.sqrt(1.0 - zeta * zeta)) * Math.sin(omegaD * t.toDouble())
            
            return (1.0 - envelope * (cosPart + sinPart)).toFloat()
        }
    }

    private val quantumBounceInterpolator = QuantumSpringInterpolator(damping = 0.42f, stiffness = 15.5f)

    fun appendLog(msg: String) {
        activity.runOnUiThread {
            if (::logTextView.isInitialized && ::logScroll.isInitialized) {
                val t = logTimeFormat.format(Date())
                logTextView.text = if (logTextView.text.isEmpty()) "[$t] $msg"
                else logTextView.text.toString() + "\n[$t] $msg"
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    fun updateBackground(uriString: String?) {
        activity.runOnUiThread {
            if (::bgImageView.isInitialized && ::bgOverlayView.isInitialized) {
                bgOverlayView.setBackgroundColor(Color.TRANSPARENT)
                bgImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    bgImageView.setRenderEffect(null)
                if (!uriString.isNullOrEmpty()) {
                    try { bgImageView.setImageURI(Uri.parse(uriString)) }
                    catch (_: Exception) { setDefaultBackground() }
                } else setDefaultBackground()
            }
        }
    }

    private fun setDefaultBackground() {
        val id = activity.resources.getIdentifier("default_bg", "drawable", activity.packageName)
        if (id > 0) bgImageView.setImageResource(id)
        else {
            bgImageView.setImageDrawable(null)
            bgOverlayView.setBackgroundColor(Color.parseColor("#2C2C2E"))
        }
    }

    private fun vibrateClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            else vibrator.vibrate(VibrationEffect.createOneShot(12, 180))
        } catch (_: Exception) {}
    }

    private fun vibrateTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            else vibrator.vibrate(VibrationEffect.createOneShot(6, 110))
        } catch (_: Exception) {}
    }

    private fun triggerSafeSliderHaptic() {
        val now = System.currentTimeMillis()
        if (now - lastSliderHapticTime >= SLIDER_HAPTIC_THROTTLE_MS) {
            vibrateTick()
            lastSliderHapticTime = now
        }
    }

    // Outer shadow renderer with mask clipping to keep translucent area clean
    private class IosPremiumShadowDrawable(
        private val radius: Float,
        private val marginLeft: Float,
        private val marginTop: Float,
        private val marginRight: Float,
        private val marginBottom: Float
    ) : Drawable() {
        private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(28f, 0f, 6f, Color.argb(0x1A, 0, 0, 0))
        }
        private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(14f, 0f, 12f, Color.argb(0x14, 0, 0, 0))
        }
        private val shadowRect = RectF()
        private val clipPath = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            shadowRect.set(
                b.left + marginLeft,
                b.top + marginTop,
                b.right - marginRight,
                b.bottom - marginBottom
            )
            
            clipPath.reset()
            clipPath.addRoundRect(shadowRect, radius, radius, Path.Direction.CW)

            canvas.save()
            // Exclude internal card bounds to prevent bleeding into transparent background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutPath(clipPath)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipPath(clipPath, Region.Op.DIFFERENCE)
            }

            canvas.drawRoundRect(shadowRect, radius, radius, ambientPaint)
            canvas.drawRoundRect(shadowRect, radius, radius, keyPaint)
            canvas.restore()
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // Core glass card component layout structure
    inner class LiquidGlassCard(
        context: Context,
        private val contentView: View
    ) : FrameLayout(context) {

        private val blurContainer: View
        private val bodyDrawable: GradientDrawable
        private val rimLightDrawable: GradientDrawable
        private val specularSheen: View
        private val glossLayerView: View
        val contentContainer: FrameLayout
        
        private var sheenAnimator: ViewPropertyAnimator? = null
        private var isSheenReturning = false

        init {
            val marginL = dp(16).toFloat()
            val marginT = dp(12).toFloat()
            val marginR = dp(16).toFloat()
            val marginB = dp(20).toFloat()

            // Outer clipped shadow component
            val shadowView = View(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
                background = IosPremiumShadowDrawable(
                    dpf(CARD_RADIUS_DP.toFloat()),
                    marginL, marginT, marginR, marginB
                )
            }
            addView(shadowView)

            val glassWrapper = FrameLayout(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
                    setMargins(marginL.toInt(), marginT.toInt(), marginR.toInt(), marginB.toInt())
                }
            }

            blurContainer = View(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dpf(CARD_RADIUS_DP.toFloat()))
                    }
                }
                clipToOutline = true
            }

            bodyDrawable = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, BODY_GRADIENT
            ).apply { cornerRadius = dpf(CARD_RADIUS_DP.toFloat()) }
            blurContainer.background = bodyDrawable

            rimLightDrawable = GradientDrawable().apply {
                cornerRadius = dpf(CARD_RADIUS_DP.toFloat())
                setStroke(dp(1), RIM_LIGHT_COLOR)
                setColor(Color.TRANSPARENT)
            }
            val rimLightOverlay = View(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
                background = rimLightDrawable
                isClickable = false; isFocusable = false
            }

            specularSheen = View(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(0x33,0xFF,0xFF,0xFF), Color.TRANSPARENT)
                ).apply { cornerRadius = dpf(CARD_RADIUS_DP.toFloat()) }
                alpha = 0f
                isClickable = false; isFocusable = false
            }

            glossLayerView = View(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, dp(80))
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(0x40,0xFF,0xFF,0xFF), Color.argb(0x00,0xFF,0xFF,0xFF))
                ).apply { cornerRadius = dpf(CARD_RADIUS_DP.toFloat()) }
                alpha = GLOSS_ALPHA
                isClickable = false; isFocusable = false
            }

            contentContainer = FrameLayout(context).apply {
                layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                addView(contentView)
            }

            glassWrapper.addView(blurContainer)    
            glassWrapper.addView(rimLightOverlay)  
            glassWrapper.addView(specularSheen)    
            glassWrapper.addView(glossLayerView)   
            glassWrapper.addView(contentContainer) 
            
            addView(glassWrapper)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                elevation = 0f
                clipToOutline = false
            }
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val xFraction = ev.x / width
            when (ev.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    updateSheenPosition(xFraction)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    returnSheen()
                    pulseSheen()
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        private fun updateSheenPosition(xFraction: Float) {
            if (isSheenReturning) sheenAnimator?.cancel()
            isSheenReturning = false
            val maxOffset = dpf(SHEEN_MAX_OFFSET_DP.toFloat())
            val offset = (xFraction - 0.5f) * 2f * maxOffset
            val dist = kotlin.math.abs(xFraction - 0.5f) * 2f
            val alpha = SHEEN_ALPHA_PRESS + (SHEEN_ALPHA_PEAK - SHEEN_ALPHA_PRESS) * dist.coerceIn(0f,1f)
            
            sheenAnimator = specularSheen.animate()
                .translationX(offset)
                .alpha(alpha)
                .setDuration(SHEEN_FOLLOW_MS)
                .setInterpolator(DecelerateInterpolator())
        }

        private fun returnSheen() {
            sheenAnimator?.cancel()
            isSheenReturning = true
            sheenAnimator = specularSheen.animate()
                .translationX(0f)
                .setDuration(SHEEN_RETURN_MS)
                .setInterpolator(DecelerateInterpolator())
                .setUpdateListener { a ->
                    val f = a.animatedFraction
                    val eased = 1f - (1f - f) * (1f - f)
                    specularSheen.alpha = SHEEN_ALPHA_PEAK * (1f - eased)
                    if (eased > 0.95f) {
                        ValueAnimator.ofFloat(specularSheen.alpha, 0f).apply {
                            duration = SHEEN_FADE_MS
                            addUpdateListener { specularSheen.alpha = it.animatedValue as Float }
                            start()
                        }
                        a.removeAllUpdateListeners()
                    }
                }
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        specularSheen.alpha = 0f
                        specularSheen.translationX = 0f
                        isSheenReturning = false
                        sheenAnimator = null
                    }
                })
        }

        private fun pulseSheen() {
            specularSheen.animate().cancel()
            specularSheen.alpha = SHEEN_PULSE_ALPHA
            specularSheen.animate()
                .alpha(0f)
                .setDuration(SHEEN_FADE_MS)
                .setStartDelay(40)
                .setInterpolator(DecelerateInterpolator())
        }
    }

    private fun createStyledTextItem(
        title: String,
        subtitle: String? = null,
        actionView: View? = null,
        onClick: (() -> Unit)? = null
    ): LinearLayout {
        val finalOnClick = onClick ?: if (actionView is SpringSwitch) {
            { actionView.isChecked = !actionView.isChecked }
        } else null

        val itemLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            if (finalOnClick != null) applyQuantumSpringClick(this, finalOnClick)
        }

        val textLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(activity).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#E6000000"))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        textLayout.addView(titleView)

        if (!subtitle.isNullOrEmpty()) {
            textLayout.addView(TextView(activity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.parseColor("#99000000"))
                setPadding(0, dp(4), 0, 0)
            })
        }

        itemLayout.addView(textLayout)

        if (actionView != null) {
            actionView.isClickable = false
            actionView.isFocusable = false
            itemLayout.addView(actionView)
        } else if (finalOnClick != null) {
            itemLayout.addView(TextView(activity).apply {
                text = "〉"
                textSize = 14f
                setTextColor(Color.parseColor("#66000000"))
            })
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
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val titleLabel = if (isFloat)
            "$title: ${String.format("%.1f", currentVal)}$valueSuffix"
        else
            "$title: ${currentVal.toInt()}$valueSuffix"

        val titleView = TextView(activity).apply {
            text = titleLabel
            textSize = 15f
            setTextColor(Color.parseColor("#E6000000"))
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(titleView)

        val seekBar = SeekBar(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(28))
            max = maxProgress - minProgress
            progress = if (isFloat)
                ((currentVal - (minProgress / 100f)) * 100).toInt().coerceIn(0, max)
            else
                (currentVal.toInt() - minProgress).coerceIn(0, max)

            val trackGrad = GradientDrawable().apply {
                cornerRadius = dpf(8f)
                setColor(Color.parseColor("#33000000"))
            }
            val progressGrad = GradientDrawable().apply {
                cornerRadius = dpf(8f)
                setColor(Color.parseColor("#80000000"))
            }
            progressDrawable = LayerDrawable(arrayOf(
                trackGrad, ClipDrawable(progressGrad, Gravity.START, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }

            thumb = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(26), dp(26))
                setColor(Color.parseColor("#F8FFFFFF"))
                setStroke(dp(1), Color.parseColor("#1A000000"))
            }
            thumbOffset = dp(13)
            splitTrack = false

            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                    val finalVal = if (isFloat)
                        (minProgress / 100f) + (prog / 100f)
                    else
                        (minProgress + prog).toFloat()

                    titleView.text = if (isFloat)
                        "$title: ${String.format("%.1f", finalVal)}$valueSuffix"
                    else
                        "$title: ${finalVal.toInt()}$valueSuffix"

                    val key = title.substringBefore("/").trim().lowercase().replace(" ", "_")
                    if (isFloat) prefs.edit().putFloat(key, finalVal).apply()
                    else prefs.edit().putInt(key, finalVal.toInt()).apply()

                    if (fromUser) {
                        triggerSafeSliderHaptic()
                        onValChanged(finalVal)
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    sb?.animate()?.cancel()
                    sb?.animate()?.scaleX(1.02f)?.scaleY(1.15f)
                        ?.setDuration(180)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.start()
                    vibrateClick()
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    sb?.animate()?.cancel()
                    val rx = ObjectAnimator.ofFloat(sb, "scaleX", sb?.scaleX ?: 1.02f, 1.0f)
                    val ry = ObjectAnimator.ofFloat(sb, "scaleY", sb?.scaleY ?: 1.15f, 1.0f)
                    AnimatorSet().apply {
                        playTogether(rx, ry)
                        duration = 550
                        interpolator = quantumBounceInterpolator
                        start()
                    }
                    vibrateTick()
                }
            })
        }

        container.addView(seekBar)
        return container
    }

    private fun applyQuantumSpringClick(view: View, onClick: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            view.foreground = RippleDrawable(
                android.content.res.ColorStateList.valueOf(RIPPLE_COLOR), null, ColorDrawable(Color.BLACK)
            )
        }
        view.setOnTouchListener { v, ev ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                v.foreground?.setHotspot(ev.x, ev.y)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.pivotX = v.width / 2f
                    v.pivotY = v.height / 2f
                    v.animate().cancel()
                    v.animate().scaleX(SPRING_COMPRESS).scaleY(SPRING_COMPRESS)
                        .setDuration(100).setInterpolator(DecelerateInterpolator()).start()
                    vibrateClick()
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    v.animate().cancel()
                    
                    val rx = ObjectAnimator.ofFloat(v, "scaleX", v.scaleX, 1.0f)
                    val ry = ObjectAnimator.ofFloat(v, "scaleY", v.scaleY, 1.0f)
                    AnimatorSet().apply {
                        playTogether(rx, ry)
                        duration = 580 
                        interpolator = quantumBounceInterpolator
                        start()
                    }
                    vibrateTick()
                    onClick()
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    v.animate().cancel()
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            }
            true
        }
    }

    private fun toggleLogWithSpring(logView: View, expand: Boolean) {
        if (expand) {
            logView.visibility = View.VISIBLE
            logView.alpha = 0f
            logView.translationY = -dpf(20f)
            logView.animate()
                .alpha(1f).translationY(0f)
                .setDuration(450)
                .setInterpolator(quantumBounceInterpolator)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(anim: Animator) {
                        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }).start()
        } else {
            logView.animate()
                .alpha(0f).translationY(-dpf(20f))
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(anim: Animator) {
                        logView.visibility = View.GONE
                        logView.translationY = 0f
                    }
                }).start()
        }
    }

    private fun createStyledGroup(title: String?, vararg items: View): View {
        val groupLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        if (!title.isNullOrEmpty()) {
            groupLayout.addView(TextView(activity).apply {
                text = title
                textSize = 13f
                setTextColor(Color.parseColor("#B3000000"))
                setShadowLayer(3f, 0f, 1f, Color.parseColor("#4DFFFFFF"))
                setPadding(dp(36), dp(12), dp(16), 0)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (i in items.indices) {
            contentLayout.addView(items[i])
            if (i < items.size - 1) {
                contentLayout.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
                        setMargins(dp(20), 0, dp(20), 0)
                    }
                    setBackgroundColor(Color.parseColor("#1A000000"))
                })
            }
        }

        val card = LiquidGlassCard(activity, contentLayout)
        groupLayout.addView(card)
        return groupLayout
    }

    fun buildView(): View {
        rootLayout = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        bgImageView = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        rootLayout.addView(bgImageView)

        bgOverlayView = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        rootLayout.addView(bgOverlayView)

        val savedWallpaper = prefs.getString("wallpaper_uri", null)
        updateBackground(savedWallpaper)

        val rootScrollView = ScrollView(activity).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val mainLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(0), 0, dp(0), dp(32))
        }

        // Header
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val topPadding = getStatusBarHeight() + dp(24)
            setPadding(dp(24), topPadding, dp(24), dp(12))
        }
        headerLayout.addView(TextView(activity).apply {
            text = "MusicHapticsX"
            textSize = 38f
            setTextColor(Color.parseColor("#E6000000"))
            setShadowLayer(12f, 0f, 4f, Color.parseColor("#66FFFFFF"))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        headerLayout.addView(TextView(activity).apply {
            val verCode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
                else
                    @Suppress("DEPRECATION") activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
            } catch (_: Exception) { 90L }
            text = "Liquid Glass Engine Build $verCode"
            textSize = 14f
            setTextColor(Color.parseColor("#99000000"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        })
        mainLayout.addView(headerLayout)

        // Master Switch
        val masterSwitch = SpringSwitch(activity).apply {
            isChecked = prefs.getBoolean("master_switch", true)
            setOnCheckedChangeListener { checked ->
                prefs.edit().putBoolean("master_switch", checked).apply()
                onConfigChanged()
            }
        }
        mainLayout.addView(createStyledGroup(
            "CORE SYSTEM / 核心系统",
            createStyledTextItem("Haptic Engine / 触感引擎",
                "Status: System parameters injected / 当前状态：已注入系统参数", masterSwitch)
        ))

        // ── 预设档位选择器 ──
        val presetContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
        }
        val presetBtns = mutableListOf<TextView>()
        for ((idx, def) in presetDefs.withIndex()) {
            val btn = TextView(activity).apply {
                text = "${def.label}\n${def.labelCN}"
                textSize = 11f; gravity = Gravity.CENTER
                setTextColor(if (idx == currentPresetLevel) Color.WHITE else Color.parseColor("#99000000"))
                background = GradientDrawable().apply {
                    cornerRadius = dpf(12f)
                    setColor(if (idx == currentPresetLevel) Color.parseColor("#80000000") else Color.TRANSPARENT)
                }
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
                setOnClickListener {
                    applyPreset(idx)
                    for (j in presetBtns.indices) {
                        val b = presetBtns[j]
                        (b.background as GradientDrawable).setColor(
                            if (j == idx) Color.parseColor("#80000000") else Color.TRANSPARENT
                        )
                        b.setTextColor(if (j == idx) Color.WHITE else Color.parseColor("#99000000"))
                    }
                    vibrateClick()
                }
            }
            presetBtns.add(btn)
            presetContainer.addView(btn)
        }
        mainLayout.addView(createStyledGroup("HAPTIC PRESET / 触感预设", presetContainer))

        // ── 高级设置折叠 ──
        val advancedPanel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val amp = createStyledSliderItem("Haptic Amplitude / 触感强度",
            prefs.getFloat("haptic_amplitude", 1.0f), true, 200, 10, "x") { onConfigChanged() }
        val bass = createStyledSliderItem("Haptic Bass Purity / 低频纯度",
            prefs.getInt("haptic_bass_purity", 14).toFloat(), false, 100, 0, "%") { onConfigChanged() }
        val gain = createStyledSliderItem("Haptic Gain / 触感增益",
            prefs.getFloat("haptic_gain", 1.0f), true, 200, 50, "x") { onConfigChanged() }
        val size = createStyledSliderItem("Haptic Frame Size / 分析帧大小",
            prefs.getInt("haptic_frame_size", 240).toFloat(), false, 512, 32, " samples") { onConfigChanged() }
        val interval = createStyledSliderItem("Haptic Interval / 触发间隔",
            prefs.getInt("haptic_interval", 14).toFloat(), false, 100, 0, " ms") { onConfigChanged() }
        val threshold = createStyledSliderItem("Haptic Threshold / 灵敏阈值",
            prefs.getInt("haptic_threshold", 1200).toFloat(), false, 5000, 500, "") { onConfigChanged() }
        listOf(amp, bass, gain, size, interval, threshold).forEach { advancedPanel.addView(it) }

        val advToggle = createStyledTextItem("Advanced Settings / 高级调校",
            "Fine-tune all DSP parameters manually / 手动微调全部DSP参数", null) {
            val expanding = advancedPanel.visibility == View.GONE
            if (expanding) {
                advancedPanel.visibility = View.VISIBLE
                advancedPanel.alpha = 0f
                advancedPanel.translationY = -dpf(16f)
                advancedPanel.animate().alpha(1f).translationY(0f).setDuration(400)
                    .setInterpolator(quantumBounceInterpolator).start()
            } else {
                advancedPanel.animate().alpha(0f).translationY(-dpf(16f)).setDuration(250)
                    .setInterpolator(DecelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: Animator) { advancedPanel.visibility = View.GONE }
                    }).start()
            }
        }
        mainLayout.addView(createStyledGroup("ADVANCED TUNING / 高级调校", advToggle))
        mainLayout.addView(advancedPanel)

        // Wallpaper
        val selectWall = createStyledTextItem("Change Background / 更改底层渲染背景",
            "Use high-resolution images for optimal glass effect / 建议搭配高清背景使用") { onSelectWallpaper() }
        val clearWall = createStyledTextItem("Restore Default / 恢复默认背景",
            "Revert to standard dark theme / 恢复默认暗场背景") { onClearWallpaper() }
        mainLayout.addView(createStyledGroup("PERSONALIZATION / 视觉个性化", selectWall, clearWall))

        // Log
        logScroll = ScrollView(activity).apply {
            visibility = View.GONE
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#99000000"), Color.parseColor("#B3000000"), Color.parseColor("#CC000000"))
            ).apply {
                cornerRadius = dpf(24f)
                setStroke(dp(1), Color.parseColor("#45FFFFFF"))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(200)).apply {
                setMargins(dp(20), dp(4), dp(20), dp(24))
            }
        }
        logTextView = TextView(activity).apply {
            text = ""
            textSize = 11f
            setTextColor(Color.parseColor("#E5FFFFFF"))
            typeface = Typeface.MONOSPACE
        }
        logScroll.addView(logTextView)
        appendLog("[System Ready] HapticEventGenerator v2 initialized.")

        val logTrigger = createStyledTextItem("Event Stream Monitor / 事件流监视器",
            "Monitor haptic event generation in real-time / 实时观测触觉事件生成", null) {
            val isExpanding = logScroll.visibility == View.GONE
            toggleLogWithSpring(logScroll, isExpanding)
        }
        mainLayout.addView(createStyledGroup("EVENT MONITOR / 事件监视", logTrigger))
        mainLayout.addView(logScroll)

        // Telemetry
        val entryDashboard = createStyledTextItem(
            "Haptic Dashboard / 触感仪表盘",
            "Real-time frequency spectrum & haptic telemetry / 实时频谱与触感遥测"
        ) {
            activity.startActivity(Intent(activity, HapticDashboardActivity::class.java))
        }
        mainLayout.addView(createStyledGroup("TELEMETRY / 遥测数据", entryDashboard))

        rootScrollView.addView(mainLayout)
        rootLayout.addView(rootScrollView)

        // Cascade cascade introduction animation
        mainLayout.post {
            val count = mainLayout.childCount
            for (i in 0 until count) {
                val child = mainLayout.getChildAt(i)
                if (child == logScroll) continue
                child.alpha = 0f
                child.translationY = dpf(40f)
                child.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(650)
                    .setStartDelay(i * 70L)
                    .setInterpolator(quantumBounceInterpolator)
                    .start()
            }
        }

        return rootLayout
    }

    // Toggle custom switch element
    inner class SpringSwitch(context: Context) : FrameLayout(context) {
        var isChecked = false
            set(value) {
                if (field != value) {
                    field = value
                    updateState(animate = true)
                }
            }

        private val trackDrawable = GradientDrawable().apply {
            cornerRadius = dpf(16f)
            setColor(Color.parseColor("#26000000"))
        }

        private val thumbView = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        }

        private var onCheckedChangeListener: ((Boolean) -> Unit)? = null

        init {
            background = trackDrawable
            val thumbSize = dp(32)
            val trackHeight = dp(32)
            val trackWidth = dp(56)
            layoutParams = FrameLayout.LayoutParams(trackWidth, trackHeight)

            thumbView.layoutParams = FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(2)
            }
            addView(thumbView)

            setOnClickListener {
                isChecked = !isChecked
                vibrateTick()
                onCheckedChangeListener?.invoke(isChecked)
            }
        }

        fun setOnCheckedChangeListener(listener: (Boolean) -> Unit) {
            onCheckedChangeListener = listener
        }

        private fun updateState(animate: Boolean) {
            val targetMargin = if (isChecked) dp(2) + dp(24) else dp(2)
            val targetTrackColor = if (isChecked)
                Color.parseColor("#4034C759") else Color.parseColor("#26000000")

            if (animate) {
                ValueAnimator.ofInt(
                    (thumbView.layoutParams as FrameLayout.LayoutParams).marginStart,
                    targetMargin
                ).apply {
                    duration = 330
                    interpolator = quantumBounceInterpolator
                    addUpdateListener {
                        val lp = thumbView.layoutParams as FrameLayout.LayoutParams
                        lp.marginStart = it.animatedValue as Int
                        thumbView.layoutParams = lp
                    }
                    start()
                }

                ValueAnimator.ofArgb(0, targetTrackColor).apply {
                    duration = 300
                    interpolator = PathInterpolator(0.2f, 1.0f, 0.3f, 1.0f)
                    addUpdateListener {
                        trackDrawable.setColor(it.animatedValue as Int)
                        background = trackDrawable
                    }
                    start()
                }
            } else {
                val lp = thumbView.layoutParams as FrameLayout.LayoutParams
                lp.marginStart = targetMargin
                thumbView.layoutParams = lp
                trackDrawable.setColor(targetTrackColor)
                background = trackDrawable
            }
        }
    }
}