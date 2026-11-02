package com.mouya.musichaptics

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        Thread {
            // Device identification runs unconditionally: it reads public build
            // properties and no longer depends on su. Gating it behind root meant
            // the profile was never written when su was refused, so every device
            // silently fell back to DEFAULT and the actuator matching work was
            // effectively dead code.
            runCatching { RootHardwareProbe.probeAndPersist(this) }
            runCatching { RootHapticDaemon.ensureRunning(this) }
            runCatching { NativeBridge.preloadLibrary(this) }
        }.start()

        startActivity(Intent(this, HapticDashboardActivity::class.java))
        finish()
    }
}
