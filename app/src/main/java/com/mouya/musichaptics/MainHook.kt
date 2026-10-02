package com.mouya.musichaptics

import android.util.Log
import com.mouya.musichaptics.hook.HookCoordinator
import com.mouya.musichaptics.phira.PhiraController
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/** Thin Xposed entry point. All method hooks live in HookCoordinator. */
class MainHook : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "MusicHapticsX-Hook"
        private const val MODULE_PACKAGE = "com.mouya.musichaptics"
        private const val PHIRA_PACKAGE = "org.flos.phira"
        private val BLOCKED = setOf("android", "com.android.systemui", "com.android.phone")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg.isBlank() || pkg in BLOCKED || pkg == MODULE_PACKAGE) return
        val loader = lpparam.classLoader ?: return

        val whitelist = WhitelistManager()
        if (!whitelist.isPackageAllowed(pkg)) {
            Log.d(TAG, "[$pkg] not enabled by application filter")
            return
        }

        val coordinator = HookCoordinator(
            contextProvider = { null },
            targetPackage = pkg,
            whitelist = whitelist
        )
        coordinator.install(lpparam)
        Log.i(TAG, "HookCoordinator installed for $pkg")

        if (pkg == PHIRA_PACKAGE) {
            installPhiraController(loader, coordinator)
        }
    }

    private fun installPhiraController(
        classLoader: ClassLoader,
        coordinator: HookCoordinator
    ) {
        val started = booleanArrayOf(false)
        runCatching {
            val applicationClass = XposedHelpers.findClass("android.app.Application", classLoader)
            XposedBridge.hookAllMethods(applicationClass, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (started[0]) return
                    started[0] = true
                    Thread {
                        runCatching {
                            val appContext = (param.thisObject as? android.content.Context)?.applicationContext
                                ?: return@runCatching
                            val engine = coordinator.engine() ?: return@runCatching
                            PhiraController(appContext, engine).start()
                            Log.i(TAG, "[Phira] chart controller active")
                        }.onFailure { Log.w(TAG, "[Phira] controller init failed: ${it.message}") }
                    }.start()
                }
            })
        }.onFailure {
            Log.w(TAG, "[Phira] Application.onCreate hook failed: ${it.message}")
        }
    }
}
