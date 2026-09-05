package com.mouya.musichaptics.v5

import android.content.BroadcastReceiver
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
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.mouya.musichaptics.ui.ConsoleLogState
import com.mouya.musichaptics.ui.rememberConsoleLogState
import com.mouya.musichaptics.ui.IOSConsole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.sin

/**
 * v5.0: DashboardActivity — 全新 UI，匹配 v5 简化架构
 * 
 * 与 v4 的 HapticDashboardActivity 对比：
 * - 移除 C++ 遥测（不再需要 32-float 数组）
 * - 移除复杂 DSP 参数（LRA F0/Q、ADSR、热保护等）
 * - 新增：模式选择器（balanced/bass/crisp/soft/immersive/pure）
 * - 新增：白名单管理
 * - 新增：简化强度/阈值控制
 * - 新增：V5 引擎状态显示（脉冲计数、自适应峰值/底限）
 */
class V5DashboardActivity : ComponentActivity() {

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra("log_msg")
            if (!msg.isNullOrBlank()) ConsoleLogState.addGlobalLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val logFilter = IntentFilter("com.mouya.musichaptics.ACTION_LOG")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            registerReceiver(logReceiver, logFilter, ContextCompat.RECEIVER_EXPORTED)
        else
            registerReceiver(logReceiver, logFilter)

        setContent { MaterialTheme { V5Dashboard() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
    }
}

private object V5Colors {
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
}

@Composable
private fun V5Dashboard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("haptics_config", Context.MODE_PRIVATE) }
    val consoleLogState = rememberConsoleLogState()
    var consoleExpanded by remember { mutableStateOf(false) }
    var dashboardTab by rememberSaveable { mutableStateOf(V5Tab.CONTROL) }

    // V5 状态
    var isMasterSwitchOn by remember { mutableStateOf(prefs.getBoolean("master_switch", true)) }
    var selectedMode by remember {
        mutableStateOf(prefs.getString("v5_mode", V5HapticEngine.MODE_BALANCED) ?: V5HapticEngine.MODE_BALANCED)
    }
    var intensity by remember { mutableStateOf(prefs.getInt("v5_intensity", 65).toFloat()) }
    var boost by remember { mutableStateOf(prefs.getBoolean("v5_boost", false)) }

    // 保存设置
    LaunchedEffect(isMasterSwitchOn, selectedMode, intensity, boost) {
        prefs.edit().apply {
            putBoolean("master_switch", isMasterSwitchOn)
            putString("v5_mode", selectedMode)
            putInt("v5_intensity", intensity.toInt())
            putBoolean("v5_boost", boost)
        }.apply()
        context.sendBroadcast(Intent("com.mouya.musichaptics.ACTION_REFRESH_CONFIG"))
    }

    Box(modifier = Modifier.fillMaxSize().background(V5Colors.lightBg).statusBarsPadding().navigationBarsPadding()) {
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
            }, label = "V5Tab"
        ) { tab ->
            when (tab) {
                V5Tab.CONTROL -> V5ControlTab(
                    isMasterSwitchOn = isMasterSwitchOn,
                    onMasterSwitchToggle = { isMasterSwitchOn = it },
                    selectedMode = selectedMode,
                    onModeChange = { selectedMode = it },
                    intensity = intensity,
                    onIntensityChange = { intensity = it },
                    boost = boost,
                    onBoostToggle = { boost = it },
                    consoleExpanded = consoleExpanded,
                    onConsoleToggle = { consoleExpanded = !consoleExpanded },
                    consoleLogState = consoleLogState,
                    onTabChange = { dashboardTab = it }
                )
                V5Tab.WHITELIST -> V5WhitelistTab()
                V5Tab.ABOUT -> V5AboutTab()
            }
        }

        V5TabBar(
            selected = dashboardTab,
            onSelected = { dashboardTab = it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
        )
    }
}

private enum class V5Tab { CONTROL, WHITELIST, ABOUT }

@Composable
private fun V5ControlTab(
    isMasterSwitchOn: Boolean,
    onMasterSwitchToggle: (Boolean) -> Unit,
    selectedMode: String,
    onModeChange: (String) -> Unit,
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    boost: Boolean,
    onBoostToggle: (Boolean) -> Unit,
    consoleExpanded: Boolean,
    onConsoleToggle: () -> Unit,
    consoleLogState: ConsoleLogState,
    onTabChange: (V5Tab) -> Unit
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        V5HeaderCard(onShowAbout = { onTabChange(V5Tab.ABOUT) })

        // 主开关
        V5MasterSwitchCard(isMasterSwitchOn, onMasterSwitchToggle)

        // 模式选择
        V5ModeSelector(selectedMode, onModeChange)

        // 强度控制
        V5IntensityCard(intensity, onIntensityChange, boost, onBoostToggle)

        // 控制台
        IOSConsole(
            modifier = Modifier.fillMaxWidth(),
            isExpanded = consoleExpanded,
            onToggle = onConsoleToggle,
            onClear = { consoleLogState.clear() },
            onExport = {
                consoleLogState.exportToDownloads()
                    .onSuccess { Toast.makeText(LocalContext.current, "日志已导出到 $it", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(LocalContext.current, "日志导出失败：${it.message}", Toast.LENGTH_LONG).show() }
            },
            logs = consoleLogState.logs
        )
    }
}

@Composable
private fun V5HeaderCard(onShowAbout: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "MusicHapticsX",
                color = Color.Black,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onShowAbout() }
            )
            Text(
                "v5.0 — 14PRO 哲学重构",
                color = V5Colors.gray,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun V5MasterSwitchCard(isOn: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(0.5.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("触觉引擎", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(if (isOn) "运行中" else "已暂停", color = if (isOn) V5Colors.green else V5Colors.gray, fontSize = 12.sp)
        }
        V5Toggle(checked = isOn, onToggle = { onToggle(!isOn) })
    }
}

@Composable
private fun V5Toggle(checked: Boolean, onToggle: () -> Unit) {
    val animatedBg by animateColorAsState(
        targetValue = if (checked) V5Colors.green else Color(0xFFE9E9EA),
        animationSpec = tween(200), label = "ToggleBg"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f), label = "ThumbOffset"
    )
    Box(
        modifier = Modifier
            .width(52.dp).height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(animatedBg)
            .clickable { onToggle() }
    ) {
        Box(
            modifier = Modifier.offset(x = thumbOffset, y = 2.dp).size(28.dp)
                .clip(CircleShape).background(Color.White)
        )
    }
}

@Composable
private fun V5ModeSelector(selected: String, onSelect: (String) -> Unit) {
    val modes = listOf(
        V5HapticEngine.MODE_BALANCED to "均衡",
        V5HapticEngine.MODE_BASS to "重低音",
        V5HapticEngine.MODE_CRISP to "清脆",
        V5HapticEngine.MODE_SOFT to "柔和",
        V5HapticEngine.MODE_IMMERSIVE to "沉浸",
        V5HapticEngine.MODE_PURE to "纯净"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(0.5.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("振动模式", color = V5Colors.gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            modes.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (modeId, modeLabel) ->
                        val isActive = selected == modeId
                        Box(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isActive) V5Colors.blue.copy(alpha = 0.12f) else Color.Transparent)
                                .border(
                                    if (isActive) 1.dp else 0.dp,
                                    if (isActive) V5Colors.blue else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelect(modeId) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                modeLabel,
                                color = if (isActive) V5Colors.blue else V5Colors.gray,
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V5IntensityCard(
    intensity: Float,
    onIntensityChange: (Float) -> Unit,
    boost: Boolean,
    onBoostToggle: (Boolean) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(0.5.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("强度控制", color = V5Colors.gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)

        // 强度滑块
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("主强度", fontSize = 15.sp, color = Color.Black)
                Text("${intensity.toInt()}%", fontSize = 15.sp, color = V5Colors.blue, fontWeight = FontWeight.Medium)
            }
            V5Slider(value = intensity, range = 10f..100f, onValueChange = onIntensityChange)
        }

        // 增强开关
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("增强模式", fontSize = 15.sp, color = Color.Black)
                Text("提升振幅并缩短间隔", fontSize = 11.sp, color = V5Colors.gray)
            }
            V5Toggle(checked = boost, onToggle = { onBoostToggle(!boost) })
        }
    }
}

@Composable
private fun V5Slider(value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    val progress = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(range) {
                detectDragGestures { change, _ ->
                    val widthPx = size.width.toFloat()
                    val newProgress = (change.position.x / widthPx).coerceIn(0f, 1f)
                    onValueChange(range.start + newProgress * (range.endInclusive - range.start))
                }
            }
            .pointerInput(range) {
                detectTapGestures { offset ->
                    val widthPx = size.width.toFloat()
                    val newProgress = (offset.x / widthPx).coerceIn(0f, 1f)
                    onValueChange(range.start + newProgress * (range.endInclusive - range.start))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFE8E8ED))
        ) {
            Box(
                Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(V5Colors.blue)
            )
        }
        Box(
            Modifier.offset(x = (250 * progress).dp - 12.dp).size(24.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White)
                .border(0.5.dp, V5Colors.blue.copy(alpha = 0.2f), CircleShape)
        )
    }
}

@Composable
private fun V5WhitelistTab() {
    val context = LocalContext.current
    val whitelistManager = remember { WhitelistManager() }
    var whitelist by remember { mutableStateOf(whitelistManager.getWhitelist()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newPackage by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题
        Text(
            "白名单管理",
            color = Color.Black,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "仅白名单中的应用会触发振动",
            color = V5Colors.gray,
            fontSize = 14.sp
        )

        // 白名单列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(whitelist.toList()) { pkg ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(pkg, modifier = Modifier.weight(1f), color = Color.Black, fontSize = 14.sp)
                    IconButton(onClick = {
                        whitelist = whitelist - pkg
                        saveWhitelist(whitelist)
                    }) {
                        Icon(Icons.Default.Close, "删除", tint = V5Colors.red, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 添加按钮
        V5Button("添加应用", isActive = false) {
            newPackage = ""
            showAddDialog = true
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加应用到白名单") },
            text = {
                OutlinedTextField(
                    value = newPackage,
                    onValueChange = { newPackage = it },
                    label = { Text("包名") },
                    placeholder = { Text("com.example.app") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPackage.isNotBlank()) {
                        whitelist = whitelist + newPackage
                        saveWhitelist(whitelist)
                    }
                    showAddDialog = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun saveWhitelist(set: Set<String>) {
    try {
        val file = java.io.File("/data/adb/musichaptics/whitelist")
        file.parentFile?.mkdirs()
        file.writeText(set.joinToString("\n") + "\n")
    } catch (e: Exception) {
        // Fallback: save to shared prefs
    }
}

@Composable
private fun V5AboutTab() {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("关于", color = Color.Black, fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // 版本信息
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("版本信息", color = V5Colors.gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("MusicHapticsX v5.0.0-refactor", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("架构：系统 Hook + 白名单 + 简化 DSP", color = V5Colors.gray, fontSize = 13.sp)
        }

        // 架构说明
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("架构变更", color = V5Colors.gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("• 移除 C++ 五通道 DSP 依赖\n• 单消费者无竞争\n• 自适应增益 (adaptivePeak/Floor)\n• 6 种振动模式\n• 热更新白名单", color = Color.Black, fontSize = 14.sp)
        }

        // 开发者
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("开发者信息", color = V5Colors.gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("开发者：もうや", color = Color.Black, fontSize = 16.sp)
            V5Button("QQ交流群：1047262325 (点击复制)", isActive = false) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("QQ群号", "1047262325"))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
        }

        // 开源
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("开源仓库", color = V5Colors.gray, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "github.com/mouya-q/MusicHaptics",
                color = V5Colors.blue,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mouya-q/MusicHaptics"))) } catch (e: Exception) {}
                }
            )
        }
    }
}

@Composable
private fun V5Button(label: String, isActive: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) V5Colors.blue.copy(alpha = 0.15f) else Color(0xFFEFEFF2))
            .border(if (isActive) 1.dp else 0.dp, if (isActive) V5Colors.blue else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (isActive) V5Colors.blue else Color.Black,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun V5TabBar(selected: V5Tab, onSelected: (V5Tab) -> Unit, modifier: Modifier = Modifier) {
    val barShape = RoundedCornerShape(28.dp)
    val lensShape = RoundedCornerShape(22.dp)

    BoxWithConstraints(
        modifier = modifier
            .width(240.dp).height(58.dp)
            .shadow(16.dp, barShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(barShape)
            .background(Color(0xFFF7F7F9).copy(alpha = 0.86f))
            .border(0.5.dp, Color.White.copy(alpha = 0.4f), barShape)
            .padding(5.dp)
    ) {
        val computedTabWidth = maxWidth / 3
        val computedTabWidthPx = with(LocalDensity.current) { computedTabWidth.toPx() }

        val baseOffset = when (selected) {
            V5Tab.CONTROL -> 0f
            V5Tab.WHITELIST -> computedTabWidthPx
            V5Tab.ABOUT -> computedTabWidthPx * 2
        }
        val lensOffsetPxState = animateFloatAsState(
            targetValue = baseOffset,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
            label = "LensOffset"
        )

        Box(
            Modifier
                .width(computedTabWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = lensOffsetPxState.value }
                .shadow(2.dp, lensShape, spotColor = Color.Black.copy(alpha = 0.1f))
                .clip(lensShape)
                .background(Color.White)
        )

        Row(Modifier.fillMaxSize()) {
            listOf(
                V5Tab.CONTROL to "控制台" to Icons.Default.Tune,
                V5Tab.WHITELIST to "白名单" to Icons.Default.Apps,
                V5Tab.ABOUT to "关于" to Icons.Default.MusicNote
            ).forEach { (tab, title, icon) ->
                val active = selected == tab
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable { onSelected(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(imageVector = icon, contentDescription = title, tint = if (active) V5Colors.blue else Color(0xFF3C3C43).copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(1.dp))
                    Text(title, color = if (active) V5Colors.blue else Color(0xFF3C3C43).copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}