package com.example.input_ds.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

class LifeRestartGameTest {
    private fun loadOfficialRepository(): LifeRestartRepository {
        val candidates = listOf(
            File("src/main/assets/liferestart"),
            File("app/src/main/assets/liferestart")
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate liferestart assets directory from ${File(".").absolutePath}")
        return LifeRestartRepository.load(
            talentsStream = File(dir, "talents.json").inputStream(),
            eventsStream = File(dir, "events.json").inputStream(),
            agesStream = File(dir, "ages.json").inputStream(),
            achievementsStream = File(dir, "achievements.json").inputStream()
        )
    }

    @Test
    fun conditionEvaluatorMatchesOfficialOperatorsAndPrecedence() {
        val props = mapOf<String, Any?>(
            "AGE" to 18,
            "CHR" to 9,
            "INT" to 4,
            "TLT" to setOf(1001, 1048),
            "EVT" to setOf(10001, 10200),
            "TMS" to 12
        )

        assertTrue(LifeRestartCondition.check(props, "AGE>=18&CHR>8"))
        assertFalse(LifeRestartCondition.check(props, "AGE<18&CHR>8"))
        assertTrue(LifeRestartCondition.check(props, "(AGE<10)|(TLT?[1048]&EVT![99999])"))
        assertFalse(LifeRestartCondition.check(props, "TLT![1001]"))
        assertTrue(LifeRestartCondition.check(props, "TLT=1001"))
        assertFalse(LifeRestartCondition.check(props, "TLT!=1001"))
        assertTrue(LifeRestartCondition.check(props, "AGE?[18,19,20]"))
        assertFalse(LifeRestartCondition.check(props, "AGE![18,19]"))
        assertTrue(LifeRestartCondition.check(props, "TMS>9"))
    }

    @Test
    fun officialDatasetLoadsAllTablesCleanly() {
        val repo = loadOfficialRepository()
        assertTrue("Expected >150 official talents", repo.talents.size >= 150)
        assertTrue("Expected >1500 official events", repo.events.size >= 1500)
        assertTrue("Expected >100 age pools", repo.ages.size >= 100)
        assertTrue("Expected >100 achievements", repo.achievements.size >= 100)
        assertNotNull(repo.ages[0])
        assertNotNull(repo.talents[1001])
        assertNotNull(repo.events[10000])
    }

    @Test
    fun talentSelectionEnforcesMutualExclusionAndLimit() {
        val repo = loadOfficialRepository()
        val engine = LifeRestartEngine(repo, Random(123))
        engine.startNewSession(LifeRestartProfile())

        assertEquals(LifeRestartRules.PULL_COUNT, engine.state.pulledTalents.size)
        assertEquals(LifeRestartPhase.PICK_TALENTS, engine.state.phase)

        // Mutual exclusion check between 生而为男 (1003) and 生而为女 (1004)
        assertEquals(1004, engine.findExcludedConflict(1003, listOf(1004)))
        assertEquals(1003, engine.findExcludedConflict(1004, listOf(1003)))

        // Auto-fill proceeds cleanly to ALLOCATE with balanced full points
        val (allocState, notice) = engine.autoFillAndProceedToAllocate()
        assertNull(notice)
        assertEquals(LifeRestartPhase.ALLOCATE, allocState.phase)
        assertEquals(LifeRestartRules.PICK_COUNT, allocState.selectedTalentIds.size)
        assertEquals(0, allocState.remainingPoints)
        assertEquals(allocState.totalPoints, allocState.usedPoints)
    }

    @Test
    fun talentAutofillDoesNotStackOverflowWhenAllRemainingTalentsConflict() {
        val repo = loadOfficialRepository()
        val engine = LifeRestartEngine(repo, Random(77))
        val poolIds = listOf(1001, 1142, 1003, 1004, 1012, 1013, 1014, 1110, 1124, 1125)
        val pulled = poolIds.mapNotNull { repo.talents[it] }
        assertEquals(10, pulled.size)

        engine.startSessionWithPulledTalents(LifeRestartProfile(), pulled)
        engine.toggleTalent(1001)
        engine.toggleTalent(1142)

        val (after, notice) = engine.autoFillAndProceedToAllocate()
        assertEquals(LifeRestartPhase.PICK_TALENTS, after.phase)
        assertNotNull("Expected conflict notice when no third talent is compatible", notice)
        assertEquals(listOf(1001, 1142), after.selectedTalentIds)
    }

    @Test
    fun beginLifePreservesExistingAllocationAndOnlyDistributesUnspentPoints() {
        val repo = loadOfficialRepository()
        val engine = LifeRestartEngine(repo, Random(99))
        engine.startNewSession(LifeRestartProfile())
        engine.autoFillAndProceedToAllocate()
        engine.applyAllocationPreset(LifeAllocationPreset.CHARM)
        val before = engine.state
        engine.adjustStat("charm", -1)
        val afterMinus = engine.state
        assertEquals(1, afterMinus.remainingPoints)

        val started = engine.beginLife()
        assertEquals(LifeRestartPhase.PLAYING, started.phase)
        assertTrue(started.allocCharm >= afterMinus.allocCharm)
        assertTrue(started.allocIntelligence >= afterMinus.allocIntelligence)
        assertTrue(started.allocStrength >= afterMinus.allocStrength)
        assertTrue(started.allocMoney >= afterMinus.allocMoney)
        assertEquals(before.totalPoints, started.usedPoints)
        assertEquals(0, started.remainingPoints)
    }

    @Test
    fun finalizationIsIdempotentMatchesSummaryAndAwardsTenthRunMilestoneOnTenthCompletion() {
        val minimalRepo = LifeRestartRepository.parse(
            talentsJson = """[{"id":1,"name":"T1","description":"d"},{"id":2,"name":"T2","description":"d"},{"id":3,"name":"T3","description":"d"}]""",
            eventsJson = """[{"id":10,"event":"开局","effect":{"CHR":50,"INT":50,"LIF":-1}}]""",
            agesJson = """[{"age":0,"levels":[[[10,1]]]}]""",
            achievementsJson = """[
                {"id":245,"name":"高分","description":"SUM>120","condition":"SUM>120","opportunity":"SUMMARY"},
                {"id":101,"name":"重开10次","description":"TMS>9","condition":"TMS>9","opportunity":"END"},
                {"id":155,"name":"同世收集","description":"AEVT?[10]","condition":"AEVT?[10]","opportunity":"TRAJECTORY"}
            ]"""
        )

        // Direct exit after death (without opening summary) on 10th run (prior times = 9)
        val directEngine = LifeRestartEngine(minimalRepo, Random(1))
        directEngine.startSessionWithPulledTalents(
            LifeRestartProfile(times = 9),
            minimalRepo.talents.values.toList()
        )
        directEngine.autoFillAndProceedToAllocate()
        val playing = directEngine.beginLife()
        assertTrue(playing.ended)
        // AEVT includes current-life event 10 immediately during trajectory
        assertTrue(playing.runAchievements.any { it.id == 155 })

        val pDirect1 = directEngine.finalizeRunAndUpdateProfile()
        assertEquals(10, pDirect1.times)
        assertTrue("Direct exit should still award SUMMARY achievement 245", 245 in pDirect1.achievements)
        assertTrue("10th completion should unlock TMS>9 END achievement 101", 101 in pDirect1.achievements)
        assertTrue(155 in pDirect1.achievements)

        // Calling finalize again is idempotent (times stays 10)
        val pDirect2 = directEngine.finalizeRunAndUpdateProfile()
        assertEquals(10, pDirect2.times)
        assertEquals(pDirect1.achievements, pDirect2.achievements)

        // Summary path produces identical achievements
        val summaryEngine = LifeRestartEngine(minimalRepo, Random(1))
        summaryEngine.startSessionWithPulledTalents(
            LifeRestartProfile(times = 9),
            minimalRepo.talents.values.toList()
        )
        summaryEngine.autoFillAndProceedToAllocate()
        summaryEngine.beginLife()
        summaryEngine.openSummary()
        val pSummary = summaryEngine.finalizeRunAndUpdateProfile()
        assertEquals(pDirect1.times, pSummary.times)
        assertEquals(pDirect1.achievements, pSummary.achievements)
    }

    @Test
    fun miniJsonRejectsTruncatedInput() {
        try {
            LifeRestartRepository.parse(
                talentsJson = """[{"id":1}""",
                eventsJson = "[]",
                agesJson = "[]",
                achievementsJson = "[]"
            )
            fail("Expected truncated JSON array to fail parsing")
        } catch (_: IllegalStateException) {
            // Expected
        }
    }

    @Test
    fun toggleAutoPlayAndManualStepManageNarrationPausedState() {
        val minimalRepo = LifeRestartRepository.parse(
            talentsJson = """[{"id":1,"name":"T1","description":"d"},{"id":2,"name":"T2","description":"d"},{"id":3,"name":"T3","description":"d"}]""",
            eventsJson = """[{"id":10,"event":"零岁"},{"id":11,"event":"一岁"}]""",
            agesJson = """[{"age":0,"levels":[[[10,1]]]},{"age":1,"levels":[[[11,1]]]}]""",
            achievementsJson = "[]"
        )
        val engine = LifeRestartEngine(minimalRepo, Random(1))
        engine.startSessionWithPulledTalents(LifeRestartProfile(), minimalRepo.talents.values.toList())
        engine.autoFillAndProceedToAllocate()
        var s = engine.beginLife()
        assertTrue(s.autoPlay)
        assertFalse(s.narrationPaused)

        s = engine.toggleAutoPlay()
        assertFalse(s.autoPlay)
        assertTrue(s.narrationPaused)

        s = engine.toggleAutoPlay()
        assertTrue(s.autoPlay)
        assertFalse(s.narrationPaused)

        s = engine.toggleAutoPlay()
        assertTrue(s.narrationPaused)
        s = engine.nextYear()
        assertFalse("Manual step should clear narrationPaused so stepped year can narrate", s.narrationPaused)
    }

    @Test
    fun allocationPresetsAlwaysSpendExactTotalPoints() {
        val repo = loadOfficialRepository()
        val engine = LifeRestartEngine(repo, Random(456))
        engine.startNewSession(LifeRestartProfile())
        engine.autoFillAndProceedToAllocate()

        for (preset in LifeAllocationPreset.entries) {
            val s = engine.applyAllocationPreset(preset)
            assertEquals("Preset $preset should spend exact total points", s.totalPoints, s.usedPoints)
            assertTrue(s.allocCharm >= 0 && s.allocIntelligence >= 0 && s.allocStrength >= 0 && s.allocMoney >= 0)
        }
    }

    @Test
    fun fullLifeSimulationsRunToCompletionAcrossThirtySeedsWithoutErrors() {
        val repo = loadOfficialRepository()
        var profile = LifeRestartProfile()

        for (seed in 1..30) {
            val engine = LifeRestartEngine(repo, Random(seed * 7919L), baseYear = 2026)
            engine.startNewSession(profile)
            engine.autoFillAndProceedToAllocate()
            engine.applyAllocationPreset(LifeAllocationPreset.entries[seed % LifeAllocationPreset.entries.size])
            var state = engine.beginLife()

            assertEquals(LifeRestartPhase.PLAYING, state.phase)
            assertEquals(1, state.logs.size)
            assertEquals(0, state.logs.first().age)

            var steps = 0
            while (!state.ended && steps < 600) {
                state = engine.nextYear()
                steps++
            }
            assertTrue("Seed $seed should reach natural end within 600 years", state.ended)
            assertTrue("Life should produce at least 1 year log", state.logs.isNotEmpty())
            assertTrue("Each year should have readable text", state.logs.all { it.eventLines.isNotEmpty() || it.talents.isNotEmpty() })

            val summary = engine.openSummary()
            assertEquals(LifeRestartPhase.SUMMARY, summary.phase)
            assertEquals(7, summary.summaryJudges.size)
            assertTrue(summary.summaryJudges.any { it.key == "summary" && it.ratingText.isNotBlank() })

            // Lock first active talent for inheritance test
            val lockTarget = summary.activeTalents.first().id
            engine.toggleLockTalent(lockTarget)
            val restarted = engine.restartAfterSummary()
            profile = restarted.profile

            assertEquals(seed, profile.times)
            assertEquals(lockTarget, profile.lockedTalentId)
            assertTrue(
                "Locked talent $lockTarget should be guaranteed in next draw",
                restarted.pulledTalents.any { it.id == lockTarget }
            )
            assertFalse(restarted.selectedTalentIds.isEmpty())
        }
    }
}
