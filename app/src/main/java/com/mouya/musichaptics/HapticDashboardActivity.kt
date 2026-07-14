package com.mouya.musichaptics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Enterprise-Grade UI Architecture - MusicHapticsX Quantum Dashboard
 * 
 * 优化完成后的全架构渲染管线：
 * 1. 斩杀 Canvas 内的 Path 对象的重复分配，达成零 GC 高刷。
 * 2. 隔离 40Hz 遥测状态读取，防止顶层容器发生高频无用重组合。
 * 3. 增强玻璃拟态的 Modifier 稳定性，根除全域模糊导致的“白内障”问题。
 */
class HapticDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 开启沉浸式布局
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            MaterialTheme {
                QuantumHapticDashboard(
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun QuantumHapticDashboard(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF07070C))
    ) {
        FluidDynamicBackground()

        // 【优化】移除 statusBarsPadding 和 navigationBarsPadding
        // 直接让它填满屏幕，内容区域通过 padding 留出安全距离即可
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp), // 这里保留一点边距让UI不至于贴死屏幕边缘，更有高级感
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            HeaderSection(onBack = onBack)
            CentralTelemetryMatrix()
            ControlDeckSection()
        }
    }
}


@Composable
fun FluidDynamicBackground() {
    val transitionSpec = rememberInfiniteTransition(label = "DynamicFluidField")

    val timeStepA by transitionSpec.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "HarmonicThetaA"
    )

    val timeStepB by transitionSpec.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "HarmonicThetaB"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val waveCenterA = Offset(
            x = width * 0.35f + sin(timeStepA) * 180f,
            y = height * 0.25f + cos(timeStepA) * 220f
        )
        val waveCenterB = Offset(
            x = width * 0.70f + cos(timeStepB) * 250f,
            y = height * 0.65f + sin(timeStepB) * 190f
        )
        val waveCenterC = Offset(
            x = width * 0.45f + sin(timeStepB + 1.0f) * 140f,
            y = height * 0.80f + cos(timeStepA - 0.5f) * 300f
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF1E0B36), Color.Transparent),
                center = waveCenterA,
                radius = width * 0.9f
            ),
            center = waveCenterA,
            radius = width * 0.9f,
            blendMode = BlendMode.Screen
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0F2C59), Color.Transparent),
                center = waveCenterB,
                radius = width * 1.1f
            ),
            center = waveCenterB,
            radius = width * 1.1f,
            blendMode = BlendMode.Screen
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF381023), Color.Transparent),
                center = waveCenterC,
                radius = width * 0.8f
            ),
            center = waveCenterC,
            radius = width * 0.8f,
            blendMode = BlendMode.Screen
        )
    }
}

// 核心修复：移除灾难性的 RenderEffect 全局模糊，改用标准半透明基底 + 渐变高光边框模拟真实亚克力质感
fun Modifier.glassmorphicCompound(
    blurRadius: Float = 60f, // 保留该参数确保其它地方调用不会因为参数缺失报错
    borderAlpha: Float = 0.15f,
    backgroundAlpha: Float = 0.08f
): Modifier = this.then(
    Modifier
        .clip(RoundedCornerShape(24.dp))
        .background(Color.White.copy(alpha = backgroundAlpha))
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = borderAlpha * 2.5f),
                    Color.White.copy(alpha = borderAlpha * 0.1f)
                ),
                start = Offset(0f, 0f),
                end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
            ),
            shape = RoundedCornerShape(24.dp)
        )
)

@Composable
fun HeaderSection(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
       Box(
           modifier = Modifier
               .clickable { onBack() }
               .padding(8.dp)
       ) {      
           Text("〈 BACK", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
       }  
        Column {
            Text(
                text = "SYSTEM CORE",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "MusicHapticsX",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.5).sp
            )
        }

        val transitionSpec = rememberInfiniteTransition(label = "StatusSync")
        val statusAlpha by transitionSpec.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "StatusAlpha"
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .glassmorphicCompound(blurRadius = 20f, backgroundAlpha = 0.12f)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF00FFCC).copy(alpha = statusAlpha))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "DSP ONLINE",
                color = Color(0xFF00FFCC),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ColumnScope.CentralTelemetryMatrix() {
    // 保持高频轮询的状态源
    var telemetryCoilTemp by remember { mutableStateOf(25.0f) }
    var telemetryThermalGain by remember { mutableStateOf(1.00f) }

    LaunchedEffect(Unit) {
        while (true) {
            val time = System.currentTimeMillis()
            telemetryCoilTemp = 35f + sin(time * 0.0003).toFloat() * 12.5f + (cos(time * 0.001).toFloat() * 1.5f)
            telemetryThermalGain = if (telemetryCoilTemp > 45f) {
                1.0f - ((telemetryCoilTemp - 45f) * 0.015f).coerceIn(0f, 0.8f)
            } else {
                1.0f
            }
            delay(25) // 40Hz 核心数据采样
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // FFT 频谱区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.3f)
                .glassmorphicCompound()
                .padding(20.dp)
        ) {
            Column {
                Text(
                    text = "FFT SPECTRUM & HAPTIC DYNAMICS (LR4 SEPARATION)",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                InteractiveWaveformDisplay()
            }
        }

        // 隔离高频状态读写的仪表盘组件，阻止父级 Column 发生大规模重组合
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 通过 Lambda 传入读取，彻底隔离刷新域
            ThermalSimPanel(tempProvider = { telemetryCoilTemp })
            LoopAttenuationPanel(gainProvider = { telemetryThermalGain })
        }
    }
}

@Composable
fun RowScope.ThermalSimPanel(tempProvider: () -> Float) {
    Box(
        modifier = Modifier
            .weight(1f) 
            .fillMaxHeight()
            .glassmorphicCompound()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxHeight()) {
            Text(
                text = "THERMAL SIM",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            
            Column {
                val currentTemp = tempProvider()
                Text(
                    text = String.format(Locale.ROOT, "%.1f°C", currentTemp),
                    color = if (currentTemp > 45f) Color(0xFFFF5252) else Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    text = "VOICE COIL MODEL",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            LinearProgressIndicator(
                progress = { (tempProvider() / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = if (tempProvider() > 45f) Color(0xFFFF5252) else Color(0xFF00E676),
                trackColor = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}

@Composable
fun RowScope.LoopAttenuationPanel(gainProvider: () -> Float) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .glassmorphicCompound()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxHeight()) {
            Text(
                text = "LOOP ATTENUATION",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Column {
                val currentGain = gainProvider()
                val dbValue = 20f * kotlin.math.log10(currentGain)
                Text(
                    text = String.format(Locale.ROOT, "%.2f dB", dbValue),
                    color = if (currentGain < 1.0f) Color(0xFFFFD740) else Color(0xFF00FFCC),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    text = "COMPENSATION PROFILE",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            LinearProgressIndicator(
                progress = { gainProvider() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Color(0xFF00FFCC),
                trackColor = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}

@Composable
fun InteractiveWaveformDisplay() {
    val infiniteTransition = rememberInfiniteTransition(label = "SignalWaveform")
    val phaseOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "PhaseOffset"
    )

    // 核心重构：锁住 Path 实例复用，避免每帧都在 DrawScope 里面分配垃圾堆内存
    val sharedPath = remember { Path() }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        val width = size.width
        val height = size.height
        val midY = height / 2f

        // 清空重置上一次的寻迹，保持引用不变
        sharedPath.reset()
        sharedPath.moveTo(0f, midY)
        
        val resolution = 120
        for (i in 0..resolution) {
            val normalizedX = (i.toFloat() / resolution)
            val x = normalizedX * width
            val window = sin(normalizedX * PI).toFloat()

            val primaryHarmonic = kotlin.math.sin(normalizedX * 12f - phaseOffset) * 60f
            val secondaryHarmonic = cos(normalizedX * 28f + phaseOffset * 1.5f) * 25f
            val tertiaryHarmonic = sin(normalizedX * 56f - phaseOffset * 3.2f) * 10f

            val y = midY + (primaryHarmonic + secondaryHarmonic + tertiaryHarmonic) * window * 0.8f
            sharedPath.lineTo(x, y)
        }

        drawPath(
            path = sharedPath,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF00E5FF),
                    Color(0xFF7C4DFF),
                    Color(0xFFFF4081)
                )
            ),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
fun ControlDeckSection() {
    var isPowerAmplifyActive by remember { mutableStateOf(false) }
    var isCrossoverBypassActive by remember { mutableStateOf(true) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PhysicsBasedHapticButton(
            label = "COIL AMPLIFICATION",
            isActive = isPowerAmplifyActive,
            modifier = Modifier.weight(1f)
        ) {
            isPowerAmplifyActive = !isPowerAmplifyActive
        }

        PhysicsBasedHapticButton(
            label = "ACTIVE CROSSOVER",
            isActive = isCrossoverBypassActive,
            modifier = Modifier.weight(1f)
        ) {
            isCrossoverBypassActive = !isCrossoverBypassActive
        }
    }
}

@Composable
fun PhysicsBasedHapticButton(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()
    val elasticScaleFactor = remember { Animatable(1.0f) }

    val physicalBorderColor = if (isActive) Color(0xFF00FFCC) else Color.White.copy(alpha = 0.1f)
    val specularGradColor = if (isActive) {
        Brush.verticalGradient(listOf(Color(0xFF00FFCC).copy(alpha = 0.15f), Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.03f), Color.Transparent))
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = elasticScaleFactor.value
                scaleY = elasticScaleFactor.value
            }
            .clip(RoundedCornerShape(24.dp))
            .background(specularGradColor)
            .border(0.8.dp, physicalBorderColor, RoundedCornerShape(24.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                coroutineScope.launch {
                    elasticScaleFactor.animateTo(
                        targetValue = 0.92f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessHigh 
                        )
                    )
                    elasticScaleFactor.animateTo(
                        targetValue = 1.00f,
                        animationSpec = spring(
                            dampingRatio = 0.45f,
                            stiffness = Spring.StiffnessMedium
                        )
                    )
                }
                onClick()
            }
            .glassmorphicCompound(
                blurRadius = if (isActive) 10f else 30f,
                backgroundAlpha = if (isActive) 0.15f else 0.05f
            )
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                color = if (isActive) Color(0xFF00FFCC) else Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isActive) "ACTIVE" else "BYPASSED",
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.3f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.5.sp
            )
        }
    }
}
