package com.example.input_ds.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatedHomeTest {
    @Test
    fun changesOnlyTheSelectedLightAndKeepsCurtainPosition() {
        val initial = SimulatedHome(curtainPositionPercent = 30)
        val bedside = checkNotNull(HomeAction.parse("bedside_light", mapOf("power" to "on")))
        val updated = checkNotNull(initial.apply(bedside, now = 0L))

        assertTrue(updated.bedsideLightOn)
        assertFalse(updated.mainLightOn)
        assertEquals(30, updated.curtainPositionPercent)
        val halfOpen = checkNotNull(HomeAction.parse("curtain", mapOf("position_percent" to "50")))
        assertEquals(50, updated.apply(halfOpen, now = 0L)?.curtainPositionPercent)
    }

    @Test
    fun appliesAirConditionerSettingsTogetherAndExpiresTheTimer() {
        val initial = SimulatedHome(airConditioner = AirConditionerState(powerOn = false))
        val start = checkNotNull(HomeAction.parse("air_conditioner", mapOf(
            "power" to "on",
            "temperature_c" to "24",
            "mode" to "heat",
            "swing" to "down",
            "off_timer_minutes" to "2"
        )))
        val updated = checkNotNull(initial.apply(start, now = 0L))

        assertTrue(updated.airConditioner.powerOn)
        assertEquals(24, updated.airConditioner.temperatureC)
        assertEquals(AirConditionerMode.HEAT, updated.airConditioner.mode)
        assertEquals(AirConditionerSwing.DOWN, updated.airConditioner.swing)
        assertEquals(1, updated.airConditioner.remainingMinutes(90_000L))
        assertTrue(updated.expireTimer(119_999L).airConditioner.powerOn)
        val expired = updated.expireTimer(120_000L)
        assertFalse(expired.airConditioner.powerOn)
        assertNull(expired.airConditioner.offAtMillis)
        assertEquals(24, expired.airConditioner.temperatureC)
    }

    @Test
    fun rejectsUnsupportedSettingsAndTimersAfterTheAirConditionerTurnsOff() {
        assertNull(HomeAction.parse("air_conditioner", mapOf("temperature_c" to "32")))
        assertNull(HomeAction.parse("curtain", mapOf("power" to "on")))
        val timer = checkNotNull(HomeAction.parse("air_conditioner", mapOf("off_timer_minutes" to "5")))
        val home = SimulatedHome(airConditioner = AirConditionerState(offAtMillis = 60_000L))
        assertNull(home.apply(timer, now = 60_000L))
    }
}
