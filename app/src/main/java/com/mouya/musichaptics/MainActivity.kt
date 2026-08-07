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
// Console logs are received only by HapticDashboardActivity to avoid duplicates.

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
    // The dashboard owns console-log reception. Registering a second receiver here
    // appended every broadcast twice to the shared ConsoleLogState archive.


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

        // Root is mandatory for hardware-profile activation: Build fields can be
        // spoofed, while the probe reads board/driver data from the rooted system.
        // The probe itself is read-only and will trigger Magisk/APatch authorization.
        if (!RootHardwareProbe.hasRootAccess()) {
            startActivity(Intent(this, RootActivationActivity::class.java))
            finish()
            return
        }
        RootHardwareProbe.probeAndPersist(this)

        // Defensive: HapticEngine creates NativeBridge which loads native lib.
        // First launch after install/update may not have lib extracted yet → catch gracefully.
        try {
            hapticEngine = HapticEngine(this, prefs!!)
        } catch (t: Throwable) {
            Log.w("MainActivity", "HapticEngine init failed, will retry on next launch: ${t.message}")
            hapticEngine = null
        }

        val telemetryFilter = IntentFilter("com.mouya.musichaptics.ACTION_TELEMETRY")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(telemetryReceiver, telemetryFilter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(telemetryReceiver, telemetryFilter)
        }

        startActivity(Intent(this, HapticDashboardActivity::class.java))
        // This activity is a root/bootstrapper only. Keeping it in the back stack
        // made system Back reveal a blank white window with no Compose content.
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(telemetryReceiver) } catch (_: Exception) {}
        // Release native resources to prevent leak across Activity recreation
        try { hapticEngine?.release() } catch (_: Exception) {}
        hapticEngine = null
    }
}