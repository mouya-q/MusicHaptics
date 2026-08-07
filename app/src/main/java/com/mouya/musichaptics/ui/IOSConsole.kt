package com.mouya.musichaptics.ui

import com.mouya.musichaptics.AppFontFamily
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

@Composable
private fun isDark() = androidx.compose.foundation.isSystemInDarkTheme()

private val iosBlue = Color(0xFF007AFF)
private val iosGreen = Color(0xFF34C759)
private val iosRed = Color(0xFFFF3B30)
private val iosOrange = Color(0xFFFF9500)
private val iosGray = Color(0xFF8E8E93)

@Composable
private fun glassColor() = if (isDark()) Color(0xFF1C1C1E).copy(alpha = 0.72f) else Color(0xFFFFFFFF).copy(alpha = 0.72f)
@Composable
private fun textPrimary() = if (isDark()) Color(0xFFFFFFFF) else Color(0xFF000000)
@Composable
private fun textSecondary() = if (isDark()) Color(0xFFEBEBF5).copy(alpha = 0.6f) else Color(0xFF3C3C43).copy(alpha = 0.6f)
@Composable
private fun textTertiary() = if (isDark()) Color(0xFFEBEBF5).copy(alpha = 0.3f) else Color(0xFF3C3C43).copy(alpha = 0.3f)
@Composable
private fun separatorColor() = if (isDark()) Color(0xFF38383A) else Color(0xFFC6C6C8)
@Composable
private fun cardAltColor() = if (isDark()) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)

@Composable
fun IOSConsole(
    modifier: Modifier = Modifier,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    logs: List<String>
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "ChevronRotation"
    )

    val pulseAlpha by rememberInfiniteTransition(label = "StatusPulse").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "PulseAlpha"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = glassColor(),
        border = BorderStroke(0.5.dp, if (isDark()) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)),
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().animateContentSize(tween(250, easing = FastOutSlowInEasing))) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(8.dp).graphicsLayer { alpha = pulseAlpha }.clip(RoundedCornerShape(4.dp)).background(iosGreen))
                Text(
                    "实时触觉遥测日志", color = textPrimary(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = AppFontFamily, modifier = Modifier.padding(start = 10.dp)
                )
                Spacer(Modifier.weight(1f))
                IOSChevronIcon(rotationDegrees = chevronRotation, color = textSecondary(), modifier = Modifier.size(20.dp))
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(250, easing = FastOutSlowInEasing), Alignment.Top) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(250, easing = FastOutSlowInEasing), Alignment.Top) + fadeOut(tween(180))
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    HorizontalDivider(color = separatorColor(), thickness = 0.5.dp)

                    // Toolbar
                    Row(
                        modifier = Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("TELEMETRY STREAM", color = textTertiary(), fontSize = 10.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "DOWNLOAD", color = iosBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(4.dp).clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onExport
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "CLEAR", color = iosBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(4.dp).clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClear
                                )
                            )
                        }
                    }

                    // Log content
                    Column(
                        modifier = Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 12.dp, vertical = 6.dp).verticalScroll(rememberScrollState())
                    ) {
                        if (logs.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                logs.forEach { log -> IOSLogLine(text = log) }
                            }
                        } else {
                            Text("[等待遥测数据流...]", color = textTertiary(), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IOSChevronIcon(rotationDegrees: Float, color: Color = Color.White, modifier: Modifier = Modifier) {
    val path = remember {
        Path().apply {
            moveTo(8.59f, 16.58f); lineTo(13.17f, 12f); lineTo(8.59f, 7.41f)
            lineTo(10f, 6f); lineTo(16f, 12f); lineTo(10f, 18f)
            lineTo(8.59f, 16.58f); close()
        }
    }
    Canvas(modifier = modifier.graphicsLayer { rotationZ = rotationDegrees; transformOrigin = TransformOrigin.Center }) {
        drawPath(path = path, color = color, style = Fill)
    }
}

@Composable
fun IOSLogLine(text: String) {
    val colorBlue = Color(0xFF007AFF)
    val colorPurple = Color(0xFF5856D6)
    val colorPink = Color(0xFFFF2D55)
    val colorYellow = Color(0xFFFFCC00)
    val colorDim = if (isDark()) Color(0xFF636366) else Color(0xFF8E8E93)
    val colorRed = Color(0xFFFF3B30)
    val colorOrange = Color(0xFFFF9500)
    val colorDefault = if (isDark()) Color(0xFFEBEBF5) else Color(0xFF1C1C1E)

    val annotatedString = buildAnnotatedString {
        val tags = listOf("[Native]", "[Hook]", "[HapticDSPCore]", "[HapticComposer]", "[HapticEventGen]", "[HapticDebug]")
        val keyVals = mapOf(
            "S:" to colorBlue, "M:" to colorPurple, "T:" to colorYellow,
            "F0:" to colorYellow, "pitch=" to colorYellow, "amp~" to colorBlue,
            "blend=" to colorPurple, "final=" to colorPurple, "mood=" to colorYellow, "Δ=" to colorDim
        )
        val keywords = mapOf(
            "SUB_STRIKE" to colorRed, "KICK_DRUM" to colorRed,
            "SNARE_ACCENT" to colorRed, "RHYTHM_PATTERN" to colorRed,
            "BASS_GHOST" to colorRed, "KEY_STRIKE" to colorRed,
            "KEY-STRIKE" to colorRed
        )
        val thermalKeywords = mapOf(
            "Temp:" to colorOrange, "ThermalGain:" to colorOrange, "thermalGain" to colorOrange
        )

        var remaining = text

        while (remaining.isNotEmpty()) {
            var earliestIdx = remaining.length
            var earliestType = ""
            var earliestColor = colorDefault
            var earliestBold = false

            for (tag in tags) {
                val idx = remaining.indexOf(tag)
                if (idx != -1 && idx < earliestIdx) { earliestIdx = idx; earliestType = tag; earliestColor = colorBlue; earliestBold = true }
            }
            for ((key, color) in keyVals) {
                val idx = remaining.indexOf(key)
                if (idx != -1 && idx < earliestIdx) { earliestIdx = idx; earliestType = key; earliestColor = color; earliestBold = true }
            }
            for ((kw, color) in keywords) {
                val idx = remaining.indexOf(kw)
                if (idx != -1 && idx < earliestIdx) { earliestIdx = idx; earliestType = kw; earliestColor = color; earliestBold = true }
            }
            for ((kw, color) in thermalKeywords) {
                val idx = remaining.indexOf(kw)
                if (idx != -1 && idx < earliestIdx) { earliestIdx = idx; earliestType = kw; earliestColor = color; earliestBold = true }
            }
            val pipeIdx = remaining.indexOf('|')
            if (pipeIdx != -1 && pipeIdx < earliestIdx) { earliestIdx = pipeIdx; earliestType = "|"; earliestColor = colorDim; earliestBold = false }
            val eqIdx = remaining.indexOf('=')
            if (eqIdx != -1 && eqIdx < earliestIdx) { earliestIdx = eqIdx; earliestType = "="; earliestColor = colorDim; earliestBold = false }

            if (earliestIdx > 0) {
                withStyle(SpanStyle(color = colorDefault)) { append(remaining.substring(0, earliestIdx)) }
            }
            val endIdx = if (earliestType.length == 1) earliestIdx + 1
                else remaining.indexOf(" ", earliestIdx).let { if (it == -1) remaining.length else it }
            if (earliestType.isNotEmpty()) {
                val segment = remaining.substring(earliestIdx, min(endIdx, remaining.length))
                withStyle(SpanStyle(color = earliestColor, fontWeight = if (earliestBold) FontWeight.Bold else FontWeight.Normal, fontFamily = FontFamily.Monospace)) {
                    append(segment)
                }
            }
            remaining = remaining.substring(min(endIdx, remaining.length))
        }
    }

    Text(text = annotatedString, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
}