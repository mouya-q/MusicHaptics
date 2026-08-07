package com.mouya.musichaptics

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-run gate: a root probe is needed to avoid relying on spoofable Build data. */
class RootActivationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { RootActivationScreen(onActivated = {
                startActivity(Intent(this, HapticDashboardActivity::class.java))
                finish()
            }) }
        }
    }
}

@Composable
private fun RootActivationScreen(onActivated: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(30.dp)

    Box(
        Modifier.fillMaxSize().background(Color.White)
            .padding(24.dp), contentAlignment = Alignment.Center
    ) {
        // Translucent, iOS-inspired glass card. No fake blur is used: Compose's
        // backdrop blur is not reliable across all API 28+ OEM renderers.
        Column(
            Modifier.fillMaxWidth().clip(shape)
                .background(Color(0xFFF8F9FC))
                .border(1.dp, Color(0x14000000), shape)
                .padding(horizontal = 26.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(74.dp).clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0A84FF).copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Vibration, null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("硬件触觉适配", color = Color(0xFF111827), fontWeight = FontWeight.Bold, fontSize = 27.sp, fontFamily = AppFontFamily)
            Spacer(Modifier.height(10.dp))
            Text(
                "MusicHapticsX 需要 Root 读取板级硬件与振动驱动指纹，\n避免被修改过的机型信息误导。",
                color = Color(0xFF3C3C43).copy(alpha = 0.72f), textAlign = TextAlign.Center, lineHeight = 21.sp, fontFamily = AppFontFamily
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFE9F3FF)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, null, tint = Color(0xFF64D2FF))
                Spacer(Modifier.width(12.dp))
                Text("仅执行只读 getprop、device-tree 与\nvibrator 驱动节点探测。", color = Color(0xFF3C3C43).copy(alpha = 0.82f), fontSize = 13.sp, fontFamily = AppFontFamily)
            }
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error!!, color = Color(0xFFFF9F9A), fontSize = 13.sp, textAlign = TextAlign.Center, fontFamily = AppFontFamily)
            }
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = {
                    checking = true; error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { RootHardwareProbe.probeAndPersist(context) }
                        checking = false
                        if (result.rootGranted) onActivated()
                        else error = "尚未获得 Root 授权。请在 Magisk/APatch 等管理器中允许本应用后，点击重试。"
                    }
                }, enabled = !checking,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF)),
                contentPadding = PaddingValues(vertical = 15.dp), modifier = Modifier.fillMaxWidth()
            ) {
                if (checking) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("授权并检测硬件", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, fontFamily = AppFontFamily)
            }
            Spacer(Modifier.height(12.dp))
            Text("授权后将自动进入控制台；更换内核或机型后可重新检测。", color = Color(0xFF3C3C43).copy(alpha = 0.50f), fontSize = 12.sp, textAlign = TextAlign.Center, fontFamily = AppFontFamily)
        }
    }
}