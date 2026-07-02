package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.data.SpecificFeature
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Stores the user's Home and Work quick-access places in DataStore. Each slot is
 * persisted as a small JSON string; clearing a slot writes an empty string, which
 * decodes back to null.
 */
class SavedPlacesViewModel(application: Application) : AndroidViewModel(application) {
    private val ds = DataStoreUtils.getInstance(application)

    val home: StateFlow<SavedPlace?> = slotFlow(KEY_HOME)
    val work: StateFlow<SavedPlace?> = slotFlow(KEY_WORK)

    fun setHome(feature: SpecificFeature.RoutableFeature) = save(KEY_HOME, SavedPlace.from(feature))
    fun setWork(feature: SpecificFeature.RoutableFeature) = save(KEY_WORK, SavedPlace.from(feature))
    fun clearHome() = clear(KEY_HOME)
    fun clearWork() = clear(KEY_WORK)

    private fun slotFlow(key: String): StateFlow<SavedPlace?> =
        ds.stringFlow(key)
            .map { decode(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, decode(ds.getString(key)))

    private fun save(key: String, place: SavedPlace) {
        viewModelScope.launch { ds.setString(key, Json.encodeToString(place)) }
    }

    private fun clear(key: String) {
        viewModelScope.launch { ds.setString(key, "") }
    }

    private fun decode(raw: String?): SavedPlace? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { Json.decodeFromString<SavedPlace>(it) }.getOrNull() }

    companion object {
        private const val KEY_HOME = "saved_place_home"
        private const val KEY_WORK = "saved_place_work"
    }
}
