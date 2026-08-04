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
import com.mouya.musichaptics.ui.ConsoleLogState

class MainActivity : ComponentActivity() {

    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val bundle = android.os.Bundle().apply {
                putFloat("sub", intent.getFloatExtra("sub", 0f))
                putFloat("mid", intent.getFloatExtra("mid", 0f))
                putFloat("pres", intent.getFloatExtra("pres", 0f))
                putFloat("f0", intent.getFloatExtra("f0", 150f))
                putFloat("temp", intent.getFloatExtra("temp", 25f))
                putFloat("atten", intent.getFloatExtra("atten", 1f))
                putLong("latency", intent.getLongExtra("latency", 0L))
                putFloat("loFreq", intent.getFloatExtra("loFreq", 160f))
                putFloat("hiFreq", intent.getFloatExtra("hiFreq", 350f))
                putFloat("ampScale", intent.getFloatExtra("ampScale", 1f))
                putLong("overruns", intent.getLongExtra("overruns", 0L))
                putLong("subCount", intent.getLongExtra("subCount", 0L))
                putLong("midCount", intent.getLongExtra("midCount", 0L))
                putLong("texCount", intent.getLongExtra("texCount", 0L))
                putLong("time", intent.getLongExtra("time", System.currentTimeMillis()))

                putFloat("lraDisp", intent.getFloatExtra("lraDisp", 0f))
                putFloat("lraVel", intent.getFloatExtra("lraVel", 0f))
                putFloat("lraForce", intent.getFloatExtra("lraForce", 0f))
                putFloat("lraPhase", intent.getFloatExtra("lraPhase", 0f))
                putFloat("adsrEnv", intent.getFloatExtra("adsrEnv", 0f))
                putFloat("thermalGain", intent.getFloatExtra("thermalGain", 1f))
                putBoolean("keyStrikeActive", intent.getBooleanExtra("keyStrikeActive", false))
                putString("keyStrikeSemantic", intent.getStringExtra("keyStrikeSemantic") ?: "NONE")
                putString("semanticType", intent.getStringExtra("semanticType") ?: "BALANCED")

                putString("personaName", intent.getStringExtra("personaName") ?: "POP")
                putString("primitiveType", intent.getStringExtra("primitiveType") ?: "")
                putString("primitiveSemantic", intent.getStringExtra("primitiveSemantic") ?: "")
                putInt("primitiveIntensity", intent.getIntExtra("primitiveIntensity", 0))
                putInt("primitiveDuration", intent.getIntExtra("primitiveDuration", 0))
                putFloat("gammaValue", intent.getFloatExtra("gammaValue", 0.5f))
            }
            TelemetryHub.applySnapshot(bundle)
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra("log_msg") ?: return

            ConsoleLogState.addGlobalLog(msg)
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

        // Defensive: HapticEngine creates NativeBridge which loads native lib.
        // First launch after install/update may not have lib extracted yet → catch gracefully.
        try {
            hapticEngine = HapticEngine(this, prefs!!)
        } catch (t: Throwable) {
            Log.w("MainActivity", "HapticEngine init failed, will retry on next launch: ${t.message}")
            hapticEngine = null
        }

        val logFilter = IntentFilter("com.mouya.musichaptics.ACTION_LOG")
        val telemetryFilter = IntentFilter("com.mouya.musichaptics.ACTION_TELEMETRY")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, logFilter, ContextCompat.RECEIVER_EXPORTED)
            registerReceiver(telemetryReceiver, telemetryFilter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(logReceiver, logFilter)
            registerReceiver(telemetryReceiver, telemetryFilter)
        }

        startActivity(Intent(this, HapticDashboardActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(telemetryReceiver) } catch (_: Exception) {}
        // Release native resources to prevent leak across Activity recreation
        try { hapticEngine?.release() } catch (_: Exception) {}
        hapticEngine = null
    }
}