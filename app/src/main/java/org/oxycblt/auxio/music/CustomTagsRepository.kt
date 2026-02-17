/*
 * Copyright (c) 2026 Auxio Project
 * CustomTagsRepository.kt is part of Auxio.
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

interface CustomTagsRepository {
    fun addTag(songUid: Music.UID, tag: String)

    fun removeTag(songUid: Music.UID, tag: String)

    fun getTags(songUid: Music.UID): Set<String>

    fun getAllTags(): Set<String>

    fun getSongsWithTag(tag: String): Set<String>
}

class CustomTagsRepositoryImpl @Inject constructor(@ApplicationContext context: Context) :
    CustomTagsRepository {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun addTag(songUid: Music.UID, tag: String) {
        val key = songUid.toString()
        val current = getTags(songUid).toMutableSet()
        current.add(tag.trim())
        val all = readAll().toMutableMap()
        all[key] = current
        prefs.edit { putStringSet(KEY_TAGS, encode(all)) }
    }

    override fun removeTag(songUid: Music.UID, tag: String) {
        val key = songUid.toString()
        val current = getTags(songUid).toMutableSet()
        current.remove(tag.trim())
        val all = readAll().toMutableMap()
        if (current.isEmpty()) {
            all.remove(key)
        } else {
            all[key] = current
        }
        prefs.edit { putStringSet(KEY_TAGS, encode(all)) }
    }

    override fun getTags(songUid: Music.UID): Set<String> {
        return readAll()[songUid.toString()] ?: emptySet()
    }

    override fun getAllTags(): Set<String> {
        return readAll().values.flatten().toSet()
    }

    override fun getSongsWithTag(tag: String): Set<String> {
        return readAll()
            .filter { (_, tags) -> tag in tags }
            .keys
    }

    private fun readAll(): Map<String, Set<String>> {
        val encoded = prefs.getStringSet(KEY_TAGS, emptySet()) ?: emptySet()
        return decode(encoded)
    }

    private fun encode(data: Map<String, Set<String>>): Set<String> =
        data.mapTo(mutableSetOf()) { (uid, tags) ->
            uid + SEP + tags.joinToString(TAG_SEP)
        }

    private fun decode(encoded: Set<String>): Map<String, Set<String>> {
        val out = mutableMapOf<String, Set<String>>()
        for (line in encoded) {
            val parts = line.split(SEP, limit = 2)
            if (parts.size != 2) {
                continue
            }
            val uid = parts[0]
            val tags = parts[1].split(TAG_SEP).filter { it.isNotBlank() }.toSet()
            out[uid] = tags
        }
        return out
    }

    private companion object {
        private const val PREFS_NAME = "auxio_custom_tags"
        private const val KEY_TAGS = "tags"
        private const val SEP = "\u001F"
        private const val TAG_SEP = "\u001E"
    }
}
