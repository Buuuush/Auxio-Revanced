/*
 * Copyright (c) 2026 Auxio Project
 * PlayStatsRepository.kt is part of Auxio.
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

package org.oxycblt.auxio.music

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import org.oxycblt.musikr.Music

data class PlayStat(val playCount: Int, val lastPlayedMs: Long)

interface PlayStatsRepository {
    fun recordPlayed(songUid: Music.UID)

    fun readStats(): Map<String, PlayStat>
}

class PlayStatsRepositoryImpl @Inject constructor(@ApplicationContext context: Context) :
    PlayStatsRepository {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun recordPlayed(songUid: Music.UID) {
        val key = songUid.toString()
        val previous = readStats()[key]
        val updated =
            PlayStat(
                playCount = (previous?.playCount ?: 0) + 1,
                lastPlayedMs = System.currentTimeMillis(),
            )
        val all = readStats().toMutableMap().apply { put(key, updated) }
        prefs.edit { putStringSet(KEY_STATS, encode(all)) }
    }

    override fun readStats(): Map<String, PlayStat> {
        val encoded = prefs.getStringSet(KEY_STATS, emptySet()) ?: emptySet()
        return decode(encoded)
    }

    private fun encode(stats: Map<String, PlayStat>): Set<String> =
        stats.mapTo(mutableSetOf()) { (uid, stat) ->
            listOf(uid, stat.playCount.toString(), stat.lastPlayedMs.toString()).joinToString(SEP)
        }

    private fun decode(encoded: Set<String>): Map<String, PlayStat> {
        val out = mutableMapOf<String, PlayStat>()
        for (line in encoded) {
            val parts = line.split(SEP)
            if (parts.size != 3) {
                continue
            }
            val uid = parts[0]
            val count = parts[1].toIntOrNull() ?: continue
            val lastPlayed = parts[2].toLongOrNull() ?: continue
            out[uid] = PlayStat(count, lastPlayed)
        }
        return out
    }

    private companion object {
        private const val PREFS_NAME = "auxio_play_stats"
        private const val KEY_STATS = "stats"
        private const val SEP = "\u001F"
    }
}
