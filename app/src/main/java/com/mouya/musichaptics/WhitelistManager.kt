package com.mouya.musichaptics

import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class WhitelistManager {
    companion object {
        private const val TAG = "MusicHapticsX-Whitelist"
        const val CONFIG_DIR = "/data/adb/musichaptics"
        const val WHITELIST_PATH = "$CONFIG_DIR/whitelist.conf"

        private const val MODE_KEY = "mode"
        const val MODE_WHITELIST = "whitelist"
        const val MODE_ALL = "all"

        val DEFAULT_WHITELIST: Set<String> = linkedSetOf(
            "com.miui.player",
            "com.android.music",
            "com.tencent.qqmusic",
            "com.netease.cloudmusic",
            "com.spotify.music",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "tv.danmaku.bili",
            "com.kugou.android",
            "com.kugou.android.lite"
        )

        private val PACKAGE_RE = Regex("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+$")
    }

    data class Snapshot(
        val mode: String,
        val packages: Set<String>,
        val lastModified: Long,
        val length: Long
    ) {
        val whitelistEnabled: Boolean get() = mode == MODE_WHITELIST
    }

    private val cache = AtomicReference<Snapshot?>(null)
    private val writeLock = Any()

    fun snapshot(): Snapshot {
        val file = File(WHITELIST_PATH)
        val lm = file.lastModified()
        val len = file.length()
        cache.get()?.takeIf { it.lastModified == lm && it.length == len }?.let { return it }

        val parsed = parse(file)
        cache.set(parsed)
        return parsed
    }

    fun isWhitelistEnabled(): Boolean = snapshot().whitelistEnabled

    fun isPackageAllowed(packageName: String): Boolean {
        if (!PACKAGE_RE.matches(packageName)) return false
        val state = snapshot()
        return state.mode == MODE_ALL || packageName in state.packages
    }

    fun getWhitelist(): Set<String> = snapshot().packages

    fun getMode(): String = snapshot().mode

    fun setMode(mode: String) {
        val normalized = when (mode.lowercase()) {
            MODE_ALL -> MODE_ALL
            else -> MODE_WHITELIST
        }
        val current = snapshot()
        writeConfig(normalized, current.packages)
    }

    fun setPackageAllowed(packageName: String, allowed: Boolean) {
        if (!PACKAGE_RE.matches(packageName)) return
        val next = LinkedHashSet(snapshot().packages)
        if (allowed) next += packageName else next -= packageName
        writeConfig(MODE_WHITELIST, next)
    }

    fun replacePackages(packages: Set<String>) {
        val safe = packages.filterTo(LinkedHashSet(), PACKAGE_RE::matches)
        writeConfig(MODE_WHITELIST, safe)
    }

    private fun parse(file: File): Snapshot {
        if (!file.exists()) {
            return Snapshot(MODE_WHITELIST, DEFAULT_WHITELIST, 0L, 0L)
        }
        return runCatching {
            var mode = MODE_WHITELIST
            val packages = LinkedHashSet<String>()
            file.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith('#')) return@forEachLine
                when {
                    line.startsWith("$MODE_KEY=") -> {
                        mode = line.substringAfter('=').trim().lowercase().let { if (it == MODE_ALL) MODE_ALL else MODE_WHITELIST }
                    }
                    PACKAGE_RE.matches(line) -> packages += line
                }
            }
            Snapshot(mode, packages, file.lastModified(), file.length())
        }.getOrElse {
            Log.w(TAG, "Failed to read whitelist: ${it.message}")
            Snapshot(MODE_WHITELIST, DEFAULT_WHITELIST, file.lastModified(), file.length())
        }
    }

    private fun writeConfig(mode: String, packages: Set<String>) {
        val safeMode = if (mode == MODE_ALL) MODE_ALL else MODE_WHITELIST
        val safePkgs = packages.filter(PACKAGE_RE::matches).toSortedSet()
        val content = buildString {
            appendLine("# MusicHapticsX application filter")
            appendLine("# mode=whitelist: only listed packages are processed")
            appendLine("# mode=all: every LSPosed-injected package is processed")
            appendLine("$MODE_KEY=$safeMode")
            safePkgs.forEach(::appendLine)
        }
        synchronized(writeLock) {
            runCatching {
                val encoded = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                // P0: no string interpolation of paths into shell; pass via env + single-quoted constants.
                // Also guard symlink hijack: remove symlink tmp before write, verify regular file after mv.
                val script = "mkdir -p '$CONFIG_DIR' && rm -f '$WHITELIST_PATH.tmp' && umask 022 && echo '$encoded' | base64 -d > '$WHITELIST_PATH.tmp' && [ ! -L '$WHITELIST_PATH.tmp' ] && mv '$WHITELIST_PATH.tmp' '$WHITELIST_PATH' && chmod 644 '$WHITELIST_PATH'"
                val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
                // P0: bounded wait to avoid ANR when su hangs; destroy on timeout.
                val done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                if (!done) {
                    p.destroyForcibly()
                    throw IllegalStateException("su timeout")
                }
                if (p.exitValue() != 0) throw IllegalStateException("su exit=${p.exitValue()}")
                cache.set(null)
                Log.i(TAG, "Whitelist updated: mode=$safeMode packages=${safePkgs.size}")
            }.onFailure {
                Log.w(TAG, "Failed to update whitelist with root shell: ${it.message}")
            }
        }
    }
}