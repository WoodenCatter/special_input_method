package com.example.input_ds.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntertainmentNavigationTest {
    @Test
    fun entertainmentCyclesThroughBackMusicAndTv() {
        val initial = EntertainmentSelectionState()

        assertEquals(EntertainmentAction.MUSIC, initial.selectedAction)
        assertEquals(EntertainmentAction.BACK, initial.moveLeft().selectedAction)
        assertEquals(EntertainmentAction.TV, initial.moveRight().selectedAction)
        assertEquals(EntertainmentAction.TV, initial.moveLeft().moveLeft().selectedAction)
    }

    @Test
    fun musicSelectEntersPlaylistAndPlayingTrackReturnsToControls() {
        val selectFocused = MusicSelectionState().selectControl(MusicControlAction.SELECT)
        val enterResult = selectFocused.confirm(trackCount = 3, currentTrackIndex = 1)

        assertNull(enterResult.effect)
        assertEquals(MusicSelectionMode.PLAYLIST, enterResult.state.mode)
        assertEquals(1, enterResult.state.highlightedTrackIndex)

        val highlighted = enterResult.state.moveRight(trackCount = 3)
        assertEquals(2, highlighted.highlightedTrackIndex)

        val playResult = highlighted.confirm(trackCount = 3, currentTrackIndex = 1)
        assertEquals(MusicSelectionEffect.PlayTrack(2), playResult.effect)
        assertEquals(MusicSelectionMode.CONTROLS, playResult.state.mode)
        assertEquals(MusicControlAction.SELECT, playResult.state.selectedControl)
    }

    @Test
    fun musicControlsWrapAndMapToExpectedEffects() {
        val back = MusicSelectionState().selectControl(MusicControlAction.BACK)
        assertEquals(MusicControlAction.SELECT, back.moveLeft(3).selectedControl)
        assertEquals(MusicControlAction.PREVIOUS, back.moveRight(3).selectedControl)
        assertEquals(MusicSelectionEffect.Back, back.confirm(3, 0).effect)
        assertEquals(
            MusicSelectionEffect.TogglePlayback,
            MusicSelectionState().confirm(3, 0).effect
        )
    }

    @Test
    fun televisionCyclesBackAndFiveChannels() {
        val initial = TvSelectionState()

        assertEquals(TvSelectionEffect.PlayChannel(0), initial.confirm(channelCount = 5))
        assertEquals(TvSelectionEffect.Back, initial.moveLeft(5).confirm(channelCount = 5))
        assertEquals(
            TvSelectionEffect.PlayChannel(4),
            initial.moveLeft(5).moveLeft(5).confirm(channelCount = 5)
        )
    }
}
