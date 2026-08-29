#!/usr/bin/env python3
# Kotlin Multi-Track Timeline upgrade script
with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'r') as f:
    content = f.read()

# We will insert multi-track infrastructure before the closing brace of the class
multitrack_insert = '''
    // ════════════════════════════════════════════════════════════════
    //  v3.8 Multi-Track Timeline: Independent track rendering + sidechain
    // ════════════════════════════════════════════════════════════════
    // Four independent tracks: Kick (transient), Snare (texture), Vocal (melody), Body (rumble)
    data class SemanticTrack(
        val name: String,
        val envelope: FloatArray = FloatArray(10) { 0f },
        var active: Boolean = false,
        var priority: Int = 0
    )

    private val tracks = mutableMapOf(
        "KICK" to SemanticTrack("Kick"),
        "SNARE" to SemanticTrack("Snare"),
        "VOCAL" to SemanticTrack("Vocal"),
        "BODY" to SemanticTrack("Body")
    )

    // Priority masking: higher-priority tracks suppress lower ones in same bin (sidechain)
    fun applyMultiTrackFrames(frames: FloatArray, count: Int) {
        // Each frame contains 4 float values: [kickAmp, snareAmp, vocalAmp, bodyAmp]
        for (i in 0 until minOf(count, maxFramesPerPull)) {
            val kick = frames.getOrElse(i * 4 + 0) { 0f }
            val snare = frames.getOrElse(i * 4 + 1) { 0f }
            val vocal = frames.getOrElse(i * 4 + 2) { 0f }
            val body = frames.getOrElse(i * 4 + 3) { 0f }
            tracks["KICK"]!!.envelope[i % 10] = kick
            tracks["SNARE"]!!.envelope[i % 10] = snare
            tracks["VOCAL"]!!.envelope[i % 10] = vocal
            tracks["BODY"]!!.envelope[i % 10] = body
            tracks["KICK"]!!.active = kick > 0.01f
            tracks["SNARE"]!!.active = snare > 0.01f
            tracks["VOCAL"]!!.active = vocal > 0.01f
            tracks["BODY"]!!.active = body > 0.01f
        }
    }

    // Sidechain compression: if KICK > threshold, suppress BODY; if SNARE > threshold, suppress VOCAL
    fun composeSidechainCompressed(): IntArray {
        val bins = 10
        val result = IntArray(bins) { 0 }
        val kickVals = tracks["KICK"]!!.envelope
        val snareVals = tracks["SNARE"]!!.envelope
        val vocalVals = tracks["VOCAL"]!!.envelope
        val bodyVals = tracks["BODY"]!!.envelope
        for (i in 0 until bins) {
            val k = kickVals[i]
            val s = snareVals[i]
            val v = vocalVals[i]
            val b = bodyVals[i]
            // Sidechain rules
            val compressedBody = if (k > 0.3f) b * 0.4f else b
            val compressedVocal = if (s > 0.3f) v * 0.5f else v
            // Priority mix: Kick (100) dominates, Snare (80), Vocal (60), Body (45)
            val composed = maxOf(
                k * 1.0f,
                s * 0.95f,
                compressedVocal * 0.85f,
                compressedBody * 0.75f
            )
            result[i] = (composed * 255f).roundToInt().coerceIn(0, 255)
        }
        return result
    }
'''

# Append before the final closing brace
last_brace = content.rfind('}')
if last_brace != -1:
    content = content[:last_brace] + multitrack_insert + content[last_brace:]

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/java/com/mouya/musichaptics/HapticTimelineScheduler.kt', 'w') as f:
    f.write(content)

print("Multi-Track Timeline added to HapticTimelineScheduler")
