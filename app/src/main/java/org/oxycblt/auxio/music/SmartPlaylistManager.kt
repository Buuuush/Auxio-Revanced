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

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

class SmartPlaylistManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val playStatsRepository: PlayStatsRepository,
) {
    private val syncLock = Mutex()

    suspend fun createArtistRadio(artistName: String, maxSongs: Int = 50) {
        syncLock.withLock {
            val library = musicRepository.library ?: return
            val artist = library.artists.firstOrNull { 
                it.name.resolve(context).equals(artistName, ignoreCase = true) 
            } ?: return
            
            // Get all genres from this artist's songs
            val artistGenres = artist.songs.flatMap { it.genres }.toSet()
            
            if (artistGenres.isEmpty()) {
                // If no genres, use songs from same album artists
                val relatedSongs = library.songs
                    .filter { song -> 
                        song.artists.any { it.name.resolve(context) != artistName } &&
                        song.album.artists.any { artistGenres.isEmpty() || it.uid == artist.uid }
                    }
                    .shuffled()
                    .take(maxSongs)
                
                upsertPlaylist("Radio: $artistName", relatedSongs)
                return
            }
            
            // Find songs that share at least one genre with the artist
            val genreSimilarSongs = library.songs
                .filter { song ->
                    // Exclude artist's own songs (or include few of them)
                    val isFromArtist = song.artists.any { it.uid == artist.uid }
                    val sharesGenre = song.genres.any { it in artistGenres }
                    !isFromArtist && sharesGenre
                }
                .shuffled()
                .take(maxSongs - 5) // Reserve space for artist's own songs
            
            // Add a few of the artist's own songs at the beginning
            val artistOwnSongs = artist.songs.shuffled().take(5)
            
            val radioPlaylist = (artistOwnSongs + genreSimilarSongs).take(maxSongs)
            upsertPlaylist("Radio: $artistName", radioPlaylist)
        }
    }

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

            val autoArchived =
                library.songs
                    .mapNotNull { song ->
                        val stat = stats[song.uid.toString()]
                        val lastActivityMs = stat?.lastPlayedMs ?: song.addedMs
                        if (nowMs - lastActivityMs < ARCHIVE_INACTIVITY_MS) {
                            return@mapNotNull null
                        }
                        song to lastActivityMs
                    }
                    .sortedBy { it.second }
                    .map { it.first }

            upsertPlaylist(FAVORITES_PLAYLIST_NAME, favorites)
            upsertPlaylist(RECENTLY_ADDED_PLAYLIST_NAME, recentlyAdded)
            upsertPlaylist(RECENTLY_PLAYED_PLAYLIST_NAME, recentlyPlayed)
            upsertPlaylist(MOST_PLAYED_PLAYLIST_NAME, mostPlayed)
            upsertPlaylist(NEVER_PLAYED_PLAYLIST_NAME, neverPlayed)
            upsertPlaylist(FULL_HISTORY_PLAYLIST_NAME, fullHistory)
            upsertPlaylist(DUPLICATES_PLAYLIST_NAME, duplicates)
            upsertPlaylist(AUTO_ARCHIVE_PLAYLIST_NAME, autoArchived)

            // Smart Mix: Intelligent playlist based on recent listening patterns
            val smartMix = generateSmartMix(library, stats, history, nowMs)
            upsertPlaylist(SMART_MIX_PLAYLIST_NAME, smartMix)
        }
    }

    /**
     * Generate an intelligent mix based on listening patterns, combining:
     * - Recently played favorites
     * - Songs from frequently played artists
     * - Songs with similar genres to recently played tracks
     * - Some variety from less-played but liked songs
     */
    private fun generateSmartMix(
        library: org.oxycblt.musikr.Library,
        stats: Map<String, PlayStat>,
        history: List<PlayHistoryEntry>,
        nowMs: Long
    ): List<Song> {
        val twoWeeksAgoMs = nowMs - (2 * ONE_WEEK_MS)
        
        // Get recent listening history
        val recentHistory = history
            .filter { it.playedAtMs >= twoWeeksAgoMs }
            .mapNotNull { library.findSongByUidString(it.songUid) }
            .take(50)
        
        if (recentHistory.isEmpty()) {
            // No recent history, return top played songs
            return library.songs
                .mapNotNull { song ->
                    val stat = stats[song.uid.toString()] ?: return@mapNotNull null
                    song to stat.playCount
                }
                .sortedByDescending { it.second }
                .take(50)
                .map { it.first }
        }
        
        // Get frequently played artists from recent history
        val topArtists = recentHistory
            .flatMap { it.artists }
            .groupingBy { it.uid }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }
        
        // Get frequently played genres
        val topGenres = recentHistory
            .flatMap { it.genres }
            .groupingBy { it.uid }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
        
        val smartMixSongs = mutableListOf<Song>()
        
        // Add songs from top artists (not in recent history)
        val artistSongs = library.songs
            .filter { song ->
                song.artists.any { it.uid in topArtists } &&
                song.uid !in recentHistory.map { it.uid }
            }
            .shuffled()
            .take(15)
        smartMixSongs.addAll(artistSongs)
        
        // Add songs with similar genres (not in recent history)
        val genreSongs = library.songs
            .filter { song ->
                song.genres.any { it.uid in topGenres } &&
                song.uid !in recentHistory.map { it.uid } &&
                song.uid !in smartMixSongs.map { it.uid }
            }
            .shuffled()
            .take(15)
        smartMixSongs.addAll(genreSongs)
        
        // Add some variety: moderately played songs (avoid very played and never played)
        val varietySongs = library.songs
            .mapNotNull { song ->
                val stat = stats[song.uid.toString()] ?: return@mapNotNull null
                if (stat.playCount in 2..10 && song.uid !in smartMixSongs.map { it.uid }) {
                    song
                } else null
            }
            .shuffled()
            .take(10)
        smartMixSongs.addAll(varietySongs)
        
        // Add a few recent favorites at the beginning for familiarity
        val recentFavorites = recentHistory
            .mapNotNull { song ->
                val stat = stats[song.uid.toString()] ?: return@mapNotNull null
                if (stat.playCount >= 3) song else null
            }
            .take(10)
        
        return (recentFavorites + smartMixSongs.shuffled()).distinctBy { it.uid }.take(50)
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
        const val AUTO_ARCHIVE_PLAYLIST_NAME = "Archives automatiques"
        const val SMART_MIX_PLAYLIST_NAME = "Mix intelligent"

        private const val ONE_WEEK_MS = 7L * 24L * 60L * 60L * 1000L
        private const val ARCHIVE_INACTIVITY_MS = 60L * 24L * 60L * 60L * 1000L
    }
}
