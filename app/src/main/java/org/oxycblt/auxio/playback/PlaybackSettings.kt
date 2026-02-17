/*
 * Copyright (c) 2023 Auxio Project
 * PlaybackSettings.kt is part of Auxio.
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

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.auxio.IntegerTable
import org.oxycblt.auxio.R
import org.oxycblt.auxio.playback.replaygain.ReplayGainMode
import org.oxycblt.auxio.playback.replaygain.ReplayGainPreAmp
import org.oxycblt.auxio.settings.Settings
import timber.log.Timber as L

/**
 * User configuration specific to the playback system.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
interface PlaybackSettings : Settings<PlaybackSettings.Listener> {
    /** The action to display on the playback bar. */
    val barAction: ActionMode
    /** Whether to start playback when a headset is plugged in. */
    val headsetAutoplay: Boolean
    /** Delay in minutes before auto-stopping while paused. 0 disables auto-stop. */
    val autoStopDelayMinutes: Int
    /** Delay in minutes before auto-resuming playback while paused. 0 disables scheduling. */
    val autoStartDelayMinutes: Int
    /** Duration of custom loop in milliseconds. 0 disables custom loop. */
    val customLoopDurationMs: Int
    /** Whether expanded playback panel should use compact mini-player controls. */
    val compactPlayerMode: Boolean
    /** Duration of crossfade transition in milliseconds. 0 disables crossfade. */
    val crossfadeDurationMs: Int
    /** The current ReplayGain configuration. */
    val replayGainMode: ReplayGainMode
    /** The current ReplayGain pre-amp configuration. */
    var replayGainPreAmp: ReplayGainPreAmp
    /** Additional amplification in millibels applied to the active audio session. */
    val effectGainMb: Int
    /** Bass boost strength applied to the active audio session. 0 disables bass boost. */
    val bassBoostStrength: Int
    /** Volume normalization gain in millibels. 0 means no normalization. */
    val normalizationGainMb: Int
    /** Equalizer preset ID applied to the active audio session. 0 disables equalizer. */
    val equalizerPreset: Int
    /** Whether equalizer preset should be automatically selected from current song genre. */
    val equalizerAutoGenre: Boolean
    /** Reverb preset ID applied to the active audio session. 0 disables reverb. */
    val reverbPreset: Int
    /** How to play a song from a general list of songs, specified by [PlaySong] */
    val playInListWith: PlaySong
    /**
     * How to play a song from a parent item, specified by [PlaySong]. Null if to delegate to the UI
     * context.
     */
    val inParentPlaybackMode: PlaySong?
    /** Whether to keep shuffle on when playing a new Song. */
    val keepShuffle: Boolean
    /** Whether to rewind when the skip previous button is pressed before skipping back. */
    val rewindWithPrev: Boolean
    /** Whether a song should pause after every repeat. */
    val pauseOnRepeat: Boolean
    /** Whether to maintain the play/pause state when skipping or editing the queue */
    val rememberPause: Boolean
    /** Whether to always exit when task is removed, even if playing. */
    val exitOnTaskRemoval: Boolean

    interface Listener {
        /** Called when one of the ReplayGain configurations have changed. */
        fun onReplayGainSettingsChanged() {}

        /** Called when [barAction] has changed. */
        fun onBarActionChanged() {}

        /** Called when [pauseOnRepeat] has changed. */
        fun onPauseOnRepeatChanged() {}

        /** Called when advanced playback effects have changed. */
        fun onAdvancedEffectsChanged() {}

        /** Called when compact player mode visibility has changed. */
        fun onCompactPlayerModeChanged() {}
    }
}

class PlaybackSettingsImpl @Inject constructor(@ApplicationContext context: Context) :
    Settings.Impl<PlaybackSettings.Listener>(context), PlaybackSettings {
    override val playInListWith: PlaySong
        get() =
            PlaySong.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_play_in_list_with),
                    Int.MIN_VALUE,
                )
            ) ?: PlaySong.FromAll

    override val inParentPlaybackMode: PlaySong?
        get() =
            PlaySong.fromIntCode(
                sharedPreferences.getInt(
                    getString(R.string.set_key_play_in_parent_with),
                    Int.MIN_VALUE,
                )
            )

    override val barAction: ActionMode
        get() =
            ActionMode.fromIntCode(
                sharedPreferences.getInt(getString(R.string.set_key_bar_action), Int.MIN_VALUE)
            ) ?: ActionMode.NEXT

    override val headsetAutoplay: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_headset_autoplay), false)

    override val autoStopDelayMinutes: Int
        get() =
            sharedPreferences.getInt(
                getString(R.string.set_key_auto_stop_delay),
                30,
            )

    override val autoStartDelayMinutes: Int
        get() =
            sharedPreferences.getInt(
                getString(R.string.set_key_auto_start_delay),
                0,
            )

    override val customLoopDurationMs: Int
        get() = sharedPreferences.getInt(getString(R.string.set_key_custom_loop_duration), 0)

    override val compactPlayerMode: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_compact_player_mode), false)

    override val normalizationGainMb: Int
        get() = sharedPreferences.getInt(getString(R.string.set_key_normalization_gain), 0)

    override val crossfadeDurationMs: Int
        get() = sharedPreferences.getInt(getString(R.string.set_key_crossfade_duration), 0)

    override val replayGainMode: ReplayGainMode
        get() =
            ReplayGainMode.fromIntCode(
                sharedPreferences.getInt(getString(R.string.set_key_replay_gain), Int.MIN_VALUE)
            ) ?: ReplayGainMode.DYNAMIC

    override var replayGainPreAmp: ReplayGainPreAmp
        get() =
            ReplayGainPreAmp(
                sharedPreferences.getFloat(getString(R.string.set_key_pre_amp_with), 0f),
                sharedPreferences.getFloat(getString(R.string.set_key_pre_amp_without), 0f),
            )
        set(value) {
            sharedPreferences.edit {
                putFloat(getString(R.string.set_key_pre_amp_with), value.with)
                putFloat(getString(R.string.set_key_pre_amp_without), value.without)
                apply()
            }
        }

    override val effectGainMb: Int
        get() = sharedPreferences.getInt(getString(R.string.set_key_effect_gain), 0)

    override val bassBoostStrength: Int
        get() = sharedPreferences.getInt(getString(R.string.set_key_bass_boost), 0)

    override val equalizerPreset: Int
        get() = sharedPreferences.getInt(getString(R.string.set_key_equalizer_preset), 0)

    override val equalizerAutoGenre: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_equalizer_auto_genre), false)

    override val reverbPreset: Int
        get() = sharedPreferences.getInt(getString(R.string.set_key_reverb_preset), 0)

    override val keepShuffle: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_keep_shuffle), true)

    override val rewindWithPrev: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_rewind_prev), true)

    override val pauseOnRepeat: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_repeat_pause), false)

    override val rememberPause: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_remember_pause), false)

    override val exitOnTaskRemoval: Boolean
        get() = sharedPreferences.getBoolean(getString(R.string.set_key_task_exit), false)

    override fun migrate() {
        // MusicMode was converted to PlaySong in 3.2.0
        fun Int.migrateMusicMode() =
            when (this) {
                IntegerTable.MUSIC_MODE_SONGS -> PlaySong.FromAll
                IntegerTable.MUSIC_MODE_ALBUMS -> PlaySong.FromAlbum
                IntegerTable.MUSIC_MODE_ARTISTS -> PlaySong.FromArtist(null)
                IntegerTable.MUSIC_MODE_GENRES -> PlaySong.FromGenre(null)
                else -> null
            }

        if (sharedPreferences.contains(OLD_KEY_LIB_MUSIC_PLAYBACK_MODE)) {
            L.d("Migrating $OLD_KEY_LIB_MUSIC_PLAYBACK_MODE")

            val mode =
                sharedPreferences
                    .getInt(OLD_KEY_LIB_MUSIC_PLAYBACK_MODE, Int.MIN_VALUE)
                    .migrateMusicMode()

            sharedPreferences.edit {
                putInt(
                    getString(R.string.set_key_play_in_list_with),
                    mode?.intCode ?: Int.MIN_VALUE,
                )
                remove(OLD_KEY_LIB_MUSIC_PLAYBACK_MODE)
                apply()
            }
        }

        if (sharedPreferences.contains(OLD_KEY_DETAIL_MUSIC_PLAYBACK_MODE)) {
            L.d("Migrating $OLD_KEY_DETAIL_MUSIC_PLAYBACK_MODE")

            val mode =
                sharedPreferences
                    .getInt(OLD_KEY_DETAIL_MUSIC_PLAYBACK_MODE, Int.MIN_VALUE)
                    .migrateMusicMode()

            sharedPreferences.edit {
                putInt(
                    getString(R.string.set_key_play_in_parent_with),
                    mode?.intCode ?: Int.MIN_VALUE,
                )
                remove(OLD_KEY_DETAIL_MUSIC_PLAYBACK_MODE)
                apply()
            }
        }
    }

    override fun onSettingChanged(key: String, listener: PlaybackSettings.Listener) {
        when (key) {
            getString(R.string.set_key_replay_gain),
            getString(R.string.set_key_pre_amp_with),
            getString(R.string.set_key_pre_amp_without) -> {
                L.d("Dispatching ReplayGain setting change")
                listener.onReplayGainSettingsChanged()
            }
            getString(R.string.set_key_bar_action) -> {
                L.d("Dispatching bar action change")
                listener.onBarActionChanged()
            }
            getString(R.string.set_key_repeat_pause) -> {
                L.d("Dispatching pause on repeat change")
                listener.onPauseOnRepeatChanged()
            }
            getString(R.string.set_key_compact_player_mode) -> {
                L.d("Dispatching compact player mode change")
                listener.onCompactPlayerModeChanged()
            }
            getString(R.string.set_key_effect_gain),
            getString(R.string.set_key_bass_boost),
            getString(R.string.set_key_custom_loop_duration),
            getString(R.string.set_key_crossfade_duration),
            getString(R.string.set_key_equalizer_preset),
            getString(R.string.set_key_equalizer_auto_genre),
            getString(R.string.set_key_reverb_preset) -> {
                L.d("Dispatching advanced effects setting change")
                listener.onAdvancedEffectsChanged()
            }
        }
    }

    private companion object {
        const val OLD_KEY_LIB_MUSIC_PLAYBACK_MODE = "auxio_library_playback_mode"
        const val OLD_KEY_DETAIL_MUSIC_PLAYBACK_MODE = "auxio_detail_playback_mode"
    }
}
