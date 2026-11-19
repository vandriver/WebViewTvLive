package com.vasthread.webviewtv.playlist

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.vasthread.webviewtv.misc.application
import com.vasthread.webviewtv.misc.preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

object PlaylistManager {

    private const val TAG = "PlaylistManager"
    private const val CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L
    private const val KEY_PLAYLIST_URL = "playlist_url"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val UPDATE_RETRY_DELAY = 10 * 1000L
    private const val ASSET_PREFIX = "asset://"

    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val gson = GsonBuilder().setPrettyPrinting().create()!!
    private val jsonTypeToken = object : TypeToken<List<Channel>>() {}
    private val playlistFile = File(application.filesDir, "playlist.json")

    // The channel lists that used to be downloaded are gone, so the channels ship inside the apk.
    // Without them the app has no playlist on a fresh install and the channel list has no group.
    private val builtInPlaylists = listOf(Pair("默认", "${ASSET_PREFIX}channels.json"))

    var onPlaylistChange: ((Playlist) -> Unit)? = null
    var onUpdatePlaylistJobStateChange: ((Boolean) -> Unit)? = null
    private var updatePlaylistJob: Job? = null
    private var isUpdating = false
        set(value) {
            onUpdatePlaylistJobStateChange?.invoke(value)
        }

    fun getBuiltInPlaylists() = builtInPlaylists

    fun setPlaylistUrl(url: String) {
        preference.edit()
            .putString(KEY_PLAYLIST_URL, url)
            .putLong(KEY_LAST_UPDATE, 0)
            .apply()
        requestUpdatePlaylist()
    }

    fun getPlaylistUrl() = preference.getString(KEY_PLAYLIST_URL, builtInPlaylists.first().second)!!

    fun setLastUpdate(time: Long, requestUpdate: Boolean = false) {
        preference.edit().putLong(KEY_LAST_UPDATE, time).apply()
        if (requestUpdate) requestUpdatePlaylist()
    }

    private fun requestUpdatePlaylist() {
        val lastJobCompleted = updatePlaylistJob?.isCompleted
        if (lastJobCompleted != null && !lastJobCompleted) {
            Log.i(TAG, "A job is executing, ignore!")
            return
        }
        updatePlaylistJob = CoroutineScope(Dispatchers.IO).launch {
            var times = 0
            val needUpdate = { System.currentTimeMillis() - preference.getLong(KEY_LAST_UPDATE, 0L) > CACHE_EXPIRATION_MS }
            isUpdating = true
            while (needUpdate()) {
                ++times
                Log.i(TAG, "Updating playlist... times=${times}")
                try {
                    val remote = readPlaylist(getPlaylistUrl())
                    val local = runCatching { playlistFile.readText() }.getOrNull()
                    if (remote != local) {
                        playlistFile.writeText(remote)
                        onPlaylistChange?.invoke(createPlaylistFromJson(remote))
                    }

                    setLastUpdate(System.currentTimeMillis())
                    Log.i(TAG, "Update playlist successfully.")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot update playlist, reason: ${e.message}")
                }
                if (needUpdate()) {
                    delay(UPDATE_RETRY_DELAY)
                }
            }
            isUpdating = false
        }
    }

    private fun readPlaylist(url: String): String {
        val assetName = url.removePrefix(ASSET_PREFIX)
        if (assetName != url) {
            return application.assets.open(assetName).bufferedReader().use(BufferedReader::readText)
        }
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("Response code ${response.code}")
        return response.body!!.string()
    }

    private fun createPlaylistFromJson(json: String): Playlist {
        val channels = gson.fromJson(json, jsonTypeToken)
        return Playlist.createFromAllChannels("default", channels)
    }

    private fun loadBuiltInPlaylist(): Playlist {
        val json = runCatching { readPlaylist(builtInPlaylists.first().second) }.getOrNull()
        return createPlaylistFromJson(json ?: "[]")
    }

    fun loadPlaylist(): Playlist {
        return try {
            val json = playlistFile.readText()
            createPlaylistFromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load playlist, reason: ${e.message}")
            setLastUpdate(0L)
            loadBuiltInPlaylist()
        } finally {
            requestUpdatePlaylist()
        }
    }

}