/*
 * Copyright (c) 2026 Auxio Project
 * EditTagsDialog.kt is part of Auxio.
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

package org.oxycblt.auxio.music.decision

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogPlaylistNameBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.ui.ViewBindingMaterialDialogFragment
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.edit.EditResult
import org.oxycblt.musikr.tag.edit.TagEditor
import org.oxycblt.musikr.tag.edit.TagEdits

@AndroidEntryPoint
class EditTagsDialog : ViewBindingMaterialDialogFragment<DialogPlaylistNameBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val args: EditTagsDialogArgs by navArgs()
    private var selectedField = EditableField.ARTIST

    override fun onConfigDialog(builder: AlertDialog.Builder) {
        builder
            .setTitle(R.string.lbl_edit_tags)
            .setSingleChoiceItems(
                EditableField.entries.map { getString(it.labelRes) }.toTypedArray(),
                EditableField.entries.indexOf(selectedField),
            ) { _, which ->
                selectedField = EditableField.entries[which]
                updateInputType()
            }
            .setPositiveButton(R.string.lbl_ok, null)
            .setNegativeButton(R.string.lbl_cancel, null)
    }

    override fun onCreateBinding(inflater: LayoutInflater) =
        DialogPlaylistNameBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogPlaylistNameBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        updateInputType()
    }

    override fun onStart() {
        super.onStart()
        (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            submitEdit()
        }
    }

    private fun submitEdit() {
        val songs = args.songUids.mapNotNull(musicModel::findSong)
        if (songs.isEmpty()) {
            findNavController().navigateUp()
            return
        }
        val value = requireBinding().playlistName.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) {
            requireContext().showToast(R.string.err_tag_value_empty)
            return
        }

        val edits =
            when (selectedField) {
                EditableField.ARTIST -> TagEdits(artist = value)
                EditableField.ALBUM -> TagEdits(album = value)
                EditableField.ALBUM_ARTIST -> TagEdits(albumArtist = value)
                EditableField.GENRE -> TagEdits(genre = value)
                EditableField.DATE -> TagEdits(date = value)
                EditableField.TRACK -> {
                    val parsed = value.toIntOrNull()
                    if (parsed == null) {
                        requireContext().showToast(R.string.err_tag_number_invalid)
                        return
                    }
                    TagEdits(track = parsed)
                }
                EditableField.DISC -> {
                    val parsed = value.toIntOrNull()
                    if (parsed == null) {
                        requireContext().showToast(R.string.err_tag_number_invalid)
                        return
                    }
                    TagEdits(disc = parsed)
                }
            }

        lifecycleScope.launch(Dispatchers.IO) {
            val editor = TagEditor.from(requireContext())
            var success = 0
            for (song in songs) {
                if (editor.edit(song, edits) is EditResult.Success) {
                    success++
                }
            }
            withContext(Dispatchers.Main) {
                if (success > 0) {
                    musicModel.rescan()
                    requireContext().showToast(R.string.lng_tags_updated)
                    findNavController().navigateUp()
                } else {
                    requireContext().showToast(R.string.err_tag_write_failed)
                }
            }
        }
    }

    private fun updateInputType() {
        val binding = binding ?: return
        binding.playlistName.hint = getString(selectedField.labelRes)
        binding.playlistName.inputType =
            when (selectedField) {
                EditableField.TRACK,
                EditableField.DISC -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
    }

    private enum class EditableField(val labelRes: Int) {
        ARTIST(R.string.lbl_tag_artist),
        ALBUM(R.string.lbl_tag_album),
        ALBUM_ARTIST(R.string.lbl_tag_album_artist),
        GENRE(R.string.lbl_tag_genre),
        DATE(R.string.lbl_tag_date),
        TRACK(R.string.lbl_tag_track),
        DISC(R.string.lbl_tag_disc),
    }
}
