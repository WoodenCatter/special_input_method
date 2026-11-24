package com.example.input_ds.ui.music

import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode

internal enum class MusicFocusMode { CONTROLS, PLAYLIST, VOLUME, SETTINGS, DELETE_LIST }

internal data class MusicSelectionResult(
    val slotIndex: Int,
    val targetIndex: Int,
    val compensated: Boolean
)

/**
 * Pure §6 scan-cursor state machine for [MusicScreen].
 *
 * Drives controls, playlist, volume, settings (skipping disabled "删除音乐" when no tracks exist),
 * and delete-list scanning with uniform direction, grace-period delay compensation,
 * same-direction look restart, and pause-boundary reset semantics.
 */
internal data class MusicScanState(
    val focusMode: MusicFocusMode = MusicFocusMode.CONTROLS,
    val controlCursor: ScanCursor = ScanCursor(size = CONTROL_COUNT, index = DEFAULT_CONTROL_INDEX, direction = 1),
    val playlistCursor: ScanCursor = ScanCursor(size = 0, index = 0, direction = 1),
    val volumeCursor: ScanCursor = ScanCursor(size = VOLUME_COUNT, index = 0, direction = 1),
    val settingsCursor: ScanCursor = ScanCursor(size = SETTINGS_COUNT, index = 0, direction = 1),
    val deleteCursor: ScanCursor = ScanCursor(size = 0, index = 0, direction = 1),
    val trackCount: Int = 0,
    val dwellRevision: Int = 0
) {
    fun settingsEnabledIndices(): List<Int> =
        if (trackCount == 0) listOf(0, 2) else listOf(0, 1, 2)

    fun activeCursor(): ScanCursor = when (focusMode) {
        MusicFocusMode.CONTROLS -> controlCursor
        MusicFocusMode.PLAYLIST -> playlistCursor
        MusicFocusMode.VOLUME -> volumeCursor
        MusicFocusMode.SETTINGS -> settingsCursor
        MusicFocusMode.DELETE_LIST -> deleteCursor
    }

    fun activeTargetIndex(): Int = targetIndexForSlot(activeCursor().index)

    fun targetIndexForSlot(slotIndex: Int): Int = when (focusMode) {
        MusicFocusMode.SETTINGS -> {
            val enabled = settingsEnabledIndices()
            enabled.getOrElse(slotIndex) { enabled.first() }
        }
        else -> slotIndex
    }

    fun syncTrackCount(newCount: Int): MusicScanState {
        val safeCount = newCount.coerceAtLeast(0)
        if (safeCount == trackCount) return this
        val nextPlaylist = if (safeCount == 0) {
            ScanCursor(size = 0)
        } else {
            playlistCursor.reset(
                newSize = safeCount,
                initial = playlistCursor.index.coerceIn(0, safeCount - 1)
            )
        }
        val currentSettingsTarget = settingsEnabledIndices().getOrElse(settingsCursor.index) { 0 }
        val nextEnabled = if (safeCount == 0) listOf(0, 2) else listOf(0, 1, 2)
        val preservedSettingsSlot = nextEnabled.indexOf(currentSettingsTarget).let { if (it >= 0) it else 0 }
        val nextSettings = if (settingsCursor.size != nextEnabled.size) {
            settingsCursor.reset(newSize = nextEnabled.size, initial = preservedSettingsSlot)
        } else {
            settingsCursor
        }
        val nextDeleteSize = if (safeCount == 0) 0 else safeCount + 1
        val nextDelete = if (nextDeleteSize == 0) {
            ScanCursor(size = 0)
        } else {
            deleteCursor.reset(
                newSize = nextDeleteSize,
                initial = deleteCursor.index.coerceIn(0, nextDeleteSize - 1)
            )
        }
        val nextFocusMode = when {
            safeCount == 0 && focusMode == MusicFocusMode.PLAYLIST -> MusicFocusMode.CONTROLS
            safeCount == 0 && focusMode == MusicFocusMode.DELETE_LIST -> MusicFocusMode.SETTINGS
            else -> focusMode
        }
        val nextControlCursor = if (safeCount == 0 && focusMode == MusicFocusMode.PLAYLIST) {
            ScanCursor(size = CONTROL_COUNT, index = CONTROL_PLAYLIST_INDEX, direction = 1)
        } else {
            controlCursor
        }
        val reconciledSettings = if (safeCount == 0 && focusMode == MusicFocusMode.DELETE_LIST) {
            ScanCursor(size = nextEnabled.size, index = 0, direction = 1)
        } else {
            nextSettings
        }
        return copy(
            focusMode = nextFocusMode,
            trackCount = safeCount,
            controlCursor = nextControlCursor,
            playlistCursor = nextPlaylist,
            settingsCursor = reconciledSettings,
            deleteCursor = nextDelete,
            dwellRevision = if (nextFocusMode != focusMode) dwellRevision + 1 else dwellRevision
        )
    }

    fun look(step: Int, mode: ScanMode): MusicScanState =
        withActiveCursor { it.look(step, mode) }.copy(dwellRevision = dwellRevision + 1)

    fun advance(nowMs: Long, mode: ScanMode): MusicScanState =
        withActiveCursor { it.advance(nowMs, mode) }

    fun selectOnBite(nowMs: Long, graceMs: Long, mode: ScanMode): MusicSelectionResult? {
        val cursor = activeCursor()
        if (cursor.size <= 0) return null
        val slot = cursor.selected(nowMs, graceMs, mode)
        return MusicSelectionResult(
            slotIndex = slot,
            targetIndex = targetIndexForSlot(slot),
            compensated = slot != cursor.index
        )
    }

    fun clearActiveGrace(): MusicScanState =
        withActiveCursor { it.copy(previousIndex = -1, advancedAtMs = 0L) }

    fun resetForPauseBoundary(): MusicScanState =
        withActiveCursor { it.reset() }.copy(dwellRevision = dwellRevision + 1)

    fun commitControlSelection(controlIndex: Int): MusicScanState = copy(
        controlCursor = controlCursor.copy(
            index = controlIndex.coerceIn(0, CONTROL_COUNT - 1),
            previousIndex = -1,
            advancedAtMs = 0L
        ),
        dwellRevision = if (focusMode == MusicFocusMode.CONTROLS) dwellRevision + 1 else dwellRevision
    )

    fun commitVolumeSelection(volumeIndex: Int): MusicScanState = copy(
        volumeCursor = volumeCursor.copy(
            index = volumeIndex.coerceIn(0, VOLUME_COUNT - 1),
            previousIndex = -1,
            advancedAtMs = 0L
        ),
        dwellRevision = if (focusMode == MusicFocusMode.VOLUME) dwellRevision + 1 else dwellRevision
    )

    fun commitDeleteSelection(slotIndex: Int): MusicScanState = copy(
        deleteCursor = if (deleteCursor.size <= 0) {
            deleteCursor
        } else {
            deleteCursor.copy(
                index = slotIndex.coerceIn(0, deleteCursor.size - 1),
                previousIndex = -1,
                advancedAtMs = 0L
            )
        },
        dwellRevision = if (focusMode == MusicFocusMode.DELETE_LIST) dwellRevision + 1 else dwellRevision
    )

    fun enterControls(index: Int = controlCursor.index): MusicScanState = copy(
        focusMode = MusicFocusMode.CONTROLS,
        controlCursor = ScanCursor(size = CONTROL_COUNT, index = index.coerceIn(0, CONTROL_COUNT - 1), direction = 1),
        dwellRevision = dwellRevision + 1
    )

    fun enterPlaylist(trackIndex: Int): MusicScanState {
        if (trackCount <= 0) return enterControls(CONTROL_PLAYLIST_INDEX)
        return copy(
            focusMode = MusicFocusMode.PLAYLIST,
            playlistCursor = ScanCursor(
                size = trackCount,
                index = trackIndex.coerceIn(0, trackCount - 1),
                direction = 1
            ),
            dwellRevision = dwellRevision + 1
        )
    }

    fun enterVolume(index: Int = 0): MusicScanState = copy(
        focusMode = MusicFocusMode.VOLUME,
        volumeCursor = ScanCursor(size = VOLUME_COUNT, index = index.coerceIn(0, VOLUME_COUNT - 1), direction = 1),
        dwellRevision = dwellRevision + 1
    )

    fun enterSettings(targetIndex: Int = 0): MusicScanState {
        val enabled = settingsEnabledIndices()
        val slot = enabled.indexOf(targetIndex).let { if (it >= 0) it else 0 }
        return copy(
            focusMode = MusicFocusMode.SETTINGS,
            settingsCursor = ScanCursor(size = enabled.size, index = slot, direction = 1),
            dwellRevision = dwellRevision + 1
        )
    }

    fun enterDeleteList(index: Int = 0): MusicScanState {
        val size = if (trackCount == 0) 0 else trackCount + 1
        return copy(
            focusMode = MusicFocusMode.DELETE_LIST,
            deleteCursor = ScanCursor(
                size = size,
                index = if (size > 0) index.coerceIn(0, size - 1) else 0,
                direction = 1
            ),
            dwellRevision = dwellRevision + 1
        )
    }

    private inline fun withActiveCursor(transform: (ScanCursor) -> ScanCursor): MusicScanState = when (focusMode) {
        MusicFocusMode.CONTROLS -> copy(controlCursor = transform(controlCursor))
        MusicFocusMode.PLAYLIST -> copy(playlistCursor = transform(playlistCursor))
        MusicFocusMode.VOLUME -> copy(volumeCursor = transform(volumeCursor))
        MusicFocusMode.SETTINGS -> copy(settingsCursor = transform(settingsCursor))
        MusicFocusMode.DELETE_LIST -> copy(deleteCursor = transform(deleteCursor))
    }

    companion object {
        const val CONTROL_COUNT = 7
        const val DEFAULT_CONTROL_INDEX = 3
        const val CONTROL_PLAYLIST_INDEX = 6
        const val VOLUME_COUNT = 3
        const val SETTINGS_COUNT = 3
    }
}
