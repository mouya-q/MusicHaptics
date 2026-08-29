package com.mouya.musichaptics.phira

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.File
import java.io.InputStreamReader

object PhiraChart {

    private const val TAG = "PhiraChart"

    // RPE note type
    const val TAP = 1
    const val HOLD = 2
    const val FLICK = 3
    const val DRAG = 4

    /**
     * note 类型 → MusicHapticsX 的 beat 事件（对应 HapticEngine.BEAT_SHAPES 的 key）
     *  Tap   → KICK   干脆冲击
     *  Hold  → BODY   温暖持续
     *  Flick → SNARE  中频扫击
     *  Drag  → TICK   最轻的高频针
     */
    fun eventOf(noteType: Int): String? = when (noteType) {
        TAP -> "KICK"
        HOLD -> "BODY"
        FLICK -> "SNARE"
        DRAG -> "TICK"
        else -> null
    }

    /** 同刻多 note 时保留最重的那个 */
    fun priorityOf(event: String): Int = when (event) {
        "KICK" -> 4
        "SNARE" -> 3
        "BODY" -> 2
        "TICK" -> 1
        else -> 0
    }

    /** 一个原始 note：绝对秒数 + 类型 + 持续秒数（非 hold 为 0） */
    data class Note(val sec: Double, val type: Int, val durSec: Double)

    data class Meta(
        val name: String,
        val durationSec: Double,
        val bpmSegments: Int,
        val fakeSkipped: Int,
        val noteCount: Int,
    )

    data class Parsed(val notes: List<Note>, val meta: Meta)

    // ── BPM 段表 ──────────────────────────────────────────────────────
    /**
     * 多段 BPM → (起始拍, 起始秒, bpm) 的积分表。
     *
     * 不能直接 `beat * 60 / bpm`：变速谱里前面每一段的时长要按**那一段自己的**
     * bpm 累加，从换速那一拍起才用新 bpm。样本里 c66701 有 4 段、c16437 有 2 段。
     */
    private class BpmTable(segs: List<Pair<Double, Double>>) {
        val startBeat = DoubleArray(segs.size)
        val startSec = DoubleArray(segs.size)
        val bpm = DoubleArray(segs.size)

        init {
            var acc = 0.0
            for (i in segs.indices) {
                startBeat[i] = segs[i].first
                startSec[i] = acc
                bpm[i] = segs[i].second
                if (i + 1 < segs.size) {
                    acc += (segs[i + 1].first - segs[i].first) * 60.0 / segs[i].second
                }
            }
        }

        val size: Int get() = bpm.size

        fun toSec(beat: Double): Double {
            var idx = 0
            for (i in startBeat.indices) {
                if (startBeat[i] <= beat) idx = i else break
            }
            return startSec[idx] + (beat - startBeat[idx]) * 60.0 / bpm[idx]
        }
    }

    private fun buildBpmTable(raw: List<Pair<Double, Double>>): BpmTable {
        if (raw.isEmpty()) return BpmTable(listOf(0.0 to 120.0))
        val sorted = raw.sortedBy { it.first }.toMutableList()
        // 首段不是从 0 拍起就用它的 bpm 往前补，否则开头的 note 会算错
        if (sorted[0].first > 0.0) sorted.add(0, 0.0 to sorted[0].second)
        return BpmTable(sorted)
    }

    /** RPE 时间 [小节, 分子, 分母] → 绝对拍数 */
    private fun beatOf(bar: Double, num: Double, den: Double): Double =
        if (den == 0.0) bar else bar + num / den

    // ── RPE JSON ─────────────────────────────────────────────────────
    fun parseRpe(file: File): Parsed {
        var bpmRaw: List<Pair<Double, Double>> = emptyList()
        var name = file.nameWithoutExtension
        var durationSec = 0.0
        var offsetSec = 0.0

        // 第一遍：BPMList + META（judgeLineList 直接 skip
        readJson(file) { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "BPMList" -> bpmRaw = readBpmList(reader)
                    "META" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> name = reader.nextString()
                                "duration" -> durationSec = reader.nextDouble()
                                "offset" -> offsetSec = reader.nextDouble() / 1000.0
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        val table = buildBpmTable(bpmRaw)
        val notes = ArrayList<Note>(4096)
        var fake = 0

        // 第二遍：只读 notes
        readJson(file) { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "judgeLineList") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    fake += readJudgeLine(reader, table, offsetSec, notes)
                }
                reader.endArray()
            }
            reader.endObject()
        }

        notes.sortBy { it.sec }
        return Parsed(
            notes,
            Meta(name, durationSec, table.size, fake, notes.size),
        )
    }

    private fun readBpmList(reader: JsonReader): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>(8)
        reader.beginArray()
        while (reader.hasNext()) {
            var bpm = 120.0
            var beat = 0.0
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "bpm" -> bpm = reader.nextDouble()
                    "startTime" -> beat = readBeatTriple(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            out.add(beat to bpm)
        }
        reader.endArray()
        return out
    }

    /** 返回该判定线剔除的 fake note 数 */
    private fun readJudgeLine(
        reader: JsonReader,
        table: BpmTable,
        offsetSec: Double,
        out: MutableList<Note>,
    ): Int {
        // bpmfactor 会缩放这条线的拍速。样本里全是 1.0，但格式允许非 1，
        // 而且这会直接决定 note 落在第几秒 —— 不能想当然当 1 处理。
        // notes 可能出现在 bpmfactor 之前，所以先收原始拍数，读完整条线再换算。
        var factor = 1.0
        var fake = 0
        data class Raw(val startBeat: Double, val endBeat: Double, val type: Int)
        val raw = ArrayList<Raw>(256)

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "bpmfactor" -> factor = reader.nextDouble().let { if (it <= 0.0) 1.0 else it }
                "notes" -> {
                    if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        continue
                    }
                    reader.beginArray()
                    while (reader.hasNext()) {
                        var type = 0
                        var isFake = false
                        var sb = 0.0
                        var eb = 0.0
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "type" -> type = reader.nextInt()
                                "isFake" -> isFake = readBoolish(reader)
                                "startTime" -> sb = readBeatTriple(reader)
                                "endTime" -> eb = readBeatTriple(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                        when {
                            isFake -> fake++          // 假 note 不参与判定，也就不该有触感
                            eventOf(type) != null -> raw.add(Raw(sb, eb, type))
                        }
                    }
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        for (r in raw) {
            val s = table.toSec(r.startBeat / factor) + offsetSec
            val e = table.toSec(r.endBeat / factor) + offsetSec
            out.add(Note(s, r.type, (e - s).coerceAtLeast(0.0)))
        }
        return fake
    }

    private fun readBeatTriple(reader: JsonReader): Double {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            // 极少数谱把拍数写成裸数字
            return reader.nextDouble()
        }
        val v = DoubleArray(3)
        var i = 0
        reader.beginArray()
        while (reader.hasNext()) {
            if (i < 3) v[i] = reader.nextDouble() else reader.skipValue()
            i++
        }
        reader.endArray()
        return beatOf(v[0], v[1], v[2])
    }

    /** isFake 在不同导出器里可能是 bool 或 0/1 */
    private fun readBoolish(reader: JsonReader): Boolean = when (reader.peek()) {
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NUMBER -> reader.nextInt() != 0
        JsonToken.STRING -> reader.nextString() == "1"
        else -> { reader.skipValue(); false }
    }

    private inline fun readJson(file: File, body: (JsonReader) -> Unit) {
        file.inputStream().buffered(1 shl 16).use { ins ->
            JsonReader(InputStreamReader(ins, Charsets.UTF_8)).use(body)
        }
    }

    // ── PEC 文本 ──────────────────────────────────────────────────────
    /**
     * PEC：首行是 offset(ms)，`bp <beat> <bpm>` 是变速事件，note 行形如
     *   n1/n3/n4 <line> <beat> <x> <above> <fake>
     *   n2       <line> <beat> <endBeat> <x> <above> <fake>
     * 最后一列 1 = fake。样本 18175 里 n1 是 6 列、n2 是 7 列
     */
    fun parsePec(file: File): Parsed {
        var offsetSec = 0.0
        val bpmRaw = ArrayList<Pair<Double, Double>>(4)
        data class Raw(val startBeat: Double, val endBeat: Double, val type: Int)
        val raw = ArrayList<Raw>(2048)
        var fake = 0
        var firstLine = true

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.trim()
                if (t.isEmpty()) continue
                if (firstLine) {
                    firstLine = false
                    val head = t.toDoubleOrNull()
                    if (head != null) {
                        offsetSec = head / 1000.0
                        continue
                    }
                }
                val p = t.split(' ', '\t').filter { it.isNotEmpty() }
                if (p.isEmpty()) continue
                when {
                    p[0] == "bp" && p.size >= 3 -> {
                        val b = p[1].toDoubleOrNull() ?: continue
                        val v = p[2].toDoubleOrNull() ?: continue
                        bpmRaw.add(b to v)
                    }
                    p[0].length == 2 && p[0][0] == 'n' && p.size >= 4 -> {
                        val type = p[0][1] - '0'
                        if (eventOf(type) == null) continue
                        val isHold = type == HOLD
                        val sb = p[2].toDoubleOrNull() ?: continue
                        val eb = if (isHold) (p[3].toDoubleOrNull() ?: sb) else sb
                        val fakeIdx = if (isHold) 6 else 5
                        if (p.size > fakeIdx && p[fakeIdx] == "1") {
                            fake++
                            continue
                        }
                        raw.add(Raw(sb, eb, type))
                    }
                }
            }
        }

        val table = buildBpmTable(bpmRaw)
        val notes = ArrayList<Note>(raw.size)
        for (r in raw) {
            val s = table.toSec(r.startBeat) + offsetSec
            val e = table.toSec(r.endBeat) + offsetSec
            notes.add(Note(s, r.type, (e - s).coerceAtLeast(0.0)))
        }
        notes.sortBy { it.sec }
        return Parsed(notes, Meta(file.name, 0.0, table.size, fake, notes.size))
    }

    /** 按扩展名分派；失败返回 null 而不抛，调用方要能退回实时 DSP */
    fun parse(file: File): Parsed? = try {
        if (file.extension.equals("pec", true)) parsePec(file) else parseRpe(file)
    } catch (t: Throwable) {
        Log.w(TAG, "parse failed for ${file.name}: ${t.message}")
        null
    }
}