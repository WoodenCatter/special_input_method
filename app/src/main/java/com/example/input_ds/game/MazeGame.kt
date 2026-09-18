package com.example.input_ds.game

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class MazeProtocol {
    FOUR_CLASS,
    SIX_ACTION
}

enum class MazeAction {
    REST,
    BITE,
    LOOK_LEFT,
    LOOK_RIGHT,
    LEFT_RIGHT,
    RIGHT_LEFT
}

data class MazePosition(val column: Int, val row: Int)

data class MazeState(
    val protocol: MazeProtocol,
    val columns: Int,
    val rows: Int,
    val walkable: Set<MazePosition>,
    val start: MazePosition,
    val finish: MazePosition,
    val player: MazePosition = start,
    val barriers: Map<MazePosition, MazeAction> = emptyMap(),
    val completed: Boolean = false,
    val message: String = "等待耳电动作"
)

/** Pure game rules shared by the Compose screen and deterministic JVM tests. */
object MazeGame {
    fun create(
        protocol: MazeProtocol,
        columns: Int = 9,
        rows: Int = 7,
        random: Random = Random.Default
    ): MazeState {
        require(columns >= 2 && rows >= 2)
        var column = random.nextInt(columns)
        val start = MazePosition(column, rows - 1)
        val walkable = linkedSetOf(start)
        for (row in rows - 1 downTo 1) {
            val nextColumn = random.nextInt(columns)
            for (pathColumn in min(column, nextColumn)..max(column, nextColumn)) {
                walkable += MazePosition(pathColumn, row)
            }
            walkable += MazePosition(nextColumn, row - 1)
            column = nextColumn
        }
        val finish = MazePosition(column, 0)
        val barriers = if (protocol == MazeProtocol.SIX_ACTION) {
            createBarriers(walkable, start, finish, rows, random)
        } else {
            emptyMap()
        }
        return MazeState(
            protocol = protocol,
            columns = columns,
            rows = rows,
            walkable = walkable,
            start = start,
            finish = finish,
            barriers = barriers
        )
    }

    fun applyAction(state: MazeState, action: MazeAction): MazeState {
        if (state.completed) return state
        if (action == MazeAction.REST) return state.copy(message = "静息：保持不动")
        if (action == MazeAction.LEFT_RIGHT || action == MazeAction.RIGHT_LEFT) {
            return clearBarrier(state, action)
        }
        val movement = when (action) {
            MazeAction.BITE -> Triple(0, -1, "咬牙：向上")
            MazeAction.LOOK_LEFT -> Triple(-1, 0, "左看：向左")
            MazeAction.LOOK_RIGHT -> Triple(1, 0, "右看：向右")
            else -> return state.copy(message = "当前动作不适用于迷宫")
        }
        return move(state, movement.first, movement.second, movement.third)
    }

    private fun move(state: MazeState, dx: Int, dy: Int, command: String): MazeState {
        val target = MazePosition(state.player.column + dx, state.player.row + dy)
        if (target.column !in 0 until state.columns ||
            target.row !in 0 until state.rows ||
            target !in state.walkable
        ) {
            return state.copy(message = "$command，但前方是墙")
        }
        state.barriers[target]?.let { barrier ->
            return state.copy(message = "$command，但前方有“${barrier.displayName()}”障碍，请先执行${barrier.displayName()}")
        }
        val completed = target == state.finish
        return state.copy(
            player = target,
            completed = completed,
            message = if (completed) "$command，到达终点！" else command
        )
    }

    private fun clearBarrier(state: MazeState, action: MazeAction): MazeState {
        if (state.protocol != MazeProtocol.SIX_ACTION) {
            return state.copy(message = "四分类模式不支持${action.displayName()}")
        }
        val adjacentPriority = listOf(
            MazePosition(state.player.column, state.player.row - 1),
            MazePosition(state.player.column - 1, state.player.row),
            MazePosition(state.player.column + 1, state.player.row),
            MazePosition(state.player.column, state.player.row + 1)
        )
        val target = adjacentPriority.firstOrNull { state.barriers[it] == action }
            ?: return state.copy(message = "${action.displayName()}：相邻位置没有对应障碍")
        return state.copy(
            barriers = state.barriers - target,
            message = "${action.displayName()}：已消除对应障碍"
        )
    }

    private fun createBarriers(
        walkable: Set<MazePosition>,
        start: MazePosition,
        finish: MazePosition,
        rows: Int,
        random: Random
    ): Map<MazePosition, MazeAction> {
        val candidates = walkable.filterNot { it == start || it == finish }.shuffled(random)
        val count = min(max(2, rows / 2), candidates.size)
        val kinds = List(count) { index ->
            if (index % 2 == 0) MazeAction.LEFT_RIGHT else MazeAction.RIGHT_LEFT
        }.shuffled(random)
        return candidates.take(count).mapIndexed { index, position -> position to kinds[index] }.toMap()
    }

    fun MazeAction.displayName(): String = when (this) {
        MazeAction.REST -> "静息"
        MazeAction.BITE -> "咬牙"
        MazeAction.LOOK_LEFT -> "左看"
        MazeAction.LOOK_RIGHT -> "右看"
        MazeAction.LEFT_RIGHT -> "左右"
        MazeAction.RIGHT_LEFT -> "右左"
    }
}
