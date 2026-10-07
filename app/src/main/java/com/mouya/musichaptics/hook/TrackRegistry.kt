package com.mouya.musichaptics.hook

import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap

class TrackRegistry {
    data class TrackState(
        @Volatile var sampleRate: Int,
        @Volatile var channels: Int,
        @Volatile var sessionId: Int,
        @Volatile var playing: Boolean = false,
        @Volatile var lastPcmAtMs: Long = 0L,
        @Volatile var lastWriteBytes: Int = 0,
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
            sessionId = runCatching { track.audioSessionId }.getOrDefault(0)
        )
        states[track] = state
        return state
    }

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