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
        @Volatile var initialByteBufferPosition: Int = -1
    )

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
}