package com.example.input_ds.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

enum class SnakeDirection {
    UP,
    LEFT,
    RIGHT
}

enum class SnakeEndChoice {
    RESTART,
    EXIT
}

enum class SnakeAction {
    TICK,
    LOOK_LEFT,
    LOOK_RIGHT,
    BITE,
    CANCEL_EXIT,
    CONFIRM_EXIT,
    RESTART
}

data class SnakeCell(val column: Int, val row: Int)

data class SnakeNumberWall(
    val row: Int,
    val value: Int,
    val applesInSegment: Int
)

data class SnakeClimbState(
    val columns: Int = 10,
    val visibleRows: Int = 13,
    val head: SnakeCell,
    /** Body cells are ordered from the neck to the tail; the head is excluded. */
    val body: List<SnakeCell>,
    val direction: SnakeDirection = SnakeDirection.UP,
    val apples: Set<SnakeCell> = emptySet(),
    val walls: List<SnakeNumberWall> = emptyList(),
    val generatedThroughRow: Int = 0,
    val nextSegmentIndex: Int = 0,
    val seed: Int,
    val applesEaten: Int = 0,
    val step: Long = 0L,
    val gameOver: Boolean = false,
    val gameOverSelection: SnakeEndChoice = SnakeEndChoice.RESTART,
    val exitConfirmation: Boolean = false,
    val exitSelected: Boolean = false,
    val exitRequested: Boolean = false,
    val message: String = "咬牙向上，左看向左，右看向右"
) {
    val bodyLength: Int get() = body.size
    val headDisplayRow: Int get() = visibleRows - 3
}

/** Pure rules for the endless upward snake rehabilitation game. */
object SnakeClimbGame {
    private const val MIN_WALL_DISTANCE = 14
    private const val WALL_DISTANCE_VARIATION = 5
    private const val MIN_APPLES_PER_SEGMENT = 4
    private const val APPLE_COUNT_VARIATION = 3

    fun create(
        columns: Int = 10,
        visibleRows: Int = 13,
        random: Random = Random.Default
    ): SnakeClimbState {
        require(columns >= 6)
        require(visibleRows >= 9)
        val seed = random.nextInt()
        val startColumn = random.nextInt(1, columns - 1)
        val head = SnakeCell(startColumn, 0)
        val base = SnakeClimbState(
            columns = columns,
            visibleRows = visibleRows,
            head = head,
            body = listOf(
                SnakeCell(startColumn, -1),
                SnakeCell(startColumn, -2)
            ),
            seed = seed
        )
        return ensureGenerated(base)
    }

    fun applyAction(state: SnakeClimbState, action: SnakeAction): SnakeClimbState {
        if (state.exitRequested) return state
        if (state.gameOver) return handleGameOverAction(state, action)
        if (state.exitConfirmation) return handleExitAction(state, action)

        return when (action) {
            SnakeAction.TICK -> tick(state)
            SnakeAction.LOOK_LEFT -> changeHorizontalDirection(state, SnakeDirection.LEFT)
            SnakeAction.LOOK_RIGHT -> changeHorizontalDirection(state, SnakeDirection.RIGHT)
            SnakeAction.BITE -> if (state.direction == SnakeDirection.UP) {
                state.copy(
                    exitConfirmation = true,
                    exitSelected = false,
                    message = "是否退出游戏？右看继续，左看退出，咬牙确认"
                )
            } else {
                state.copy(direction = SnakeDirection.UP, message = "咬牙：转向上方")
            }
            SnakeAction.CANCEL_EXIT -> state
            SnakeAction.CONFIRM_EXIT -> state.copy(exitRequested = true)
            SnakeAction.RESTART -> restart(state)
        }
    }

    fun nextWall(state: SnakeClimbState): SnakeNumberWall? =
        state.walls.filter { it.row > state.head.row }.minByOrNull { it.row }

    fun remainingApplesBeforeNextWall(state: SnakeClimbState): Int {
        val wall = nextWall(state) ?: return 0
        return state.apples.count { it.row >= state.head.row && it.row < wall.row }
    }

    fun rowsUntilNextWall(state: SnakeClimbState): Int =
        nextWall(state)?.let { max(0, it.row - state.head.row) } ?: 0

    private fun changeHorizontalDirection(
        state: SnakeClimbState,
        requested: SnakeDirection
    ): SnakeClimbState {
        val opposite = (state.direction == SnakeDirection.LEFT && requested == SnakeDirection.RIGHT) ||
            (state.direction == SnakeDirection.RIGHT && requested == SnakeDirection.LEFT)
        return if (opposite && state.body.isNotEmpty()) {
            state.copy(message = "不能直接掉头，请先咬牙向上")
        } else {
            state.copy(
                direction = requested,
                message = if (requested == SnakeDirection.LEFT) "左看：转向左方" else "右看：转向右方"
            )
        }
    }

    private fun tick(state: SnakeClimbState): SnakeClimbState {
        val target = when (state.direction) {
            SnakeDirection.UP -> state.head.copy(row = state.head.row + 1)
            SnakeDirection.LEFT -> state.head.copy(column = state.head.column - 1)
            SnakeDirection.RIGHT -> state.head.copy(column = state.head.column + 1)
        }

        if (target.column !in 0 until state.columns) {
            val shortened = if (state.body.isEmpty()) state.body else state.body.dropLast(1)
            val side = if (target.column < 0) "左侧" else "右侧"
            if (shortened.isEmpty()) {
                return state.copy(
                    body = emptyList(),
                    step = state.step + 1,
                    gameOver = true,
                    gameOverSelection = SnakeEndChoice.RESTART,
                    message = "撞到${side}墙，身体长度降到0，游戏结束"
                )
            }
            return moveTo(
                state = state.copy(body = shortened, direction = SnakeDirection.UP),
                target = state.head.copy(row = state.head.row + 1),
                prefix = "撞到${side}墙，身体长度减1并转向上方。"
            )
        }
        return moveTo(state, target, "")
    }

    private fun moveTo(state: SnakeClimbState, target: SnakeCell, prefix: String): SnakeClimbState {
        var targetLength = state.bodyLength
        var walls = state.walls
        val crossedWall = if (target.row > state.head.row) {
            walls.firstOrNull { it.row == target.row }
        } else {
            null
        }
        if (crossedWall != null) {
            if (crossedWall.value >= targetLength) {
                return state.copy(
                    step = state.step + 1,
                    gameOver = true,
                    gameOverSelection = SnakeEndChoice.RESTART,
                    message = "数字墙为${crossedWall.value}，身体只有$targetLength 格，撞墙了"
                )
            }
            targetLength -= crossedWall.value
            walls = walls - crossedWall
        }

        val ateApple = target in state.apples
        if (ateApple) targetLength += 1
        val nextBody = (listOf(state.head) + state.body).take(targetLength)
        val wallMessage = crossedWall?.let { "通过数字墙${it.value}，长度减${it.value}。" }.orEmpty()
        val appleMessage = if (ateApple) "吃到苹果，长度加1。" else ""
        val moved = state.copy(
            head = target,
            body = nextBody,
            apples = if (ateApple) state.apples - target else state.apples,
            walls = walls,
            applesEaten = state.applesEaten + if (ateApple) 1 else 0,
            step = state.step + 1,
            message = (prefix + wallMessage + appleMessage).ifBlank {
                when (state.direction) {
                    SnakeDirection.UP -> "继续向上"
                    SnakeDirection.LEFT -> "继续向左"
                    SnakeDirection.RIGHT -> "继续向右"
                }
            }
        )
        return ensureGenerated(moved).pruneBehind()
    }

    private fun handleExitAction(state: SnakeClimbState, action: SnakeAction): SnakeClimbState =
        when (action) {
            SnakeAction.TICK -> state.copy(exitSelected = !state.exitSelected)
            SnakeAction.LOOK_LEFT, SnakeAction.LOOK_RIGHT -> state
            SnakeAction.BITE -> if (state.exitSelected) {
                state.copy(exitRequested = true)
            } else {
                state.copy(exitConfirmation = false, message = "继续游戏")
            }
            SnakeAction.CONFIRM_EXIT -> state.copy(exitRequested = true)
            SnakeAction.CANCEL_EXIT -> state.copy(exitConfirmation = false, message = "继续游戏")
            else -> state
        }

    private fun handleGameOverAction(state: SnakeClimbState, action: SnakeAction): SnakeClimbState =
        when (action) {
            SnakeAction.TICK -> state.copy(
                gameOverSelection = if (state.gameOverSelection == SnakeEndChoice.RESTART) {
                    SnakeEndChoice.EXIT
                } else {
                    SnakeEndChoice.RESTART
                }
            )
            SnakeAction.LOOK_LEFT, SnakeAction.LOOK_RIGHT -> state
            SnakeAction.BITE -> if (state.gameOverSelection == SnakeEndChoice.EXIT) {
                state.copy(exitRequested = true)
            } else {
                restart(state)
            }
            SnakeAction.CONFIRM_EXIT -> state.copy(exitRequested = true)
            SnakeAction.RESTART -> restart(state)
            else -> state
        }

    private fun restart(state: SnakeClimbState): SnakeClimbState {
        val nextSeed = state.seed * 31 + state.step.toInt() * 17 + 0x51A7
        return create(
            columns = state.columns,
            visibleRows = state.visibleRows,
            random = Random(nextSeed)
        )
    }

    private fun ensureGenerated(input: SnakeClimbState): SnakeClimbState {
        var apples = input.apples
        var walls = input.walls
        var through = input.generatedThroughRow
        var segmentIndex = input.nextSegmentIndex
        val requiredRow = input.head.row + input.visibleRows * 2

        while (through < requiredRow) {
            val segmentRandom = Random(input.seed * 31 + segmentIndex * 9_973)
            val distance = MIN_WALL_DISTANCE + segmentRandom.nextInt(WALL_DISTANCE_VARIATION)
            val appleCount = MIN_APPLES_PER_SEGMENT + segmentRandom.nextInt(APPLE_COUNT_VARIATION)
            val wallRow = through + distance
            val possibleOffsets = (2 until distance - 1 step 2).toList().shuffled(segmentRandom)
            val chosenRows = possibleOffsets.take(appleCount).sorted()
            chosenRows.forEach { offset ->
                apples = apples + SnakeCell(
                    column = segmentRandom.nextInt(input.columns),
                    row = through + offset
                )
            }
            val wallValue = appleCount - 1 - segmentRandom.nextInt(2)
            walls = walls + SnakeNumberWall(
                row = wallRow,
                value = wallValue,
                applesInSegment = appleCount
            )
            through = wallRow
            segmentIndex += 1
        }
        return input.copy(
            apples = apples,
            walls = walls.sortedBy { it.row },
            generatedThroughRow = through,
            nextSegmentIndex = segmentIndex
        )
    }

    private fun SnakeClimbState.pruneBehind(): SnakeClimbState {
        val oldestVisibleRow = head.row - visibleRows
        return copy(
            apples = apples.filterTo(linkedSetOf()) { it.row >= oldestVisibleRow },
            walls = walls.filter { it.row >= oldestVisibleRow }
        )
    }

    fun generatedMapIsValid(state: SnakeClimbState): Boolean {
        val sortedApples = state.apples.groupBy { apple ->
            state.walls.firstOrNull { it.row > apple.row }?.row
        }
        val spacingIsValid = sortedApples.values.all { segment ->
            segment.map { it.row }.distinct().sorted().zipWithNext().all { (a, b) -> abs(a - b) >= 2 }
        }
        return spacingIsValid && state.walls.all { wall ->
            wall.value < wall.applesInSegment && wall.value >= max(1, wall.applesInSegment - 2)
        }
    }
}
