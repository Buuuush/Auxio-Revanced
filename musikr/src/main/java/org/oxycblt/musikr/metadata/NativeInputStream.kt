/*
 * Copyright (c) 2024 Auxio Project
 * NativeInputStream.kt is part of Auxio.
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
 
package org.oxycblt.musikr.metadata

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import org.oxycblt.musikr.fs.File

internal class NativeInputStream(
    private val deviceFile: File,
    fis: FileInputStream,
    fos: FileOutputStream? = null,
) {
    private val readChannel = fis.channel
    private val writeChannel = fos?.channel
    private var position = 0L

    fun name() = requireNotNull(deviceFile.path.name)

    fun readBlock(buf: ByteBuffer): Int {
        try {
            val read = readChannel.read(buf, position)
            if (read > 0) {
                position += read
            }
            return read
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error reading block", e)
            return -2
        }
    }

    fun writeBlock(buf: ByteBuffer): Int {
        try {
            val channel = writeChannel ?: return -2
            val wrote = channel.write(buf, position)
            if (wrote > 0) {
                position += wrote
            }
            return wrote
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error writing block", e)
            return -2
        }
    }

    fun insert(buf: ByteBuffer, start: Long, replace: Long): Boolean {
        try {
            val channel = writeChannel ?: return false
            if (start < 0 || replace < 0) {
                return false
            }
            val fileLength = length()
            if (start > fileLength) {
                return false
            }
            val replaceClamped = minOf(replace, fileLength - start)
            val insertSize = buf.remaining().toLong()
            val delta = insertSize - replaceClamped
            val tailStart = start + replaceClamped

            if (delta > 0) {
                shiftRight(readChannel, channel, tailStart, fileLength, delta)
            } else if (delta < 0) {
                shiftLeft(readChannel, channel, tailStart, fileLength, -delta)
                channel.truncate(fileLength + delta)
            }

            channel.write(buf, start)
            position = start + insertSize
            return true
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error inserting block", e)
            return false
        }
    }

    fun removeBlock(start: Long, length: Long): Boolean {
        try {
            val channel = writeChannel ?: return false
            if (start < 0 || length < 0) {
                return false
            }
            val fileLength = this.length()
            if (start > fileLength) {
                return false
            }
            val removeLength = minOf(length, fileLength - start)
            val tailStart = start + removeLength
            shiftLeft(readChannel, channel, tailStart, fileLength, removeLength)
            channel.truncate(fileLength - removeLength)
            if (position > fileLength - removeLength) {
                position = fileLength - removeLength
            }
            return true
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error removing block", e)
            return false
        }
    }

    fun truncate(length: Long): Boolean {
        return try {
            val channel = writeChannel ?: return false
            channel.truncate(length)
            if (position > length) {
                position = length
            }
            true
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error truncating", e)
            false
        }
    }

    fun isReadOnly(): Boolean {
        return writeChannel == null
    }

    fun isOpen(): Boolean {
        return readChannel.isOpen
    }

    fun seekFromBeginning(offset: Long): Boolean {
        try {
            if (offset < 0) {
                return false
            }
            position = offset
            return true
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error seeking from beginning", e)
            return false
        }
    }

    fun seekFromCurrent(offset: Long): Boolean {
        try {
            val next = position + offset
            if (next < 0) {
                return false
            }
            position = next
            return true
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error seeking from current", e)
            return false
        }
    }

    fun seekFromEnd(offset: Long): Boolean {
        try {
            val next = readChannel.size() + offset
            if (next < 0) {
                return false
            }
            position = next
            return true
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error seeking from end", e)
            return false
        }
    }

    fun tell() =
        try {
            position
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error getting position", e)
            Long.MIN_VALUE
        }

    fun length() =
        try {
            readChannel.size()
        } catch (e: Exception) {
            Log.d("NativeInputStream", "Error getting length", e)
            Long.MIN_VALUE
        }

    fun close() {
        readChannel.close()
        writeChannel?.close()
    }

    private fun shiftRight(
        source: FileChannel,
        sink: FileChannel,
        start: Long,
        end: Long,
        delta: Long,
    ) {
        val scratch = ByteArray(CHUNK_SIZE)
        var cursor = end
        while (cursor > start) {
            val amount = minOf(CHUNK_SIZE.toLong(), cursor - start).toInt()
            val readPos = cursor - amount
            val wrapped = ByteBuffer.wrap(scratch, 0, amount)
            source.read(wrapped, readPos)
            wrapped.flip()
            sink.write(wrapped, readPos + delta)
            cursor = readPos
        }
    }

    private fun shiftLeft(
        source: FileChannel,
        sink: FileChannel,
        start: Long,
        end: Long,
        delta: Long,
    ) {
        val scratch = ByteArray(CHUNK_SIZE)
        var cursor = start
        while (cursor < end) {
            val amount = minOf(CHUNK_SIZE.toLong(), end - cursor).toInt()
            val wrapped = ByteBuffer.wrap(scratch, 0, amount)
            source.read(wrapped, cursor)
            wrapped.flip()
            sink.write(wrapped, cursor - delta)
            cursor += amount
        }
    }

    private companion object {
        const val CHUNK_SIZE = 8192
    }
}
