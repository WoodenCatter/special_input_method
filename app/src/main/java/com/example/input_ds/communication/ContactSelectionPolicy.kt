package com.example.input_ds.communication

/**
 * Contact list row indexing for `[self] + contacts + [invite, home]`.
 */
object ContactSelectionPolicy {
    const val SELF_INDEX = 0

    fun size(contactCount: Int): Int = contactCount.coerceAtLeast(0) + 3

    fun inviteIndex(contactCount: Int): Int = contactCount.coerceAtLeast(0) + 1

    fun homeIndex(contactCount: Int): Int = contactCount.coerceAtLeast(0) + 2

    fun contactIndexForRow(rowIndex: Int, contactCount: Int): Int? =
        (rowIndex - 1).takeIf { it in 0 until contactCount.coerceAtLeast(0) }

    fun rowForContactIndex(contactIndex: Int): Int = contactIndex + 1

    fun initialIndex(contacts: List<ChatContact>): Int {
        val unread = contacts.indexOfFirst { it.unread > 0 }
        return if (unread >= 0) rowForContactIndex(unread) else SELF_INDEX
    }

    fun move(current: Int, contactCount: Int, step: Int): Int {
        val total = size(contactCount)
        return ((current.coerceIn(0, total - 1) + step) % total + total) % total
    }
}
