package com.example.input_ds.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionSettingsTest {
    @Test
    fun defaults_matchSupportedCollectionExperience() {
        val settings = CollectionSettings()
        assertEquals(20, settings.rounds)
        assertEquals(50, settings.trainingEpochs)
        assertEquals(1f, settings.actionSeconds)
        assertEquals(setOf("csanet"), settings.selectedModels)
    }

    @Test
    fun trainingEpochs_acceptsServerSupportedRange() {
        assertEquals(1, CollectionSettings(trainingEpochs = 1).trainingEpochs)
        assertEquals(50, CollectionSettings(trainingEpochs = 50).trainingEpochs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun trainingEpochs_rejectsValuesAboveServerLimit() {
        CollectionSettings(trainingEpochs = 51)
    }

    @Test
    fun protocols_keepExactServingClassOrder() {
        assertEquals(
            listOf("rest", "jaw", "look_left", "look_right"),
            ClassificationProtocol.FOUR_CLASS.labelNames
        )
        assertEquals(
            listOf("rest", "look_left", "look_right", "jaw", "look_left_right", "look_right_left"),
            CollectionSettings(protocol = ClassificationProtocol.SIX_ACTION).labels
        )
    }

    @Test
    fun legacyLabelInferenceDoesNotGuessAnUnknownProtocol() {
        assertEquals(
            ClassificationProtocol.FOUR_CLASS,
            ClassificationProtocol.inferLegacy(listOf("rest", "jaw", "look_left", "look_right"))
        )
        assertNull(ClassificationProtocol.inferLegacy(listOf("rest", "left", "right")))
        assertEquals(
            ClassificationProtocol.SIX_ACTION,
            ClassificationProtocol.inferLegacy(
                listOf("rest", "look_left", "look_right", "jaw", "look_left_right", "look_right_left")
            )
        )
    }
}
