/*
 * Copyright (c) 2024 Auxio Project
 * FolderListFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.list

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeListBinding
import org.oxycblt.auxio.databinding.ItemParentBinding
import org.oxycblt.auxio.home.Folder
import org.oxycblt.auxio.home.HomeViewModel
import org.oxycblt.auxio.list.recycler.FastScrollRecyclerView
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.context
import org.oxycblt.auxio.util.getPlural

/** A list of folders built from indexed song paths. */
class FolderListFragment :
    ViewBindingFragment<FragmentHomeListBinding>(),
    FastScrollRecyclerView.PopupProvider,
    FastScrollRecyclerView.Listener {
    private val homeModel: HomeViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val adapter = FolderAdapter(::onFolderClicked)

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeRecycler.apply {
            id = R.id.home_folder_recycler
            adapter = this@FolderListFragment.adapter
            popupProvider = this@FolderListFragment
            listener = this@FolderListFragment
        }

        binding.homeNoMusicPlaceholder.apply {
            setImageResource(R.drawable.ic_file_24)
            contentDescription = getString(R.string.lbl_folders)
        }
        binding.homeNoMusicMsg.text = getString(R.string.lng_empty_folders)

        collectImmediately(homeModel.folderList, ::updateFolders)
        collectImmediately(
            homeModel.empty,
            homeModel.folderList,
            ::updateNoMusicIndicator,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    override fun getPopup(pos: Int): String? =
        homeModel.folderList.value.getOrNull(pos)?.path?.name?.ifBlank { null }

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    private fun onFolderClicked(folder: Folder) {
        playbackModel.play(folder.songs)
    }

    private fun updateFolders(folders: List<Folder>) {
        adapter.submitList(folders)
    }

    private fun updateNoMusicIndicator(empty: Boolean, folders: List<Folder>) {
        val binding = requireBinding()
        binding.homeRecycler.isInvisible = empty
        binding.homeNoMusic.isInvisible = !empty && folders.isNotEmpty()
        binding.homeNoMusicAction.isVisible = true
        binding.homeNoMusicAction.text = getString(R.string.set_locations)
        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }
    }

    private class FolderAdapter(private val onClick: (Folder) -> Unit) :
        ListAdapter<Folder, FolderViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FolderViewHolder.from(parent)

        override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
            holder.bind(getItem(position), onClick)
        }

        private companion object {
            val DIFF =
                object : DiffUtil.ItemCallback<Folder>() {
                    override fun areItemsTheSame(oldItem: Folder, newItem: Folder) =
                        oldItem.path == newItem.path

                    override fun areContentsTheSame(oldItem: Folder, newItem: Folder) =
                        oldItem.songs.size == newItem.songs.size
                }
        }
    }

    private class FolderViewHolder private constructor(private val binding: ItemParentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(folder: Folder, onClick: (Folder) -> Unit) {
            binding.parentImage.bind(
                folder.songs,
                binding.context.getString(R.string.lbl_folders),
                R.drawable.ic_file_24,
            )
            binding.parentName.text = folder.path.name ?: folder.path.components.unixString
            binding.parentInfo.text =
                binding.context.getPlural(R.plurals.fmt_song_count, folder.songs.size)
            binding.parentMenu.isVisible = false
            binding.root.setOnClickListener { onClick(folder) }
        }

        companion object {
            fun from(parent: ViewGroup) =
                FolderViewHolder(
                    ItemParentBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false,
                    )
                )
        }
    }
}
