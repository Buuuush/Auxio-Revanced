/*
 * Copyright (c) 2026 Auxio Project
 * TagEditor.kt is part of Auxio.
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

package org.oxycblt.musikr.tag.edit

import android.content.ContentResolver
import android.content.Context
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.metadata.TagEditPayload
import org.oxycblt.musikr.metadata.TagLibJNI
import org.oxycblt.musikr.metadata.TagWriteResult

interface TagEditor {
    suspend fun edit(song: Song, edits: TagEdits): EditResult

    companion object {
        fun from(context: Context): TagEditor = TagEditorImpl(context.contentResolver)
    }
}

data class TagEdits(
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val date: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
)

sealed interface EditResult {
    data object Success : EditResult

    data object NotAudio : EditResult

    data object Unsupported : EditResult

    data object ProviderFailed : EditResult
}

private class TagEditorImpl(private val contentResolver: ContentResolver) : TagEditor {
    override suspend fun edit(song: Song, edits: TagEdits): EditResult =
        withContext(Dispatchers.IO) {
            val deviceFile =
                File(
                    song.uri,
                    song.path,
                    object : AddedMs {
                        override suspend fun resolve() = song.addedMs
                    },
                    song.modifiedMs,
                    song.format.mimeType,
                    song.size,
                    null,
                )
            contentResolver.openFileDescriptor(song.uri, "rw")?.use { pfd ->
                val fis = FileInputStream(pfd.fileDescriptor)
                val fos = FileOutputStream(pfd.fileDescriptor)
                val result =
                    TagLibJNI.write(
                        deviceFile,
                        fis,
                        fos,
                        TagEditPayload(
                            artist = edits.artist,
                            album = edits.album,
                            albumArtist = edits.albumArtist,
                            genre = edits.genre,
                            date = edits.date,
                            track = edits.track,
                            disc = edits.disc,
                        ),
                    )
                fis.close()
                fos.close()
                when (result) {
                    TagWriteResult.Success -> EditResult.Success
                    TagWriteResult.NotAudio -> EditResult.NotAudio
                    TagWriteResult.Unsupported -> EditResult.Unsupported
                    TagWriteResult.ProviderFailed -> EditResult.ProviderFailed
                }
            } ?: EditResult.ProviderFailed
        }
}
