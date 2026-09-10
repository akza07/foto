package com.phouton.phooto.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val respectNoMedia: Boolean = true,
    val requireExplicit: Boolean = false,
    val selectedTreeUris: List<String> = emptyList(),
    val preferHighRefreshRate: Boolean = true,
    val confirmBeforeDelete: Boolean = true,
    val loopVideos: Boolean = false,
)

object SettingsRepository {
    private const val PREFS = "phooto_settings"
    private const val KEY_RESPECT_NO_MEDIA = "respect_no_media"
    private const val KEY_REQUIRE_EXPLICIT = "require_explicit"
    private const val KEY_TREE_URIS = "tree_uris"
    private const val KEY_PREFER_HIGH_REFRESH_RATE = "prefer_high_refresh_rate"
    private const val KEY_CONFIRM_BEFORE_DELETE = "confirm_before_delete"
    private const val KEY_LOOP_VIDEOS = "loop_videos"

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
