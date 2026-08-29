package com.mouya.musichaptics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private val telemetryReceiver = object : BroadcastReceiver() {
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


    private var prefs: SharedPreferences? = null
    private var hapticEngine: HapticEngine? = null

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

        prefs = getSharedPreferences("haptics_config", Context.MODE_PRIVATE)

        if (!RootHardwareProbe.hasRootAccess()) {
            startActivity(Intent(this, RootActivationActivity::class.java))
            finish()
            return
        }

        // v4.6: Launch dashboard immediately for visible startup animation,
        // then perform heavy initialization in background.
        // Previously all init was synchronous, causing ~1s blank screen.
        Thread {
            try {
                RootHardwareProbe.probeAndPersist(this)
                NativeBridge.preloadLibrary(this)
                Log.i("MainActivity", "NativeBridge preload=${NativeBridge.isLibraryPreloaded()}")
                startRootHapticDaemon()
                hapticEngine = HapticEngine(this, prefs!!)
            } catch (t: Throwable) {
                Log.w("MainActivity", "HapticEngine init failed: ${t.message}")
            }
        }.start()

        val telemetryFilter = IntentFilter("com.mouya.musichaptics.ACTION_TELEMETRY")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(telemetryReceiver, telemetryFilter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(telemetryReceiver, telemetryFilter)
        }

        // Navigate to dashboard immediately (UI appears without waiting for init)
        startActivity(Intent(this, HapticDashboardActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(telemetryReceiver) } catch (_: Exception) {}
        try { hapticEngine?.release() } catch (_: Exception) {}
        hapticEngine = null
        // Do NOT stop RootHapticDaemon here. MainActivity intentionally finishes
        // after opening HapticDashboardActivity; stopping it here killed the root
        // UDP bridge immediately after a successful start. The daemon is shared
        // with hooked target processes and remains alive for the app process.
    }

    private fun startRootHapticDaemon() {
        try {
            // Get cached direct drive nodes from SharedPreferences
            val nodes = prefs?.getString(RootHardwareProbe.PREF_DIRECT_DRIVE_NODES, null)
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