/*
 * Copyright (c) 2026 Auxio Project
 * SmartPlaylistManager.kt is part of Auxio.
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

import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

class SmartPlaylistManager @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playStatsRepository: PlayStatsRepository,
) {
    private val syncLock = Mutex()

    suspend fun sync() {
        syncLock.withLock {
            val library = musicRepository.library ?: return
            val nowMs = System.currentTimeMillis()
            val weekAgoMs = nowMs - ONE_WEEK_MS
            val stats = playStatsRepository.readStats()
            val history = playStatsRepository.readHistory()

            val favorites =
                library.findPlaylistByName(FAVORITES_PLAYLIST_NAME)?.songs ?: emptyList()

            val recentlyAdded =
                library.songs
                    .filter { it.addedMs >= weekAgoMs }
                    .sortedByDescending { it.addedMs }

            val recentlyPlayed =
                library.songs
                    .mapNotNull { song ->
                        val stat = stats[song.uid.toString()] ?: return@mapNotNull null
                        if (stat.lastPlayedMs < weekAgoMs) return@mapNotNull null
                        song to stat.lastPlayedMs
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }

            val mostPlayed =
                library.songs
                    .mapNotNull { song ->
                        val stat = stats[song.uid.toString()] ?: return@mapNotNull null
                        if (stat.playCount <= 0) return@mapNotNull null
                        song to stat.playCount
                    }
                    .sortedByDescending { it.second }
                    .map { it.first }

            val neverPlayed =
                library.songs
                    .filter { (stats[it.uid.toString()]?.playCount ?: 0) <= 0 }
                    .sortedBy { it.name.raw.lowercase() }

            val fullHistory =
                history
                    .asReversed()
                    .mapNotNull { entry -> library.findSongByUidString(entry.songUid) }

            val duplicates =
                library.songs
                    .groupBy { song ->
                        buildString {
                            append(song.name.raw.trim().lowercase())
                            append('|')
                            append(
                                song.artists.joinToString(";") {
                                    when (val artistName = it.name) {
                                        is Name.Known -> artistName.raw.trim().lowercase()
                                        is Name.Unknown -> artistName.placeholder.name.lowercase()
                                    }
                                }
                            )
                            append('|')
                            append(song.durationMs / 1000)
                        }
                    }
                    .values
                    .filter { it.size > 1 }
                    .flatten()
                    .sortedBy { it.name.raw.lowercase() }

            upsertPlaylist(FAVORITES_PLAYLIST_NAME, favorites)
            upsertPlaylist(RECENTLY_ADDED_PLAYLIST_NAME, recentlyAdded)
            upsertPlaylist(RECENTLY_PLAYED_PLAYLIST_NAME, recentlyPlayed)
            upsertPlaylist(MOST_PLAYED_PLAYLIST_NAME, mostPlayed)
            upsertPlaylist(NEVER_PLAYED_PLAYLIST_NAME, neverPlayed)
            upsertPlaylist(FULL_HISTORY_PLAYLIST_NAME, fullHistory)
            upsertPlaylist(DUPLICATES_PLAYLIST_NAME, duplicates)
        }
    }

    private fun org.oxycblt.musikr.Library.findSongByUidString(uidString: String): Song? {
        val uid = org.oxycblt.musikr.Music.UID.fromString(uidString) ?: return null
        return songs.firstOrNull { it.uid == uid }
    }

    private suspend fun upsertPlaylist(name: String, songs: List<Song>) {
        val library = musicRepository.library ?: return
        val existing = library.findPlaylistByName(name)
        if (existing == null) {
            musicRepository.createPlaylist(name, songs)
            return
        }

        if (isSamePlaylist(existing, songs)) {
            return
        }

        musicRepository.rewritePlaylist(existing, songs)
    }

    private fun isSamePlaylist(existing: Playlist, songs: List<Song>): Boolean {
        if (existing.songs.size != songs.size) {
            return false
        }

        return existing.songs.zip(songs).all { (a, b) -> a.uid == b.uid }
    }

    companion object {
        const val FAVORITES_PLAYLIST_NAME = "Favoris"
        const val RECENTLY_ADDED_PLAYLIST_NAME = "Ajoutés récemment"
        const val RECENTLY_PLAYED_PLAYLIST_NAME = "Joués récemment"
        const val MOST_PLAYED_PLAYLIST_NAME = "Les plus joués"
        const val NEVER_PLAYED_PLAYLIST_NAME = "Non joués"
        const val FULL_HISTORY_PLAYLIST_NAME = "Historique complet"
        const val DUPLICATES_PLAYLIST_NAME = "Doublons"

        private const val ONE_WEEK_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
