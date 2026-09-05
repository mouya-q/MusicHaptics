package com.mouya.musichaptics.v5

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class V5DashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { V5Dashboard() } }
    }
}

@Composable
private fun V5Dashboard() {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("MusicHapticsX v5.0", fontSize = 24.sp)
    }
}