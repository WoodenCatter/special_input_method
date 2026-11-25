package com.example.input_ds.adarkroom

import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.EntertainmentCatalog
import com.example.input_ds.model.EntertainmentCategory
import com.example.input_ds.model.EntertainmentHubModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ADarkRoomTest {
    private fun locateAssetDir(): File {
        val candidates = listOf(
            File("src/main/assets/adarkroom"),
            File("app/src/main/assets/adarkroom")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate adarkroom assets directory from ${File(".").absolutePath}")
    }

    @Test
    fun offlineBundleHasAllReferencedScriptsStylesAudioAndMplLicenseWithNoExternalUrls() {
        val dir = locateAssetDir()
        val indexFile = File(dir, "index.html")
        assertTrue("index.html should exist", indexFile.isFile)
        val html = indexFile.readText(Charsets.UTF_8)

        // Zero external scripts, CDNs, or analytics in the offline bundle
        assertFalse("index.html must not load Google CDN", html.contains("googleapis.com"))
        assertFalse("index.html must not load Google Analytics", html.contains("googletagmanager.com"))

        // Verify every referenced local script and stylesheet exists on disk and is relative (not external URL)
        val refRegex = Regex("""(?:src|href)="([^"]+)"""")
        val refs = refRegex.findAll(html).map { it.groupValues[1] }.toList()
        assertTrue("Expected at least 25 script/style references in index.html", refs.size >= 25)
        for (ref in refs) {
            assertFalse("External URL not allowed in offline bundle: $ref", ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("//"))
            val target = File(dir, ref)
            assertTrue("Missing asset referenced by index.html: $ref", target.isFile)
        }

        // Verify bundled Simplified Chinese strings & MPL-2.0 license notice
        val strings = File(dir, "lang/zh_cn/strings.js").readText(Charsets.UTF_8)
        assertTrue(
            "zh_cn strings should translate A Dark Room to 小黑屋",
            strings.contains("\"A Dark Room\": \"\\u5c0f\\u9ed1\\u5c4b\"") || strings.contains("小黑屋")
        )
        val licenseInAssets = File(dir, "LICENSE.md")
        assertTrue("MPL-2.0 LICENSE.md must be packaged in assets/adarkroom/", licenseInAssets.isFile)
        assertTrue(licenseInAssets.readText(Charsets.UTF_8).contains("Mozilla Public License"))

        // Verify BCI adapter & theme files exist
        assertTrue(File(dir, "script/bci_adapter.js").isFile)
        assertTrue(File(dir, "css/bci_theme.css").isFile)

        // Verify audio files referenced by audioLibrary.js exist
        val audioLib = File(dir, "script/audioLibrary.js").readText(Charsets.UTF_8)
        val audioRefs = Regex("""'(audio/[^']+)'""").findAll(audioLib).map { it.groupValues[1] }.toList()
        assertTrue("Expected >50 audio files in audioLibrary.js", audioRefs.size >= 50)
        for (audioPath in audioRefs) {
            assertTrue("Missing bundled audio file: $audioPath", File(dir, audioPath).isFile)
        }
    }

    @Test
    fun inputBusDispatchesSignalsAndDebugTicksUntilDetached() {
        val receivedSignals = mutableListOf<ControlSignal>()
        var ticks = 0
        val sink = object : ADarkRoomSignalSink {
            override fun onSignal(signal: ControlSignal) {
                receivedSignals += signal
            }

            override fun onDebugTick() {
                ticks++
            }
        }

        ADarkRoomInputBus.attach(sink)
        try {
            assertTrue(ADarkRoomInputBus.dispatch(ControlSignal.LEFT_LOOK))
            assertTrue(ADarkRoomInputBus.dispatch(ControlSignal.BITE))
            assertTrue(ADarkRoomInputBus.dispatch(ControlSignal.RIGHT_LOOK))
            assertTrue(ADarkRoomInputBus.debugTick())
            assertEquals(
                listOf(ControlSignal.LEFT_LOOK, ControlSignal.BITE, ControlSignal.RIGHT_LOOK),
                receivedSignals
            )
            assertEquals(1, ticks)
        } finally {
            ADarkRoomInputBus.detach(sink)
        }

        assertFalse(ADarkRoomInputBus.dispatch(ControlSignal.BITE))
        assertFalse(ADarkRoomInputBus.debugTick())
    }

    @Test
    fun catalogRoutesADarkRoomUnderGamesAndRestoresFocusOnReturn() {
        val all = EntertainmentHubModule.allSubFeatureIds
        assertTrue(EntertainmentHubModule.A_DARK_ROOM.id in all)

        val (cat, idx) = EntertainmentCatalog.entryContextFor(
            previous = AppDestination.A_DARK_ROOM,
            currentCategory = null,
            enabledIds = all
        )
        assertEquals(EntertainmentCategory.GAMES, cat)
        val entries = EntertainmentCatalog.entriesFor(cat, all)
        assertEquals("小黑屋", entries[idx].displayName)
    }
}
