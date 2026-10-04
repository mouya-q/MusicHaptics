package com.mouya.musichaptics

import android.content.Context
import android.content.Intent
import android.graphics.Color
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
            if (runCatching { RootHardwareProbe.hasRootAccess() }.getOrDefault(false)) {
                runCatching { RootHardwareProbe.probeAndPersist(this) }
                runCatching { startRootHapticDaemon() }
            }
            runCatching { NativeBridge.preloadLibrary(this) }
        }.start()

        startActivity(Intent(this, HapticDashboardActivity::class.java))
        finish()
    }


    private fun startRootHapticDaemon() {
        try {
            
            val nodes = getSharedPreferences("haptics_config", Context.MODE_PRIVATE).getString(RootHardwareProbe.PREF_DIRECT_DRIVE_NODES, null)
            if (nodes.isNullOrBlank()) {
                Log.w("MainActivity", "No cached direct drive nodes, skipping daemon start")
                return
            }

            val paths = nodes.split(",").filter { it.isNotBlank() }
            val activatePath = paths.firstOrNull()?.trim() ?: return
            val dirPath = activatePath.substringBeforeLast('/')
            var amplitudePath: String? = null
            for (ampName in listOf("gain", "amplitude", "index_value")) {
                val candidate = "$dirPath/$ampName"
                if (java.io.File(candidate).exists()) {
                    amplitudePath = candidate
                    break
                }
            }

            Log.i("MainActivity", "Starting RootHapticDaemon: activate=$activatePath amp=$amplitudePath")
            val started = RootHapticDaemon.start(this, activatePath, amplitudePath)
            Log.i("MainActivity", "RootHapticDaemon started: $started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start RootHapticDaemon: ${e.message}", e)
        }
    }
}