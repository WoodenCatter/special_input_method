package com.example.input_ds.tv

import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.tv.TvControlEffect
import com.example.input_ds.ui.tv.TvControlState
import com.example.input_ds.ui.tv.TvPlaybackCommand
import com.example.input_ds.ui.tv.dispatchTvSignal
import com.example.input_ds.ui.tv.resolveTvPlaybackCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlaybackControlTest {
    @Test fun leftAndRightTuneChannelsWhileBiteAlwaysOpensMenu() {
        assertEquals(TvPlaybackCommand.Tune(8), resolveTvPlaybackCommand(ControlSignal.LEFT_LOOK, 0, 9))
        assertEquals(TvPlaybackCommand.Tune(1), resolveTvPlaybackCommand(ControlSignal.RIGHT_LOOK, 0, 9))
        assertEquals(TvPlaybackCommand.Tune(0), resolveTvPlaybackCommand(ControlSignal.RIGHT_LOOK, 8, 9))
        assertEquals(TvPlaybackCommand.OpenMenu, resolveTvPlaybackCommand(ControlSignal.BITE, 0, 9))
        assertEquals(TvPlaybackCommand.OpenMenu, resolveTvPlaybackCommand(ControlSignal.BITE, 5, 9))
        assertEquals(TvPlaybackCommand.Ignore, resolveTvPlaybackCommand(ControlSignal.LEFT_RIGHT, 2, 9))
    }

    @Test fun fullscreenPlaybackRoutesLookAndBiteIdenticallyToNormalPlayback() {
        val fullscreen = TvControlState(
            currentIndex = 1,
            channelCount = 9,
            fullscreen = true,
            operationVisible = false,
            operationIndex = 1,
            operationDirection = 1
        )
        assertEquals(TvControlEffect.Tune(0), dispatchTvSignal(fullscreen, ControlSignal.LEFT_LOOK, ScanMode.AUTO))
        assertEquals(TvControlEffect.Tune(2), dispatchTvSignal(fullscreen, ControlSignal.RIGHT_LOOK, ScanMode.AUTO))
        assertEquals(TvControlEffect.OpenMenu(exitFullscreen = true), dispatchTvSignal(fullscreen, ControlSignal.BITE, ScanMode.AUTO))

        val normal = fullscreen.copy(fullscreen = false)
        assertEquals(TvControlEffect.Tune(0), dispatchTvSignal(normal, ControlSignal.LEFT_LOOK, ScanMode.AUTO))
        assertEquals(TvControlEffect.Tune(2), dispatchTvSignal(normal, ControlSignal.RIGHT_LOOK, ScanMode.AUTO))
        assertEquals(TvControlEffect.OpenMenu(exitFullscreen = false), dispatchTvSignal(normal, ControlSignal.BITE, ScanMode.AUTO))

        val menuOpen = normal.copy(operationVisible = true, operationIndex = 0)
        assertEquals(TvControlEffect.UpdateMenu(index = 2, direction = -1), dispatchTvSignal(menuOpen, ControlSignal.LEFT_LOOK, ScanMode.MANUAL))
        assertEquals(TvControlEffect.ExecuteMenu(index = 0), dispatchTvSignal(menuOpen, ControlSignal.BITE, ScanMode.AUTO))
    }
}
