package com.example.input_ds.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.input_ds.R
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val file: File
)

internal data class MusicImportResult(
    val importedCount: Int,
    val rejectedCount: Int
)

/**
 * Every playable song lives in the same private directory. Bundled songs are
 * copied there once, so they behave exactly like songs imported later and can
 * be removed without reappearing on the next launch.
 */
internal class MusicLibrary(private val context: Context) {
    private val musicDirectory = File(context.filesDir, "imported_music")
    private val libraryPreferences = context.getSharedPreferences("music_library", Context.MODE_PRIVATE)
    private val metadataPreferences = context.getSharedPreferences("music_metadata", Context.MODE_PRIVATE)

    suspend fun initializeAndLoadTracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        seedBundledTracksIfNeeded()
        loadTracks()
    }

    suspend fun reloadTracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        loadTracks()
    }

    fun loadTracks(): List<MusicTrack> {
        if (!musicDirectory.exists()) return emptyList()
        var unknownNumber = nextUnknownNumber()
        return musicDirectory.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS }
            .sortedWith(
                compareBy<File> { file ->
                    BUNDLED_TRACKS.indexOfFirst { it.fileName == file.name }
                        .takeIf { it >= 0 }
                        ?: Int.MAX_VALUE
                }.thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .map { file ->
                val savedTitle = metadataPreferences.getString(titleKey(file), null)
                val savedArtist = metadataPreferences.getString(artistKey(file), null)
                if (savedTitle != null && savedArtist != null) {
                    MusicTrack(trackId(file), savedTitle, savedArtist, file)
                } else {
                    val embedded = readEmbeddedMetadata(file)
                    val title = embedded.first?.takeIf(String::isNotBlank) ?: "未知歌曲${unknownNumber++}"
                    val artist = embedded.second?.takeIf(String::isNotBlank) ?: "未知歌手"
                    metadataPreferences.edit()
                        .putString(titleKey(file), title)
                        .putString(artistKey(file), artist)
                        .apply()
                    MusicTrack(trackId(file), title, artist, file)
                }
            }
    }

    suspend fun importTracks(uris: List<Uri>): MusicImportResult = withContext(Dispatchers.IO) {
        musicDirectory.mkdirs()
        var imported = 0
        var rejected = 0
        uris.forEach { uri ->
            val displayName = queryDisplayName(uri) ?: "导入音乐"
            val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val acceptedExtension = extension.takeIf(SUPPORTED_EXTENSIONS::contains)
                ?: runCatching { extensionForMime(context.contentResolver.getType(uri)) }.getOrNull()
            if (acceptedExtension == null) {
                rejected += 1
                return@forEach
            }

            val baseName = displayName
                .substringBeforeLast('.', displayName)
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim()
                .ifBlank { "导入音乐" }
            val target = uniqueTarget(baseName, acceptedExtension)
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use(input::copyTo)
                } ?: error("无法读取文件")
            }.isSuccess
            if (copied && target.length() > 0L) {
                imported += 1
            } else {
                target.delete()
                rejected += 1
            }
        }
        MusicImportResult(imported, rejected)
    }

    suspend fun deleteTrack(track: MusicTrack): Boolean = withContext(Dispatchers.IO) {
        val expectedParent = musicDirectory.canonicalFile
        val file = track.file.canonicalFile
        if (file.parentFile != expectedParent) return@withContext false
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            metadataPreferences.edit()
                .remove(titleKey(file))
                .remove(artistKey(file))
                .apply()
        }
        deleted
    }

    private fun seedBundledTracksIfNeeded() {
        if (libraryPreferences.getInt(KEY_SEED_VERSION, 0) >= SEED_VERSION) return
        musicDirectory.mkdirs()
        var allCopied = true
        BUNDLED_TRACKS.forEach { seed ->
            val target = File(musicDirectory, seed.fileName)
            if (target.exists() && target.length() > 0L) return@forEach
            val copied = runCatching {
                context.resources.openRawResource(seed.resourceId).use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }.isSuccess
            if (!copied || target.length() == 0L) {
                target.delete()
                allCopied = false
            }
        }
        if (allCopied) libraryPreferences.edit().putInt(KEY_SEED_VERSION, SEED_VERSION).apply()
    }

    private fun readEmbeddedMetadata(file: File): Pair<String?, String?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim() to
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()
        } catch (_: RuntimeException) {
            null to null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun nextUnknownNumber(): Int {
        val expression = Regex("^未知歌曲(\\d+)$")
        return metadataPreferences.all.values
            .mapNotNull { value -> expression.matchEntire(value as? String ?: return@mapNotNull null) }
            .mapNotNull { match -> match.groupValues[1].toIntOrNull() }
            .maxOrNull()
            ?.plus(1)
            ?: 1
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
    }.getOrNull()

    private fun uniqueTarget(baseName: String, extension: String): File {
        var target = File(musicDirectory, "$baseName.$extension")
        var suffix = 2
        while (target.exists()) {
            target = File(musicDirectory, "$baseName ($suffix).$extension")
            suffix += 1
        }
        return target
    }

    private fun titleKey(file: File) = "${file.name}.title"
    private fun artistKey(file: File) = "${file.name}.artist"
    private fun trackId(file: File) = "local:${file.name}"

    private fun extensionForMime(mime: String?): String? = when (mime?.lowercase(Locale.ROOT)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        else -> null
    }

    private data class BundledTrack(val resourceId: Int, val fileName: String)

    companion object {
        private const val KEY_SEED_VERSION = "seed_version"
        private const val SEED_VERSION = 2

        val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")

        private val BUNDLED_TRACKS = listOf(
            BundledTrack(R.raw.xue_actor, "薛之谦 - 演员.mp3"),
            BundledTrack(R.raw.xue_just_right, "薛之谦 - 刚刚好.mp3"),
            BundledTrack(R.raw.xue_gentleman, "薛之谦 - 绅士.mp3"),
            BundledTrack(R.raw.xue_seen_you, "薛之谦 - 我好像在哪见过你.mp3"),
            BundledTrack(R.raw.xue_extraterrestrial, "薛之谦 - 天外来物.mp3"),
            BundledTrack(R.raw.xue_freak, "薛之谦 - 怪咖.mp3"),
            BundledTrack(R.raw.xue_ugly, "薛之谦 - 丑八怪.mp3"),
            BundledTrack(R.raw.xue_serious_snow, "薛之谦 - 认真的雪.mp3"),
            BundledTrack(R.raw.xue_fang_yuan_ji_li, "薛之谦 - 方圆几里.mp3"),
            BundledTrack(R.raw.xue_accident, "薛之谦 - 意外.mp3")
        )
    }
}
