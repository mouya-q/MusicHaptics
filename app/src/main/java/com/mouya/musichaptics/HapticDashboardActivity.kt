package com.mouya.musichaptics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.BackEventCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Spring
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mouya.musichaptics.ui.ConsoleLogState
import com.mouya.musichaptics.ui.rememberConsoleLogState
import com.mouya.musichaptics.ui.IOSConsole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.abs

// ════════════════════════════════════════════════════════════════
//  iOS Liquid Glass Design System
// ════════════════════════════════════════════════════════════════

private object IOSColors {
    val blue = Color(0xFF007AFF)
    val purple = Color(0xFF5856D6)
    val green = Color(0xFF34C759)
    val red = Color(0xFFFF3B30)
    val orange = Color(0xFFFF9500)
    val pink = Color(0xFFFF2D92)
    val teal = Color(0xFF30B0C7)
    val indigo = Color(0xFF5E5CE6)
    val gray = Color(0xFF8E8E93)
    val lightBg = Color(0xFFF2F2F7)
    val lightCard = Color(0xFFFFFFFF)
    val lightCardAlt = Color(0xFFF2F2F7)
    val darkBg = Color(0xFF000000)
    val darkCard = Color(0xFF1C1C1E)
    val darkCardAlt = Color(0xFF2C2C2E)
    val glassLight = Color(0xFFFFFFFF).copy(alpha = 0.72f)
    val glassDark = Color(0xFF1C1C1E).copy(alpha = 0.72f)
    val lightTextPrimary = Color(0xFF000000)
    val lightTextSecondary = Color(0xFF3C3C43).copy(alpha = 0.6f)
    val lightTextTertiary = Color(0xFF3C3C43).copy(alpha = 0.3f)
    val darkTextPrimary = Color(0xFFFFFFFF)
    val darkTextSecondary = Color(0xFFEBEBF5).copy(alpha = 0.6f)
    val darkTextTertiary = Color(0xFFEBEBF5).copy(alpha = 0.3f)
}

// MusicHapticsX uses a fixed white canvas regardless of the system appearance.
@Composable private fun isDark() = false

@Composable private fun bgPrimary() = Color.White
@Composable private fun cardColor() = Color.White
@Composable private fun cardAltColor() = Color(0xFFF2F2F7)
@Composable private fun glassColor() = Color.White.copy(alpha = 0.86f)
@Composable private fun textPrimary() = Color(0xFF000000)
@Composable private fun textSecondary() = Color(0xFF3C3C43).copy(alpha = 0.6f)
@Composable private fun textTertiary() = Color(0xFF3C3C43).copy(alpha = 0.3f)
@Composable private fun separatorColor() = Color(0xFFC6C6C8)

/** iOS Liquid Glass card modifier */
@Composable
fun Modifier.liquidGlass(corner: Dp = 22.dp): Modifier = this.then(
    Modifier
        .clip(RoundedCornerShape(corner))
        .background(glassColor())
        .border(0.5.dp, if (isDark()) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f), RoundedCornerShape(corner))
)

/** iOS-style toggle switch */
@Composable
fun IOSToggle(
    checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
    onStyle: ((newChecked: Boolean) -> HapticFeedbackEngine.HapticStyle)? = null
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    val animatedBg by animateColorAsState(
        targetValue = if (checked) IOSColors.green else if (isDark()) Color(0xFF39393B) else Color(0xFFE9E9EA),
        animationSpec = PhysicsSpring.colorBounce(), label = "ToggleBg"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = PhysicsSpring.bouncyDp(),
        label = "ThumbOffset"
    )
    Box(
        modifier = modifier.width(52.dp).height(32.dp)
            .clip(RoundedCornerShape(16.dp)).background(animatedBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val style = onStyle?.invoke(!checked) ?: HapticFeedbackEngine.HapticStyle.KICK
                hapticEngine.perform(style)
                onToggle()
            }
    ) {
        Box(
            modifier = Modifier.offset(x = thumbOffset, y = 2.dp).size(28.dp)
                .clip(CircleShape).background(Color.White)
        )
    }
}

/** iOS-style segmented control */
@Composable
fun <T> IOSSegmentedControl(
    items: List<T>, selected: T, onSelect: (T) -> Unit,
    label: (T) -> String, modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    
    var pressedItem by remember { mutableStateOf<T?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(36.dp)
            .shadow(4.dp, RoundedCornerShape(10.dp), ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.08f))
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDark()) Color(0xFF2C2C2E) else Color(0xFFEFEFF2)) // flat light gray bar
            .padding(2.dp)
    ) {
        val itemWidth = maxWidth / items.size
        val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
        
        val isInteracting = pressedItem != null || dragOffset != 0f
        
        // Squeeze/Expand morphing scale
        val lensScale by animateFloatAsState(
            targetValue = if (isInteracting) 1.15f else 1f,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 400f),
            label = "LensScale"
        )
        
        val baseOffset = itemWidthPx * items.indexOf(selected)
        val lensOffsetPx by animateFloatAsState(
            targetValue = baseOffset + dragOffset,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f),
            label = "LensOffset"
        )
        
        val glassAlpha by animateFloatAsState(
            targetValue = if (isInteracting) 1f else 0.35f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
            label = "GlassAlpha"
        )

        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(lensOffsetPx.toInt(), 0) }
                .width(itemWidth)
                .fillMaxHeight()
                .graphicsLayer { 
                    scaleX = lensScale
                    scaleY = lensScale 
                }
                .shadow(if (isInteracting) 6.dp else 0.dp, RoundedCornerShape(8.dp), ambientColor = IOSColors.blue.copy(alpha=0.4f), spotColor = IOSColors.blue.copy(alpha=0.3f))
                .clip(RoundedCornerShape(8.dp))
                .background(if (isDark()) Color(0xFF48484A) else Color.White)
                .background(Color.White.copy(alpha = glassAlpha))
        )
        
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .pointerInput(item) {
                            detectDragGestures(
                                onDragStart = { pressedItem = item },
                                onDragEnd = {
                                    val totalOffset = baseOffset + dragOffset
                                    val targetIndex = (totalOffset / itemWidthPx).roundToInt().coerceIn(0, items.size - 1)
                                    val targetItem = items[targetIndex]
                                    if (targetItem != selected) {
                                        hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                        onSelect(targetItem)
                                    }
                                    pressedItem = null
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    pressedItem = null
                                    dragOffset = 0f
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.x
                                
                                val totalOffset = baseOffset + dragOffset
                                val targetIndex = (totalOffset / itemWidthPx).roundToInt().coerceIn(0, items.size - 1)
                                val targetItem = items[targetIndex]
                                if (targetItem != selected) {
                                    hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                    onSelect(targetItem)
                                }
                            }
                        }
                        .pointerInput(item, "tap") {
                            detectTapGestures(
                                onPress = {
                                    pressedItem = item
                                    tryAwaitRelease()
                                    pressedItem = null
                                },
                                onTap = {
                                    if (item != selected) {
                                        hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                        onSelect(item)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(item), fontSize = 13.sp,
                        fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (item == selected) textPrimary() else textSecondary(),
                        fontFamily = AppFontFamily
                    )
                }
            }
        }
    }
}

/** iOS-style settings slider row */
@Composable
fun IOSSettingSliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    unit: String, onValueChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    var lastHapticValue by remember { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = textPrimary(), fontFamily = AppFontFamily)
            Text("${String.format(Locale.ROOT, "%.1f", value)} $unit", fontSize = 15.sp, color = IOSColors.blue, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
        }
        Slider(
            value = value, onValueChange = {
                onValueChange(it)
                // Throttle haptic: only fire when value changes by ~5% of range
                val rangeSize = range.endInclusive - range.start
                if (rangeSize > 0f && kotlin.math.abs(it - lastHapticValue) >= rangeSize * 0.05f) {
                    hapticEngine.perform(HapticFeedbackEngine.HapticStyle.LIGHT_TICK)
                    lastHapticValue = it
                }
            }, valueRange = range, modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = IOSColors.blue,
                inactiveTrackColor = if (isDark()) Color(0xFF39393B) else Color(0xFFE0E0E5)
            )
        )
    }
}

/** iOS-style action button */
@Composable
fun IOSButton(
    label: String, isActive: Boolean, modifier: Modifier = Modifier,
    hapticStyle: HapticFeedbackEngine.HapticStyle? = null,
    onClick: () -> Unit
) {
    val scale = remember { Animatable(1f) }
    val bouncyPress = rememberBouncyPress()
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    val bg by animateColorAsState(
        targetValue = when {
            isActive -> IOSColors.blue.copy(alpha = 0.15f)
            isDark() -> Color(0xFF2C2C2E)
            else -> Color(0xFFEFEFF2)
        },
        animationSpec = PhysicsSpring.colorBounce(), label = "BtnBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) IOSColors.blue else Color.Transparent,
        animationSpec = PhysicsSpring.colorBounce(), label = "BtnBorder"
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(RoundedCornerShape(14.dp)).background(bg)
            .border(if (isActive) 1.dp else 0.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                bouncyPress.pressAndRelease(scale)
                hapticEngine.perform(hapticStyle ?: HapticFeedbackEngine.HapticStyle.IMPACT)
                onClick()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (isActive) IOSColors.blue else textPrimary(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFontFamily)
    }
}

// ════════════════════════════════════════════════════════════════
//  Activity
// ════════════════════════════════════════════════════════════════

class HapticDashboardActivity : ComponentActivity() {

    private val telemetryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // v3.13: Unpack the packed telemetry format
            val floats = intent.getFloatArrayExtra("floats")
            val longs = intent.getLongArrayExtra("longs")
            val ints = intent.getIntArrayExtra("ints")
            val bundle = android.os.Bundle().apply {
                if (floats != null && floats.size >= 16) {
                    putFloat("sub", floats[0])
                    putFloat("mid", floats[1])
                    putFloat("pres", floats[2])
                    putFloat("f0", floats[3])
                    putFloat("temp", floats[4])
                    putFloat("atten", floats[5])
                    putFloat("loFreq", floats[6])
                    putFloat("hiFreq", floats[7])
                    putFloat("ampScale", floats[8])
                    putFloat("lraDisp", floats[9])
                    putFloat("lraVel", floats[10])
                    putFloat("lraForce", floats[11])
                    putFloat("lraPhase", floats[12])
                    putFloat("adsrEnv", floats[13])
                    putFloat("thermalGain", floats[14])
                    putFloat("gammaValue", floats[15])
                }
                if (longs != null && longs.size >= 5) {
                    putLong("latency", longs[0])
                    putLong("overruns", longs[1])
                    putLong("subCount", longs[2])
                    putLong("midCount", longs[3])
                    putLong("texCount", longs[4])
                }
                if (ints != null && ints.size >= 2) {
                    putInt("primitiveIntensity", ints[0])
                    putInt("primitiveDuration", ints[1])
                }
                putBoolean("keyStrikeActive", intent.getBooleanExtra("ksActive", false))
                putString("keyStrikeSemantic", intent.getStringExtra("ksSem") ?: "NONE")
                putString("semanticType", intent.getStringExtra("semType") ?: "BALANCED")
                putString("personaName", intent.getStringExtra("persona") ?: "POP")
                putString("primitiveType", intent.getStringExtra("primType") ?: "")
                putString("primitiveSemantic", intent.getStringExtra("primSem") ?: "")
                putLong("time", intent.getLongExtra("time", System.currentTimeMillis()))
            }
            TelemetryHub.applySnapshot(bundle)
        }
    }

    private val logReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(LogBroadcaster.EXTRA_LOG_MSG)
            if (!msg.isNullOrBlank()) ConsoleLogState.addGlobalLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ enforces edge-to-edge; setDecorFitsSystemWindows(true) is
        // deprecated and ignored on API 35+. We handle insets in Compose instead.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val telemetryFilter = IntentFilter(LogBroadcaster.ACTION_TELEMETRY)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            registerReceiver(telemetryReceiver, telemetryFilter, ContextCompat.RECEIVER_EXPORTED)
        else
            registerReceiver(telemetryReceiver, telemetryFilter)

        val logFilter = IntentFilter(LogBroadcaster.ACTION_LOG)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            registerReceiver(logReceiver, logFilter, ContextCompat.RECEIVER_EXPORTED)
        else
            registerReceiver(logReceiver, logFilter)

        setContent { MaterialTheme { HapticDashboard() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(telemetryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
    }
}

// ════════════════════════════════════════════════════════════════
//  Main Dashboard
// ════════════════════════════════════════════════════════════════

@Composable
fun HapticDashboard() {
    var telemetry by remember { mutableStateOf(TelemetrySnapshot()) }
    val consoleLogState = rememberConsoleLogState()
    var consoleExpanded by remember { mutableStateOf(false) }

    // ── Primitive hold: prevents texture flicker ──
    var heldPrimitiveType by remember { mutableStateOf("") }
    var heldPrimitiveSemantic by remember { mutableStateOf("") }
    var heldPrimitiveIntensity by remember { mutableStateOf(0) }
    var heldPrimitiveDuration by remember { mutableStateOf(0) }
    var lastPrimitiveTime by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(25)
            val fftEnergy = (TelemetryHub.subBassLevel + TelemetryHub.midBassLevel + TelemetryHub.presenceLevel) / 3f
            val physicsEnergy = TelemetryHub.adsrEnvelope + TelemetryHub.lraForce * 0.5f
            telemetry = TelemetrySnapshot(
                subBass = TelemetryHub.subBassLevel, midBass = TelemetryHub.midBassLevel,
                presence = TelemetryHub.presenceLevel,
                intensity = maxOf(fftEnergy, physicsEnergy).coerceIn(0f, 1f),
                latencyMs = TelemetryHub.frameLatencyMs.toFloat(),
                temperature = TelemetryHub.coilTemperature,
                f0Hz = TelemetryHub.fundamentalFrequencyHz.toInt(),
                adsrEnv = TelemetryHub.adsrEnvelope, lraForce = TelemetryHub.lraForce,
                lraPhase = TelemetryHub.lraPhase, lraDisp = TelemetryHub.lraDisplacement,
                thermalAttenuation = TelemetryHub.thermalAttenuation,
            )

            // Hold primitive display for 800ms after last event
            val now = System.currentTimeMillis()
            val currentType = TelemetryHub.primitiveType
            if (currentType.isNotEmpty()) {
                heldPrimitiveType = currentType
                heldPrimitiveSemantic = TelemetryHub.primitiveSemantic
                heldPrimitiveIntensity = TelemetryHub.primitiveIntensity
                heldPrimitiveDuration = TelemetryHub.primitiveDuration
                lastPrimitiveTime = now
            } else if (now - lastPrimitiveTime > 800) {
                heldPrimitiveType = ""
                heldPrimitiveSemantic = ""
                heldPrimitiveIntensity = 0
                heldPrimitiveDuration = 0
            }
        }
    }

    // v2.1.2: Read initial values from prefs to prevent
    // "settings reset on restart" bug — previously these were hardcoded
    // defaults that immediately overwrote saved prefs via LaunchedEffect.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE) }

    var isMasterSwitchOn by remember { mutableStateOf(prefs.getBoolean("master_switch", true)) }
    var isPowerAmplifyActive by remember { mutableStateOf(prefs.getBoolean("power_amplify", false)) }
    var isCrossoverBypassActive by remember { mutableStateOf(prefs.getBoolean("crossover_bypass", true)) }
    var selectedPreset by remember {
        val idx = prefs.getInt("selected_preset", Preset.HIGH.ordinal)
        mutableStateOf(Preset.entries.getOrElse(idx) { Preset.HIGH })
    }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var customAmplitude by remember { mutableStateOf(prefs.getFloat("haptic_amplitude", 2.0f)) }
    var customBassBoost by remember { mutableStateOf(prefs.getFloat("haptic_bass_boost", 1.6f)) }
    var hapticPreset by remember {
        val idx = prefs.getInt("haptic_preset_id", HapticPreset.BALANCED.ordinal)
        mutableStateOf(HapticPreset.entries.getOrElse(idx) { HapticPreset.BALANCED })
    }

    var selectedPersonaName by remember { mutableStateOf(prefs.getString("music_persona", MusicPersona.DEFAULT.name) ?: MusicPersona.DEFAULT.name) }
    var gammaOverride by remember { mutableStateOf(prefs.getFloat("haptic_gamma_override", -1f)) }

    var synthLraF0 by remember { mutableStateOf(prefs.getFloat("synth_lra_f0", HapticSynthesizer.LRA_F0)) }
    var synthLraQ by remember { mutableStateOf(prefs.getFloat("synth_lra_q", HapticSynthesizer.LRA_Q)) }
    var synthRateHz by remember { mutableStateOf(prefs.getInt("synth_rate_hz", HapticSynthesizer.SYNTHESIS_RATE_HZ)) }
    var synthAttackImpact by remember { mutableStateOf(prefs.getFloat("synth_attack_impact", HapticSynthesizer.ATTACK_TAU_IMPACT)) }
    var synthDecayImpact by remember { mutableStateOf(prefs.getFloat("synth_decay_impact", HapticSynthesizer.DECAY_TAU_IMPACT)) }
    var synthAttackContinuous by remember { mutableStateOf(prefs.getFloat("synth_attack_continuous", HapticSynthesizer.ATTACK_TAU_CONTINUOUS)) }
    var synthDecayContinuous by remember { mutableStateOf(prefs.getFloat("synth_decay_continuous", HapticSynthesizer.DECAY_TAU_CONTINUOUS)) }
    var synthReleaseTau by remember { mutableStateOf(prefs.getFloat("synth_release", HapticSynthesizer.RELEASE_TAU)) }
    var synthSustainLevel by remember { mutableStateOf(prefs.getFloat("synth_sustain", HapticSynthesizer.SUSTAIN_LEVEL)) }
    var synthThermalWarn by remember { mutableStateOf(prefs.getFloat("synth_thermal_warn", HapticSynthesizer.THERMAL_WARN)) }
    var synthThermalCrit by remember { mutableStateOf(prefs.getFloat("synth_thermal_crit", HapticSynthesizer.THERMAL_CRIT)) }
    var synthThermalRth by remember { mutableStateOf(prefs.getFloat("synth_thermal_rth", HapticSynthesizer.THERMAL_RTH)) }
    var synthThermalCth by remember { mutableStateOf(prefs.getFloat("synth_thermal_cth", HapticSynthesizer.THERMAL_CTH)) }
    var synthImpactGain by remember { mutableStateOf(prefs.getFloat("synth_impact_gain", 1.0f)) }
    var synthContinuousGain by remember { mutableStateOf(prefs.getFloat("synth_continuous_gain", 1.0f)) }
    var synthTextureGain by remember { mutableStateOf(prefs.getFloat("synth_texture_gain", 1.0f)) }
    var synthMasterGain by remember { mutableStateOf(prefs.getFloat("synth_master_gain", 1.0f)) }
    var hardwareRootVerified by remember { mutableStateOf(prefs.getBoolean(RootHardwareProbe.PREF_ROOT_OK, false)) }
    var hardwareProfileId by remember { mutableStateOf(prefs.getString(RootHardwareProbe.PREF_PROFILE, "DEFAULT") ?: "DEFAULT") }
    var hardwareFingerprint by remember { mutableStateOf(prefs.getString(RootHardwareProbe.PREF_FINGERPRINT, "") ?: "") }
    var hardwareRefreshing by remember { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    var dashboardTab by rememberSaveable { mutableStateOf(DashboardTab.CONSOLE) }
    // The source layer is sampled by the floating tab bar below. Unlike a
    // translucent fill, this gives the bar a real backdrop blur on Android 12+.
    val liquidGlassBackdrop = remember { HazeState() }
 
LaunchedEffect(isMasterSwitchOn, isPowerAmplifyActive, isCrossoverBypassActive, selectedPreset, customAmplitude, customBassBoost, hapticPreset,
                 synthLraF0, synthLraQ, synthRateHz, synthAttackImpact, synthDecayImpact, synthAttackContinuous, synthDecayContinuous,
                 synthReleaseTau, synthSustainLevel, synthThermalWarn, synthThermalCrit, synthThermalRth, synthThermalCth,
                 synthImpactGain, synthContinuousGain, synthTextureGain, synthMasterGain) {
         prefs.edit().apply {
             putBoolean("master_switch", isMasterSwitchOn)
             putBoolean("power_amplify", isPowerAmplifyActive)
             putBoolean("crossover_bypass", isCrossoverBypassActive)
             putInt("selected_preset", selectedPreset.ordinal)
             putFloat("haptic_amplitude", customAmplitude)
             // Engine consumes haptic_boost_level; retain the legacy key for backward compatibility.
             putFloat("haptic_bass_boost", customBassBoost)
             putFloat("haptic_boost_level", customBassBoost)
             putInt("haptic_preset_id", hapticPreset.ordinal)
             putString("haptic_preset", hapticPreset.name)
             putFloat("synth_lra_f0", synthLraF0)
             putFloat("synth_lra_q", synthLraQ)
             putInt("synth_rate_hz", synthRateHz)
             putFloat("synth_attack_impact", synthAttackImpact)
             putFloat("synth_decay_impact", synthDecayImpact)
             putFloat("synth_attack_continuous", synthAttackContinuous)
             putFloat("synth_decay_continuous", synthDecayContinuous)
             putFloat("synth_release", synthReleaseTau)
             putFloat("synth_sustain", synthSustainLevel)
             putFloat("synth_thermal_warn", synthThermalWarn)
             putFloat("synth_thermal_crit", synthThermalCrit)
             putFloat("synth_thermal_rth", synthThermalRth)
             putFloat("synth_thermal_cth", synthThermalCth)
             putFloat("synth_impact_gain", synthImpactGain)
             putFloat("synth_continuous_gain", synthContinuousGain)
             putFloat("synth_texture_gain", synthTextureGain)
putFloat("synth_master_gain", synthMasterGain)
          }.apply()

          // Hooked app processes own a private preference snapshot. Notify them
          // to re-read the module provider and apply changes without restart or
          // dynamic code loading. The receiver requires our signature permission.
          context.sendBroadcast(
              Intent("com.mouya.musichaptics.ACTION_REFRESH_CONFIG").setPackage(null),
              "com.mouya.musichaptics.permission.CONFIG_SYNC"
          )
      }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(bgPrimary())
        .hazeSource(liquidGlassBackdrop)
        .statusBarsPadding()
        .navigationBarsPadding()
    ) {
        AnimatedContent(
            targetState = dashboardTab,
            transitionSpec = {
                // Direction follows tab position: switching to a tab on the
                // right slides content left-to-right; switching left does the
                // reverse. This matches iOS behavior where the new view comes
                // from the direction of its tab.
                if (targetState.ordinal > initialState.ordinal) {
                    (slideInHorizontally { it / 6 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally { -it / 6 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally { -it / 6 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally { it / 6 } + fadeOut(tween(160)))
                }
            }, label = "DashboardTab"
        ) { tab ->
            if (tab == DashboardTab.CONSOLE) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IOSHeaderCard()
            IOSHardwareProfileCard(
                rootVerified = hardwareRootVerified,
                profileId = hardwareProfileId,
                fingerprint = hardwareFingerprint,
                refreshing = hardwareRefreshing,
                onRefresh = {
                    hardwareRefreshing = true
                    // Probe uses only read-only root commands; run it off the UI thread.
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { RootHardwareProbe.probeAndPersist(context) }
                        hardwareRootVerified = result.rootGranted
                        hardwareProfileId = result.profileId
                        hardwareFingerprint = result.fingerprint
                        hardwareRefreshing = false
                    }
                }
            )
            IOSTelemetryCard(telemetry, isMasterSwitchOn)
            IOSComposerPanel(
                selectedPersonaName, { selectedPersonaName = it; prefs.edit().putString("music_persona", it).apply() },
                gammaOverride, { gammaOverride = it; prefs.edit().putFloat("haptic_gamma_override", it).apply() },
                heldPrimitiveType, heldPrimitiveSemantic, heldPrimitiveIntensity, heldPrimitiveDuration,
                TelemetryHub.gammaValue, TelemetryHub.personaName,
                onRestartScopedApps = { showRestartDialog = true }
            )
            IOSControlPanel(
                isMasterSwitchOn,
                isPowerAmplifyActive, { isPowerAmplifyActive = !isPowerAmplifyActive },
                isCrossoverBypassActive, { isCrossoverBypassActive = !isCrossoverBypassActive },
                selectedPreset, { selectedPreset = it },
                showAdvancedSettings, { showAdvancedSettings = !showAdvancedSettings },
                customAmplitude, { customAmplitude = it },
                customBassBoost, { customBassBoost = it },
                hapticPreset, { hapticPreset = it },
                synthLraF0, { synthLraF0 = it; prefs.edit().putFloat("synth_lra_f0", it).apply() },
                synthLraQ, { synthLraQ = it; prefs.edit().putFloat("synth_lra_q", it).apply() },
                synthRateHz, { synthRateHz = it; prefs.edit().putInt("synth_rate_hz", it).apply() },
                synthAttackImpact, { synthAttackImpact = it; prefs.edit().putFloat("synth_attack_impact", it).apply() },
                synthDecayImpact, { synthDecayImpact = it; prefs.edit().putFloat("synth_decay_impact", it).apply() },
                synthAttackContinuous, { synthAttackContinuous = it; prefs.edit().putFloat("synth_attack_continuous", it).apply() },
                synthDecayContinuous, { synthDecayContinuous = it; prefs.edit().putFloat("synth_decay_continuous", it).apply() },
                synthReleaseTau, { synthReleaseTau = it; prefs.edit().putFloat("synth_release", it).apply() },
                synthSustainLevel, { synthSustainLevel = it; prefs.edit().putFloat("synth_sustain", it).apply() },
                synthThermalWarn, { synthThermalWarn = it; prefs.edit().putFloat("synth_thermal_warn", it).apply() },
                synthThermalCrit, { synthThermalCrit = it; prefs.edit().putFloat("synth_thermal_crit", it).apply() },
                synthThermalRth, { synthThermalRth = it; prefs.edit().putFloat("synth_thermal_rth", it).apply() },
                synthThermalCth, { synthThermalCth = it; prefs.edit().putFloat("synth_thermal_cth", it).apply() },
                synthImpactGain, { synthImpactGain = it; prefs.edit().putFloat("synth_impact_gain", it).apply() },
                synthContinuousGain, { synthContinuousGain = it; prefs.edit().putFloat("synth_continuous_gain", it).apply() },
                synthTextureGain, { synthTextureGain = it; prefs.edit().putFloat("synth_texture_gain", it).apply() },
                synthMasterGain, { synthMasterGain = it; prefs.edit().putFloat("synth_master_gain", it).apply() },
            )
            IOSConsole(
                modifier = Modifier.fillMaxWidth(), isExpanded = consoleExpanded,
                onToggle = { consoleExpanded = !consoleExpanded },
                onClear = { consoleLogState.clear() },
                onExport = {
                    consoleLogState.exportToDownloads()
                        .onSuccess { Toast.makeText(context, "日志已导出到 $it", Toast.LENGTH_LONG).show() }
                        .onFailure { Toast.makeText(context, "日志导出失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show() }
                },
                logs = consoleLogState.logs
            )
            IOSDeveloperCard()
        }

        // The restart dialog state is hoisted to HapticDashboardActivity root scope
        if (showRestartDialog) ScopedAppsRestartDialog(
            onDismiss = { showRestartDialog = false },
            onConfirm = { selected ->
                val rootGranted = forceStopSelectedAppsWithRoot(selected)
                val message = if (rootGranted) {
                    "已通过 Root 重启 ${selected.size} 个 App。请重新打开它们。"
                } else {
                    "未获取 Root 权限，请手动结束并重新打开已勾选 App，或授予 Root 权限后重试。"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                showRestartDialog = false
            }
        )
            } else {
                ScopedAppsScreen()
            }
        }
        LiquidGlassTabBar(
            selected = dashboardTab,
            onSelected = { dashboardTab = it },
            backdrop = liquidGlassBackdrop,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
        )
    }
}

private enum class DashboardTab { CONSOLE, APPS }
private data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable?)

@Composable
private fun LiquidGlassTabBar(
    selected: DashboardTab,
    onSelected: (DashboardTab) -> Unit,
    backdrop: HazeState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = remember { HapticFeedbackEngine.create(context) }
    
    var pressedTab by remember { mutableStateOf<DashboardTab?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val barShape = RoundedCornerShape(28.dp)
    val lensShape = RoundedCornerShape(22.dp)

    BoxWithConstraints(
        modifier
            .width(160.dp)
            .height(58.dp)
            .shadow(16.dp, barShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(barShape)
            // The main bar gets the heavy haze blur. Since it doesn't move,
            // it won't trigger 120fps recalculations (unless background logs scroll).
            .hazeEffect(backdrop) {
                blurRadius = 32.dp
                noiseFactor = 0.04f
                backgroundColor = Color.Transparent
            }
            .background(Color(0xFFF7F7F9).copy(alpha = 0.65f))
            .border(0.5.dp, Color.White.copy(alpha = 0.4f), barShape)
            .padding(5.dp)
    ) {
        val computedTabWidth = maxWidth / 2
        val computedTabWidthPx = with(LocalDensity.current) { computedTabWidth.toPx() }
        
        val baseOffset = if (selected == DashboardTab.CONSOLE) 0f else computedTabWidthPx
        val lensOffsetPxState = animateFloatAsState(
            targetValue = baseOffset + dragOffset,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            label = "LensOffset"
        )
        
        val isInteracting = pressedTab != null || dragOffset != 0f
        val scaleState = animateFloatAsState(
            targetValue = if (isInteracting) 0.92f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
            label = "LensScale"
        )
        
        // The moving indicator itself is just a simple drawn shape.
        // NO HAZE on the moving part! This makes the animation 120fps buttery smooth.
        Box(
            Modifier
                .width(computedTabWidth)
                .fillMaxHeight()
                .graphicsLayer { 
                    translationX = lensOffsetPxState.value
                    scaleX = scaleState.value
                    scaleY = scaleState.value
                }
                .shadow(if (isInteracting) 6.dp else 2.dp, lensShape, spotColor = Color.Black.copy(alpha = 0.1f))
                .clip(lensShape)
                .background(Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            listOf(DashboardTab.CONSOLE to "控制台", DashboardTab.APPS to "应用").forEach { (tab, title) ->
                val active = selected == tab
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { delta ->
                                val newOffset = dragOffset + delta
                                if (selected == DashboardTab.CONSOLE) {
                                    dragOffset = newOffset.coerceIn(-20f, computedTabWidthPx + 20f)
                                } else {
                                    dragOffset = newOffset.coerceIn(-computedTabWidthPx - 20f, 20f)
                                }
                            },
                            onDragStarted = {
                                pressedTab = tab
                                haptic.perform(HapticFeedbackEngine.HapticStyle.LIGHT_TICK)
                            },
                            onDragStopped = {
                                val switchThreshold = computedTabWidthPx / 2.5f
                                if (selected == DashboardTab.CONSOLE && dragOffset > switchThreshold) {
                                    onSelected(DashboardTab.APPS)
                                    haptic.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                } else if (selected == DashboardTab.APPS && dragOffset < -switchThreshold) {
                                    onSelected(DashboardTab.CONSOLE)
                                    haptic.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                }
                                pressedTab = null
                                dragOffset = 0f
                            }
                        )
                        .pointerInput(tab) {
                            detectTapGestures(
                                onPress = {
                                    pressedTab = tab
                                    haptic.perform(HapticFeedbackEngine.HapticStyle.LIGHT_TICK)
                                    tryAwaitRelease()
                                    pressedTab = null
                                },
                                onTap = {
                                    if (!active) {
                                        haptic.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                        onSelected(tab)
                                    }
                                }
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = if (tab == DashboardTab.CONSOLE) Icons.Default.Tune else Icons.Default.Apps,
                        contentDescription = title,
                        tint = if (active) IOSColors.blue else Color(0xFF3C3C43).copy(alpha = .6f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(title, color = if (active) IOSColors.blue else Color(0xFF3C3C43).copy(alpha = .6f), fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun ScopedAppsScreen() {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // PredictiveBackHandler exposes the actual edge-swipe progress. A normal
    // BackHandler only runs after completion and cannot offer a preview.
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backFromLeft by remember { mutableStateOf(true) }
    PredictiveBackHandler(enabled = selected != null) { events ->
        try {
            events.collect { event: BackEventCompat ->
                backProgress = event.progress
                backFromLeft = event.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            selected = null
            backProgress = 0f
        } catch (cancelled: CancellationException) {
            // Releasing before the threshold must return the detail view to
            // its original position; it must not navigate away.
            backProgress = 0f
            throw cancelled
        }
    }
    val scopedPackages by LsposedScopeState.packages
    val apps by produceState<List<LaunchableApp>>(emptyList(), scopedPackages, context) {
        value = withContext(Dispatchers.IO) {
            scopedPackages.orEmpty().filter { it != context.packageName }.mapNotNull { packageName ->
                try {
                    val info = context.packageManager.getApplicationInfo(packageName, 0)
                    LaunchableApp(packageName, context.packageManager.getApplicationLabel(info).toString(), context.packageManager.getApplicationIcon(info))
                } catch (_: PackageManager.NameNotFoundException) { null }
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }
        }
    }
    
    // Instead of completely unloading the underlying list via AnimatedContent (which 
    // causes it to re-render from scratch), we stack both views.
    // The list is always present in the background. The detail view slides/scales over it.
    Box(Modifier.fillMaxSize()) {
        // Background list
        LazyColumn(contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 102.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("应用触觉", color = textPrimary(), fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Text("为每个 LSPosed 作用域单独覆写触觉参数", color = textSecondary(), fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
            }
            if (scopedPackages == null) item { Text("无法读取 LSPosed 作用域。请在 LSPosed 中启用模块后重新打开。", color = textSecondary(), modifier = Modifier.padding(20.dp)) }
            else if (apps.isEmpty()) item { Text("LSPosed 当前没有为本模块勾选应用。", color = textSecondary(), modifier = Modifier.padding(20.dp)) }
            items(apps, key = { it.packageName }) { app ->
                ScopedAppRow(app) { selected = app.packageName }
            }
        }
        
        // Detail overlay (animates in/out)
        AnimatedVisibility(
            visible = selected != null,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(220)),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(180))
        ) {
            val packageName = selected ?: return@AnimatedVisibility
            // Follow the finger during an Android 13+ predictive-back swipe:
            // the detail sheet retreats, scales slightly, and reveals the list
            // underneath. This is progress-driven rather than a fixed tween.
            val density = LocalDensity.current
            val direction = if (backFromLeft) 1f else -1f
            Box(
                Modifier.fillMaxSize().background(bgPrimary()).graphicsLayer {
                    translationX = with(density) { 56.dp.toPx() } * backProgress * direction
                    scaleX = 1f - (0.045f * backProgress)
                    scaleY = 1f - (0.045f * backProgress)
                    alpha = 1f - (0.16f * backProgress)
                    transformOrigin = TransformOrigin(if (backFromLeft) 0f else 1f, .5f)
                }
            ) {
                ScopedAppSettings(packageName = packageName, label = apps.firstOrNull { it.packageName == packageName }?.label ?: packageName, onBack = { selected = null })
            }
        }
    }
}

@Composable private fun ScopedAppRow(app: LaunchableApp, onClick: () -> Unit) {
    val initial = app.label.firstOrNull()?.uppercase() ?: "•"
    Row(Modifier.fillMaxWidth().liquidGlass(20.dp).clickable { onClick() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(IOSColors.indigo.copy(alpha=.10f)), contentAlignment = Alignment.Center) {
            if (app.icon != null) AndroidView(factory = { ImageView(it).apply { setImageDrawable(app.icon); scaleType = ImageView.ScaleType.CENTER_CROP } }, modifier = Modifier.fillMaxSize())
            else Text(initial, color=IOSColors.indigo, fontWeight=FontWeight.Bold, fontSize=19.sp)
        }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(app.label, color=textPrimary(), fontWeight=FontWeight.SemiBold); Text(app.packageName, color=textSecondary(), fontSize=12.sp, maxLines=1) }
        Text("›", color=IOSColors.gray, fontSize=30.sp)
    }
}

@Composable private fun ScopedAppSettings(packageName: String, label: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scopedPrefs = remember(packageName) { context.getSharedPreferences("scoped_haptics_$packageName", Context.MODE_PRIVATE) }
    val global = remember { context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE) }
    var enabled by remember(packageName) { mutableStateOf(scopedPrefs.getBoolean("master_switch", global.getBoolean("master_switch", true))) }
    var amp by remember(packageName) { mutableStateOf(scopedPrefs.getFloat("haptic_amplitude", global.getFloat("haptic_amplitude", 2f))) }
    var boost by remember(packageName) { mutableStateOf(scopedPrefs.getFloat("haptic_boost_level", global.getFloat("haptic_boost_level", 1.6f))) }
    var power by remember(packageName) { mutableStateOf(scopedPrefs.getBoolean("power_amplify", global.getBoolean("power_amplify", false))) }
    var crossover by remember(packageName) { mutableStateOf(scopedPrefs.getBoolean("crossover_bypass", global.getBoolean("crossover_bypass", true))) }
    LaunchedEffect(enabled, amp, boost, power, crossover) {
        scopedPrefs.edit().putBoolean("master_switch", enabled).putFloat("haptic_amplitude", amp).putFloat("haptic_boost_level", boost).putBoolean("power_amplify", power).putBoolean("crossover_bypass", crossover).apply()
        context.sendBroadcast(Intent("com.mouya.musichaptics.ACTION_REFRESH_CONFIG"), "com.mouya.musichaptics.permission.CONFIG_SYNC")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 24.dp, 16.dp, 104.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // iOS-style leading navigation control: 44dp touch target, 18dp chevron,
        // blue tint, then the destination title below it.
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回应用列表", tint = IOSColors.blue, modifier = Modifier.size(24.dp))
        }
        Text(label, color=textPrimary(), fontSize=27.sp, fontWeight=FontWeight.Bold)
        Text(packageName, color=textSecondary(), fontSize=13.sp)
        Text("此处仅覆写该应用；未设置的高级参数继续继承全局配置。", color=textSecondary(), fontSize=13.sp, modifier=Modifier.liquidGlass(16.dp).padding(14.dp))
        Row(Modifier.fillMaxWidth().liquidGlass().padding(16.dp), verticalAlignment=Alignment.CenterVertically) { Text("启用此应用触觉", Modifier.weight(1f), color=textPrimary(), fontWeight=FontWeight.Medium); IOSToggle(checked = enabled, onToggle = { enabled = !enabled }) }
        Column(Modifier.liquidGlass().padding(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("专属强度", color=textPrimary(), fontWeight=FontWeight.SemiBold)
            IOSSettingSliderRow("总强度", amp, .5f..3f, "x") { amp = it }
            IOSSettingSliderRow("低音强调", boost, 1f..2.5f, "x") { boost = it }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            IOSButton("线圈放大", power, Modifier.weight(1f)) { power = !power }
            IOSButton("有源分频", crossover, Modifier.weight(1f)) { crossover = !crossover }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  Header Card
// ════════════════════════════════════════════════════════════════

@Composable
fun IOSHeaderCard() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("MusicHapticsX", color = textPrimary(), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = AppFontFamily, letterSpacing = (-0.5).sp)
        Spacer(Modifier.height(2.dp))
        Text("音乐触觉控制台", color = textSecondary(), fontSize = 14.sp, fontFamily = AppFontFamily)
    }
}

@Composable
fun IOSHardwareProfileCard(
    rootVerified: Boolean,
    profileId: String,
    fingerprint: String,
    refreshing: Boolean,
    onRefresh: () -> Unit
) {
    val profile = detectDeviceProfile(persistedProfileId = profileId)
    val statusColor = if (rootVerified) IOSColors.green else IOSColors.red
    val statusText = if (rootVerified) "Root 已验证 · 已使用板级指纹" else "Root 未授权 · 未验证"
    val compactFingerprint = fingerprint.lineSequence()
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString(" · ")
        .ifBlank { "尚未读取硬件指纹" }

    Column(
        Modifier.fillMaxWidth().liquidGlass().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                .background(statusColor.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Security, null, tint = statusColor, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("硬件触觉适配", color = textPrimary(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(statusText, color = statusColor, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = IOSColors.blue)
                else Icon(Icons.Default.Refresh, "重新检测硬件", tint = IOSColors.blue)
            }
        }
        HorizontalDivider(color = separatorColor().copy(alpha = 0.55f))
        Text(profile.name, color = textPrimary(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            "f₀ ${profile.actuator.resonanceFreq.toInt()} Hz  ·  Q ${"%.1f".format(Locale.ROOT, profile.actuator.qFactor)}  ·  上升 ${"%.1f".format(Locale.ROOT, profile.actuator.riseTimeMs)} ms",
            color = textSecondary(), fontSize = 12.sp
        )
        Text(compactFingerprint, color = textTertiary(), fontSize = 11.sp, maxLines = 2)
        Text("重新检测后，请重启已启用的音乐 App，使 Hook 进程加载新参数。", color = textTertiary(), fontSize = 11.sp)
    }
}

// ════════════════════════════════════════════════════════════════
//  Telemetry Card
// ════════════════════════════════════════════════════════════════

@Composable
fun IOSTelemetryCard(telemetry: TelemetrySnapshot, isMasterSwitchOn: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(220.dp).liquidGlass().padding(18.dp)
                .graphicsLayer { alpha = if (isMasterSwitchOn) 1f else 0.4f }
        ) {
            Column(Modifier.fillMaxSize()) {
                Text("频谱与触觉动态", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFontFamily)
                Spacer(Modifier.height(12.dp))
                IOSWaveformDisplay(telemetry, isMasterSwitchOn, Modifier.fillMaxSize())
            }
        }
        Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IOSThermalPanel(Modifier.weight(1f).graphicsLayer { alpha = if (isMasterSwitchOn) 1f else 0.4f }, telemetry.temperature)
            IOSAttenuationPanel(Modifier.weight(1f).graphicsLayer { alpha = if (isMasterSwitchOn) 1f else 0.4f }, telemetry.thermalAttenuation)
        }
    }
}

@Composable
fun IOSWaveformDisplay(telemetry: TelemetrySnapshot, isActive: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "Wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing), RepeatMode.Restart), label = "Phase"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Restart), label = "Phase2"
    )
    // ── Standby idle undulation phase: slow drift when no music ──
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "IdlePhase"
    )
    val idlePhase2 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "IdlePhase2"
    )
    val physicsAmplitude = (telemetry.adsrEnv + telemetry.lraForce * 0.5f).coerceIn(0f, 1f)
    val smoothedAmplitude by animateFloatAsState(
        targetValue = if (isActive) physicsAmplitude else 0.02f,
        animationSpec = PhysicsSpring.waveformAmp(), label = "Amp"
    )
    // ── Dynamic energy level for color morphing ──
    val energyLevel = (telemetry.subBass * 0.4f + telemetry.midBass * 0.35f + telemetry.presence * 0.25f).coerceIn(0f, 1f)
    val smoothEnergy by animateFloatAsState(
        targetValue = if (isActive) energyLevel else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f), label = "Energy"
    )
    val wavePath = remember { Path() }

    Canvas(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        val w = size.width; val h = size.height; val midY = h / 2f
        val res = 220
        val baseFreq = (telemetry.f0Hz / 12f).coerceIn(6f, 28f)
        val amp = smoothedAmplitude * h * 0.35f
        val p = phase + telemetry.lraPhase
        val p2 = phase2
        val bassW = telemetry.subBass.coerceIn(0f, 1f)
        val midW = telemetry.midBass.coerceIn(0f, 1f)
        val trebleW = telemetry.presence.coerceIn(0f, 1f)

        // ── Dynamic color palette: morphs from cool→warm with energy ──
        val cBase = IOSColors.blue
        val cMid = IOSColors.purple
        val cWarm = IOSColors.pink
        val cHot = IOSColors.orange
        val energyMix = smoothEnergy
        val color2 = lerp(cMid, cWarm, energyMix)
        val color3 = lerp(cWarm, cHot, (energyMix * 1.5f).coerceIn(0f, 1f))

        // ═══ Simplified Perlin-like noise for irregular waveform ═══
        // Pseudo-noise: multiple sine sums with incommensurate frequencies
        // create organic non-repeating perturbations on wave phase
        fun noiseAt(nx: Float, t: Float): Float {
            val n1 = sin(nx * 13.7f + t * 1.3f) * 0.4f
            val n2 = sin(nx * 27.3f + t * 0.7f) * 0.3f
            val n3 = sin(nx * 41.1f + t * 1.9f) * 0.2f
            val n4 = sin(nx * 7.1f + t * 0.3f) * 0.1f
            return (n1 + n2 + n3 + n4) * 0.15f  // Scale down: subtle irregularity
        }

        // ── Pre-compute waveform points with noise perturbation ──
        val points = FloatArray(res + 1)
        for (i in 0..res) {
            val nx = (i.toFloat() / res); val x = nx * w
            val win = sin(nx * PI).toFloat()

            if (isActive && amp > 0.5f) {
                // Active mode: full waveform with noise perturbation
                val noise = noiseAt(nx, p * 0.3f)
                val bass = sin(nx * baseFreq - p + noise * 2f) * amp * bassW
                val mid = sin(nx * (baseFreq * 2.5f) + p * 1.3f + noise) * amp * 0.5f * midW
                val treble = sin(nx * (baseFreq * 6f) - p * 2.5f + noise * 3f) * amp * 0.2f * trebleW
                val harm = sin(nx * (baseFreq * 0.5f) + p * 0.7f) * amp * 0.3f * bassW
                val detail = sin(nx * (baseFreq * 4f) + p2 * 0.8f) * amp * 0.08f * (midW + trebleW) * 0.5f
                // Add irregular phase offset from noise
                val irregularPhase = noise * baseFreq * 0.1f
                val irregular = sin(nx * baseFreq + irregularPhase + noise * 5f) * amp * 0.15f
                points[i] = midY + (bass + mid + treble + harm + detail + irregular) * win
            } else {
                // ── Standby idle undulation: subtle organic drift ──
                // Use idle phases + noise to create a gentle, living waveform
                val idleAmp = h * 0.02f  // Very small amplitude
                val n1 = noiseAt(nx, idlePhase * 0.5f)
                val n2 = noiseAt(nx + 0.3f, idlePhase2 * 0.4f)
                val drift1 = sin(nx * 3f + idlePhase + n1 * 2f) * idleAmp
                val drift2 = sin(nx * 5.5f + idlePhase2 + n2 * 2f) * idleAmp * 0.6f
                val drift3 = sin(nx * 1.8f + idlePhase * 0.7f + n1) * idleAmp * 0.4f
                points[i] = midY + (drift1 + drift2 + drift3) * win
            }
        }

        // ── Build main wave path with smooth quadratic curves ──
        wavePath.reset(); wavePath.moveTo(0f, points[0])
        for (i in 1..res) {
            val nx = (i.toFloat() / res); val x = nx * w
            wavePath.lineTo(x, points[i])
        }

        // ═══ Single-line waveform: clean, organic, irregular ═══
        // Layer 1: Very faint outer glow (wide, low alpha)
        drawPath(
            wavePath,
            brush = Brush.horizontalGradient(
                listOf(cBase.copy(alpha = 0.06f), color2.copy(alpha = 0.08f), cBase.copy(alpha = 0.06f))
            ),
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        )

        // Layer 2: Main waveform line — the single expressive line
        val mainStrokeWidth = (1.5f + smoothEnergy * 1.5f).dp.toPx()
        drawPath(
            wavePath,
            brush = Brush.horizontalGradient(
                listOf(cBase, color2, color3, color2, cBase)
            ),
            style = Stroke(width = mainStrokeWidth, cap = StrokeCap.Round)
        )

        // ═══ Subtle center axis reference (barely visible) ═══
        drawLine(
            color = cBase.copy(alpha = 0.04f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 0.5.dp.toPx()
        )
    }
}

@Composable
fun IOSThermalPanel(modifier: Modifier, temp: Float) {
    var smoothedTemp by remember { mutableStateOf(25f) }
    LaunchedEffect(temp) { smoothedTemp = smoothedTemp * 0.85f + temp * 0.15f }
    Box(modifier = modifier.fillMaxHeight().liquidGlass().padding(14.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text("热模型", color = textSecondary(), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
            Text(String.format(Locale.ROOT, "%.1f°C", smoothedTemp), color = if (smoothedTemp > 45f) IOSColors.red else textPrimary(), fontSize = 24.sp, fontWeight = FontWeight.Light, fontFamily = AppFontFamily)
            LinearProgressIndicator(
                progress = { (smoothedTemp / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = if (smoothedTemp > 45f) IOSColors.red else IOSColors.green,
                trackColor = if (isDark()) Color(0xFF39393B) else Color(0xFFE0E0E5)
            )
        }
    }
}

@Composable
fun IOSAttenuationPanel(modifier: Modifier, gain: Float) {
    var smoothedGain by remember { mutableStateOf(1f) }
    LaunchedEffect(gain) { smoothedGain = smoothedGain * 0.85f + gain * 0.15f }
    val dbValue = 20f * kotlin.math.log10((smoothedGain + 0.001f).coerceAtLeast(0.001f))
    Box(modifier = modifier.fillMaxHeight().liquidGlass().padding(14.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text("环路衰减", color = textSecondary(), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
            Text(String.format(Locale.ROOT, "%.2f dB", dbValue), color = IOSColors.blue, fontSize = 24.sp, fontWeight = FontWeight.Light, fontFamily = AppFontFamily)
            LinearProgressIndicator(
                progress = { smoothedGain.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = IOSColors.blue, trackColor = if (isDark()) Color(0xFF39393B) else Color(0xFFE0E0E5)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  Composer Panel
// ════════════════════════════════════════════════════════════════

@Composable
fun IOSComposerPanel(
    selectedPersonaName: String, onPersonaChange: (String) -> Unit,
    gammaOverride: Float, onGammaChange: (Float) -> Unit,
    primitiveType: String, primitiveSemantic: String, primitiveIntensity: Int, primitiveDuration: Int,
    currentGamma: Float, activePersonaName: String,
    onRestartScopedApps: () -> Unit
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    
    Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = IOSColors.blue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Haptic Composer", color = textPrimary(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFontFamily)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                hapticEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)
                onRestartScopedApps()
            }) {
                Icon(Icons.Default.Refresh, "重启作用域 App", tint = IOSColors.blue, modifier = Modifier.size(24.dp))
            }
        }

        Text("Music Persona", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicPersona.ALL.forEach { persona ->
                val isSelected = selectedPersonaName == persona.name
                Box(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) IOSColors.blue.copy(alpha = 0.12f) else Color.Transparent)
                        .border(if (isSelected) 1.dp else 0.dp, if (isSelected) IOSColors.blue else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable {
                            if (!isSelected) {
                                hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                onPersonaChange(persona.name)
                            }
                        }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(persona.displayName, fontSize = 13.sp, color = if (isSelected) IOSColors.blue else textSecondary(), fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, fontFamily = AppFontFamily, textAlign = TextAlign.Center)
                }
            }
        }

        val effectiveGamma = if (gammaOverride > 0f) gammaOverride else currentGamma
        var lastGammaHaptic by remember { mutableStateOf(effectiveGamma) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Gamma 曲线", color = textSecondary(), fontSize = 14.sp, fontFamily = AppFontFamily)
                Text(String.format(Locale.US, "γ = %.2f", effectiveGamma), color = IOSColors.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
            }
            Slider(
                value = effectiveGamma, onValueChange = {
                    onGammaChange(it)
                    if (kotlin.math.abs(it - lastGammaHaptic) >= 0.02f) {
                        hapticEngine.perform(HapticFeedbackEngine.HapticStyle.LIGHT_TICK)
                        lastGammaHaptic = it
                    }
                }, valueRange = 0.3f..0.8f, steps = 49,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = IOSColors.blue, inactiveTrackColor = if (isDark()) Color(0xFF39393B) else Color(0xFFE0E0E5))
            )
            Text("γ < 1 提升小信号震感 · γ > 1 强化大信号冲击", color = textTertiary(), fontSize = 11.sp, fontFamily = AppFontFamily)
        }

        HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)

        // Now Playing with hold mechanism — fixed height to prevent container resize
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(activePersonaName, color = textPrimary(), fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
            if (primitiveType.isNotEmpty()) {
                val badgeAlpha by animateFloatAsState(targetValue = 1f, animationSpec = tween(150), label = "BadgeIn")
                Surface(
                    shape = RoundedCornerShape(10.dp), color = IOSColors.blue.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, IOSColors.blue.copy(alpha = 0.3f)),
                    modifier = Modifier.graphicsLayer { alpha = badgeAlpha }
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("[$primitiveType]", color = IOSColors.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFontFamily)
                        if (primitiveSemantic.isNotEmpty())
                            Text(primitiveSemantic, color = IOSColors.blue.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = AppFontFamily)
                        Text("I:$primitiveIntensity  D:${primitiveDuration}ms", color = textSecondary(), fontSize = 10.sp, fontFamily = AppFontFamily)
                    }
                }
            } else {
                // Placeholder with same height as the badge to prevent layout shift
                Surface(
                    shape = RoundedCornerShape(10.dp), color = Color.Transparent,
                    modifier = Modifier
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("—", color = textTertiary(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFontFamily)
                        Text("待机", color = textTertiary(), fontSize = 11.sp, fontFamily = AppFontFamily)
                        Text(" ", color = Color.Transparent, fontSize = 10.sp, fontFamily = AppFontFamily)
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  Control Panel
// ════════════════════════════════════════════════════════════════

@Composable
fun IOSControlPanel(
    isMasterSwitchOn: Boolean,
    isPowerAmplifyActive: Boolean, onPowerAmplifyClick: () -> Unit,
    isCrossoverBypassActive: Boolean, onCrossoverBypassClick: () -> Unit,
    selectedPreset: Preset, onPresetChange: (Preset) -> Unit,
    showAdvancedSettings: Boolean, onAdvancedSettingsToggle: () -> Unit,
    customAmplitude: Float, onAmplitudeChange: (Float) -> Unit,
    customBassBoost: Float, onBassBoostChange: (Float) -> Unit,
    hapticPreset: HapticPreset, onHapticPresetChange: (HapticPreset) -> Unit,
    synthLraF0: Float, onSynthLraF0Change: (Float) -> Unit,
    synthLraQ: Float, onSynthLraQChange: (Float) -> Unit,
    synthRateHz: Int, onSynthRateHzChange: (Int) -> Unit,
    synthAttackImpact: Float, onSynthAttackImpactChange: (Float) -> Unit,
    synthDecayImpact: Float, onSynthDecayImpactChange: (Float) -> Unit,
    synthAttackContinuous: Float, onSynthAttackContinuousChange: (Float) -> Unit,
    synthDecayContinuous: Float, onSynthDecayContinuousChange: (Float) -> Unit,
    synthReleaseTau: Float, onSynthReleaseTauChange: (Float) -> Unit,
    synthSustainLevel: Float, onSynthSustainLevelChange: (Float) -> Unit,
    synthThermalWarn: Float, onSynthThermalWarnChange: (Float) -> Unit,
    synthThermalCrit: Float, onSynthThermalCritChange: (Float) -> Unit,
    synthThermalRth: Float, onSynthThermalRthChange: (Float) -> Unit,
    synthThermalCth: Float, onSynthThermalCthChange: (Float) -> Unit,
    synthImpactGain: Float, onSynthImpactGainChange: (Float) -> Unit,
    synthContinuousGain: Float, onSynthContinuousGainChange: (Float) -> Unit,
    synthTextureGain: Float, onSynthTextureGainChange: (Float) -> Unit,
    synthMasterGain: Float, onSynthMasterGainChange: (Float) -> Unit,
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IOSButton("线圈放大", isPowerAmplifyActive, Modifier.weight(1f),
                hapticStyle = if (!isPowerAmplifyActive) HapticFeedbackEngine.HapticStyle.KICK else HapticFeedbackEngine.HapticStyle.IMPACT
            ) { onPowerAmplifyClick() }
            IOSButton("有源分频", isCrossoverBypassActive, Modifier.weight(1f),
                hapticStyle = if (!isCrossoverBypassActive) HapticFeedbackEngine.HapticStyle.KICK else HapticFeedbackEngine.HapticStyle.IMPACT
            ) { onCrossoverBypassClick() }
        }

        Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("增益档位", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
            IOSSegmentedControl(items = Preset.entries.toList(), selected = selectedPreset, onSelect = onPresetChange, label = { it.label })

            Text("风格预设", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HapticPreset.entries.forEach { preset ->
                    val isSelected = hapticPreset == preset
                    Box(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) IOSColors.blue.copy(alpha = 0.12f) else Color.Transparent)
                            .border(if (isSelected) 1.dp else 0.dp, if (isSelected) IOSColors.blue else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable {
                                if (hapticPreset != preset) {
                                    onHapticPresetChange(preset)
                                    hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                }
                            }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(preset.label, color = if (isSelected) IOSColors.blue else textSecondary(), fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, fontFamily = AppFontFamily)
                            Text(preset.description, color = if (isSelected) IOSColors.blue.copy(alpha = 0.7f) else textTertiary(), fontSize = 9.sp, fontFamily = AppFontFamily, maxLines = 1)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showAdvancedSettings,
            enter = expandVertically(spring(dampingRatio = 0.62f, stiffness = 200f), Alignment.Top) + fadeIn(spring(dampingRatio = 0.62f, stiffness = 200f)),
            exit = shrinkVertically(spring(dampingRatio = 0.62f, stiffness = 200f), Alignment.Top) + fadeOut(spring(dampingRatio = 0.62f, stiffness = 200f))
        ) {
            Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("高级设置", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
                IOSSettingSliderRow("总强度", customAmplitude, 0.5f..3.0f, "x", onAmplitudeChange)
                IOSSettingSliderRow("低音强调", customBassBoost, 1.0f..2.5f, "x", onBassBoostChange)
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                Text("触觉合成器参数", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
                IOSSettingSliderRow("LRA 谐振频率", synthLraF0, 150f..250f, "Hz", onSynthLraF0Change)
                IOSSettingSliderRow("LRA 品质因子 Q", synthLraQ, 5f..30f, "", onSynthLraQChange)
                IOSSettingSliderRow("合成帧率", synthRateHz.toFloat(), 30f..120f, "Hz", { onSynthRateHzChange(it.toInt()) })
                IOSSettingSliderRow("冲击攻击时间", synthAttackImpact * 1000f, 0.1f..10f, "ms", { onSynthAttackImpactChange(it / 1000f) })
                IOSSettingSliderRow("冲击衰减时间", synthDecayImpact * 1000f, 1f..100f, "ms", { onSynthDecayImpactChange(it / 1000f) })
                IOSSettingSliderRow("持续音攻击时间", synthAttackContinuous * 1000f, 1f..50f, "ms", { onSynthAttackContinuousChange(it / 1000f) })
                IOSSettingSliderRow("持续音衰减时间", synthDecayContinuous * 1000f, 10f..200f, "ms", { onSynthDecayContinuousChange(it / 1000f) })
                IOSSettingSliderRow("释放时间", synthReleaseTau * 1000f, 10f..200f, "ms", { onSynthReleaseTauChange(it / 1000f) })
                IOSSettingSliderRow("维持电平", synthSustainLevel, 0.1f..0.8f, "", onSynthSustainLevelChange)
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                Text("热保护参数", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
                IOSSettingSliderRow("热警告温度", synthThermalWarn, 50f..85f, "°C", onSynthThermalWarnChange)
                IOSSettingSliderRow("热临界温度", synthThermalCrit, 80f..110f, "°C", onSynthThermalCritChange)
                IOSSettingSliderRow("热阻 Rth", synthThermalRth, 10f..50f, "°C/W", onSynthThermalRthChange)
                IOSSettingSliderRow("热容 Cth", synthThermalCth, 0.5f..5.0f, "J/°C", onSynthThermalCthChange)
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                Text("三基元增益", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
                IOSSettingSliderRow("冲击增益", synthImpactGain, 0.1f..3.0f, "x", onSynthImpactGainChange)
                IOSSettingSliderRow("持续音增益", synthContinuousGain, 0.1f..3.0f, "x", onSynthContinuousGainChange)
                IOSSettingSliderRow("纹理增益", synthTextureGain, 0.1f..3.0f, "x", onSynthTextureGainChange)
                IOSSettingSliderRow("主增益", synthMasterGain, 0.1f..3.0f, "x", onSynthMasterGainChange)
            }
        }

        IOSButton(
            if (showAdvancedSettings) "收起高级设置" else "展开高级设置", showAdvancedSettings, Modifier.fillMaxWidth(),
            hapticStyle = if (!showAdvancedSettings) HapticFeedbackEngine.HapticStyle.CRESCENDO else HapticFeedbackEngine.HapticStyle.IMPACT,
            onClick = onAdvancedSettingsToggle
        )
    }
}

// ════════════════════════════════════════════════════════════════
//  Developer Card
// ════════════════════════════════════════════════════════════════

@Composable
fun IOSDeveloperCard() {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }

    Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("开发者信息", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = AppFontFamily)
                Spacer(Modifier.height(4.dp))
                Text("开发者：もうや", color = textPrimary(), fontSize = 15.sp, fontFamily = AppFontFamily)
            }
        }
        IOSButton("QQ交流群：1047262325  (点击复制)", false, Modifier.fillMaxWidth(), HapticFeedbackEngine.HapticStyle.SUCCESS) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", "1047262325"))
            Toast.makeText(context, "QQ群号已复制", Toast.LENGTH_SHORT).show()
        }
        Text(
            "GitHub：github.com/mouya-q/MusicHaptics",
            color = IOSColors.blue, fontSize = 14.sp, fontFamily = AppFontFamily, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth().clickable {
                hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/MusicHaptics"))) } catch (_: Exception) {}
            }
        )
        Text("修改设置后，点击右上角 ↻ 并重启对应音乐 App，设置才会由 Xposed 重新加载。", color = IOSColors.orange, fontSize = 12.sp, fontFamily = AppFontFamily)
    }
}

private data class ScopedApp(val packageName: String, val label: String)

@Composable
private fun ScopedAppsRestartDialog(onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val context = LocalContext.current
    val scopedPackages = remember { listOf(
        "tv.danmaku.bili", "com.kugou.android", "com.kugou.android.lite", "cn.kuwo.player",
        "com.md3music.md3music", "com.netease.cloudmusic", "com.tencent.qqmusic",
        "com.ss.android.ugc.aweme", "com.smile.gifmaker", "fm.xiami.main", "cmccwm.mobilemusic",
        "com.luna.music", "com.spotify.music", "com.google.android.apps.youtube.music"
    ) }
    val apps = remember {
        scopedPackages.mapNotNull { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                ScopedApp(pkg, context.packageManager.getApplicationLabel(info).toString())
            } catch (_: Exception) { null }
        }
    }
    val selected = remember { mutableStateListOf<String>().apply { addAll(apps.map { it.packageName }) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重启作用域 App") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("勾选需要重新注入 Hook 并加载新设置的 App。", color = textSecondary(), fontSize = 14.sp)
                Text("将尝试通过 Root 执行 force-stop；未获取 Root 权限时，请手动结束并重新打开所选 App。", color = IOSColors.orange, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                if (apps.isEmpty()) Text("未检测到已安装的作用域 App。", color = textSecondary())
                apps.forEach { app ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (app.packageName in selected) selected.remove(app.packageName) else selected.add(app.packageName)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = app.packageName in selected, onCheckedChange = { checked ->
                            if (checked && app.packageName !in selected) selected.add(app.packageName)
                            if (!checked) selected.remove(app.packageName)
                        })
                        Column {
                            Text(app.label, color = textPrimary(), fontSize = 15.sp)
                            Text(app.packageName, color = textTertiary(), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.toList()) }, enabled = selected.isNotEmpty()) { Text("确定重启") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun forceStopSelectedAppsWithRoot(packages: List<String>): Boolean {
    if (packages.isEmpty()) return false
    return try {
        val command = packages.joinToString("; ") { "am force-stop ${it}" }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        process.waitFor() == 0
    } catch (_: Exception) { false }
}

// ════════════════════════════════════════════════════════════════
//  Data Classes & Enums
// ════════════════════════════════════════════════════════════════

data class TelemetrySnapshot(
    val subBass: Float = 0f, val midBass: Float = 0f, val presence: Float = 0f,
    val intensity: Float = 0f, val latencyMs: Float = 0f, val temperature: Float = 25f,
    val f0Hz: Int = 150, val adsrEnv: Float = 0f, val lraForce: Float = 0f,
    val lraPhase: Float = 0f, val lraDisp: Float = 0f, val thermalAttenuation: Float = 1f,
)

enum class HapticPreset(val label: String, val description: String) {
    BALANCED("均衡", "全频还原"), BASS_ENHANCED("重低音", "震感加强"),
    TEXTURE_FOCUS("纹理", "高频细腻"), IMPACT_MAX("冲击", "瞬态最大"),
    CUSTOM("自定义", "手动调参"),
}

enum class Preset(val label: String) {
    LOW("Low"), MID("Mid"), HIGH("High"), ULTRA("Ultra")
}