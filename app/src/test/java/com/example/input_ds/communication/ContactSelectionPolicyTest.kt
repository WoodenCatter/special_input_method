package com.example.input_ds.communication

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactSelectionPolicyTest {
    @Test fun wrapsAcrossContactsAndBack() {
        assertEquals(3, ContactSelectionPolicy.move(0, 3, -1))
        assertEquals(0, ContactSelectionPolicy.move(3, 3, 1))
        assertEquals(2, ContactSelectionPolicy.move(1, 3, 1))
    }
    @Test fun emptyListHasReachableBack() {
        assertEquals(0, ContactSelectionPolicy.move(0, 0, 1))
    }
}
