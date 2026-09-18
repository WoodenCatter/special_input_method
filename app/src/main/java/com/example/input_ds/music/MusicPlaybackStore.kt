package com.example.input_ds.music

import android.content.Context
import org.json.JSONArray

internal enum class MusicPlaybackMode(val displayName: String) {
    SEQUENTIAL("顺序播放"),
    REPEAT_ONE("单曲循环"),
    SHUFFLE("随机播放");

    fun next(): MusicPlaybackMode = entries[(ordinal + 1) % entries.size]
}

internal class MusicPlaybackStore(context: Context) {
    private val preferences = context.getSharedPreferences("music_playback", Context.MODE_PRIVATE)

    fun lastTrackId(): String? = preferences.getString(KEY_LAST_TRACK, null)

    fun saveLastTrack(trackId: String?) {
        preferences.edit().apply {
            if (trackId == null) remove(KEY_LAST_TRACK) else putString(KEY_LAST_TRACK, trackId)
        }.apply()
    }

    fun playbackMode(): MusicPlaybackMode = runCatching {
        MusicPlaybackMode.valueOf(preferences.getString(KEY_MODE, null).orEmpty())
    }.getOrDefault(MusicPlaybackMode.SEQUENTIAL)

    fun savePlaybackMode(mode: MusicPlaybackMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun orderTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        val order = readIds(KEY_TRACK_ORDER)
        if (order.isEmpty()) return tracks
        val byId = tracks.associateBy(MusicTrack::id)
        return order.mapNotNull(byId::get) + tracks.filter { it.id !in order }
    }

    fun saveTrackOrder(tracks: List<MusicTrack>) {
        writeIds(KEY_TRACK_ORDER, tracks.map(MusicTrack::id))
    }

    fun reconcileShuffleOrder(tracks: List<MusicTrack>, reshuffle: Boolean = false): List<String> {
        val validIds = tracks.map(MusicTrack::id)
        if (reshuffle) return validIds.shuffled().also(::saveShuffleOrder)
        val saved = readIds(KEY_SHUFFLE_ORDER).filter(validIds::contains)
        val missing = validIds.filterNot(saved::contains).shuffled()
        return (saved + missing).also(::saveShuffleOrder)
    }

    fun saveShuffleOrder(ids: List<String>) {
        writeIds(KEY_SHUFFLE_ORDER, ids)
    }

    private fun readIds(key: String): List<String> {
        val encoded = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            List(array.length()) { index -> array.getString(index) }
        }.getOrDefault(emptyList())
    }

    private fun writeIds(key: String, ids: List<String>) {
        preferences.edit().putString(key, JSONArray(ids).toString()).apply()
    }

    companion object {
        private const val KEY_LAST_TRACK = "last_track"
        private const val KEY_MODE = "playback_mode"
        private const val KEY_TRACK_ORDER = "track_order"
        private const val KEY_SHUFFLE_ORDER = "shuffle_order"
    }
}
