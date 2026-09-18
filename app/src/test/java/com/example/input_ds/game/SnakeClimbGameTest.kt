package com.example.input_ds.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SnakeClimbGameTest {
    @Test
    fun startsWithTwoBodyCellsAndHeadOnConfiguredAnchorRow() {
        val state = SnakeClimbGame.create(random = Random(7))

        assertEquals(10, state.columns)
        assertEquals(2, state.bodyLength)
        assertEquals(state.visibleRows - 3, state.headDisplayRow)
        assertEquals(SnakeDirection.UP, state.direction)
    }

    @Test
    fun generatedSegmentsKeepApplesApartAndWallBelowAppleBudget() {
        val state = SnakeClimbGame.create(random = Random(11))
        var previousWallRow = 0

        state.walls.sortedBy { it.row }.forEach { wall ->
            val segmentApples = state.apples
                .filter { it.row > previousWallRow && it.row < wall.row }
                .sortedBy { it.row }
            assertEquals(wall.applesInSegment, segmentApples.size)
            assertTrue(segmentApples.zipWithNext().all { (a, b) -> b.row - a.row >= 2 })
            assertTrue(wall.value < wall.applesInSegment)
            assertTrue(wall.value >= wall.applesInSegment - 2)
            previousWallRow = wall.row
        }
        assertTrue(SnakeClimbGame.generatedMapIsValid(state))
    }

    @Test
    fun movingUpKeepsLengthAndAdvancesWorldRow() {
        val state = simpleState()

        val moved = SnakeClimbGame.applyAction(state, SnakeAction.TICK)

        assertEquals(state.head.row + 1, moved.head.row)
        assertEquals(2, moved.bodyLength)
        assertEquals(state.head, moved.body.first())
    }

    @Test
    fun eatingAppleAddsOneBodyCell() {
        val state = simpleState().copy(apples = setOf(SnakeCell(4, 1)))

        val moved = SnakeClimbGame.applyAction(state, SnakeAction.TICK)

        assertEquals(3, moved.bodyLength)
        assertEquals(1, moved.applesEaten)
        assertFalse(SnakeCell(4, 1) in moved.apples)
    }

    @Test
    fun wallEqualToBodyLengthEndsGame() {
        val state = simpleState().copy(
            walls = listOf(SnakeNumberWall(row = 1, value = 2, applesInSegment = 3))
        )

        val hit = SnakeClimbGame.applyAction(state, SnakeAction.TICK)

        assertTrue(hit.gameOver)
        assertEquals(state.head, hit.head)
    }

    @Test
    fun smallerWallConsumesLengthAndLetsSnakePass() {
        val state = simpleState().copy(
            body = listOf(SnakeCell(4, -1), SnakeCell(4, -2), SnakeCell(4, -3)),
            walls = listOf(SnakeNumberWall(row = 1, value = 2, applesInSegment = 3))
        )

        val passed = SnakeClimbGame.applyAction(state, SnakeAction.TICK)

        assertFalse(passed.gameOver)
        assertEquals(1, passed.bodyLength)
        assertEquals(1, passed.head.row)
        assertTrue(passed.walls.none { it.row == 1 })
    }

    @Test
    fun sideWallCostsOneCellAndTurnsUpInsteadOfKilling() {
        val state = simpleState().copy(
            head = SnakeCell(0, 0),
            body = listOf(SnakeCell(1, 0), SnakeCell(2, 0)),
            direction = SnakeDirection.LEFT
        )

        val bounced = SnakeClimbGame.applyAction(state, SnakeAction.TICK)

        assertFalse(bounced.gameOver)
        assertEquals(SnakeDirection.UP, bounced.direction)
        assertEquals(SnakeCell(0, 1), bounced.head)
        assertEquals(1, bounced.bodyLength)
    }

    @Test
    fun sideWallEndsGameWhenLastBodyCellIsLost() {
        val state = simpleState().copy(
            head = SnakeCell(0, 0),
            body = listOf(SnakeCell(1, 0)),
            direction = SnakeDirection.LEFT
        )

        val hit = SnakeClimbGame.applyAction(state, SnakeAction.TICK)

        assertTrue(hit.gameOver)
        assertEquals(0, hit.bodyLength)
        assertEquals(state.head, hit.head)
    }

    @Test
    fun biteTurnsHorizontalSnakeUpAndSecondBiteRequestsExitChoice() {
        val horizontal = simpleState().copy(direction = SnakeDirection.LEFT)

        val upward = SnakeClimbGame.applyAction(horizontal, SnakeAction.BITE)
        val prompt = SnakeClimbGame.applyAction(upward, SnakeAction.BITE)
        val ignoredLook = SnakeClimbGame.applyAction(prompt, SnakeAction.LOOK_LEFT)
        val chooseExit = SnakeClimbGame.applyAction(ignoredLook, SnakeAction.TICK)
        val confirmed = SnakeClimbGame.applyAction(chooseExit, SnakeAction.BITE)

        assertEquals(SnakeDirection.UP, upward.direction)
        assertTrue(prompt.exitConfirmation)
        assertFalse(ignoredLook.exitSelected)
        assertTrue(chooseExit.exitSelected)
        assertTrue(confirmed.exitRequested)
    }

    @Test
    fun gameOverChoicesCycleAutomaticallyAndIgnoreLooks() {
        val over = simpleState().copy(gameOver = true)

        val ignoredLook = SnakeClimbGame.applyAction(over, SnakeAction.LOOK_LEFT)
        val cycled = SnakeClimbGame.applyAction(ignoredLook, SnakeAction.TICK)
        val confirmed = SnakeClimbGame.applyAction(cycled, SnakeAction.BITE)

        assertEquals(SnakeEndChoice.RESTART, ignoredLook.gameOverSelection)
        assertEquals(SnakeEndChoice.EXIT, cycled.gameOverSelection)
        assertTrue(confirmed.exitRequested)
    }

    @Test
    fun mapKeepsGeneratingAsSnakeMovesUp() {
        var state = SnakeClimbGame.create(random = Random(23)).copy(
            body = (1..200).map { offset -> SnakeCell(5, -offset) }
        )

        repeat(160) {
            state = SnakeClimbGame.applyAction(state, SnakeAction.TICK)
            assertFalse(state.gameOver)
        }

        assertEquals(160, state.head.row)
        assertTrue(state.generatedThroughRow >= state.head.row + state.visibleRows * 2)
        assertTrue(SnakeClimbGame.nextWall(state)?.row ?: 0 > state.head.row)
    }

    private fun simpleState(): SnakeClimbState = SnakeClimbState(
        columns = 10,
        visibleRows = 13,
        head = SnakeCell(4, 0),
        body = listOf(SnakeCell(4, -1), SnakeCell(4, -2)),
        generatedThroughRow = 100,
        seed = 99
    )
}
