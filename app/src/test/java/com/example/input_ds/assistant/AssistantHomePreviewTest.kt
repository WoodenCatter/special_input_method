package com.example.input_ds.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantHomePreviewTest {
    @Test fun confirmationLabelsAlwaysPutCancelFirst() {
        val home = SimulatedHome()
        val options = listOf(
            HomeAction(SimulatedDevice.MAIN_LIGHT, powerOn = true),
            HomeAction(SimulatedDevice.BEDSIDE_LIGHT, powerOn = true)
        )
        assertEquals(
            listOf("取消", "打开大灯", "打开床头灯"),
            AssistantHomePreview.confirmationLabels(options, home)
        )
    }

    @Test fun previewRowsHighlightOnlyChangedDeviceRows() {
        val home = SimulatedHome() // AC COOL 26°C
        val coolDown = HomeAction(SimulatedDevice.AIR_CONDITIONER, temperatureC = 25)
        val preview = AssistantHomePreview.previewRows(home, coolDown, nowMillis = 1_000L)

        assertEquals(6, preview.size)
        val acRow = preview.first { it.key == "ac" }
        assertEquals("制冷 26°C", acRow.currentValue)
        assertEquals("制冷 25°C", acRow.nextValue)

        preview.filter { it.key != "ac" }.forEach { row ->
            assertNull("Expected ${row.key} unchanged", row.nextValue)
        }
    }

    @Test fun previewRowsWhenCancelFocusedHasNoChanges() {
        val home = SimulatedHome()
        val preview = AssistantHomePreview.previewRows(home, null, nowMillis = 1_000L)
        assertTrue(preview.all { it.nextValue == null })
    }

    @Test fun changedKeysReportsOnlyModifiedRows() {
        val before = SimulatedHome()
        val after = before.apply(HomeAction(SimulatedDevice.CURTAIN, position = 50), now = 1_000L)!!
        assertEquals(setOf("curtain"), AssistantHomePreview.changedKeys(before, after, nowMillis = 1_000L))
        assertEquals("50%", AssistantHomePreview.rows(after, 1_000L).first { it.key == "curtain" }.value)
    }

    @Test fun fakeReplyMatchesHotCoolDownAndClarifyCases() {
        val home = SimulatedHome()
        val hot = AssistantHomePreview.fakeReply("我热", home, emptyList(), emptyList())
        assertTrue(hot is AssistantResult.ConfirmHome)
        val confirm = hot as AssistantResult.ConfirmHome
        assertEquals(listOf("取消", "降温到 25°C"), AssistantHomePreview.confirmationLabels(confirm.options, home))

        val sports = AssistantHomePreview.fakeReply("看体育台", home, emptyList(), emptyList())
        assertTrue(sports is AssistantResult.Message && sports.clarify)
    }
}
