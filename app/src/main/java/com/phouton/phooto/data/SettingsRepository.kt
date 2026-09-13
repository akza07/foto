package com.phouton.phooto.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.phouton.phooto.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the gallery orders media within folders and folders themselves. */
enum class MediaSort(@StringRes val labelRes: Int) {
    DATE_NEWEST(R.string.sort_newest),
    DATE_OLDEST(R.string.sort_oldest),
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc),
}

/** Thumbnail grid density: the minimum tile width in dp. */
enum class GridSize(@StringRes val labelRes: Int, val minTileDp: Int) {
    SMALL(R.string.grid_small, 88),
    MEDIUM(R.string.grid_medium, 112),
    LARGE(R.string.grid_large, 144),
}

data class AppSettings(
    val respectNoMedia: Boolean = true,
    val requireExplicit: Boolean = false,
    val selectedTreeUris: List<String> = emptyList(),
    val preferHighRefreshRate: Boolean = true,
    val confirmBeforeDelete: Boolean = true,
    val loopVideos: Boolean = false,
    val sortOrder: MediaSort = MediaSort.DATE_NEWEST,
    val gridSize: GridSize = GridSize.MEDIUM,
    /** Whether the first-run tutorial has been completed or skipped. */
    val tutorialSeen: Boolean = false,
)

object SettingsRepository {
    private const val PREFS = "phooto_settings"
    private const val KEY_RESPECT_NO_MEDIA = "respect_no_media"
    private const val KEY_REQUIRE_EXPLICIT = "require_explicit"
    private const val KEY_TREE_URIS = "tree_uris"
    private const val KEY_PREFER_HIGH_REFRESH_RATE = "prefer_high_refresh_rate"
    private const val KEY_CONFIRM_BEFORE_DELETE = "confirm_before_delete"
    private const val KEY_LOOP_VIDEOS = "loop_videos"
    private const val KEY_SORT_ORDER = "sort_order"
    private const val KEY_GRID_SIZE = "grid_size"
    private const val KEY_TUTORIAL_SEEN = "tutorial_seen"

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = AppSettings(
            respectNoMedia = prefs.getBoolean(KEY_RESPECT_NO_MEDIA, true),
            requireExplicit = prefs.getBoolean(KEY_REQUIRE_EXPLICIT, false),
            selectedTreeUris = prefs.getString(KEY_TREE_URIS, "")?.split("\n").orEmpty()
                .filter { it.isNotBlank() },
            preferHighRefreshRate = prefs.getBoolean(KEY_PREFER_HIGH_REFRESH_RATE, true),
            confirmBeforeDelete = prefs.getBoolean(KEY_CONFIRM_BEFORE_DELETE, true),
            loopVideos = prefs.getBoolean(KEY_LOOP_VIDEOS, false),
            sortOrder = runCatching {
                MediaSort.valueOf(prefs.getString(KEY_SORT_ORDER, null) ?: MediaSort.DATE_NEWEST.name)
            }.getOrDefault(MediaSort.DATE_NEWEST),
            gridSize = runCatching {
                GridSize.valueOf(prefs.getString(KEY_GRID_SIZE, null) ?: GridSize.MEDIUM.name)
            }.getOrDefault(GridSize.MEDIUM),
            tutorialSeen = prefs.getBoolean(KEY_TUTORIAL_SEEN, false),
        )
    }

    fun setRespectNoMedia(value: Boolean) {
        prefs.edit().putBoolean(KEY_RESPECT_NO_MEDIA, value).apply()
        _settings.value = _settings.value.copy(respectNoMedia = value)
    }

    fun setRequireExplicit(value: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_EXPLICIT, value).apply()
        _settings.value = _settings.value.copy(requireExplicit = value)
    }

    fun setPreferHighRefreshRate(value: Boolean) {
        prefs.edit().putBoolean(KEY_PREFER_HIGH_REFRESH_RATE, value).apply()
        _settings.value = _settings.value.copy(preferHighRefreshRate = value)
    }

    fun setConfirmBeforeDelete(value: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIRM_BEFORE_DELETE, value).apply()
        _settings.value = _settings.value.copy(confirmBeforeDelete = value)
    }

    fun setLoopVideos(value: Boolean) {
        prefs.edit().putBoolean(KEY_LOOP_VIDEOS, value).apply()
        _settings.value = _settings.value.copy(loopVideos = value)
    }

    fun setSortOrder(value: MediaSort) {
        prefs.edit().putString(KEY_SORT_ORDER, value.name).apply()
        _settings.value = _settings.value.copy(sortOrder = value)
    }

    fun setGridSize(value: GridSize) {
        prefs.edit().putString(KEY_GRID_SIZE, value.name).apply()
        _settings.value = _settings.value.copy(gridSize = value)
    }

    fun setTutorialSeen(value: Boolean) {
        prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply()
        _settings.value = _settings.value.copy(tutorialSeen = value)
    }

    fun addTreeUri(uri: String) {
        val current = _settings.value.selectedTreeUris
        if (uri in current) return
        val updated = current + uri
        prefs.edit().putString(KEY_TREE_URIS, updated.joinToString("\n")).apply()
        _settings.value = _settings.value.copy(selectedTreeUris = updated)
    }

    fun removeTreeUri(uri: String) {
        val updated = _settings.value.selectedTreeUris.filterNot { it == uri }
        prefs.edit().putString(KEY_TREE_URIS, updated.joinToString("\n")).apply()
        _settings.value = _settings.value.copy(selectedTreeUris = updated)
    }
}
