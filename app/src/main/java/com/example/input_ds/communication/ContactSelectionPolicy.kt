package com.example.input_ds.communication

/** Contact count plus the reachable Back row. */
object ContactSelectionPolicy {
    fun move(current: Int, contactCount: Int, step: Int): Int {
        val size = contactCount.coerceAtLeast(0) + 1
        return ((current.coerceIn(0, size - 1) + step) % size + size) % size
    }
}
