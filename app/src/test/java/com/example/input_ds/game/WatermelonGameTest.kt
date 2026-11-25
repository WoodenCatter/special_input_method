package com.example.input_ds.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WatermelonGameTest {
    @Test
    fun aimingWrapsThroughExitFromBothEdgesAndReturnsCleanly() {
        val engine = WatermelonEngine(seed = 101L)
        assertEquals(WatermelonFocus.DropSlot(WatermelonGame.CENTER_SLOT), engine.uiState.focus)

        repeat(WatermelonGame.CENTER_SLOT) {
            engine.applyAction(WatermelonAction.LOOK_LEFT)
        }
        assertEquals(WatermelonFocus.DropSlot(0), engine.uiState.focus)

        engine.applyAction(WatermelonAction.LOOK_LEFT)
        assertEquals(WatermelonFocus.Exit, engine.uiState.focus)

        engine.applyAction(WatermelonAction.LOOK_LEFT)
        assertEquals(WatermelonFocus.DropSlot(WatermelonGame.SLOT_COUNT - 1), engine.uiState.focus)

        engine.applyAction(WatermelonAction.LOOK_RIGHT)
        assertEquals(WatermelonFocus.Exit, engine.uiState.focus)

        engine.applyAction(WatermelonAction.LOOK_RIGHT)
        assertEquals(WatermelonFocus.DropSlot(0), engine.uiState.focus)
    }

    @Test
    fun biteDropsFruitAppliesCooldownAndAllowsAimingDuringCooldown() {
        val engine = WatermelonEngine(seed = 202L)
        val expectedTier = engine.uiState.currentTier
        val expectedNext = engine.uiState.nextTier

        engine.applyAction(WatermelonAction.BITE)
        assertEquals(1, engine.fruitsSnapshot().size)
        assertEquals(expectedTier, engine.fruitsSnapshot().single().tier)
        assertEquals(expectedNext, engine.uiState.currentTier)
        assertFalse(engine.uiState.dropReady)
        assertEquals(1, engine.uiState.dropSoundRevision)

        // Immediate second bite is ignored during cooldown
        engine.applyAction(WatermelonAction.BITE)
        assertEquals(1, engine.fruitsSnapshot().size)

        // Aiming still responds during cooldown
        engine.applyAction(WatermelonAction.LOOK_RIGHT)
        assertEquals(WatermelonFocus.DropSlot(WatermelonGame.CENTER_SLOT + 1), engine.uiState.focus)

        // After cooldown expires (~0.55s), another drop succeeds
        repeat(40) { engine.step(WatermelonGame.FIXED_DT) }
        assertTrue(engine.uiState.dropReady)
        engine.applyAction(WatermelonAction.BITE)
        assertEquals(2, engine.fruitsSnapshot().size)
    }

    @Test
    fun fruitFallsUnderBox2dGravityAndSettlesInsideBounds() {
        val engine = WatermelonEngine(seed = 303L)
        engine.applyAction(WatermelonAction.BITE)
        val initialY = engine.fruitsSnapshot().single().y

        repeat(120) {
            engine.step(WatermelonGame.FIXED_DT)
        }
        val settled = engine.fruitsSnapshot().single()
        assertTrue("Fruit should fall to floor", settled.y > initialY + 250f)
        assertTrue("Fruit must not pass floor", settled.y <= WatermelonGame.WORLD_HEIGHT - settled.radius + 1.0f)
        assertTrue("Fruit must stay inside left wall", settled.x >= settled.radius - 1.0f)
        assertTrue("Fruit must stay inside right wall", settled.x <= WatermelonGame.WORLD_WIDTH - settled.radius + 1.0f)
        assertFalse(settled.x.isNaN() || settled.y.isNaN())
    }

    @Test
    fun fruitDroppedOffCenterRollsNaturallyWithRotation() {
        val engine = WatermelonEngine(seed = 350L)
        // Settled large peach on the floor
        engine.spawnFruitForTest(
            tier = FruitTier.PEACH,
            x = 220f,
            y = WatermelonGame.WORLD_HEIGHT - FruitTier.PEACH.radius
        )
        // Drop a cherry slightly right of the peach's apex
        val cherryId = engine.spawnFruitForTest(
            tier = FruitTier.CHERRY,
            x = 248f,
            y = WatermelonGame.WORLD_HEIGHT - FruitTier.PEACH.radius * 2f - FruitTier.CHERRY.radius - 18f,
            ageSeconds = 0f,
            hasCollided = false
        )

        repeat(90) { engine.step(WatermelonGame.FIXED_DT) }
        val cherry = engine.fruitsSnapshot().first { it.id == cherryId }
        assertTrue("Off-center fruit should roll rightward down the curved surface", cherry.x > 275f)
        assertTrue("Rolling fruit should acquire real angular rotation", abs(cherry.angle) > 0.3f)
    }

    @Test
    fun touchingSameTierFruitsMergeAwardPointsAndAdvanceTier() {
        val engine = WatermelonEngine(seed = 404L)
        engine.spawnFruitForTest(tier = FruitTier.CHERRY, x = 206f, y = 520f)
        engine.spawnFruitForTest(tier = FruitTier.CHERRY, x = 230f, y = 520f)

        repeat(4) { engine.step(WatermelonGame.FIXED_DT) }
        val fruits = engine.fruitsSnapshot()
        assertEquals(1, fruits.size)
        assertEquals(FruitTier.STRAWBERRY, fruits.single().tier)
        assertEquals(FruitTier.STRAWBERRY.mergeScore, engine.uiState.score)
        assertEquals(engine.uiState.score, engine.uiState.bestScore)
        assertEquals(FruitTier.STRAWBERRY, engine.uiState.highestTier)
        assertEquals(1, engine.mergeEventsSnapshot().size)
        assertEquals(1, engine.uiState.mergeSoundRevision)
    }

    @Test
    fun doubleWatermelonMergeGrantsMegaBonusAndClearsBoth() {
        val engine = WatermelonEngine(seed = 505L)
        engine.spawnFruitForTest(tier = FruitTier.WATERMELON, x = 140f, y = 440f)
        engine.spawnFruitForTest(tier = FruitTier.WATERMELON, x = 320f, y = 440f)

        repeat(6) { engine.step(WatermelonGame.FIXED_DT) }
        assertTrue("Two colliding watermelons should burst and clear", engine.fruitsSnapshot().isEmpty())
        assertEquals(WatermelonGame.DOUBLE_WATERMELON_BONUS, engine.uiState.score)
        assertEquals(1, engine.uiState.watermelonsCreated)
        assertTrue(engine.mergeEventsSnapshot().single().isDoubleWatermelon)
    }

    @Test
    fun dangerLineTriggersGameOverAfterLimitSecondsAndIgnoresFreshDrops() {
        val freshEngine = WatermelonEngine(seed = 605L)
        freshEngine.spawnFruitForTest(
            tier = FruitTier.ORANGE,
            x = 220f,
            y = WatermelonGame.SPAWN_Y,
            ageSeconds = 0f,
            hasCollided = false
        )
        freshEngine.step(WatermelonGame.FIXED_DT)
        assertEquals(0f, freshEngine.dangerFraction(), 0.0001f)

        val overflowEngine = WatermelonEngine(seed = 606L)
        overflowEngine.spawnFruitForTest(tier = FruitTier.WATERMELON, x = 220f, y = 458f)
        overflowEngine.spawnFruitForTest(tier = FruitTier.MELON, x = 220f, y = 270f)
        overflowEngine.spawnFruitForTest(tier = FruitTier.PINEAPPLE, x = 220f, y = 112f)

        repeat(240) {
            if (!overflowEngine.uiState.gameOver) {
                overflowEngine.step(WatermelonGame.FIXED_DT)
            }
        }
        assertTrue("Overflowing settled stack should end game", overflowEngine.uiState.gameOver)
        assertTrue(overflowEngine.uiState.message.contains("本局得分"))
    }

    @Test
    fun exitConfirmationAndRestartKeepBestScore() {
        val engine = WatermelonEngine(seed = 707L, initialBestScore = 100)
        engine.setScoreForTest(260)

        repeat(WatermelonGame.CENTER_SLOT + 1) {
            engine.applyAction(WatermelonAction.LOOK_LEFT)
        }
        assertEquals(WatermelonFocus.Exit, engine.uiState.focus)
        engine.applyAction(WatermelonAction.BITE)
        assertTrue(engine.uiState.exitConfirmation)

        engine.applyAction(WatermelonAction.CANCEL_EXIT)
        assertFalse(engine.uiState.exitConfirmation)
        assertTrue(engine.uiState.focus is WatermelonFocus.DropSlot)

        engine.applyAction(WatermelonAction.RESTART)
        assertEquals(0, engine.uiState.score)
        assertEquals(260, engine.uiState.bestScore)
        assertTrue(engine.fruitsSnapshot().isEmpty())
        assertFalse(engine.uiState.gameOver)

        engine.applyAction(WatermelonAction.CONFIRM_EXIT)
        assertTrue(engine.uiState.exitRequested)
    }
}
