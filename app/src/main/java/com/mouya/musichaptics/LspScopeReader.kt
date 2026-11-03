package com.mouya.musichaptics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads this module's scope straight from LSPosed's configuration database.
 *
 * The libxposed service API (see [LsposedScopeState]) only reaches modules that
 * use the modern API; this module still loads through the legacy `xposed_init`,
 * so the service never binds and the app list used to fall back to a hard-coded
 * list of "recommended" packages. Anything the user ticked in LSPosed that is not
 * on that list (and Phira under another package id) was invisible.
 *
 * The database lives under /data/adb and is root-only, so it is copied into the
 * app cache with `su` and opened there, read-only in effect. Tables used:
 * `modules(mid, module_pkg_name, ...)` and `scope(mid, app_pkg_name, user_id)`.
 */
object LspScopeReader {
    private const val TAG = "LspScopeReader"
    private val DB_DIRS = listOf("/data/adb/lspd/config", "/data/adb/lspd")
    private const val DB_NAME = "modules_config.db"

    /** Package names in this module's scope, or null when it cannot be read. */
    fun read(context: Context): List<String>? {
        val work = File(context.cacheDir, "lspd").apply { mkdirs() }
        val module = context.packageName
        try {
            work.listFiles()?.forEach { it.delete() }
            val quoted = work.absolutePath.replace("'", "")
            // A WAL database is only consistent together with its -wal and -shm files.
            val dirs = DB_DIRS.joinToString(" ")
            val script = "for d in $dirs; do " +
                "if [ -f \"\$d/$DB_NAME\" ]; then " +
                "for f in $DB_NAME $DB_NAME-wal $DB_NAME-shm; do " +
                "[ -f \"\$d/\$f\" ] && cat \"\$d/\$f\" > '$quoted'/\"\$f\"; done; break; fi; done; " +
                "chmod 666 '$quoted'/* 2>/dev/null; true"
            val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                Log.w(TAG, "su timed out while copying the LSPosed database")
                return null
            }
            val db = File(work, DB_NAME)
            if (!db.isFile || db.length() == 0L) {
                Log.w(TAG, "LSPosed database not found or empty")
                return null
            }
            SQLiteDatabase.openDatabase(
                db.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { database ->
                val names = LinkedHashSet<String>()
                database.rawQuery(
                    "SELECT DISTINCT s.app_pkg_name FROM scope s " +
                        "JOIN modules m ON s.mid = m.mid WHERE m.module_pkg_name = ?",
                    arrayOf(module)
                ).use { c ->
                    while (c.moveToNext()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() }?.let(names::add)
                }
                Log.i(TAG, "LSPosed scope has ${names.size} entries")
                return names.toList()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Reading the LSPosed scope failed: ${e.javaClass.simpleName}: ${e.message}")
            return null
        } finally {
            runCatching { work.listFiles()?.forEach { it.delete() } }
        }
    }
}
