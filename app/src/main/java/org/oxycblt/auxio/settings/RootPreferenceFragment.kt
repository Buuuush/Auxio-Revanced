/*
 * Copyright (c) 2021 Auxio Project
 * RootPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

/**
 * The [PreferenceFragmentCompat] that displays the root settings list.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class RootPreferenceFragment : BasePreferenceFragment(R.xml.preferences_root) {
    private val musicModel: MusicViewModel by activityViewModels()
    private var exportLauncher: ActivityResultLauncher<String>? = null
    private var importLauncher: ActivityResultLauncher<Array<String>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
                uri ->
                if (uri != null) {
                    exportSettings(uri)
                }
            }
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importSettings(uri)
            }
        }

        enterTransition = MaterialFadeThrough()
        returnTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_music_dirs) -> {
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicLocationsSettings())
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        // Hook generic preferences to their specified preferences
        // TODO: These seem like good things to put into a side navigation view, if I choose to
        //  do one.
        when (preference.key) {
            getString(R.string.set_key_ui) -> {
                L.d("Navigating to UI preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.uiPreferences())
            }
            getString(R.string.set_key_personalize) -> {
                L.d("Navigating to personalization preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.personalizePreferences())
            }
            getString(R.string.set_key_music) -> {
                L.d("Navigating to music preferences")
                findNavController()
                    .navigateSafe(RootPreferenceFragmentDirections.musicPreferences())
            }
            getString(R.string.set_key_audio) -> {
                L.d("Navigating to audio preferences")
                findNavController().navigateSafe(RootPreferenceFragmentDirections.audioPeferences())
            }
            getString(R.string.set_key_reindex) -> musicModel.refresh()
            getString(R.string.set_key_rescan) -> musicModel.rescan()
            getString(R.string.set_key_export_settings) -> {
                requireNotNull(exportLauncher) { "Export launcher unavailable" }
                    .launch("auxio-settings.json")
            }
            getString(R.string.set_key_import_settings) -> {
                requireNotNull(importLauncher) { "Import launcher unavailable" }
                    .launch(arrayOf("application/json", "text/plain"))
            }
            else -> return super.onPreferenceTreeClick(preference)
        }

        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        exportLauncher = null
        importLauncher = null
    }

    private fun exportSettings(uri: Uri) {
        runCatching {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val root = JSONObject()
            val entries = JSONArray()
            for ((key, value) in prefs.all) {
                val obj = JSONObject()
                obj.put("key", key)
                when (value) {
                    is Boolean -> {
                        obj.put("type", "bool")
                        obj.put("value", value)
                    }
                    is Int -> {
                        obj.put("type", "int")
                        obj.put("value", value)
                    }
                    is Long -> {
                        obj.put("type", "long")
                        obj.put("value", value)
                    }
                    is Float -> {
                        obj.put("type", "float")
                        obj.put("value", value.toDouble())
                    }
                    is String -> {
                        obj.put("type", "string")
                        obj.put("value", value)
                    }
                    is Set<*> -> {
                        obj.put("type", "set")
                        val array = JSONArray()
                        value.forEach { if (it is String) array.put(it) }
                        obj.put("value", array)
                    }
                    else -> continue
                }
                entries.put(obj)
            }
            root.put("version", 1)
            root.put("entries", entries)

            requireContext().contentResolver.openOutputStream(uri).use { stream ->
                requireNotNull(stream) { "Could not open export file" }
                OutputStreamWriter(stream).use { writer ->
                    writer.write(root.toString())
                    writer.flush()
                }
            }
        }
            .onSuccess { requireContext().showToast(R.string.lng_settings_exported) }
            .onFailure {
                L.e(it, "Settings export failed")
                requireContext().showToast(R.string.err_settings_export_failed)
            }
    }

    private fun importSettings(uri: Uri) {
        runCatching {
            val text =
                requireContext().contentResolver.openInputStream(uri).use { stream ->
                    requireNotNull(stream) { "Could not open import file" }
                    BufferedReader(InputStreamReader(stream)).use { it.readText() }
                }
            val json = JSONObject(text)
            val entries = json.getJSONArray("entries")
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.edit().clear().apply {
                for (idx in 0 until entries.length()) {
                    val obj = entries.getJSONObject(idx)
                    val key = obj.getString("key")
                    when (obj.getString("type")) {
                        "bool" -> putBoolean(key, obj.getBoolean("value"))
                        "int" -> putInt(key, obj.getInt("value"))
                        "long" -> putLong(key, obj.getLong("value"))
                        "float" -> putFloat(key, obj.getDouble("value").toFloat())
                        "string" -> putString(key, obj.getString("value"))
                        "set" -> {
                            val raw = obj.getJSONArray("value")
                            val set =
                                buildSet {
                                    for (i in 0 until raw.length()) {
                                        add(raw.getString(i))
                                    }
                                }
                            putStringSet(key, set)
                        }
                    }
                }
            }.apply()
        }
            .onSuccess {
                requireContext().showToast(R.string.lng_settings_imported)
                requireActivity().recreate()
            }
            .onFailure {
                L.e(it, "Settings import failed")
                requireContext().showToast(R.string.err_settings_import_failed)
            }
    }
}
