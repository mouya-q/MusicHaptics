package com.mouya.musichaptics.hook

import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap

class TrackRegistry {
    data class TrackState(
        @Volatile var sampleRate: Int,
        @Volatile var channels: Int,
        @Volatile var sessionId: Int,
        /**
         * PCM encoding as reported by [AudioTrack.getAudioFormat] (one of
         * ENCODING_PCM_16BIT / _FLOAT / _8BIT / _24BIT / _32BIT).
         *
         * The decode path previously assumed 16-bit unconditionally. A player
         * using ENCODING_PCM_FLOAT then produced garbage samples (float bits
         * reinterpreted as int16), which yields a noise-like spectrum where no
         * onset can ever be detected -- a silent failure with no error path.
         */
        @Volatile var encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT,
        @Volatile var playing: Boolean = false,
        @Volatile var lastPcmAtMs: Long = 0L,
        @Volatile var lastWriteBytes: Int = 0,
        /**
         * ByteBuffer contents captured in beforeHookedMethod.
         *
         * write() consumes the caller's buffer, so the payload must be copied
         * while the position still points at the unread region. Holding it on
         * the state keeps the hand-off allocation-free per write and lets the
         * after-hook use the exact bytes that were consumed.
         */
        @Volatile var pendingByteSnapshot: ByteArray? = null,
        @Volatile var initialByteBufferPosition: Int = -1,
        /**
         * Frames this hook has seen the track accept since it was created or last
         * flushed. Lines up with [AudioTrack.getTimestamp]'s frame position, which
         * counts frames presented since the same events.
         */
        @Volatile var writtenFrames: Long = 0L
    ) {
        /** Reused for every [AudioTrack.getTimestamp] call; one writer thread per track. */
        internal val timestamp = android.media.AudioTimestamp()
    }

    // P1: AudioTrack must not be held strongly, otherwise every player
    // instance leaks until process death. Weak keys + synchronized access.
    private val states: MutableMap<AudioTrack, TrackState> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<AudioTrack, TrackState>())

    fun register(track: AudioTrack): TrackState {
        val state = TrackState(
            sampleRate = runCatching { track.sampleRate }.getOrDefault(48000),
            channels = normalizeChannels(runCatching { track.channelCount }.getOrDefault(2)),
            sessionId = runCatching { track.audioSessionId }.getOrDefault(0),
            encoding = readEncoding(track)
        )
        states[track] = state
        return state
    }

    /**
     * Read the track's PCM encoding, falling back to 16-bit.
     *
     * getAudioFormat() exists from API 23 so it is safe on minSdk 28, but it is
     * still wrapped defensively: a ROM-vendored subclass could throw, and a
     * wrong guess must degrade to the previous behaviour rather than crash.
     */
    private fun readEncoding(track: AudioTrack): Int = runCatching {
        track.audioFormat
    }.getOrDefault(android.media.AudioFormat.ENCODING_PCM_16BIT)

    fun state(track: AudioTrack): TrackState? = states[track]

    /**
     * CLOCK_MONOTONIC nanoseconds at which the first of the [frames] frames the
     * track has just accepted becomes audible, or 0 when it cannot be determined.
     *
     * `AudioTrack.write` returns as soon as the frames are copied into the track
     * buffer, which can be tens of milliseconds (small buffers) or seconds (large
     * float writes) before they are heard. Firing a haptic when the PCM is
     * analysed therefore leads the sound by exactly that queue depth. The
     * timestamp pair (frame position, nano time) the audio HAL reports already
     * includes the buffer and the output pipeline, so
     *
     *     audible(F) = ts.nanoTime + (F - ts.framePosition) / sampleRate
     *
     * for any later frame F. The result is checked for plausibility; any
     * inconsistency returns 0 so the native side falls back to the old
     * fire-immediately behaviour instead of scheduling garbage.
     */
    fun audibleNsForWrite(track: AudioTrack, state: TrackState, frames: Int): Long {
        val rate = state.sampleRate
        if (rate <= 0 || frames <= 0) return 0L
        val firstFrame = state.writtenFrames
        state.writtenFrames = firstFrame + frames
        val nowNs = System.nanoTime()

        val ts = state.timestamp
        val haveTimestamp = try { track.getTimestamp(ts) } catch (_: Throwable) { false }
        val audible: Long
        val presentedNow: Long
        if (haveTimestamp && ts.nanoTime > 0L && ts.framePosition >= 0L) {
            audible = ts.nanoTime + (firstFrame - ts.framePosition) * 1_000_000_000L / rate
            presentedNow = ts.framePosition + (nowNs - ts.nanoTime) * rate / 1_000_000_000L
        } else {
            // No timestamp yet (start-up, some offload paths): use the playback
            // head and an assumed fast-mixer + HAL latency. Coarser, but far
            // closer to the truth than "now".
            val head = try { track.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Throwable) { return 0L }
            presentedNow = head
            audible = nowNs + (firstFrame - head) * 1_000_000_000L / rate + ASSUMED_OUTPUT_LATENCY_NS
        }

        // Plausibility: the first frame of a write cannot be audible more than a
        // few seconds before or after "now". Anything else means our frame counter
        // and the track's disagree (flush/stop re-basing, a restarted track).
        val leadNs = audible - nowNs
        if (leadNs < -MAX_PAST_LEAD_NS || leadNs > MAX_FUTURE_LEAD_NS) {
            state.writtenFrames = (presentedNow + frames).coerceAtLeast(frames.toLong())
            return 0L
        }
        return audible
    }

    fun remove(track: AudioTrack) {
        states.remove(track)
    }

    fun activeSession(): Int {
        val now = android.os.SystemClock.elapsedRealtime()
        return states.values.firstOrNull { it.playing && now - it.lastPcmAtMs <= 1200L }?.sessionId ?: 0
    }

    fun isOnlyTrackStopped(track: AudioTrack): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        return states.entries.none { (other, state) ->
            other !== track && state.playing && now - state.lastPcmAtMs <= 1400L
        }
    }

    private fun normalizeChannels(value: Int): Int = value.coerceIn(1, 8)

    private companion object {
        /** Typical fast-mixer buffer + HAL + DAC latency when no timestamp exists. */
        const val ASSUMED_OUTPUT_LATENCY_NS = 40_000_000L
        /** A blocking write of a very large block may start long before it returns. */
        const val MAX_PAST_LEAD_NS = 8_000_000_000L
        const val MAX_FUTURE_LEAD_NS = 4_000_000_000L
    }
}