package com.example.input_ds.music

import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.music.MusicFocusMode
import com.example.input_ds.ui.music.MusicScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicScanStateTest {
    @Test
    fun controlsGracePeriodAppliesBeforeDeadlineAndClearsAtBoundary() {
        val advanced = MusicScanState()
            .syncTrackCount(3)
            .advance(nowMs = 1_000L, mode = ScanMode.AUTO)

        assertEquals(3, advanced.controlCursor.previousIndex)
        assertEquals(4, advanced.controlCursor.index)

        val before = advanced.selectOnBite(nowMs = 1_399L, graceMs = 400L, mode = ScanMode.AUTO)!!
        assertEquals(3, before.targetIndex)
        assertTrue(before.compensated)

        val atDeadline = advanced.selectOnBite(nowMs = 1_400L, graceMs = 400L, mode = ScanMode.AUTO)!!
        assertEquals(4, atDeadline.targetIndex)
        assertFalse(atDeadline.compensated)

        val after = advanced.selectOnBite(nowMs = 1_401L, graceMs = 400L, mode = ScanMode.AUTO)!!
        assertEquals(4, after.targetIndex)
        assertFalse(after.compensated)
    }

    @Test
    fun playlistVolumeSettingsAndDeleteListHonorGracePeriodAndDisabledSettingsSkip() {
        // PLAYLIST
        val playlist = MusicScanState()
            .syncTrackCount(4)
            .enterPlaylist(1)
            .advance(2_000L, ScanMode.AUTO)
        val pBefore = playlist.selectOnBite(2_399L, 400L, ScanMode.AUTO)!!
        val pAt = playlist.selectOnBite(2_400L, 400L, ScanMode.AUTO)!!
        assertEquals(1, pBefore.targetIndex); assertTrue(pBefore.compensated)
        assertEquals(2, pAt.targetIndex); assertFalse(pAt.compensated)

        // VOLUME
        val volume = MusicScanState()
            .syncTrackCount(4)
            .enterVolume(0)
            .advance(3_000L, ScanMode.AUTO)
        val vBefore = volume.selectOnBite(3_399L, 400L, ScanMode.AUTO)!!
        val vAt = volume.selectOnBite(3_400L, 400L, ScanMode.AUTO)!!
        assertEquals(0, vBefore.targetIndex); assertTrue(vBefore.compensated)
        assertEquals(1, vAt.targetIndex); assertFalse(vAt.compensated)

        // SETTINGS with 0 tracks skips index 1 ("删除音乐")
        val emptySettings = MusicScanState()
            .syncTrackCount(0)
            .enterSettings(0)
            .advance(4_000L, ScanMode.AUTO)
        assertEquals(2, emptySettings.activeTargetIndex())
        val sBefore = emptySettings.selectOnBite(4_399L, 400L, ScanMode.AUTO)!!
        val sAt = emptySettings.selectOnBite(4_400L, 400L, ScanMode.AUTO)!!
        assertEquals(0, sBefore.targetIndex); assertTrue(sBefore.compensated)
        assertEquals(2, sAt.targetIndex); assertFalse(sAt.compensated)

        // DELETE_LIST
        val deleteList = MusicScanState()
            .syncTrackCount(2)
            .enterDeleteList(0)
            .advance(5_000L, ScanMode.AUTO)
        val dBefore = deleteList.selectOnBite(5_399L, 400L, ScanMode.AUTO)!!
        val dAt = deleteList.selectOnBite(5_400L, 400L, ScanMode.AUTO)!!
        assertEquals(0, dBefore.targetIndex); assertTrue(dBefore.compensated)
        assertEquals(1, dAt.targetIndex); assertFalse(dAt.compensated)
    }

    @Test
    fun touchingControlDuringPlaylistScanPreservesPlaylistCursor() {
        val scanningPlaylist = MusicScanState()
            .syncTrackCount(5)
            .enterPlaylist(0)
        assertEquals(MusicFocusMode.PLAYLIST, scanningPlaylist.focusMode)
        assertEquals(0, scanningPlaylist.playlistCursor.index)

        // Tap "暂停/播放" (index 3) on bottom bar while playlist focus is on track 0.
        val afterTouchControl = scanningPlaylist.commitControlSelection(3)
        assertEquals(MusicFocusMode.PLAYLIST, afterTouchControl.focusMode)
        assertEquals(0, afterTouchControl.playlistCursor.index)
        assertEquals(3, afterTouchControl.controlCursor.index)

        val bite = afterTouchControl.selectOnBite(100L, 400L, ScanMode.MANUAL)!!
        assertEquals(0, bite.targetIndex)
        assertFalse(bite.compensated)
    }

    @Test
    fun emptyPlaylistStaysOnControlsInsteadOfTrappingFocus() {
        val emptyEnter = MusicScanState()
            .syncTrackCount(0)
            .enterPlaylist(0)
        assertEquals(MusicFocusMode.CONTROLS, emptyEnter.focusMode)
        assertEquals(MusicScanState.CONTROL_COUNT, emptyEnter.activeCursor().size)
        assertEquals(MusicScanState.CONTROL_PLAYLIST_INDEX, emptyEnter.controlCursor.index)

        val moved = emptyEnter.look(-1, ScanMode.MANUAL)
        assertEquals(5, moved.controlCursor.index)
        assertEquals(5, moved.selectOnBite(10L, 400L, ScanMode.MANUAL)!!.targetIndex)

        // Losing the last track while already focused on playlist also returns to controls safely.
        val lostTracks = MusicScanState()
            .syncTrackCount(2)
            .enterPlaylist(1)
            .syncTrackCount(0)
        assertEquals(MusicFocusMode.CONTROLS, lostTracks.focusMode)
        assertEquals(MusicScanState.CONTROL_COUNT, lostTracks.activeCursor().size)
        assertEquals(MusicScanState.CONTROL_PLAYLIST_INDEX, lostTracks.controlCursor.index)
    }

    @Test
    fun sameDirectionLookRestartsDwellRevisionAndClearsGrace() {
        val advanced = MusicScanState()
            .syncTrackCount(3)
            .advance(1_000L, ScanMode.AUTO)
        assertEquals(1, advanced.controlCursor.direction)
        assertEquals(3, advanced.controlCursor.previousIndex)

        val sameDirLook = advanced.look(1, ScanMode.AUTO)
        assertEquals(1, sameDirLook.controlCursor.direction)
        assertEquals(4, sameDirLook.controlCursor.index)
        assertEquals(-1, sameDirLook.controlCursor.previousIndex)
        assertEquals(advanced.dwellRevision + 1, sameDirLook.dwellRevision)

        val biteAfterLook = sameDirLook.selectOnBite(1_100L, 400L, ScanMode.AUTO)!!
        assertEquals(4, biteAfterLook.targetIndex)
        assertFalse(biteAfterLook.compensated)
    }

    @Test
    fun manualModeAndPauseBoundaryResetBehavePerSpec() {
        val manual = MusicScanState()
            .syncTrackCount(3)
            .look(1, ScanMode.MANUAL)
        assertEquals(4, manual.controlCursor.index)
        val selected = manual.selectOnBite(10L, 400L, ScanMode.MANUAL)!!
        assertEquals(4, selected.targetIndex)
        assertFalse(selected.compensated)

        val reversedAndTicked = MusicScanState()
            .syncTrackCount(3)
            .look(-1, ScanMode.AUTO)
            .advance(2_000L, ScanMode.AUTO)
        assertEquals(MusicFocusMode.CONTROLS, reversedAndTicked.focusMode)
        assertEquals(-1, reversedAndTicked.controlCursor.direction)
        assertEquals(2, reversedAndTicked.controlCursor.index)

        val afterPause = reversedAndTicked.resetForPauseBoundary()
        assertEquals(2, afterPause.controlCursor.index)
        assertEquals(1, afterPause.controlCursor.direction)
        assertEquals(-1, afterPause.controlCursor.previousIndex)
        assertEquals(reversedAndTicked.dwellRevision + 1, afterPause.dwellRevision)
    }
}
