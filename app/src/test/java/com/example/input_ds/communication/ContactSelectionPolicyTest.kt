package com.example.input_ds.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactSelectionPolicyTest {
    @Test fun wrapsAcrossSelfContactsInviteAndHome() {
        // 3 contacts -> 6 rows: [0:self, 1:c0, 2:c1, 3:c2, 4:invite, 5:home]
        assertEquals(6, ContactSelectionPolicy.size(3))
        assertEquals(4, ContactSelectionPolicy.inviteIndex(3))
        assertEquals(5, ContactSelectionPolicy.homeIndex(3))
        assertEquals(5, ContactSelectionPolicy.move(0, 3, -1))
        assertEquals(0, ContactSelectionPolicy.move(5, 3, 1))
        assertEquals(2, ContactSelectionPolicy.move(1, 3, 1))
        assertNull(ContactSelectionPolicy.contactIndexForRow(0, 3))
        assertEquals(0, ContactSelectionPolicy.contactIndexForRow(1, 3))
        assertEquals(2, ContactSelectionPolicy.contactIndexForRow(3, 3))
        assertNull(ContactSelectionPolicy.contactIndexForRow(4, 3))
        assertNull(ContactSelectionPolicy.contactIndexForRow(5, 3))
    }

    @Test fun emptyListStillHasSelfInviteAndHome() {
        // 0 contacts -> 3 rows: [0:self, 1:invite, 2:home]
        assertEquals(3, ContactSelectionPolicy.size(0))
        assertEquals(1, ContactSelectionPolicy.move(0, 0, 1))
        assertEquals(2, ContactSelectionPolicy.move(1, 0, 1))
        assertEquals(0, ContactSelectionPolicy.move(2, 0, 1))
        assertEquals(2, ContactSelectionPolicy.move(0, 0, -1))
    }

    @Test fun initialFocusSelectsFirstUnreadOrFallsBackToSelf() {
        val readOnly = listOf(
            ChatContact("p1", "妈妈", "c1", online = true, unread = 0, lastMessage = null),
            ChatContact("p2", "小雨", "c2", online = true, unread = 0, lastMessage = null)
        )
        assertEquals(ContactSelectionPolicy.SELF_INDEX, ContactSelectionPolicy.initialIndex(readOnly))

        val withUnread = listOf(
            ChatContact("p1", "妈妈", "c1", online = true, unread = 0, lastMessage = null),
            ChatContact("p2", "小雨", "c2", online = true, unread = 3, lastMessage = "你好")
        )
        assertEquals(2, ContactSelectionPolicy.initialIndex(withUnread))
    }
}
