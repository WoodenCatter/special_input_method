package com.example.input_ds.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
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

/** Imported songs are stored in the app's private music directory. */
internal class MusicLibrary(private val context: Context) {
    private val musicDirectory = File(context.filesDir, "imported_music")
    private val metadataPreferences = context.getSharedPreferences("music_metadata", Context.MODE_PRIVATE)

    suspend fun initializeAndLoadTracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
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
            .sortedBy { it.name.lowercase(Locale.ROOT) }
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

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")
    }
}
