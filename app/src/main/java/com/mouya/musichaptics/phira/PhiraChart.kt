package com.mouya.musichaptics.phira

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.File
import java.io.InputStreamReader

object PhiraChart {

    private const val TAG = "PhiraChart"

    
    const val TAP = 1
    const val HOLD = 2
    const val FLICK = 3
    const val DRAG = 4

    






    fun eventOf(noteType: Int): String? = when (noteType) {
        TAP -> "KICK"
        HOLD -> "BODY"
        FLICK -> "SNARE"
        DRAG -> "TICK"
        else -> null
    }

    
    fun priorityOf(event: String): Int = when (event) {
        "KICK" -> 4
        "SNARE" -> 3
        "BODY" -> 2
        "TICK" -> 1
        else -> 0
    }

    
    data class Note(val sec: Double, val type: Int, val durSec: Double)

    data class Meta(
        val name: String,
        val durationSec: Double,
        val bpmSegments: Int,
        val fakeSkipped: Int,
        val noteCount: Int,
    )

    data class Parsed(val notes: List<Note>, val meta: Meta)

    
    





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
        
        if (sorted[0].first > 0.0) sorted.add(0, 0.0 to sorted[0].second)
        return BpmTable(sorted)
    }

    
    private fun beatOf(bar: Double, num: Double, den: Double): Double =
        if (den == 0.0) bar else bar + num / den

    
    fun parseRpe(file: File): Parsed {
        var bpmRaw: List<Pair<Double, Double>> = emptyList()
        var name = file.nameWithoutExtension
        var durationSec = 0.0
        var offsetSec = 0.0

        
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

    
    private fun readJudgeLine(
        reader: JsonReader,
        table: BpmTable,
        offsetSec: Double,
        out: MutableList<Note>,
    ): Int {
        
        
        
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
                            isFake -> fake++          
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

    
    fun parse(file: File): Parsed? = try {
        if (file.extension.equals("pec", true)) parsePec(file) else parseRpe(file)
    } catch (t: Throwable) {
        Log.w(TAG, "parse failed for ${file.name}: ${t.message}")
        null
    }
}