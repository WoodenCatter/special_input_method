package com.example.input_ds.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MazeGameTest {
    @Test
    fun generatedMazeHasReachableStartAndFinish() {
        val state = MazeGame.create(MazeProtocol.FOUR_CLASS, random = Random(7))

        assertTrue(state.start in state.walkable)
        assertTrue(state.finish in state.walkable)
        assertTrue(isReachable(state))
        assertTrue(state.exitPoint != null)
        assertTrue(state.exitPoint in state.walkable)
        assertFalse(state.exitPoint in state.mainPath)
        assertTrue(isReachable(state, state.exitPoint!!))
    }

    @Test
    fun fourClassMovesLeftRightAndUpButNotThroughWalls() {
        val state = simpleState(MazeProtocol.FOUR_CLASS)

        val left = MazeGame.applyAction(state, MazeAction.LOOK_LEFT)
        assertEquals(MazePosition(0, 2), left.player)

        val wall = MazeGame.applyAction(left, MazeAction.LOOK_LEFT)
        assertEquals(left.player, wall.player)
        assertTrue(wall.message.contains("墙"))

        val right = MazeGame.applyAction(left, MazeAction.LOOK_RIGHT)
        val up = MazeGame.applyAction(right, MazeAction.BITE)
        assertEquals(MazePosition(1, 1), up.player)
    }

    @Test
    fun reachingFinishCompletesMaze() {
        val state = simpleState(MazeProtocol.FOUR_CLASS).copy(player = MazePosition(1, 1))

        val completed = MazeGame.applyAction(state, MazeAction.BITE)

        assertEquals(state.finish, completed.player)
        assertTrue(completed.completed)
        assertTrue(completed.message.contains("终点"))
    }

    @Test
    fun matchingSequenceClearsAdjacentBarrierOnlyOnce() {
        val barrier = MazePosition(1, 1)
        val state = simpleState(MazeProtocol.SIX_ACTION).copy(
            barriers = mapOf(barrier to MazeAction.LEFT_RIGHT)
        )

        val blocked = MazeGame.applyAction(state, MazeAction.BITE)
        assertEquals(state.player, blocked.player)

        val mismatch = MazeGame.applyAction(state, MazeAction.RIGHT_LEFT)
        assertTrue(barrier in mismatch.barriers)

        val cleared = MazeGame.applyAction(state, MazeAction.LEFT_RIGHT)
        assertFalse(barrier in cleared.barriers)
        assertTrue(cleared.message.contains("已消除"))
    }

    @Test
    fun enteringExitPointDefaultsToCancelAndCanReturnToMainPath() {
        val state = stateWithExitPoint()

        val atExit = MazeGame.applyAction(state, MazeAction.LOOK_LEFT)
        assertEquals(state.exitPoint, atExit.player)
        assertTrue(atExit.exitDialogVisible)
        assertEquals(MazeExitChoice.CANCEL, atExit.exitSelection)

        val cancelled = MazeGame.applyAction(atExit, MazeAction.BITE)
        assertFalse(cancelled.exitDialogVisible)
        assertFalse(cancelled.exitConfirmed)
        assertEquals(atExit.player, cancelled.player)

        val returned = MazeGame.applyAction(cancelled, MazeAction.LOOK_RIGHT)
        assertEquals(state.start, returned.player)
    }

    @Test
    fun rightLookThenBiteConfirmsMazeExit() {
        val atExit = MazeGame.applyAction(stateWithExitPoint(), MazeAction.LOOK_LEFT)

        val selected = MazeGame.applyAction(atExit, MazeAction.LOOK_RIGHT)
        assertEquals(MazeExitChoice.CONFIRM, selected.exitSelection)

        val confirmed = MazeGame.applyAction(selected, MazeAction.BITE)
        assertFalse(confirmed.exitDialogVisible)
        assertTrue(confirmed.exitConfirmed)
    }

    @Test
    fun touchDecisionResolvesExitDialog() {
        val atExit = MazeGame.applyAction(stateWithExitPoint(), MazeAction.LOOK_LEFT)

        val cancelled = MazeGame.resolveExitDialog(atExit, MazeExitChoice.CANCEL)
        assertFalse(cancelled.exitConfirmed)

        val confirmed = MazeGame.resolveExitDialog(atExit, MazeExitChoice.CONFIRM)
        assertTrue(confirmed.exitConfirmed)
    }

    private fun simpleState(protocol: MazeProtocol): MazeState {
        val walkable = setOf(
            MazePosition(0, 2),
            MazePosition(1, 2),
            MazePosition(1, 1),
            MazePosition(1, 0)
        )
        return MazeState(
            protocol = protocol,
            columns = 3,
            rows = 3,
            walkable = walkable,
            start = MazePosition(1, 2),
            finish = MazePosition(1, 0)
        )
    }

    private fun stateWithExitPoint(): MazeState {
        val mainPath = setOf(
            MazePosition(1, 2),
            MazePosition(1, 1),
            MazePosition(1, 0)
        )
        val exitPoint = MazePosition(0, 2)
        return MazeState(
            protocol = MazeProtocol.FOUR_CLASS,
            columns = 3,
            rows = 3,
            walkable = mainPath + exitPoint,
            start = MazePosition(1, 2),
            finish = MazePosition(1, 0),
            mainPath = mainPath,
            exitPoint = exitPoint
        )
    }

    private fun isReachable(state: MazeState, target: MazePosition = state.finish): Boolean {
        val queue = ArrayDeque<MazePosition>()
        val seen = mutableSetOf<MazePosition>()
        queue.add(state.start)
        seen += state.start
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == target) return true
            listOf(
                MazePosition(current.column - 1, current.row),
                MazePosition(current.column + 1, current.row),
                MazePosition(current.column, current.row - 1)
            ).filter { it in state.walkable && seen.add(it) }.forEach(queue::addLast)
        }
        return false
    }
}
