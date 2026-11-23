package com.example.input_ds.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Indices refer to enabled items in display order. Times use a monotonic clock. */
data class ScanCursor(
    val size: Int,
    val index: Int = 0,
    val direction: Int = 1,
    val previousIndex: Int = -1,
    val advancedAtMs: Long = 0,
    val ticks: Int = 0
) {
    fun reset(newSize: Int = size, initial: Int = index) =
        ScanCursor(newSize, initial.coerceIn(0, (newSize - 1).coerceAtLeast(0)))

    private fun move(step: Int) = if (size > 0) (index + step).mod(size) else 0

    fun look(step: Int, mode: ScanMode, hold: Boolean = false): ScanCursor {
        require(step == 1 || step == -1)
        return copy(
            index = if (mode == ScanMode.MANUAL) move(step) else index,
            direction = if (hold && mode == ScanMode.AUTO) direction else step,
            previousIndex = -1
        )
    }

    fun advance(now: Long, mode: ScanMode): ScanCursor =
        if (mode == ScanMode.MANUAL || size == 0) this
        else copy(index = move(direction), previousIndex = index, advancedAtMs = now, ticks = ticks + 1)

    fun selected(now: Long, graceMs: Long, mode: ScanMode): Int =
        if (mode == ScanMode.AUTO && previousIndex >= 0 && now - advancedAtMs in 0 until graceMs)
            previousIndex else index

    fun dwell(interval: Long, confirmation: Boolean) =
        if (confirmation && ticks == 0) interval + interval / 2 else interval

    fun timedOut(confirmation: Boolean) = confirmation && size > 0 && ticks >= 3 * size
}

/** Each iteration schedules the next deadline, including after a failed callback. */
internal suspend fun runScanTimer(
    interval: () -> Long,
    onFailure: (Exception) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) },
    tick: () -> Unit
) {
    var duration = interval()
    while (currentCoroutineContext().isActive) {
        wait(duration)
        try {
            tick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
        } finally {
            duration = interval()
        }
    }
}
