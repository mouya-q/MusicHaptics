package com.mouya.musichaptics.phira

import android.util.Log
import java.io.File

/**
 * 在 Phira 的私有目录里定位谱面文件。
 *
 * 目录结构：
 *   files/data/data.json                       ← 已下载谱面清单，含 local_path
 *   files/data/charts/download/<id>/info.yml   ← name / chart / music / offset
 *   files/data/charts/download/<id>/<hash>.json 或 .pec  ← 谱面本体
 *
 * 这个目录是 Phira 的私有数据，只有在 Phira 进程内（LSPosed hook）或 root 下能读。
 * 我们的解析器就跑在 Phira 进程里，所以走 [DEFAULT_ROOT] 直接读即可。
 */
object PhiraChartLibrary {

    private const val TAG = "PhiraChartLibrary"

    const val PACKAGE = "org.flos.phira"
    const val DEFAULT_ROOT = "/data/user/0/$PACKAGE/files/data"

    data class Entry(
        val id: String,
        /** 谱面目录 */
        val dir: File,
        /** 谱面文件（.json 或 .pec） */
        val chartFile: File,
        /** 音频文件，用于按 fd 反查"正在打哪首" */
        val musicFile: File?,
        val name: String,
        /** info.yml 里的 offset(ms)。RPE META 里也有一份，两者含义不同，别混用 */
        val infoOffsetMs: Double,
    )

    /** 扫描 charts/download 下所有谱面 */
    fun scan(root: String = DEFAULT_ROOT): List<Entry> {
        val base = File(root, "charts/download")
        val dirs = base.listFiles { f: File -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { entryOf(it) }
    }

    fun entryOf(dir: File): Entry? {
        val info = parseInfoYml(File(dir, "info.yml"))
        // info.yml 里 chart: 指名文件；缺失时退回目录里第一个 json/pec
        val chart = info["chart"]?.let { File(dir, it) }?.takeIf { it.isFile }
            ?: dir.listFiles { f: File ->
                f.isFile && (f.extension.equals("json", true) || f.extension.equals("pec", true))
            }?.minByOrNull { it.name }
            ?: return null
        val music = info["music"]?.let { File(dir, it) }?.takeIf { it.isFile }
        return Entry(
            id = dir.name,
            dir = dir,
            chartFile = chart,
            musicFile = music,
            name = info["name"] ?: dir.name,
            infoOffsetMs = info["offset"]?.toDoubleOrNull() ?: 0.0,
        )
    }

    /**
     * 极简 YAML 读取：只取顶层 `key: value`。
     *
     * info.yml 里有多行块（intro: |-）和引号值，但我们只要 name/chart/music/offset
     * 这几个标量，所以不引 YAML 库 —— 只认「行首无缩进 + 冒号」，缩进行一律跳过，
     * 这样多行块的内容不会被误当成键。
     */
    private fun parseInfoYml(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, String>()
        try {
            file.forEachLine { line ->
                if (line.isEmpty() || line[0] == ' ' || line[0] == '\t' || line[0] == '#') return@forEachLine
                val i = line.indexOf(':')
                if (i <= 0) return@forEachLine
                val k = line.substring(0, i).trim()
                var v = line.substring(i + 1).trim()
                if (v == "null" || v == "|-" || v == "|" || v == ">" || v.isEmpty()) return@forEachLine
                if (v.length >= 2 && (v[0] == '\'' || v[0] == '"') && v.last() == v[0]) {
                    v = v.substring(1, v.length - 1)
                }
                if (v.isNotEmpty()) out[k] = v
            }
        } catch (t: Throwable) {
            Log.w(TAG, "info.yml read failed: ${t.message}")
        }
        return out
    }

    /**
     * 从 `/proc/self/fd` 反查当前加载的是哪张谱。
     *
     * Phira 的逻辑全在 libphira.so 里，dex 只是个壳，没有 Java 层的"选歌"回调可 hook。
     * 但它读谱面和音频走的是 libc 的 open —— 我们在同一个进程里，直接看自己的 fd 表
     * 就知道它开了哪个目录下的文件，零 hook、零风险。
     *
     * 注意 fd 可能在读完后就关掉，所以这个要周期性轮询，而不是只看一次。
     */
    fun detectActiveChartDir(): File? {
        val fdDir = File("/proc/self/fd")
        val fds = fdDir.listFiles() ?: return null
        for (fd in fds) {
            val target = try {
                fd.canonicalPath
            } catch (_: Throwable) {
                continue
            }
            val idx = target.indexOf("/charts/download/")
            if (idx < 0) continue
            val rest = target.substring(idx + "/charts/download/".length)
            val id = rest.substringBefore('/')
            if (id.isEmpty() || id == rest) continue
            return File(target.substring(0, idx + "/charts/download/".length) + id)
        }
        return null
    }
}