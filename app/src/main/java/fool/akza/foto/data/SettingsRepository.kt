package fool.akza.foto.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import fool.akza.foto.R
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
    private const val PREFS = "foto_settings"
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

    /** Persists a boolean and applies [transform] to the in-memory settings. */
    private fun setBoolean(key: String, value: Boolean, transform: (AppSettings) -> AppSettings) {
        prefs.edit().putBoolean(key, value).apply()
        _settings.value = transform(_settings.value)
    }

    /** Persists a string and applies [transform] to the in-memory settings. */
    private fun setString(key: String, value: String, transform: (AppSettings) -> AppSettings) {
        prefs.edit().putString(key, value).apply()
        _settings.value = transform(_settings.value)
    }

    fun setRespectNoMedia(value: Boolean) {
        setBoolean(KEY_RESPECT_NO_MEDIA, value) { it.copy(respectNoMedia = value) }
    }

    fun setRequireExplicit(value: Boolean) {
        setBoolean(KEY_REQUIRE_EXPLICIT, value) { it.copy(requireExplicit = value) }
    }

    fun setPreferHighRefreshRate(value: Boolean) {
        setBoolean(KEY_PREFER_HIGH_REFRESH_RATE, value) { it.copy(preferHighRefreshRate = value) }
    }

    fun setConfirmBeforeDelete(value: Boolean) {
        setBoolean(KEY_CONFIRM_BEFORE_DELETE, value) { it.copy(confirmBeforeDelete = value) }
    }

    fun setLoopVideos(value: Boolean) {
        setBoolean(KEY_LOOP_VIDEOS, value) { it.copy(loopVideos = value) }
    }

    fun setSortOrder(value: MediaSort) {
        setString(KEY_SORT_ORDER, value.name) { it.copy(sortOrder = value) }
    }

    fun setGridSize(value: GridSize) {
        setString(KEY_GRID_SIZE, value.name) { it.copy(gridSize = value) }
    }

    fun setTutorialSeen(value: Boolean) {
        setBoolean(KEY_TUTORIAL_SEEN, value) { it.copy(tutorialSeen = value) }
    }

    fun addTreeUri(uri: String) {
        val current = _settings.value.selectedTreeUris
        if (uri in current) return
        val updated = current + uri
        setString(KEY_TREE_URIS, updated.joinToString("\n")) { it.copy(selectedTreeUris = updated) }
    }

    fun removeTreeUri(uri: String) {
        val updated = _settings.value.selectedTreeUris.filterNot { it == uri }
        setString(KEY_TREE_URIS, updated.joinToString("\n")) { it.copy(selectedTreeUris = updated) }
    }
}
