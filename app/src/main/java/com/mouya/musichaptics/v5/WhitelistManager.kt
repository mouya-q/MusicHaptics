package com.mouya.musichaptics.v5

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.ConcurrentHashMap

/**
 * v5.0: WhitelistManager — 白名单管理
 * 
 * 与 v4 的 WhitelistManager 相同，负责读取 /data/adb/musichaptics/whitelist 文件，
 * 过滤非音乐/视频应用，只允许白名单中的应用触发振动。
 */
class WhitelistManager(private val context: Context? = null) {
    
    companion object {
        private const val TAG = "MusicHapticsX"
        const val WHITELIST_PATH = "/data/adb/musichaptics/whitelist"
        const val CONFIG_DIR = "/data/adb/musichaptics"
        
        // 默认白名单
        private val DEFAULT_WHITELIST = setOf(
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
    }
    
    private val whitelist = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var whitelistLoaded = false
    private var lastFileModified = 0L
    private var lastFileLength = 0L
    
    fun isWhitelistEnabled(): Boolean {
        val file = File(WHITELIST_PATH)
        return file.exists() && file.length() > 0
    }
    
    fun reloadWhitelist() {
        val file = File(WHITELIST_PATH)
        
        if (!file.exists()) {
            if (whitelist.isEmpty()) {
                whitelist.addAll(DEFAULT_WHITELIST)
                Log.i(TAG, "[Whitelist] 使用默认白名单 (${whitelist.size} 个应用)")
            }
            whitelistLoaded = true
            return
        }
        
        val currentModified = file.lastModified()
        val currentLength = file.length()
        if (currentModified == lastFileModified && currentLength == lastFileLength && whitelistLoaded) {
            return
        }
        
        lastFileModified = currentModified
        lastFileLength = currentLength
        
        val newWhitelist = HashSet<String>()
        try {
            BufferedReader(FileReader(file)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        newWhitelist.add(trimmed)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Whitelist] 读取失败: ${e.message}")
        }
        
        if (newWhitelist.isEmpty()) {
            Log.i(TAG, "[Whitelist] 白名单为空，允许所有应用")
        } else {
            whitelist.clear()
            whitelist.addAll(newWhitelist)
            Log.i(TAG, "[Whitelist] 已加载 ${whitelist.size} 个应用: ${whitelist.joinToString(", ")}")
        }
        
        whitelistLoaded = true
    }
    
    fun isPackageAllowed(packageName: String): Boolean {
        if (!isWhitelistEnabled()) return true
        reloadWhitelist()
        return whitelist.contains(packageName)
    }
    
    fun getWhitelist(): Set<String> {
        reloadWhitelist()
        return HashSet(whitelist)
    }
    
    fun initDefaultWhitelist() {
        val file = File(WHITELIST_PATH)
        if (file.exists()) return
        
        try {
            file.parentFile?.mkdirs()
            file.writeText(DEFAULT_WHITELIST.joinToString("\n") + "\n")
            Log.i(TAG, "[Whitelist] 已创建默认白名单: $WHITELIST_PATH")
        } catch (e: Exception) {
            Log.w(TAG, "[Whitelist] 创建默认白名单失败: ${e.message}")
        }
    }
}