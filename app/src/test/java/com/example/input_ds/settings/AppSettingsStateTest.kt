package com.example.input_ds.settings

import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.ScanSettings
import com.example.input_ds.ui.design.AdjustOverlayKind
import com.example.input_ds.ui.settings.AppSettingItem
import com.example.input_ds.ui.settings.AppSettingsLogic
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsStateTest {
    @Test
    fun itemsMatchSpecOrderAndInitialFocusIsVolume() {
        assertEquals(
            listOf(
                AppSettingItem.BACK,
                AppSettingItem.VOLUME,
                AppSettingItem.BRIGHTNESS,
                AppSettingItem.SPEED,
                AppSettingItem.SCAN_MODE,
                AppSettingItem.DISPLAY_MODE,
                AppSettingItem.FLOATING_BALL,
                AppSettingItem.SPEED_PRACTICE
            ),
            AppSettingItem.entries
        )
        assertEquals(AppSettingItem.VOLUME, AppSettingItem.entries[AppSettingsLogic.INITIAL_INDEX])
    }

    @Test
    fun displayModeCyclesAutoLightDarkAuto() {
        assertEquals(AppUiSettings.DisplayMode.LIGHT, AppSettingsLogic.cycleDisplayMode(AppUiSettings.DisplayMode.AUTO))
        assertEquals(AppUiSettings.DisplayMode.DARK, AppSettingsLogic.cycleDisplayMode(AppUiSettings.DisplayMode.LIGHT))
        assertEquals(AppUiSettings.DisplayMode.AUTO, AppSettingsLogic.cycleDisplayMode(AppUiSettings.DisplayMode.DARK))
    }

    @Test
    fun adjustSteppersClampAndComputeMeterFractions() {
        assertEquals(0, AppSettingsLogic.stepVolumePercent(5, -1))
        assertEquals(100, AppSettingsLogic.stepVolumePercent(95, 1))
        assertEquals(10, AppSettingsLogic.stepBrightnessPercent(10, -1))
        assertEquals(100, AppSettingsLogic.stepBrightnessPercent(100, 1))
        assertEquals(ScanSettings.MIN_INTERVAL_MS, AppSettingsLogic.stepScanIntervalMs(ScanSettings.MIN_INTERVAL_MS, -1))
        assertEquals(ScanSettings.MAX_INTERVAL_MS, AppSettingsLogic.stepScanIntervalMs(ScanSettings.MAX_INTERVAL_MS, 1))
        assertEquals(1_600L, AppSettingsLogic.stepScanIntervalMs(1_500L, 1))

        assertEquals(0.6f, AppSettingsLogic.adjustFraction(AdjustOverlayKind.VOLUME, 60, 80, 1_500L), 0.001f)
        assertEquals(0.8f, AppSettingsLogic.adjustFraction(AdjustOverlayKind.BRIGHTNESS, 60, 80, 1_500L), 0.001f)
        assertEquals(0f, AppSettingsLogic.adjustFraction(AdjustOverlayKind.SPEED, 60, 80, ScanSettings.MIN_INTERVAL_MS), 0.001f)
        assertEquals(1f, AppSettingsLogic.adjustFraction(AdjustOverlayKind.SPEED, 60, 80, ScanSettings.MAX_INTERVAL_MS), 0.001f)
        assertEquals("1.5 秒", AppSettingsLogic.formatScanInterval(1_500L))
    }

    @Test
    fun valueFormattingMatchesSpecRows() {
        assertEquals("", AppSettingsLogic.valueFor(AppSettingItem.BACK, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, true))
        assertEquals("60%", AppSettingsLogic.valueFor(AppSettingItem.VOLUME, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, true))
        assertEquals("80%", AppSettingsLogic.valueFor(AppSettingItem.BRIGHTNESS, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, true))
        assertEquals("1.5 秒", AppSettingsLogic.valueFor(AppSettingItem.SPEED, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, true))
        assertEquals("自动", AppSettingsLogic.valueFor(AppSettingItem.SCAN_MODE, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, true))
        assertEquals("手动", AppSettingsLogic.valueFor(AppSettingItem.SCAN_MODE, 60, 80, 1_500L, ScanMode.MANUAL, AppUiSettings.DisplayMode.AUTO, true))
        assertEquals("夜间", AppSettingsLogic.valueFor(AppSettingItem.DISPLAY_MODE, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.DARK, true))
        assertEquals("显示", AppSettingsLogic.valueFor(AppSettingItem.FLOATING_BALL, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, true))
        assertEquals("隐藏", AppSettingsLogic.valueFor(AppSettingItem.FLOATING_BALL, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, false))
        assertEquals("12 题", AppSettingsLogic.valueFor(AppSettingItem.SPEED_PRACTICE, 60, 80, 1_500L, ScanMode.AUTO, AppUiSettings.DisplayMode.AUTO, true))
    }

    @Test
    fun streamVolumeConversionAndSteppingStayConsistentOnHardwareSteps() {
        // 15-step Android STREAM_MUSIC stream: 9/15 = 60%, +10% targets 10.5 -> 11/15 = 73%.
        assertEquals(60, AppSettingsLogic.streamLevelToPercent(9, 15))
        val upFrom9 = AppSettingsLogic.nextStreamVolumeLevel(9, 15, +1)
        assertEquals(11, upFrom9)
        assertEquals(73, AppSettingsLogic.streamLevelToPercent(upFrom9, 15))
        val downFrom11 = AppSettingsLogic.nextStreamVolumeLevel(11, 15, -1)
        assertEquals(9, downFrom11)

        // Coarse 5-step stream: always advances/retreats at least 1 hardware step inside bounds.
        assertEquals(4, AppSettingsLogic.nextStreamVolumeLevel(3, 5, +1))
        assertEquals(2, AppSettingsLogic.nextStreamVolumeLevel(3, 5, -1))
        assertEquals(1, AppSettingsLogic.nextStreamVolumeLevel(0, 5, +1))
        assertEquals(0, AppSettingsLogic.nextStreamVolumeLevel(0, 5, -1))
        assertEquals(5, AppSettingsLogic.nextStreamVolumeLevel(5, 5, +1))
        assertEquals(4, AppSettingsLogic.nextStreamVolumeLevel(5, 5, -1))
    }
}
