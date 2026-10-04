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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.unit.IntSize
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
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.abs


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

@Composable private fun isDark() = isSystemInDarkTheme()
@Composable private fun bgPrimary() = if (isDark()) IOSColors.darkBg else IOSColors.lightBg
@Composable private fun cardColor() = if (isDark()) IOSColors.darkCard else IOSColors.lightCard
@Composable private fun cardAltColor() = if (isDark()) IOSColors.darkCardAlt else IOSColors.lightCardAlt
@Composable private fun glassColor() = if (isDark()) IOSColors.glassDark else IOSColors.glassLight
@Composable private fun textPrimary() = if (isDark()) IOSColors.darkTextPrimary else IOSColors.lightTextPrimary
@Composable private fun textSecondary() = if (isDark()) IOSColors.darkTextSecondary else IOSColors.lightTextSecondary
@Composable private fun textTertiary() = if (isDark()) IOSColors.darkTextTertiary else IOSColors.lightTextTertiary
@Composable private fun separatorColor() = if (isDark()) Color.White.copy(alpha = 0.10f) else Color(0xFFC6C6C8)

private val LocalLiquidGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun Modifier.liquidGlass(corner: Dp = 22.dp): Modifier {
    val backdrop = LocalLiquidGlassBackdrop.current
    val shape = RoundedCornerShape(corner)
    // glassColor() is @Composable, so it must be read in composable scope; the
    // onDrawSurface lambda below is a plain DrawScope lambda and cannot call it.
    val glass = glassColor()
    return if (backdrop != null) {
        this.then(Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(18.dp.toPx())
                lens(8f, 18f, depthEffect = true, chromaticAberration = false)
            },
            highlight = { Highlight.Default },
            shadow = { Shadow(radius = 22.dp, alpha = 0.42f) },
            onDrawSurface = {
                drawRoundRect(glass, cornerRadius = CornerRadius(corner.toPx()))
            }
        ))
    } else {
        this.then(Modifier.clip(shape).background(glassColor()).border(0.5.dp, if (isDark()) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f), shape))
    }
}

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
            .liquidGlass(10.dp)
            .padding(2.dp)
    ) {
        val itemWidth = maxWidth / items.size
        val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
        
        val isInteracting = pressedItem != null || dragOffset != 0f
        
        val baseOffset = itemWidthPx * items.indexOf(selected)
        val lensOffsetPx by animateFloatAsState(
            targetValue = baseOffset + dragOffset,
            animationSpec = PhysicsSpring.elasticSelect(),  // near-critical damping
            label = "LensOffset"
        )

        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(lensOffsetPx.toInt(), 0) }
                .width(itemWidth)
                .fillMaxHeight()
                // no scale spring — flat, clean indicator
                .shadow(if (isInteracting) 6.dp else 0.dp, RoundedCornerShape(8.dp), ambientColor = IOSColors.blue.copy(alpha=0.4f), spotColor = IOSColors.blue.copy(alpha=0.3f))
                .clip(RoundedCornerShape(8.dp))
                .background(if (isDark()) Color(0xFF48484A) else Color.White)
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
                                        hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)  // commit haptic only
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
                       
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidGlassSliderTrack(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val progress = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (isDark()) Color(0xFF39393B) else Color(0xFFE8E8ED))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(IOSColors.blue.copy(alpha = 0.85f), IOSColors.blue)
                    )
                )
        )
    }
}

@Composable
fun IOSSettingSliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    unit: String, onValueChange: (Float) -> Unit
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    val thumbScale = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = textPrimary())
            Text("${String.format(Locale.ROOT, "%.1f", value)} $unit", fontSize = 15.sp, color = IOSColors.blue, fontWeight = FontWeight.Medium)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(range) {
                    val widthPx = size.width.toFloat()
                    fun xToValue(x: Float): Float {
                        val progress = (x / widthPx).coerceIn(0f, 1f)
                        return range.start + progress * (range.endInclusive - range.start)
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            coroutineScope.launch { thumbScale.animateTo(1.25f, PhysicsSpring.uiFast()) }  // v3.14
                            val v = xToValue(offset.x)
                            onValueChange(v)
                            hapticEngine.perform(HapticFeedbackEngine.HapticStyle.CONTINUOUS_HUM)  // start continuous
                        },
                        onDragEnd = {
                            coroutineScope.launch { thumbScale.animateTo(1f, PhysicsSpring.uiStandard()) }  // v3.14
                            hapticEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)  // commit tick
                        },
                        onDragCancel = {
                            coroutineScope.launch { thumbScale.animateTo(1f, PhysicsSpring.uiStandard()) }  // v3.14
                        }
                    ) { change, _ ->
                        change.consume()
                        val v = xToValue(change.position.x)
                        onValueChange(v)
                    }
                }
                .pointerInput(range) {
                    detectTapGestures(
                        onTap = { offset ->
                            val widthPx = size.width.toFloat()
                            val progress = (offset.x / widthPx).coerceIn(0f, 1f)
                            val v = range.start + progress * (range.endInclusive - range.start)
                            onValueChange(v)
                            hapticEngine.perform(HapticFeedbackEngine.HapticStyle.LIGHT_TICK)
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val trackWidth = maxWidth
            val progress = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val thumbSize = 24.dp
            val thumbOffset = trackWidth * progress - thumbSize / 2

            LiquidGlassSliderTrack(value, range, Modifier.fillMaxWidth())
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .graphicsLayer {
                        scaleX = thumbScale.value
                        scaleY = thumbScale.value
                    }
                    .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = IOSColors.blue.copy(alpha = 0.15f))
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(0.5.dp, IOSColors.blue.copy(alpha = 0.2f), CircleShape)
            )
        }
    }
}

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
        Text(label, color = if (isActive) IOSColors.blue else textPrimary(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}


class HapticDashboardActivity : ComponentActivity() {

    private val telemetryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Unpack the packed telemetry format
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

        setContent { MaterialTheme { ReducedMotionProvider { HapticDashboard() } } }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(telemetryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
    }
}


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

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE) }

    var isMasterSwitchOn by remember { mutableStateOf(prefs.getBoolean("master_switch", true)) }
    var selectedPreset by remember {
        val idx = prefs.getInt("selected_preset", Preset.HIGH.ordinal)
        mutableStateOf(Preset.entries.getOrElse(idx) { Preset.HIGH })
    }
    var showAdvancedSettings by remember { mutableStateOf(false) }
    // 强制满驱动 toggle. Defaults on for 小米10 系列 (umi/cmi/thyme), whose HAL
    // reports amplitude control but ignores the value — matches VibrateProxy detection.
    val forceDefaultAmpAutoDefault = remember {
        val d = android.os.Build.DEVICE.lowercase(java.util.Locale.ROOT)
        android.os.Build.MANUFACTURER.lowercase(java.util.Locale.ROOT) == "xiaomi" &&
            (d.contains("umi") || d.contains("cmi") || d.contains("thyme"))
    }
    var isForceDefaultAmpActive by remember {
        mutableStateOf(prefs.getBoolean("force_default_amplitude", forceDefaultAmpAutoDefault))
    }
    var customAmplitude by remember { mutableStateOf(prefs.getFloat("haptic_amplitude", 2.0f)) }
    var customBassBoost by remember { mutableStateOf(prefs.getFloat("haptic_bass_boost", 1.6f)) }
    var hapticPreset by remember {
        val idx = prefs.getInt("haptic_preset_id", HapticPreset.BALANCED.ordinal)
        mutableStateOf(HapticPreset.entries.getOrElse(idx) { HapticPreset.BALANCED })
    }
    // 5.2.7: 风格预设（与 DSP 参数一一对应）+ 强度百分比滑块。
    var stylePreset by remember {
        mutableStateOf(StylePreset.fromKey(prefs.getString("style_preset", "balanced")))
    }
    // 5.2.9: intensityPct 不再暴露为独立滑块。保留默认值用于 DSP 乘算。

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
    // 5.2.7：硬件触觉适配详情默认收起（标题行的 Root 状态始终可见），
    // 符合 iOS 设置里"次要信息默认折叠、常用信息常驻"的分级。
    var hardwareCardExpanded by rememberSaveable { mutableStateOf(false) }
    var showRestartDialog by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    var dashboardTab by rememberSaveable { mutableStateOf(DashboardTab.CONSOLE) }
    val liquidGlassBackdrop = rememberLayerBackdrop()
 
LaunchedEffect(isMasterSwitchOn, selectedPreset, customAmplitude, customBassBoost, hapticPreset,
                  stylePreset,
                  isForceDefaultAmpActive,
                 synthLraF0, synthLraQ, synthRateHz, synthAttackImpact, synthDecayImpact, synthAttackContinuous, synthDecayContinuous,
                 synthReleaseTau, synthSustainLevel, synthThermalWarn, synthThermalCrit, synthThermalRth, synthThermalCth,
                 synthImpactGain, synthContinuousGain, synthTextureGain, synthMasterGain) {
         prefs.edit().apply {
             putBoolean("master_switch", isMasterSwitchOn)
             putBoolean("force_default_amplitude", isForceDefaultAmpActive)
             putInt("selected_preset", selectedPreset.ordinal)
             putFloat("haptic_amplitude", customAmplitude)
             putFloat("haptic_bass_boost", customBassBoost)
             putFloat("haptic_boost_level", customBassBoost)
             putInt("haptic_preset_id", hapticPreset.ordinal)
             putString("haptic_preset", hapticPreset.name)
            // 5.2.7 风格预设与强度百分比
            putString("style_preset", stylePreset.key)
            // 5.2.9: intensity_pct 不再由 UI 独立写入；由风格预设 ampScale 隐式承担。
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

          context.sendBroadcast(
              Intent("com.mouya.musichaptics.ACTION_REFRESH_CONFIG").setPackage(null)
          )
      }

    CompositionLocalProvider(LocalLiquidGlassBackdrop provides liquidGlassBackdrop) {
    Box(modifier = Modifier
        .fillMaxSize()
        .background(bgPrimary())
        .statusBarsPadding()
        .navigationBarsPadding()
    ) {
        // The backdrop layer must record only what lives *behind* the glass.
        // Applying layerBackdrop() to a node that also contains the glass
        // children makes the RenderNode reference itself: the layer's display
        // list ends up drawing the glass, which in turn drawLayer()s this same
        // layer. RenderNode::prepareTreeImpl then recurses into
        // SkiaDisplayList::prepareListAndChildren and back without a base
        // case, overflowing the RenderThread stack (SIGSEGV, "stack pointer is
        // not in a rw map"). So record a dedicated background-only layer and
        // keep the glass content as a sibling drawn on top of it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(liquidGlassBackdrop)
        ) {
            // Backdrop content: a decorative gradient, no glass children.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                (bgPrimary()).copy(alpha = 1f),
                                (bgPrimary()).copy(alpha = 0.92f),
                            )
                        )
                    )
            )
        }
        AnimatedContent(
            targetState = dashboardTab,
            transitionSpec = {
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
            // 5.2.7：底部留白对齐新的 dock（64dp 栏体 + 18dp 底距 = 82dp），
            // 留 18dp 余量，确保最后一张卡滚到底时不被压住。
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            IOSHeaderCard(onRestartScopedApps = { showRestartDialog = true }, onShowAbout = { dashboardTab = DashboardTab.ABOUT })
            IOSHardwareProfileCard(
                rootVerified = hardwareRootVerified,
                profileId = hardwareProfileId,
                fingerprint = hardwareFingerprint,
                refreshing = hardwareRefreshing,
                expanded = hardwareCardExpanded,
                onToggleExpanded = { hardwareCardExpanded = !hardwareCardExpanded },
                onRefresh = {
                    hardwareRefreshing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { RootHardwareProbe.probeAndPersist(context) }
                        hardwareRootVerified = result.rootGranted
                        hardwareProfileId = result.profileId
                        hardwareFingerprint = result.fingerprint
                        hardwareRefreshing = false
                    }
                }
            )
            // 5.2.8 风格预设与强度百分比已并入下方 IOSControlPanel 的
            // "增益档位"与"风格预设"两处控件，不再单独占一张卡片。
            IOSControlPanel(
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
                stylePreset, { stylePreset = it },

                isForceDefaultAmpActive, { isForceDefaultAmpActive = !isForceDefaultAmpActive },
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
}

        // 5.2.8：对话框不再用 if 条件挂载 —— 那样组件一被移除，
        // 退出动画就没有机会播完，关闭是瞬间消失。常驻挂载、只传 show，
        // 进出会走同一条路径。
        ScopedAppsRestartDialog(
            show = showRestartDialog,
            onDismiss = { showRestartDialog = false },
            onConfirm = { selected ->
                val rootGranted = forceStopSelectedAppsWithRoot(selected)
                val message = if (rootGranted) {
                    "已通过 Root 重启 ${selected.size} 个 App"
                } else {
                    "未获取 Root 权限，请手动结束并重新打开已勾选 App，或授予 Root 权限后重试。"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                showRestartDialog = false
            }
            )
            } else if (tab == DashboardTab.APPS) {
                ScopedAppsScreen()
            } else {
                AboutScreen(onBack = { dashboardTab = DashboardTab.CONSOLE })
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
}

private enum class DashboardTab { CONSOLE, APPS, ABOUT }
private data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable?)

@Composable
private fun LiquidGlassTabBar(
    selected: DashboardTab,
    onSelected: (DashboardTab) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    // 5.2.8 回退：恢复 5.2.6 的胶囊 + 透镜横移滑块 + 可拖拽设计。
    // 5.2.7 改成"每项独立微凸"，实际用起来图标大小跳变、标签忽隐忽现，
    // 与界面对不齐，也不好拖。横移滑块更稳、更好按，故回退。
    val context = LocalContext.current
    val haptic = remember { HapticFeedbackEngine.create(context) }

    var pressedTab by remember { mutableStateOf<DashboardTab?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val tabs = listOf(
        DashboardTab.CONSOLE to ("控制台" to Icons.Default.Tune),
        DashboardTab.APPS to ("应用" to Icons.Default.Apps),
        DashboardTab.ABOUT to ("关于" to Icons.Default.MusicNote),
    )
    val barShape = RoundedCornerShape(30.dp)
    val pillShape = RoundedCornerShape(22.dp)
    val barGlass = glassColor()

    Box(
        modifier
            .width(240.dp)
            .height(58.dp)
            .shadow(16.dp, barShape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.16f))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { barShape },
                effects = {
                    vibrancy()
                    blur(20.dp.toPx())
                    lens(10f, 20f, depthEffect = true, chromaticAberration = false)
                },
                highlight = { Highlight.Default },
                shadow = { Shadow(radius = 20.dp, alpha = 0.42f) },
                onDrawSurface = { drawRoundRect(barGlass, cornerRadius = CornerRadius(30.dp.toPx())) }
            )
            .padding(4.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val itemWidth = maxWidth / tabs.size
            val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }
            val baseOffset = itemWidthPx * tabs.indexOfFirst { it.first == selected }
            val lensOffsetPx by animateFloatAsState(
                targetValue = baseOffset + dragOffset,
                animationSpec = PhysicsSpring.elasticSelect(),
                label = "DockLensOffset",
            )

            // 透镜滑块：跟随选中项与拖拽位移横移。
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(lensOffsetPx.toInt(), 0) }
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(pillShape)
                    .background(if (isDark()) Color(0xFF48484A) else Color.White)
            )

            Row(Modifier.fillMaxSize()) {
                tabs.forEach { (tab, meta) ->
                    val (title, icon) = meta
                    val active = selected == tab
                    val ink by animateColorAsState(
                        targetValue = if (active) IOSColors.blue else textSecondary(),
                        animationSpec = PhysicsSpring.colorBounce(),
                        label = "DockInk_$title",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(tab) {
                                detectDragGestures(
                                    onDragStart = { pressedTab = tab },
                                    onDragEnd = {
                                        val targetIndex = ((baseOffset + dragOffset) / itemWidthPx)
                                            .roundToInt().coerceIn(0, tabs.size - 1)
                                        val target = tabs[targetIndex].first
                                        if (target != selected) {
                                            haptic.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                            onSelected(target)
                                        }
                                        pressedTab = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = { pressedTab = null; dragOffset = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.x
                                }
                            }
                            .pointerInput(tab, "tap") {
                                detectTapGestures(
                                    onPress = {
                                        pressedTab = tab
                                        tryAwaitRelease()
                                        pressedTab = null
                                    },
                                    onTap = {
                                        if (!active) {
                                            haptic.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                            onSelected(tab)
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = ink,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                title,
                                color = ink,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ScopedAppsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { WhitelistManager() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var whitelistMode by remember { mutableStateOf(manager.getMode()) }
    var enabledPackages by remember { mutableStateOf(manager.getWhitelist()) }
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
    
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 24.dp, 16.dp, 102.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.app_haptics), color = textPrimary(), fontWeight = FontWeight.Bold, fontSize = 28.sp)
                Text(stringResource(R.string.scope_hint), color = textSecondary(), fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp, bottom = 10.dp))
            }
            item {
                WhitelistPanel(
                    mode = whitelistMode,
                    enabledCount = enabledPackages.size,
                    onModeChange = { mode ->
                        whitelistMode = mode
                        scope.launch { withContext(Dispatchers.IO) { manager.setMode(mode) } }
                    },
                    onClear = {
                        whitelistMode = WhitelistManager.MODE_WHITELIST
                        enabledPackages = emptySet()
                        scope.launch { withContext(Dispatchers.IO) { manager.replacePackages(emptySet()) } }
                    }
                )
            }
            if (scopedPackages == null) item { Text(stringResource(R.string.scope_not_found), color = textSecondary(), modifier = Modifier.padding(20.dp)) }
            else if (apps.isEmpty()) item { Text(stringResource(R.string.scope_empty), color = textSecondary(), modifier = Modifier.padding(20.dp)) }
            items(apps, key = { it.packageName }) { app ->
                val whitelisted = whitelistMode == WhitelistManager.MODE_ALL || app.packageName in enabledPackages
                ScopedAppRow(app, whitelisted, onToggleWhitelist = { allowed ->
                    enabledPackages = if (allowed) enabledPackages + app.packageName else enabledPackages - app.packageName
                    if (whitelistMode != WhitelistManager.MODE_WHITELIST) whitelistMode = WhitelistManager.MODE_WHITELIST
                    scope.launch {
                        withContext(Dispatchers.IO) { manager.setPackageAllowed(app.packageName, allowed) }
                    }
                }) { selected = app.packageName }
            }
        }
        
        AnimatedVisibility(
            visible = selected != null,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(220)),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(180))
        ) {
            val packageName = selected ?: return@AnimatedVisibility
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

@Composable
private fun WhitelistPanel(
    mode: String,
    enabledCount: Int,
    onModeChange: (String) -> Unit,
    onClear: () -> Unit
) {
    val whitelistOnlyLabel = stringResource(R.string.whitelist_only)
    val allScopeLabel = stringResource(R.string.all_scope)
    val scopeAllDesc = stringResource(R.string.scope_all_desc)
    val scopeWhitelistDesc = stringResource(R.string.scope_whitelist_desc)
    Column(Modifier.fillMaxWidth().liquidGlass(20.dp).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.processing_scope), color = textPrimary(), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    if (mode == WhitelistManager.MODE_ALL) scopeAllDesc else String.format(scopeWhitelistDesc, enabledCount),
                    color = textSecondary(), fontSize = 12.sp
                )
            }
            TextButton(onClick = onClear, enabled = enabledCount > 0) { Text(stringResource(R.string.clear)) }
        }
        Spacer(Modifier.height(10.dp))
        IOSSegmentedControl(
            items = listOf(WhitelistManager.MODE_WHITELIST, WhitelistManager.MODE_ALL),
            selected = mode,
            onSelect = onModeChange,
            label = { it -> if (it == WhitelistManager.MODE_WHITELIST) whitelistOnlyLabel else allScopeLabel }
        )
    }
}

@Composable private fun ScopedAppRow(app: LaunchableApp, whitelisted: Boolean, onToggleWhitelist: (Boolean) -> Unit, onClick: () -> Unit) {
    val initial = app.label.firstOrNull()?.uppercase() ?: "•"
    Row(Modifier.fillMaxWidth().liquidGlass(20.dp).clickable { onClick() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(IOSColors.indigo.copy(alpha=.10f)), contentAlignment = Alignment.Center) {
            if (app.icon != null) AndroidView(factory = { ImageView(it).apply { setImageDrawable(app.icon); scaleType = ImageView.ScaleType.CENTER_CROP } }, modifier = Modifier.fillMaxSize())
            else Text(initial, color=IOSColors.indigo, fontWeight=FontWeight.Bold, fontSize=19.sp)
        }
        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(app.label, color=textPrimary(), fontWeight=FontWeight.SemiBold); Text(app.packageName, color=textSecondary(), fontSize=12.sp, maxLines=1) }
        IOSToggle(checked = whitelisted, onToggle = { onToggleWhitelist(!whitelisted) })
        Spacer(Modifier.width(6.dp))
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
    LaunchedEffect(enabled, amp, boost) {
        scopedPrefs.edit().putBoolean("master_switch", enabled).putFloat("haptic_amplitude", amp).putFloat("haptic_boost_level", boost).apply()
        context.sendBroadcast(Intent("com.mouya.musichaptics.ACTION_REFRESH_CONFIG"))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 24.dp, 16.dp, 104.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回应用列表", tint = IOSColors.blue, modifier = Modifier.size(24.dp))
        }
        Text(label, color=textPrimary(), fontSize=27.sp, fontWeight=FontWeight.Bold)
        Text(packageName, color=textSecondary(), fontSize=13.sp)
        Text(stringResource(R.string.scoped_hint), color=textSecondary(), fontSize=13.sp, modifier=Modifier.liquidGlass(16.dp).padding(14.dp))
        Row(Modifier.fillMaxWidth().liquidGlass().padding(16.dp), verticalAlignment=Alignment.CenterVertically) { Text(stringResource(R.string.enable_haptic), Modifier.weight(1f), color=textPrimary(), fontWeight=FontWeight.Medium); IOSToggle(checked = enabled, onToggle = { enabled = !enabled }) }
        Column(Modifier.liquidGlass().padding(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.dedicated_intensity), color=textPrimary(), fontWeight=FontWeight.SemiBold)
            IOSSettingSliderRow(stringResource(R.string.total_intensity), amp, .5f..3f, "x") { amp = it }
            IOSSettingSliderRow(stringResource(R.string.bass_boost), boost, 1f..2.5f, "x") { boost = it }
        }
    }
}


@Composable
fun IOSHeaderCard(
    onRestartScopedApps: () -> Unit = {},
    onShowAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("MusicHapticsX", color = textPrimary(), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
                modifier = Modifier.clickable { onShowAbout() }
            )
        }
        IconButton(onClick = {
            hapticEngine.perform(HapticFeedbackEngine.HapticStyle.KICK)
            onRestartScopedApps()
        }) {
            Icon(Icons.Default.Refresh, "重启作用域 App", tint = textPrimary(), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun IOSHardwareProfileCard(
    rootVerified: Boolean,
    profileId: String,
    fingerprint: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val profile = detectDeviceProfile(persistedProfileId = profileId)
    // 同上：LocalContext 先在 composable 作用域取出，再交给 remember。
    val hwContext = LocalContext.current
    val hardwareHaptic = remember(hwContext) { HapticFeedbackEngine.create(hwContext) }
    val statusColor = if (rootVerified) IOSColors.green else IOSColors.red
    val statusText = if (rootVerified) "Root 已验证 · 已使用板级指纹" else "Root 未授权 · 未验证"
    val compactFingerprint = fingerprint.lineSequence()
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString(" · ")
        .ifBlank { "尚未读取硬件指纹" }

    // 5.2.7：折叠态在 composable 作用域解析，drawBehind 的 lambda 不能再调 @Composable。
    val dividerColor = separatorColor().copy(alpha = 0.55f)
    val headerShape = RoundedCornerShape(22.dp)
    val reducedMotion = LocalPrefersReducedMotion.current
    // Apple：折叠指示器应该在按下瞬间就反馈，所以箭头随展开状态做弹簧旋转，
    // 而不是等动画结束再变。用近临界阻尼弹簧，一次极微过冲，可随时被打断。
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.9f, stiffness = 320f),
        label = "HardwareChevron",
    )
    // Reduced motion 不是零反馈 —— 保留透明度过渡（它帮助理解"展开了"），
    // 只丢掉高度与位移这类会移动的动画，避免前庭不适。
    // expandVertically 的高度动画作用在 IntSize 上（不是 Int），类型必须对上。
    val foldExpandSpec: FiniteAnimationSpec<IntSize> =
        if (reducedMotion) tween(160) else spring(dampingRatio = 0.9f, stiffness = 300f)
    val foldFadeSpec: FiniteAnimationSpec<Float> =
        if (reducedMotion) tween(160) else spring(dampingRatio = 1f, stiffness = 400f)
    val foldShrinkSpec: FiniteAnimationSpec<IntSize> =
        if (reducedMotion) tween(120) else spring(dampingRatio = 1f, stiffness = 400f)

    Column(
        Modifier.fillMaxWidth().liquidGlass().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(headerShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                .background(statusColor.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Security, null, tint = statusColor, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.hardware_haptic), color = textPrimary(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(statusText, color = statusColor, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = IOSColors.blue)
                else Icon(Icons.Default.Refresh, "重新检测硬件", tint = IOSColors.blue)
            }
            // 折叠开关：整块标题行可点，命中区远大于图标本身（约 44dp）。
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            // 触觉与视觉同帧：状态一改，弹簧立刻起步，触觉此刻发出。
                            hardwareHaptic.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                            onToggleExpanded()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起硬件触觉适配详情" else "展开硬件触觉适配详情",
                    tint = if (expanded) IOSColors.blue else textSecondary(),
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = chevronRotation },
                )
            }
        }
        // 折叠体：Materialize，不要只用淡入淡出 —— 高度与内容一起展开，
        // 让它读起来像一块真实的材料落位，而不是一段半透明的淡入。
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = foldExpandSpec,
                expandFrom = Alignment.Top,
            ) + fadeIn(foldFadeSpec),
            exit = shrinkVertically(
                animationSpec = foldShrinkSpec,
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(if (reducedMotion) 120 else 140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = dividerColor)
                Text(profile.name, color = textPrimary(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "f₀ ${profile.actuator.resonanceFreq.toInt()} Hz  ·  Q ${"%.1f".format(Locale.ROOT, profile.actuator.qFactor)}  ·  上升 ${"%.1f".format(Locale.ROOT, profile.actuator.riseTimeMs)} ms",
                    color = textSecondary(), fontSize = 12.sp
                )
                Text(compactFingerprint, color = textTertiary(), fontSize = 11.sp, maxLines = 2)
                Text(stringResource(R.string.recheck_hint), color = textTertiary(), fontSize = 11.sp)
            }
        }
    }
}
@Composable
fun IOSControlPanel(
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
    // 5.2.8：六档风格与强度百分比并入本控件，复用既有的“增益档位 / 风格预设”两处，
    // 不再另起卡片，避免同一件事在界面上有两个入口。
    stylePreset: StylePreset, onStylePresetChange: (StylePreset) -> Unit,
    isForceDefaultAmpActive: Boolean = false, onForceDefaultAmpClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 5.2.8：强度百分比并入"增益档位"。它与既有的四档基础增益是同一件事
            // 的粗细两档 —— 档位定基准、百分比做连续微调，界面只应有一个入口。
            Text(stringResource(R.string.gain_level), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            IOSSegmentedControl(items = Preset.entries.toList(), selected = selectedPreset, onSelect = onPresetChange, label = { it.label })
            // 5.2.9：强度百分比已并入上方"增益档位"分段控件。
            // 档位本身就是强度的粗调（Low/Mid/High/Ultra 对应 0.7~1.2x），
            // 不再单独开一个滑块制造重复入口。haptic_intensity_pct 由
            // 风格预设的 ampScale 隐式承担，DSP 层已正确乘算。

            // 5.2.8：六档风格并入"风格预设"。每档改写的是一组真实 DSP 参数
            // （频段 / 锐度 / 起音 / 冷却 / 阈值 / 重音），不是常数倍率。
            Text(stringResource(R.string.style_preset), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StylePreset.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            val isSelected = stylePreset == preset
                            Box(
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) IOSColors.blue.copy(alpha = 0.12f) else Color.Transparent)
                                    .border(if (isSelected) 1.dp else 0.dp, if (isSelected) IOSColors.blue else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (!isSelected) {
                                            onStylePresetChange(preset)
                                            hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                        }
                                    }.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(preset.label, color = if (isSelected) IOSColors.blue else textSecondary(), fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                                    Text(preset.description, color = if (isSelected) IOSColors.blue.copy(alpha = 0.7f) else textTertiary(), fontSize = 9.sp, maxLines = 1)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            // 实时参数预览：与 [STYLE] 日志逐项对照，便于确认 UI 与 DSP 一致。
            Text(
                "锐度 ${"%.2f".format(stylePreset.sharpness)} · 起音 x${"%.2f".format(stylePreset.attackScale)} · " +
                    "频段 ${stylePreset.lowCutHz.toInt()}-${stylePreset.highCutHz.toInt()}Hz · " +
                    "冷却 ${stylePreset.cooldownMs}ms · 增益 x${"%.2f".format(stylePreset.ampScale)}",
                color = textTertiary(), fontSize = 10.sp,
            )
        }

        AnimatedVisibility(
            visible = showAdvancedSettings,
            enter = expandVertically(tween(300, easing = LinearOutSlowInEasing), Alignment.Top) + fadeIn(tween(250)),  // ease-out
            exit = shrinkVertically(tween(300, easing = FastOutLinearInEasing), Alignment.Top) + fadeOut(tween(200))  // ease-in
        ) {
            Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.advanced_settings), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                IOSSettingSliderRow("总强度", customAmplitude, 0.5f..3.0f, "x", onAmplitudeChange)
                IOSSettingSliderRow("低音强调", customBassBoost, 1.0f..2.5f, "x", onBassBoostChange)
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                // Some ROMs report振幅可控 but the HAL ignores it, so every custom
                // amplitude comes out equally weak. This forces DEFAULT_AMPLITUDE and lets
                // segment *durations* carry the texture instead. On 小米10 系列 it是默认开启的。
                Text(stringResource(R.string.drive_mode), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                IOSButton(stringResource(R.string.force_full_drive), isForceDefaultAmpActive, Modifier.fillMaxWidth(),
                    hapticStyle = if (!isForceDefaultAmpActive) HapticFeedbackEngine.HapticStyle.KICK else HapticFeedbackEngine.HapticStyle.IMPACT
                ) { onForceDefaultAmpClick() }
                Text(stringResource(R.string.force_drive_hint),
                    color = textTertiary(), fontSize = 10.sp
                )
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                Text(stringResource(R.string.synth_params), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
                Text(stringResource(R.string.thermal_params), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                IOSSettingSliderRow("热警告温度", synthThermalWarn, 50f..85f, "°C", onSynthThermalWarnChange)
                IOSSettingSliderRow("热临界温度", synthThermalCrit, 80f..110f, "°C", onSynthThermalCritChange)
                IOSSettingSliderRow("热阻 Rth", synthThermalRth, 10f..50f, "°C/W", onSynthThermalRthChange)
                IOSSettingSliderRow("热容 Cth", synthThermalCth, 0.5f..5.0f, "J/°C", onSynthThermalCthChange)
                HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)
                Text(stringResource(R.string.triple_gain), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
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


@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hapticEngine = remember { HapticFeedbackEngine.create(context) }
    
    Box(modifier = Modifier.fillMaxSize().background(bgPrimary())) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 24.dp, 16.dp, 104.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "返回控制台", tint = IOSColors.blue, modifier = Modifier.size(24.dp))
                }
                Text(stringResource(R.string.about), color = textPrimary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(44.dp))
            }
            
            Spacer(Modifier.height(16.dp))
            
            Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.version_info), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("MusicHapticsX ${BuildConfig.VERSION_NAME}", color = textPrimary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("(${BuildConfig.VERSION_CODE})", color = textSecondary(), fontSize = 13.sp)
            }
            
            Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.developer_info), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.developer_info) + "：" + stringResource(R.string.developer), color = textPrimary(), fontSize = 16.sp)
                IOSButton("QQ交流群：1047262325  (点击复制)", false, Modifier.fillMaxWidth(), HapticFeedbackEngine.HapticStyle.SUCCESS) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", "1047262325"))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            }
            
            Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.repo), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "github.com/mouya-q/MusicHaptics",
                    color = IOSColors.blue, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().clickable {
                        hapticEngine.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/MusicHaptics"))) } catch (e: Exception) {}
                    }
                )
            }
            
            Column(Modifier.fillMaxWidth().liquidGlass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.usage_tip), color = textSecondary(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.usage_tip_desc), color = textPrimary(), fontSize = 14.sp)
            }
        }
    }
}


private data class ScopedApp(val packageName: String, val label: String)

@Composable
private fun ScopedAppsRestartDialog(show: Boolean, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
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
    val haptic = remember { HapticFeedbackEngine.create(context) }
    val reducedMotion = LocalPrefersReducedMotion.current

    // 5.2.7：改用液态玻璃面板承载，替换默认 Material AlertDialog。
    // 空间一致性：它从触发它的顶部刷新按钮那一侧展开、也沿同一侧收回去，
    // 而不是从屏幕正中央凭空出现。scrim 压暗背景，让面板成为焦点。
    // 5.2.8：整层是否绘制与命中，都由 show 决定。
    // 组件改为常驻挂载（否则退出动画没机会播完），
    // 若 scrim 仍无条件铺满，关闭状态下它会继续吃掉整屏点击。
    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(140)),
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val enterSpec = if (reducedMotion) tween(180) else spring<Float>(dampingRatio = 0.85f, stiffness = 320f)
        AnimatedVisibility(
            visible = true,
            enter = (if (reducedMotion) fadeIn(tween(180)) else fadeIn(tween(180)) + scaleIn(
                initialScale = 0.92f,
                animationSpec = enterSpec,
                transformOrigin = TransformOrigin(0.5f, 0f),
            )),
            // 与进入对称：沿同一条路径缩回顶部锚点，而不是凭空淡出。
            exit = if (reducedMotion) fadeOut(tween(140)) else fadeOut(tween(150)) + scaleOut(
                targetScale = 0.94f,
                animationSpec = tween(150),
                transformOrigin = TransformOrigin(0.5f, 0f),
            ),
        ) {
            Column(
                Modifier
                    .padding(top = 96.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    // 5.2.8：面板必须消费自己的点击。scrim 挂在最外层 Box 上，
                    // 若面板不拦截，点标题或说明文字就会冒泡上去把整个对话框关掉。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .liquidGlass(26.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.restart_scope_apps), color = textPrimary(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.restart_scope_hint), color = textSecondary(), fontSize = 14.sp)
                Text(
                    "将尝试通过 Root 执行 force-stop；未获取 Root 权限时，请手动结束并重新打开所选 App。",
                    color = IOSColors.orange, fontSize = 12.sp,
                )
                Spacer(Modifier.height(2.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (apps.isEmpty()) Text(stringResource(R.string.no_scope_apps), color = textSecondary())
                    apps.forEach { app ->
                        // 5.2.7 修复：原实现把 Row.clickable 与 Checkbox.onCheckedChange 挂在同一次
                        // 点击上，事件既被 Checkbox 的 handler 处理又冒泡到 Row 再切换一次，
                        // 表现为"点了没反应"。现在整行是唯一点击源，Checkbox 只做状态呈现。
                        val isChecked = app.packageName in selected
                        val rowInteraction = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isChecked) IOSColors.blue.copy(alpha = 0.12f)
                                    else Color.White.copy(alpha = 0.05f)
                                )
                                .clickable(
                                    interactionSource = rowInteraction,
                                    indication = null,
                                ) {
                                    // 提交时才给触觉，与视觉同帧；不是每帧都给。
                                    haptic.perform(HapticFeedbackEngine.HapticStyle.SELECTION)
                                    if (isChecked) selected.remove(app.packageName)
                                    else selected.add(app.packageName)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // onCheckedChange = null —— 不参与点击，只呈现状态。
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = IOSColors.blue,
                                    uncheckedColor = IOSColors.gray,
                                    checkmarkColor = Color.White,
                                ),
                            )
                            Column {
                                Text(app.label, color = textPrimary(), fontSize = 15.sp)
                                Text(app.packageName, color = textTertiary(), fontSize = 11.sp)
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IOSButton("取消", false, Modifier.weight(1f), HapticFeedbackEngine.HapticStyle.SOFT_TAP) {
                        onDismiss()
                    }
                    Spacer(Modifier.width(10.dp))
                    IOSButton(
                        "确定重启",
                        selected.isNotEmpty(),
                        Modifier.weight(1f),
                        HapticFeedbackEngine.HapticStyle.SUCCESS,
                    ) {
                        onConfirm(selected.toList())
                    }
                }
            }
        }
    }
    }
}

private fun forceStopSelectedAppsWithRoot(packages: List<String>): Boolean {
    if (packages.isEmpty()) return false
    return try {
        val command = packages.joinToString("; ") { "am force-stop ${it}" }
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        process.waitFor() == 0
    } catch (_: Exception) { false }
}


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