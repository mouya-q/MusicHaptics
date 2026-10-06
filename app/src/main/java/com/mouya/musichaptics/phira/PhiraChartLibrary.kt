package com.mouya.musichaptics.phira

import android.util.Log
import java.io.File

object PhiraChartLibrary {

    private const val TAG = "PhiraChartLibrary"

    const val PACKAGE = "org.flos.phira"
    const val DEFAULT_ROOT = "/data/user/0/$PACKAGE/files/data"

    data class Entry(
        val id: String,
        
        val dir: File,
        
        val chartFile: File,
        
        val musicFile: File?,
        val name: String,
        
        val infoOffsetMs: Double,
    )

    
    fun scan(root: String = DEFAULT_ROOT): List<Entry> {
        val base = File(root, "charts/download")
        val dirs = base.listFiles { f: File -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { entryOf(it) }
    }

    fun entryOf(dir: File): Entry? {
        val info = parseInfoYml(File(dir, "info.yml"))
        
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