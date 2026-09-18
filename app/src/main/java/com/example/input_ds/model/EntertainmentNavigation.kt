package com.example.input_ds.model

private fun wrapIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0
    return ((index % size) + size) % size
}

enum class EntertainmentAction {
    BACK,
    MUSIC,
    TV
}

data class EntertainmentSelectionState(val selectedIndex: Int = 1) {
    val selectedAction: EntertainmentAction
        get() = EntertainmentAction.entries[wrapIndex(selectedIndex, EntertainmentAction.entries.size)]

    fun moveLeft() = copy(selectedIndex = wrapIndex(selectedIndex - 1, EntertainmentAction.entries.size))
    fun moveRight() = copy(selectedIndex = wrapIndex(selectedIndex + 1, EntertainmentAction.entries.size))
    fun select(action: EntertainmentAction) = copy(selectedIndex = EntertainmentAction.entries.indexOf(action))
}

enum class MusicSelectionMode {
    CONTROLS,
    PLAYLIST
}

enum class MusicControlAction {
    BACK,
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
    SELECT
}

sealed interface MusicSelectionEffect {
    data object Back : MusicSelectionEffect
    data object Previous : MusicSelectionEffect
    data object TogglePlayback : MusicSelectionEffect
    data object Next : MusicSelectionEffect
    data class PlayTrack(val index: Int) : MusicSelectionEffect
}

data class MusicSelectionResult(
    val state: MusicSelectionState,
    val effect: MusicSelectionEffect? = null
)

data class MusicSelectionState(
    val mode: MusicSelectionMode = MusicSelectionMode.CONTROLS,
    val selectedControlIndex: Int = MusicControlAction.entries.indexOf(MusicControlAction.PLAY_PAUSE),
    val highlightedTrackIndex: Int = 0
) {
    val selectedControl: MusicControlAction
        get() = MusicControlAction.entries[wrapIndex(selectedControlIndex, MusicControlAction.entries.size)]

    fun moveLeft(trackCount: Int): MusicSelectionState = when (mode) {
        MusicSelectionMode.CONTROLS -> copy(
            selectedControlIndex = wrapIndex(selectedControlIndex - 1, MusicControlAction.entries.size)
        )
        MusicSelectionMode.PLAYLIST -> copy(
            highlightedTrackIndex = wrapIndex(highlightedTrackIndex - 1, trackCount)
        )
    }

    fun moveRight(trackCount: Int): MusicSelectionState = when (mode) {
        MusicSelectionMode.CONTROLS -> copy(
            selectedControlIndex = wrapIndex(selectedControlIndex + 1, MusicControlAction.entries.size)
        )
        MusicSelectionMode.PLAYLIST -> copy(
            highlightedTrackIndex = wrapIndex(highlightedTrackIndex + 1, trackCount)
        )
    }

    fun selectControl(action: MusicControlAction): MusicSelectionState = copy(
        mode = MusicSelectionMode.CONTROLS,
        selectedControlIndex = MusicControlAction.entries.indexOf(action)
    )

    fun enterPlaylist(currentTrackIndex: Int, trackCount: Int): MusicSelectionState = copy(
        mode = MusicSelectionMode.PLAYLIST,
        highlightedTrackIndex = wrapIndex(currentTrackIndex, trackCount)
    )

    fun selectTrack(index: Int, trackCount: Int): MusicSelectionState = copy(
        mode = MusicSelectionMode.CONTROLS,
        highlightedTrackIndex = wrapIndex(index, trackCount),
        selectedControlIndex = MusicControlAction.entries.indexOf(MusicControlAction.SELECT)
    )

    fun confirm(trackCount: Int, currentTrackIndex: Int): MusicSelectionResult {
        if (mode == MusicSelectionMode.PLAYLIST) {
            val index = wrapIndex(highlightedTrackIndex, trackCount)
            return MusicSelectionResult(
                state = selectTrack(index, trackCount),
                effect = MusicSelectionEffect.PlayTrack(index)
            )
        }

        return when (selectedControl) {
            MusicControlAction.BACK -> MusicSelectionResult(this, MusicSelectionEffect.Back)
            MusicControlAction.PREVIOUS -> MusicSelectionResult(this, MusicSelectionEffect.Previous)
            MusicControlAction.PLAY_PAUSE -> MusicSelectionResult(this, MusicSelectionEffect.TogglePlayback)
            MusicControlAction.NEXT -> MusicSelectionResult(this, MusicSelectionEffect.Next)
            MusicControlAction.SELECT -> MusicSelectionResult(enterPlaylist(currentTrackIndex, trackCount))
        }
    }
}

sealed interface TvSelectionEffect {
    data object Back : TvSelectionEffect
    data class PlayChannel(val index: Int) : TvSelectionEffect
}

data class TvSelectionState(val selectedIndex: Int = 1) {
    fun moveLeft(channelCount: Int) = copy(selectedIndex = wrapIndex(selectedIndex - 1, channelCount + 1))
    fun moveRight(channelCount: Int) = copy(selectedIndex = wrapIndex(selectedIndex + 1, channelCount + 1))
    fun selectBack() = copy(selectedIndex = 0)
    fun selectChannel(index: Int, channelCount: Int) = copy(selectedIndex = wrapIndex(index, channelCount) + 1)

    fun confirm(channelCount: Int): TvSelectionEffect = if (selectedIndex == 0) {
        TvSelectionEffect.Back
    } else {
        TvSelectionEffect.PlayChannel(wrapIndex(selectedIndex - 1, channelCount))
    }
}
