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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.doOnLayout
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
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.Path
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
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val adapter = FolderBrowserAdapter(::onEntryClicked, ::onFolderMenu)
    private var currentPath: Path? = null
    private var allFolders: List<Folder> = emptyList()

    override fun onCreateBinding(inflater: LayoutInflater) =
        FragmentHomeListBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentHomeListBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        binding.homeBreadcrumb.isVisible = true
        binding.homeBreadcrumb.doOnLayout {
            binding.homeRecycler.updatePadding(top = it.height)
        }

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

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (!navigateUp()) {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }

        collectImmediately(homeModel.folderList, ::updateFolders)
        collectImmediately(
            homeModel.empty,
            homeModel.folderList,
            ::updateNoMusicIndicator,
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeListBinding) {
        super.onDestroyBinding(binding)
        binding.homeBreadcrumb.isVisible = false
        binding.homeRecycler.apply {
            adapter = null
            popupProvider = null
            listener = null
        }
    }

    override fun getPopup(pos: Int): String? =
        adapter.currentList.getOrNull(pos)?.popupText(requireContext())

    override fun onFastScrollingChanged(isFastScrolling: Boolean) {
        homeModel.setFastScrolling(isFastScrolling)
    }

    private fun onEntryClicked(entry: BrowserEntry) {
        when (entry) {
            is BrowserEntry.Up -> navigateUp()
            is BrowserEntry.Directory -> {
                currentPath = entry.path
                renderEntries()
            }
            is BrowserEntry.AudioFile -> playbackModel.play(entry.song, homeModel.playWith)
        }
    }

    private fun updateFolders(folders: List<Folder>) {
        allFolders = folders
        if (currentPath != null && folders.none { it.path.volume == currentPath?.volume }) {
            currentPath = null
        }
        renderEntries()
    }

    private fun renderEntries() {
        val entries = buildEntries(allFolders, currentPath)
        adapter.submitList(entries)
        updateBreadcrumb()
    }

    private fun updateBreadcrumb() {
        val binding = binding ?: return
        val text = SpannableStringBuilder()
        val rootLabel = getString(R.string.lbl_folders)
        appendBreadcrumbSegment(text, rootLabel, null)

        val path = currentPath
        if (path != null) {
            var cursor = Path(path.volume, Components.root())
            for (component in path.components.components) {
                text.append(" / ")
                cursor = Path(path.volume, cursor.components.child(component))
                appendBreadcrumbSegment(text, component, cursor)
            }
        }

        binding.homeBreadcrumb.text = text
        binding.homeBreadcrumb.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun appendBreadcrumbSegment(
        text: SpannableStringBuilder,
        label: String,
        target: Path?,
    ) {
        val start = text.length
        text.append(label)
        val end = text.length
        text.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    currentPath = target
                    renderEntries()
                }
            },
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    private fun navigateUp(): Boolean {
        val path = currentPath ?: return false
        val parent = path.directory
        currentPath = if (parent.components.components.isEmpty()) null else parent
        renderEntries()
        return true
    }

    private fun onFolderMenu(folder: BrowserEntry.Directory, anchor: View) {
        val songs = songsInDirectory(folder.path)
        if (songs.isEmpty()) {
            return
        }
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_PLAY_ALL, 0, R.string.lbl_play)
            menu.add(0, MENU_ADD_TO_PLAYLIST, 1, R.string.lbl_playlist_add)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_PLAY_ALL -> {
                        playbackModel.play(songs)
                        true
                    }
                    MENU_ADD_TO_PLAYLIST -> {
                        musicModel.addToPlaylist(songs)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun songsInDirectory(path: Path): List<Song> =
        allFolders
            .filter { it.path.volume == path.volume && path.components.contains(it.path.components) }
            .flatMap { it.songs }
            .distinctBy { it.uid }
            .sortedBy { it.path.name ?: "" }

    private fun buildEntries(folders: List<Folder>, path: Path?): List<BrowserEntry> {
        if (folders.isEmpty()) {
            return emptyList()
        }

        val directSongsByDirectory = folders.associate { it.path to it.songs }
        val allDirectories = mutableSetOf<Path>()
        for (folder in folders) {
            var cursor = folder.path
            allDirectories += cursor
            while (cursor.components.components.isNotEmpty()) {
                cursor = cursor.directory
                allDirectories += cursor
            }
        }

        val entries = mutableListOf<BrowserEntry>()
        if (path != null) {
            entries += BrowserEntry.Up
        }

        val directDirectories =
            allDirectories
                .filter {
                    it.components.components.isNotEmpty() &&
                        isDirectChild(parent = path, candidate = it)
                }
                .distinctBy { it.volume to it.components.unixString }
                .sortedBy { if (path == null) it.resolve(requireContext()) else (it.name ?: "") }

        entries +=
            directDirectories.map { directory ->
                val totalSongs = songsInDirectory(directory).size
                BrowserEntry.Directory(directory, totalSongs)
            }

        val directSongs =
            when (path) {
                null -> folders.filter { it.path.components.components.isEmpty() }.flatMap { it.songs }
                else -> directSongsByDirectory[path].orEmpty()
            }
        entries += directSongs.sortedBy { it.path.name ?: "" }.map { BrowserEntry.AudioFile(it) }
        return entries
    }

    private fun isDirectChild(parent: Path?, candidate: Path): Boolean {
        val depth = candidate.components.components.size
        if (parent == null) {
            return depth == 1
        }
        if (parent.volume != candidate.volume) {
            return false
        }
        return depth == parent.components.components.size + 1 &&
            parent.components.contains(candidate.components)
    }

    private fun updateNoMusicIndicator(empty: Boolean, folders: List<Folder>) {
        val binding = requireBinding()
        val showEmptyState = empty || adapter.currentList.isEmpty()
        binding.homeRecycler.isInvisible = showEmptyState
        binding.homeNoMusic.isInvisible = !showEmptyState
        binding.homeNoMusicAction.isVisible = empty
        binding.homeNoMusicAction.text = getString(R.string.set_locations)
        binding.homeNoMusicAction.setOnClickListener { homeModel.startChooseMusicLocations() }
    }

    private class FolderBrowserAdapter(
        private val onClick: (BrowserEntry) -> Unit,
        private val onFolderMenu: (BrowserEntry.Directory, View) -> Unit,
    ) : ListAdapter<BrowserEntry, FolderViewHolder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FolderViewHolder.from(parent)

        override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
            holder.bind(getItem(position), onClick, onFolderMenu)
        }

        private companion object {
            val DIFF =
                object : DiffUtil.ItemCallback<BrowserEntry>() {
                    override fun areItemsTheSame(oldItem: BrowserEntry, newItem: BrowserEntry) =
                        oldItem.stableId == newItem.stableId

                    override fun areContentsTheSame(oldItem: BrowserEntry, newItem: BrowserEntry) =
                        oldItem == newItem
                }
        }
    }

    private class FolderViewHolder private constructor(private val binding: ItemParentBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            entry: BrowserEntry,
            onClick: (BrowserEntry) -> Unit,
            onFolderMenu: (BrowserEntry.Directory, View) -> Unit,
        ) {
            when (entry) {
                is BrowserEntry.Up -> {
                    binding.parentImage.bind(emptyList(), binding.context.getString(R.string.lbl_up), R.drawable.ic_back_24)
                    binding.parentName.text = binding.context.getString(R.string.lbl_up)
                    binding.parentInfo.text = ""
                    binding.parentMenu.isVisible = false
                }
                is BrowserEntry.Directory -> {
                    binding.parentImage.bind(emptyList(), binding.context.getString(R.string.lbl_folders), R.drawable.ic_file_24)
                    binding.parentName.text = entry.path.name ?: entry.path.resolve(binding.context)
                    binding.parentInfo.text =
                        binding.context.getPlural(R.plurals.fmt_song_count, entry.songCount)
                    binding.parentMenu.isVisible = true
                    binding.parentMenu.setOnClickListener { onFolderMenu(entry, it) }
                }
                is BrowserEntry.AudioFile -> {
                    binding.parentImage.bind(emptyList(), binding.context.getString(R.string.lbl_songs), R.drawable.ic_song_24)
                    binding.parentName.text = entry.song.path.name ?: entry.song.name.resolve(binding.context)
                    binding.parentInfo.text = entry.song.album.name.resolve(binding.context)
                    binding.parentMenu.isVisible = false
                }
            }
            if (entry !is BrowserEntry.Directory) {
                binding.parentMenu.setOnClickListener(null)
            }
            binding.root.setOnClickListener { onClick(entry) }
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

    private sealed interface BrowserEntry {
        val stableId: String

        fun popupText(context: android.content.Context): String? =
            when (this) {
                is Up -> context.getString(R.string.lbl_up)
                is Directory -> path.name ?: path.resolve(context)
                is AudioFile -> song.path.name ?: song.name.resolve(context)
            }

        data object Up : BrowserEntry {
            override val stableId = "up"
        }

        data class Directory(val path: Path, val songCount: Int) : BrowserEntry {
            override val stableId: String = "dir:${path.volume}:${path.components.unixString}"
        }

        data class AudioFile(val song: Song) : BrowserEntry {
            override val stableId: String = "song:${song.uid}"
        }
    }

    private companion object {
        const val MENU_PLAY_ALL = 1
        const val MENU_ADD_TO_PLAYLIST = 2
    }
}
