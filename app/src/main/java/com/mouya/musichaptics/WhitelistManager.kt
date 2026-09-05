package com.mouya.musichaptics

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.23: WhitelistManager — 白名单管理
 * 
 * 参考 14PRO MusicHaptic 架构：系统级守护进程通过白名单控制哪些应用触发振动。
 * 配置文件路径：/data/adb/musichaptics/whitelist
 * 
 * 白名单文件格式（每行一个包名，# 开头为注释）：
 *   com.tencent.qqmusic
 *   com.netease.cloudmusic
 *   # com.example.banned
 */
class WhitelistManager(private val context: Context) {
    
    companion object {
        private const val TAG = "MusicHapticsX"
        
        // 白名单文件路径（与 14PRO 同目录结构）
        const val WHITELIST_PATH = "/data/adb/musichaptics/whitelist"
        const val CONFIG_DIR = "/data/adb/musichaptics"
        
        // 默认白名单（主流音乐/视频应用）
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
    
    /**
     * 检查是否启用白名单模式
     * 如果白名单文件存在且非空，则启用白名单过滤
     */
    fun isWhitelistEnabled(): Boolean {
        val file = File(WHITELIST_PATH)
        return file.exists() && file.length() > 0
    }
    
    /**
     * 加载/重新加载白名单（支持热更新）
     */
    fun reloadWhitelist() {
        val file = File(WHITELIST_PATH)
        
        if (!file.exists()) {
            // 使用默认白名单
            if (whitelist.isEmpty()) {
                whitelist.addAll(DEFAULT_WHITELIST)
                Log.i(TAG, "[Whitelist] 使用默认白名单 (${whitelist.size} 个应用)")
            }
            whitelistLoaded = true
            return
        }
        
        // 检查文件是否修改
        val currentModified = file.lastModified()
        val currentLength = file.length()
        if (currentModified == lastFileModified && currentLength == lastFileLength && whitelistLoaded) {
            return // 未修改，跳过
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
    
    /**
     * 检查包名是否在白名单中
     * @return true 表示允许触发振动
     */
    fun isPackageAllowed(packageName: String): Boolean {
        if (!isWhitelistEnabled()) {
            return true // 白名单未启用，允许所有应用
        }
        reloadWhitelist()
        return whitelist.contains(packageName)
    }
    
    /**
     * 获取当前白名单（用于 UI 显示）
     */
    fun getWhitelist(): Set<String> {
        reloadWhitelist()
        return HashSet(whitelist)
    }
    
    /**
     * 初始化白名单文件（首次运行时创建默认白名单）
     */
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