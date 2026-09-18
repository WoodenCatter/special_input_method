package com.example.input_ds.personalization

import org.junit.Assert.assertEquals
import org.junit.Test

class BrainApiClientProtocolTest {
    @Test
    fun missingCapabilityMetadata_allowsEveryClientProtocol() {
        assertEquals(
            ClassificationProtocol.entries.toSet(),
            BrainApiClient.supportedProtocolsFromWireNames(null)
        )
    }

    @Test
    fun explicitCapabilityMetadata_isRespected() {
        assertEquals(
            setOf(ClassificationProtocol.FOUR_CLASS),
            BrainApiClient.supportedProtocolsFromWireNames(listOf("four_class"))
        )
        assertEquals(
            setOf(ClassificationProtocol.SIX_ACTION),
            BrainApiClient.supportedProtocolsFromWireNames(listOf("six_action", "unknown"))
        )
    }
}
