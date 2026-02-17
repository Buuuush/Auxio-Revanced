/*
 * Copyright (c) 2026 Auxio Project
 * SleepTimerManager.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.playback

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.playback.state.PlaybackStateManager
import timber.log.Timber as L

/**
 * Manages sleep timer functionality for automatic playback stopping.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@Singleton
class SleepTimerManager @Inject constructor(
    private val playbackManager: PlaybackStateManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private var timerEndTimeMs: Long = 0

    private val _timerState = MutableStateFlow<TimerState>(TimerState.Inactive)

    /** The current sleep timer state. */
    val timerState: StateFlow<TimerState> = _timerState

    /**
     * Start a sleep timer that will pause playback after the specified duration.
     *
     * @param durationMs The duration in milliseconds after which to pause playback.
     * @param fadeOut Whether to fade out volume before pausing.
     */
    fun startTimer(durationMs: Long, fadeOut: Boolean = true) {
        L.d("Starting sleep timer: ${durationMs}ms, fadeOut=$fadeOut")
        cancelTimer()

        timerEndTimeMs = System.currentTimeMillis() + durationMs

        timerJob = scope.launch {
            if (fadeOut && durationMs > FADE_OUT_DURATION_MS) {
                // Wait until fade out should begin
                val waitDuration = durationMs - FADE_OUT_DURATION_MS
                _timerState.value = TimerState.Active(timerEndTimeMs, fadeOut)
                delay(waitDuration)

                // Start fade out
                _timerState.value = TimerState.FadingOut(timerEndTimeMs)
                
                // TODO: Implement volume fade out if needed
                delay(FADE_OUT_DURATION_MS)
            } else {
                _timerState.value = TimerState.Active(timerEndTimeMs, fadeOut)
                delay(durationMs)
            }

            // Pause playback
            L.d("Sleep timer expired, pausing playback")
            playbackManager.playing(false)
            _timerState.value = TimerState.Inactive
            timerJob = null
        }
    }

    /**
     * Start a sleep timer that will pause after a specific number of tracks.
     *
     * @param trackCount The number of tracks to play before pausing.
     */
    fun startTimerAfterTracks(trackCount: Int) {
        L.d("Starting sleep timer: after $trackCount tracks")
        cancelTimer()

        val initialIndex = playbackManager.index
        val targetIndex = initialIndex + trackCount

        _timerState.value = TimerState.AfterTracks(trackCount)

        timerJob = scope.launch {
            while (playbackManager.index < targetIndex) {
                delay(1000) // Check every second
                
                // Update remaining tracks
                val remaining = targetIndex - playbackManager.index
                _timerState.value = TimerState.AfterTracks(remaining)
            }

            // Pause playback
            L.d("Sleep timer expired after $trackCount tracks, pausing playback")
            playbackManager.playing(false)
            _timerState.value = TimerState.Inactive
            timerJob = null
        }
    }

    /** Cancel the active sleep timer if one is running. */
    fun cancelTimer() {
        L.d("Cancelling sleep timer")
        timerJob?.cancel()
        timerJob = null
        _timerState.value = TimerState.Inactive
    }

    /** Get the remaining time in milliseconds, or null if no timer is active. */
    fun getRemainingTimeMs(): Long? {
        if (timerJob == null || !timerJob!!.isActive) return null
        val remaining = timerEndTimeMs - System.currentTimeMillis()
        return if (remaining > 0) remaining else null
    }

    companion object {
        private const val FADE_OUT_DURATION_MS = 5000L // 5 seconds fade out
        
        /** Preset sleep timer durations in milliseconds. */
        val PRESET_DURATIONS_MS = listOf(
            5 * 60 * 1000L,      // 5 minutes
            15 * 60 * 1000L,     // 15 minutes
            30 * 60 * 1000L,     // 30 minutes
            45 * 60 * 1000L,     // 45 minutes
            60 * 60 * 1000L,     // 1 hour
            90 * 60 * 1000L,     // 1.5 hours
        )
    }
}

/**
 * Represents the state of the sleep timer.
 */
sealed interface TimerState {
    /** No sleep timer is currently active. */
    data object Inactive : TimerState

    /**
     * Sleep timer is active and will pause at the specified time.
     *
     * @param endTimeMs The system time in milliseconds when the timer will expire.
     * @param willFadeOut Whether the timer will fade out before pausing.
     */
    data class Active(val endTimeMs: Long, val willFadeOut: Boolean) : TimerState

    /**
     * Sleep timer is in the fade-out phase.
     *
     * @param endTimeMs The system time in milliseconds when the timer will expire.
     */
    data class FadingOut(val endTimeMs: Long) : TimerState

    /**
     * Sleep timer is active and will pause after the specified number of tracks.
     *
     * @param remainingTracks The number of tracks remaining before the timer expires.
     */
    data class AfterTracks(val remainingTracks: Int) : TimerState
}
