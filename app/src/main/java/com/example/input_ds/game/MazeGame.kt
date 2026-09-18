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

enum class MazeExitChoice {
    CANCEL,
    CONFIRM
}

data class MazePosition(val column: Int, val row: Int)

data class MazeState(
    val protocol: MazeProtocol,
    val columns: Int,
    val rows: Int,
    val walkable: Set<MazePosition>,
    val start: MazePosition,
    val finish: MazePosition,
    val mainPath: Set<MazePosition> = walkable,
    val exitPoint: MazePosition? = null,
    val player: MazePosition = start,
    val barriers: Map<MazePosition, MazeAction> = emptyMap(),
    val completed: Boolean = false,
    val exitDialogVisible: Boolean = false,
    val exitSelection: MazeExitChoice = MazeExitChoice.CANCEL,
    val exitConfirmed: Boolean = false,
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
        var generated = generateMainPath(columns, rows, random)
        var exitCandidates = findExitCandidates(generated.walkable, columns, rows)
        for (attempt in 1 until MAIN_PATH_GENERATION_ATTEMPTS) {
            if (exitCandidates.isNotEmpty()) break
            generated = generateMainPath(columns, rows, random)
            exitCandidates = findExitCandidates(generated.walkable, columns, rows)
        }
        if (exitCandidates.isEmpty()) {
            generated = createStraightMainPath(columns, rows, random)
            exitCandidates = findExitCandidates(generated.walkable, columns, rows)
        }

        val exitPoint = exitCandidates.random(random)
        val walkable = generated.walkable + exitPoint
        val barriers = if (protocol == MazeProtocol.SIX_ACTION) {
            createBarriers(
                generated.walkable,
                generated.start,
                generated.finish,
                rows,
                random
            )
        } else {
            emptyMap()
        }
        return MazeState(
            protocol = protocol,
            columns = columns,
            rows = rows,
            walkable = walkable,
            start = generated.start,
            finish = generated.finish,
            mainPath = generated.walkable,
            exitPoint = exitPoint,
            barriers = barriers
        )
    }

    private data class GeneratedMainPath(
        val walkable: Set<MazePosition>,
        val start: MazePosition,
        val finish: MazePosition
    )

    private fun generateMainPath(
        columns: Int,
        rows: Int,
        random: Random
    ): GeneratedMainPath {
        var column = random.nextInt(columns)
        val start = MazePosition(column, rows - 1)
        val mainPath = linkedSetOf(start)
        for (row in rows - 1 downTo 1) {
            val nextColumn = random.nextInt(columns)
            for (pathColumn in min(column, nextColumn)..max(column, nextColumn)) {
                mainPath += MazePosition(pathColumn, row)
            }
            mainPath += MazePosition(nextColumn, row - 1)
            column = nextColumn
        }
        val finish = MazePosition(column, 0)
        return GeneratedMainPath(mainPath, start, finish)
    }

    private fun createStraightMainPath(
        columns: Int,
        rows: Int,
        random: Random
    ): GeneratedMainPath {
        val column = random.nextInt(columns)
        val start = MazePosition(column, rows - 1)
        val finish = MazePosition(column, 0)
        return GeneratedMainPath(
            walkable = (0 until rows).mapTo(linkedSetOf()) { row ->
                MazePosition(column, row)
            },
            start = start,
            finish = finish
        )
    }

    /** Finds a horizontal dead-end cell attached to exactly one non-finish main-path cell. */
    private fun findExitCandidates(
        mainPath: Set<MazePosition>,
        columns: Int,
        rows: Int
    ): List<MazePosition> {
        return mainPath.asSequence()
            .filter { it.row > 0 }
            .flatMap { position ->
                sequenceOf(
                    MazePosition(position.column - 1, position.row),
                    MazePosition(position.column + 1, position.row)
                )
            }
            .filter { candidate ->
                candidate.column in 0 until columns &&
                    candidate.row in 0 until rows &&
                    candidate !in mainPath
            }
            .filter { candidate ->
                orthogonalNeighbors(candidate).count { it in mainPath } == 1
            }
            .distinct()
            .toList()
    }

    private fun orthogonalNeighbors(position: MazePosition): List<MazePosition> = listOf(
        MazePosition(position.column - 1, position.row),
        MazePosition(position.column + 1, position.row),
        MazePosition(position.column, position.row - 1),
        MazePosition(position.column, position.row + 1)
    )

    fun resolveExitDialog(state: MazeState, choice: MazeExitChoice): MazeState {
        if (!state.exitDialogVisible) return state
        return confirmExitSelection(state.copy(exitSelection = choice))
    }

    private fun confirmExitSelection(state: MazeState): MazeState {
        return if (state.exitSelection == MazeExitChoice.CONFIRM) {
            state.copy(
                exitDialogVisible = false,
                exitConfirmed = true,
                message = "已确认退出迷宫"
            )
        } else {
            state.copy(
                exitDialogVisible = false,
                message = "已取消退出，继续游戏"
            )
        }
    }

    fun applyAction(state: MazeState, action: MazeAction): MazeState {
        if (state.completed || state.exitConfirmed) return state
        if (state.exitDialogVisible) {
            return when (action) {
                MazeAction.LOOK_LEFT -> state.copy(
                    exitSelection = MazeExitChoice.CANCEL,
                    message = "退出确认：已选择取消"
                )
                MazeAction.LOOK_RIGHT -> state.copy(
                    exitSelection = MazeExitChoice.CONFIRM,
                    message = "退出确认：已选择确认退出"
                )
                MazeAction.BITE -> confirmExitSelection(state)
                else -> state.copy(message = "退出确认中：请左看或右看选择，咬牙确认")
            }
        }
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
        if (target == state.exitPoint) {
            return state.copy(
                player = target,
                exitDialogVisible = true,
                exitSelection = MazeExitChoice.CANCEL,
                message = "$command，到达退出点"
            )
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

    private const val MAIN_PATH_GENERATION_ATTEMPTS = 12
}
