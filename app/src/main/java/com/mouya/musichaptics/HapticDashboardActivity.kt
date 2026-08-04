package com.mouya.musichaptics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mouya.musichaptics.ui.ConsoleLogState
import com.mouya.musichaptics.ui.rememberConsoleLogState
import com.mouya.musichaptics.ui.IOSConsole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

@Composable
private fun isDark() = androidx.compose.foundation.isSystemInDarkTheme()

@Composable private fun bgPrimary() = if (isDark()) IOSColors.darkBg else IOSColors.lightBg
@Composable private fun cardColor() = if (isDark()) IOSColors.darkCard else IOSColors.lightCard
@Composable private fun cardAltColor() = if (isDark()) IOSColors.darkCardAlt else IOSColors.lightCardAlt
@Composable private fun glassColor() = if (isDark()) IOSColors.glassDark else IOSColors.glassLight
@Composable private fun textPrimary() = if (isDark()) IOSColors.darkTextPrimary else IOSColors.lightTextPrimary
@Composable private fun textSecondary() = if (isDark()) IOSColors.darkTextSecondary else IOSColors.lightTextSecondary
@Composable private fun textTertiary() = if (isDark()) IOSColors.darkTextTertiary else IOSColors.lightTextTertiary
@Composable private fun separatorColor() = if (isDark()) Color(0xFF38383A) else Color(0xFFC6C6C8)

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
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isDark()) Color(0xFF2C2C2E) else Color(0xFFEFEFF2))
    ) {
        val itemWidth = maxWidth / items.size
        val animatedOffset by animateDpAsState(
            targetValue = itemWidth * items.indexOf(selected),
            animationSpec = PhysicsSpring.elasticSelectDp(), label = "SegmentOffset"
        )
        Box(
            modifier = Modifier.offset(x = animatedOffset).width(itemWidth).fillMaxHeight()
                .padding(2.dp).clip(RoundedCornerShape(8.dp))
                .background(if (isDark()) Color(0xFF48484A) else Color.White)
        )
        Row(Modifier.fillMaxSize()) {
            items.forEach { item ->
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (item != selected) {
                                hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                onSelect(item)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label(item), fontSize = 13.sp,
                        fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (item == selected) textPrimary() else textSecondary(),
                        fontFamily = FontFamily.SansSerif
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
            Text(label, fontSize = 15.sp, color = textPrimary(), fontFamily = FontFamily.SansSerif)
            Text("${String.format(Locale.ROOT, "%.1f", value)} $unit", fontSize = 15.sp, color = IOSColors.blue, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
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
        Text(label, color = if (isActive) IOSColors.blue else textPrimary(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
    }
}

// ════════════════════════════════════════════════════════════════
//  Activity
// ════════════════════════════════════════════════════════════════

class HapticDashboardActivity : ComponentActivity() {

    private val telemetryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bundle = android.os.Bundle().apply {
                putFloat("sub", intent.getFloatExtra("sub", 0f))
                putFloat("mid", intent.getFloatExtra("mid", 0f))
                putFloat("pres", intent.getFloatExtra("pres", 0f))
                putFloat("f0", intent.getFloatExtra("f0", 150f))
                putFloat("temp", intent.getFloatExtra("temp", 25f))
                putFloat("atten", intent.getFloatExtra("atten", 1f))
                putLong("latency", intent.getLongExtra("latency", 0L))
                putFloat("loFreq", intent.getFloatExtra("loFreq", 160f))
                putFloat("hiFreq", intent.getFloatExtra("hiFreq", 350f))
                putFloat("ampScale", intent.getFloatExtra("ampScale", 1f))
                putLong("overruns", intent.getLongExtra("overruns", 0L))
                putLong("subCount", intent.getLongExtra("subCount", 0L))
                putLong("midCount", intent.getLongExtra("midCount", 0L))
                putLong("texCount", intent.getLongExtra("texCount", 0L))
                putLong("time", intent.getLongExtra("time", System.currentTimeMillis()))
                putFloat("lraDisp", intent.getFloatExtra("lraDisp", 0f))
                putFloat("lraVel", intent.getFloatExtra("lraVel", 0f))
                putFloat("lraForce", intent.getFloatExtra("lraForce", 0f))
                putFloat("lraPhase", intent.getFloatExtra("lraPhase", 0f))
                putFloat("adsrEnv", intent.getFloatExtra("adsrEnv", 0f))
                putFloat("thermalGain", intent.getFloatExtra("thermalGain", 1f))
                putBoolean("keyStrikeActive", intent.getBooleanExtra("keyStrikeActive", intent.getBooleanExtra("keyStrike", false)))
                putString("keyStrikeSemantic", intent.getStringExtra("keyStrikeSemantic") ?: intent.getStringExtra("keySemantic") ?: "NONE")
                putString("semanticType", intent.getStringExtra("semanticType") ?: intent.getStringExtra("semType") ?: "BALANCED")
                putString("personaName", intent.getStringExtra("personaName") ?: "POP")
                putString("primitiveType", intent.getStringExtra("primitiveType") ?: "")
                putString("primitiveSemantic", intent.getStringExtra("primitiveSemantic") ?: "")
                putInt("primitiveIntensity", intent.getIntExtra("primitiveIntensity", 0))
                putInt("primitiveDuration", intent.getIntExtra("primitiveDuration", 0))
                putFloat("gammaValue", intent.getFloatExtra("gammaValue", 0.5f))
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

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

    var isMasterSwitchOn by remember { mutableStateOf(true) }
    var isPowerAmplifyActive by remember { mutableStateOf(false) }
    var isCrossoverBypassActive by remember { mutableStateOf(true) }
    var selectedPreset by remember { mutableStateOf(Preset.HIGH) }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var customAmplitude by remember { mutableStateOf(2.0f) }
    var customBassBoost by remember { mutableStateOf(1.6f) }
    var hapticPreset by remember { mutableStateOf(HapticPreset.BALANCED) }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE) }

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

    LaunchedEffect(isMasterSwitchOn, isPowerAmplifyActive, isCrossoverBypassActive, selectedPreset, customAmplitude, customBassBoost, hapticPreset) {
        prefs.edit().apply {
            putBoolean("master_switch", isMasterSwitchOn)
            putBoolean("power_amplify", isPowerAmplifyActive)
            putBoolean("crossover_bypass", isCrossoverBypassActive)
            putInt("selected_preset", selectedPreset.ordinal)
            putFloat("haptic_amplitude", customAmplitude)
            putFloat("haptic_bass_boost", customBassBoost)
            putInt("haptic_preset_id", hapticPreset.ordinal)
            putString("haptic_preset", hapticPreset.name)
        }.apply()
    }

    Box(modifier = Modifier.fillMaxSize().background(bgPrimary())) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IOSHeaderCard(isMasterSwitchOn, { isMasterSwitchOn = !isMasterSwitchOn })
            IOSTelemetryCard(telemetry, isMasterSwitchOn)
            IOSComposerPanel(
                selectedPersonaName, { selectedPersonaName = it; prefs.edit().putString("music_persona", it).apply() },
                gammaOverride, { gammaOverride = it; prefs.edit().putFloat("haptic_gamma_override", it).apply() },
                heldPrimitiveType, heldPrimitiveSemantic, heldPrimitiveIntensity, heldPrimitiveDuration,
                TelemetryHub.gammaValue, TelemetryHub.personaName,
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
                onClear = { consoleLogState.clear() }, logs = consoleLogState.logs
            )
            IOSDeveloperCard()
        }
    }
}

// ════════════════════════════════════════════════════════════════
//  Header Card
// ════════════════════════════════════════════════════════════════

@Composable
fun IOSHeaderCard(isMasterSwitchOn: Boolean, onMasterSwitchToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("MusicHapticsX", color = textPrimary(), fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (isMasterSwitchOn) IOSColors.green else IOSColors.gray))
                Spacer(Modifier.width(6.dp))
                Text(if (isMasterSwitchOn) "运行中" else "已停用", color = textSecondary(), fontSize = 14.sp, fontFamily = FontFamily.SansSerif)
            }
        }
        IOSToggle(
            checked = isMasterSwitchOn, onToggle = onMasterSwitchToggle,
            onStyle = { if (it) HapticFeedbackEngine.HapticStyle.CRESCENDO else HapticFeedbackEngine.HapticStyle.KICK }
        )
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
                Text("频谱与触觉动态", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
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
            Text("热模型", color = textSecondary(), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
            Text(String.format(Locale.ROOT, "%.1f°C", smoothedTemp), color = if (smoothedTemp > 45f) IOSColors.red else textPrimary(), fontSize = 24.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif)
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
            Text("环路衰减", color = textSecondary(), fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
            Text(String.format(Locale.ROOT, "%.2f dB", dbValue), color = IOSColors.blue, fontSize = 24.sp, fontWeight = FontWeight.Light, fontFamily = FontFamily.SansSerif)
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
    currentGamma: Float, activePersonaName: String
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = IOSColors.blue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Haptic Composer", color = textPrimary(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
        }

        Text("Music Persona", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
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
                    Text(persona.displayName, fontSize = 13.sp, color = if (isSelected) IOSColors.blue else textSecondary(), fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, fontFamily = FontFamily.SansSerif, textAlign = TextAlign.Center)
                }
            }
        }

        val effectiveGamma = if (gammaOverride > 0f) gammaOverride else currentGamma
        var lastGammaHaptic by remember { mutableStateOf(effectiveGamma) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Gamma 曲线", color = textSecondary(), fontSize = 14.sp, fontFamily = FontFamily.SansSerif)
                Text(String.format(Locale.US, "γ = %.2f", effectiveGamma), color = IOSColors.blue, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
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
            Text("γ < 1 提升小信号震感 · γ > 1 强化大信号冲击", color = textTertiary(), fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
        }

        HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)

        // Now Playing with hold mechanism — fixed height to prevent container resize
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(activePersonaName, color = textPrimary(), fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
            if (primitiveType.isNotEmpty()) {
                val badgeAlpha by animateFloatAsState(targetValue = 1f, animationSpec = tween(150), label = "BadgeIn")
                Surface(
                    shape = RoundedCornerShape(10.dp), color = IOSColors.blue.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, IOSColors.blue.copy(alpha = 0.3f)),
                    modifier = Modifier.graphicsLayer { alpha = badgeAlpha }
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("[$primitiveType]", color = IOSColors.blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                        if (primitiveSemantic.isNotEmpty())
                            Text(primitiveSemantic, color = IOSColors.blue.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
                        Text("I:$primitiveIntensity  D:${primitiveDuration}ms", color = textSecondary(), fontSize = 10.sp, fontFamily = FontFamily.SansSerif)
                    }
                }
            } else {
                // Placeholder with same height as the badge to prevent layout shift
                Surface(
                    shape = RoundedCornerShape(10.dp), color = Color.Transparent,
                    modifier = Modifier
                ) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("—", color = textTertiary(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif)
                        Text("待机", color = textTertiary(), fontSize = 11.sp, fontFamily = FontFamily.SansSerif)
                        Text(" ", color = Color.Transparent, fontSize = 10.sp, fontFamily = FontFamily.SansSerif)
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
            Text("预设档位", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
            IOSSegmentedControl(items = Preset.values().toList(), selected = selectedPreset, onSelect = onPresetChange, label = { it.label })

            Text("震感预设", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HapticPreset.values().forEach { preset ->
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
                            Text(preset.label, color = if (isSelected) IOSColors.blue else textSecondary(), fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, fontFamily = FontFamily.SansSerif)
                            Text(preset.description, color = if (isSelected) IOSColors.blue.copy(alpha = 0.7f) else textTertiary(), fontSize = 9.sp, fontFamily = FontFamily.SansSerif, maxLines = 1)
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
                Text("高级设置", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
                IOSSettingSliderRow("总强度", customAmplitude, 0.5f..3.0f, "x", onAmplitudeChange)
                IOSSettingSliderRow("低音强调", customBassBoost, 1.0f..2.5f, "x", onBassBoostChange)
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                Text("触觉合成器参数", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
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
                Text("热保护参数", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
                IOSSettingSliderRow("热警告温度", synthThermalWarn, 50f..85f, "°C", onSynthThermalWarnChange)
                IOSSettingSliderRow("热临界温度", synthThermalCrit, 80f..110f, "°C", onSynthThermalCritChange)
                IOSSettingSliderRow("热阻 Rth", synthThermalRth, 10f..50f, "°C/W", onSynthThermalRthChange)
                IOSSettingSliderRow("热容 Cth", synthThermalCth, 0.5f..5.0f, "J/°C", onSynthThermalCthChange)
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                Text("三基元增益", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
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
    Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("开发者信息", color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.SansSerif)
        Text("开发者：もうや", color = textPrimary(), fontSize = 15.sp, fontFamily = FontFamily.SansSerif)
        IOSButton("QQ交流群：1047262325  (点击复制)", false, Modifier.fillMaxWidth(),
            hapticStyle = HapticFeedbackEngine.HapticStyle.SUCCESS
        ) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", "1047262325"))
        }
        Box(
            Modifier.fillMaxWidth().clickable {
                hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/MusicHaptics"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
        ) {
            Text("GitHub：github.com/mouya-q/MusicHaptics", color = IOSColors.blue, fontSize = 14.sp, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium)
        }
    }
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