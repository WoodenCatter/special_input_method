package com.example.input_ds.game

import org.jbox2d.callbacks.ContactImpulse
import org.jbox2d.callbacks.ContactListener
import org.jbox2d.collision.Manifold
import org.jbox2d.collision.WorldManifold
import org.jbox2d.collision.shapes.CircleShape
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.common.Vec2
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.dynamics.World
import org.jbox2d.dynamics.contacts.Contact
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 11 classic Suika Game fruit tiers with proportions matched to the Switch Suika container
 * (watermelon diameter ~46.4% of container width) and classic triangular merge scoring.
 */
enum class FruitTier(
    val level: Int,
    val label: String,
    val radius: Float,
    val mergeScore: Int,
    val spawnWeight: Int
) {
    CHERRY(0, "樱桃", 13.5f, 1, 25),
    STRAWBERRY(1, "草莓", 19.0f, 3, 24),
    GRAPE(2, "葡萄", 24.5f, 6, 22),
    TANGERINE(3, "金橘", 28.5f, 10, 18),
    ORANGE(4, "橙子", 36.0f, 15, 11),
    APPLE(5, "苹果", 45.0f, 21, 0),
    PEAR(6, "鸭梨", 52.0f, 28, 0),
    PEACH(7, "水蜜桃", 62.0f, 36, 0),
    PINEAPPLE(8, "菠萝", 71.0f, 45, 0),
    MELON(9, "哈密瓜", 86.0f, 55, 0),
    WATERMELON(10, "大西瓜", 102.0f, 66, 0);

    fun next(): FruitTier? = entries.getOrNull(level + 1)

    companion object {
        val SPAWNABLE: List<FruitTier> = entries.filter { it.spawnWeight > 0 }
        val TOTAL_SPAWN_WEIGHT: Int = SPAWNABLE.sumOf { it.spawnWeight }
    }
}

sealed interface WatermelonFocus {
    data class DropSlot(val slot: Int) : WatermelonFocus
    data object Exit : WatermelonFocus
}

enum class WatermelonAction {
    LOOK_LEFT,
    LOOK_RIGHT,
    BITE,
    REQUEST_EXIT,
    CANCEL_EXIT,
    CONFIRM_EXIT,
    RESTART
}

data class WatermelonFruitRender(
    val id: Long,
    val tier: FruitTier,
    val x: Float,
    val y: Float,
    val angle: Float,
    val popProgress: Float
) {
    val radius: Float get() = tier.radius
}

data class WatermelonMergeEvent(
    val id: Long,
    val x: Float,
    val y: Float,
    val tier: FruitTier,
    val points: Int,
    val combo: Int,
    val isDoubleWatermelon: Boolean = false,
    val elapsedSeconds: Float = 0f
)

/**
 * Coarse UI snapshot — changes only on discrete events (aim, score, drop ready, danger countdown,
 * exit/game-over), so the 60fps Box2D simulation never triggers full-Activity recompositions.
 */
data class WatermelonUiState(
    val currentTier: FruitTier = FruitTier.CHERRY,
    val nextTier: FruitTier = FruitTier.STRAWBERRY,
    val focus: WatermelonFocus = WatermelonFocus.DropSlot(WatermelonGame.CENTER_SLOT),
    val lastDropSlot: Int = WatermelonGame.CENTER_SLOT,
    val aimX: Float = WatermelonGame.WORLD_WIDTH / 2f,
    val dropReady: Boolean = true,
    val score: Int = 0,
    val bestScore: Int = 0,
    val watermelonsCreated: Int = 0,
    val highestTier: FruitTier = FruitTier.CHERRY,
    val fruitCount: Int = 0,
    val dangerActive: Boolean = false,
    val dangerRemainingSeconds: Int = 3,
    val lastMergeSoundTier: Int = 0,
    val mergeSoundRevision: Int = 0,
    val dropSoundRevision: Int = 0,
    val exitConfirmation: Boolean = false,
    val exitRequested: Boolean = false,
    val gameOver: Boolean = false,
    val message: String = ""
) {
    val activeSlot: Int
        get() = when (val f = focus) {
            is WatermelonFocus.DropSlot -> f.slot
            WatermelonFocus.Exit -> lastDropSlot
        }
}

object WatermelonGame {
    const val WORLD_WIDTH = 440f
    const val WORLD_HEIGHT = 560f
    const val SPAWN_Y = 52f
    const val DANGER_LINE_Y = 104f
    const val SLOT_COUNT = 9
    const val CENTER_SLOT = SLOT_COUNT / 2
    const val DROP_COOLDOWN_SECONDS = 0.50f
    const val DANGER_GRACE_AGE_SECONDS = 1.25f
    const val DANGER_LIMIT_SECONDS = 3.0f
    const val DOUBLE_WATERMELON_BONUS = 200
    const val MERGE_EFFECT_DURATION_SECONDS = 0.85f
    const val POP_DURATION_SECONDS = 0.22f

    // Box2D scale & tuning (aligned with Switch Suika / Forge2D Box2D sweet spot)
    const val PIXELS_PER_METER = 50f
    const val FIXED_DT = 1f / 60f
    private const val MAX_ACCUMULATED_DT = 0.05f
    private const val GRAVITY_M = 34.0f
    private const val WALL_MARGIN = 6f
    private const val WALL_THICKNESS_M = 2.0f
    private const val VELOCITY_ITERATIONS = 8
    private const val POSITION_ITERATIONS = 4

    fun slotX(slot: Int, radius: Float): Float {
        val clamped = slot.coerceIn(0, SLOT_COUNT - 1)
        val minX = radius + WALL_MARGIN
        val maxX = WORLD_WIDTH - radius - WALL_MARGIN
        if (maxX <= minX) return WORLD_WIDTH / 2f
        val fraction = clamped.toFloat() / (SLOT_COUNT - 1).toFloat()
        return minX + (maxX - minX) * fraction
    }

    fun clampX(worldX: Float, radius: Float): Float =
        worldX.coerceIn(radius + WALL_MARGIN, WORLD_WIDTH - radius - WALL_MARGIN)

    fun nearestSlotForX(worldX: Float, radius: Float): Int {
        val minX = radius + WALL_MARGIN
        val maxX = WORLD_WIDTH - radius - WALL_MARGIN
        if (maxX <= minX) return CENTER_SLOT
        val normalized = ((worldX - minX) / (maxX - minX)).coerceIn(0f, 1f)
        return (normalized * (SLOT_COUNT - 1) + 0.5f).toInt().coerceIn(0, SLOT_COUNT - 1)
    }

    fun predictLandingY(fruits: List<WatermelonFruitRender>, dropX: Float, radius: Float): Float {
        var landingY = WORLD_HEIGHT - radius
        for (fruit in fruits) {
            val dx = dropX - fruit.x
            val sumR = radius + fruit.radius
            if (abs(dx) < sumR) {
                val dy = sqrt((sumR * sumR - dx * dx).coerceAtLeast(0f))
                val hitY = fruit.y - dy
                if (hitY >= SPAWN_Y && hitY < landingY) {
                    landingY = hitY
                }
            }
        }
        return landingY.coerceIn(SPAWN_Y, WORLD_HEIGHT - radius)
    }

    internal fun newBox2dWorld(): World {
        val world = World(Vec2(0f, GRAVITY_M)).apply {
            isAllowSleep = true
            isWarmStarting = true
            isContinuousPhysics = true
        }
        val widthM = WORLD_WIDTH / PIXELS_PER_METER
        val heightM = WORLD_HEIGHT / PIXELS_PER_METER
        val halfWall = WALL_THICKNESS_M / 2f
        val wallHeightHalf = (heightM + 8f) / 2f
        val wallCenterY = (heightM - 4f) / 2f

        fun addStaticBox(cx: Float, cy: Float, halfW: Float, halfH: Float, friction: Float, restitution: Float) {
            val bodyDef = BodyDef().apply {
                type = BodyType.STATIC
                position.set(cx, cy)
            }
            val shape = PolygonShape().apply {
                setAsBox(halfW, halfH)
            }
            val fixture = FixtureDef().apply {
                this.shape = shape
                this.friction = friction
                this.restitution = restitution
            }
            world.createBody(bodyDef).createFixture(fixture)
        }

        // Floor
        addStaticBox(
            cx = widthM / 2f,
            cy = heightM + halfWall,
            halfW = widthM / 2f + WALL_THICKNESS_M,
            halfH = halfWall,
            friction = 0.42f,
            restitution = 0.14f
        )
        // Left wall
        addStaticBox(
            cx = -halfWall,
            cy = wallCenterY,
            halfW = halfWall,
            halfH = wallHeightHalf,
            friction = 0.32f,
            restitution = 0.16f
        )
        // Right wall
        addStaticBox(
            cx = widthM + halfWall,
            cy = wallCenterY,
            halfW = halfWall,
            halfH = wallHeightHalf,
            friction = 0.32f,
            restitution = 0.16f
        )
        return world
    }

    internal fun createFruitBody(
        world: World,
        meta: FruitBodyMeta,
        worldX: Float,
        worldY: Float,
        vx: Float = 0f,
        vy: Float = 0f,
        angle: Float = 0f
    ): Body {
        val bodyDef = BodyDef().apply {
            type = BodyType.DYNAMIC
            position.set(worldX / PIXELS_PER_METER, worldY / PIXELS_PER_METER)
            this.angle = angle
            linearVelocity.set(vx / PIXELS_PER_METER, vy / PIXELS_PER_METER)
            linearDamping = 0.10f
            angularDamping = 0.28f
            userData = meta
        }
        val circle = CircleShape().apply {
            m_radius = meta.tier.radius / PIXELS_PER_METER
        }
        val fixtureDef = FixtureDef().apply {
            shape = circle
            density = 1.0f
            friction = 0.28f
            restitution = 0.20f
        }
        val body = world.createBody(bodyDef)
        body.createFixture(fixtureDef)
        return body
    }

    internal fun stepWorld(world: World) {
        world.step(FIXED_DT, VELOCITY_ITERATIONS, POSITION_ITERATIONS)
    }

    internal class FruitBodyMeta(
        val id: Long,
        val tier: FruitTier,
        var ageSeconds: Float = 0f,
        var popSeconds: Float = POP_DURATION_SECONDS,
        var hasCollided: Boolean = false
    )
}

/**
 * Stateful JBox2D-backed engine for Suika / Watermelon merge game.
 * Supports fixed-timestep 60Hz simulation, deterministic seeding, and clean separation
 * between hot per-frame Canvas snapshots and coarse Compose UI state.
 */
class WatermelonEngine(
    seed: Long = System.nanoTime(),
    initialBestScore: Int = 0
) {
    private data class PendingMerge(
        val idA: Long,
        val idB: Long,
        val contactWorldX: Float,
        val contactWorldY: Float
    )

    private var world: World = WatermelonGame.newBox2dWorld()
    private val bodiesById = LinkedHashMap<Long, Body>()
    private val pendingMerges = mutableListOf<PendingMerge>()
    private val mergeEvents = mutableListOf<WatermelonMergeEvent>()
    private val worldManifold = WorldManifold()

    private var rngState: Long = normalizeSeed(seed)
    private var nextFruitId: Long = 1L
    private var nextEventId: Long = 1L

    private var currentTier: FruitTier = FruitTier.CHERRY
    private var nextTier: FruitTier = FruitTier.STRAWBERRY
    private var focus: WatermelonFocus = WatermelonFocus.DropSlot(WatermelonGame.CENTER_SLOT)
    private var lastDropSlot: Int = WatermelonGame.CENTER_SLOT
    private var aimX: Float = WatermelonGame.WORLD_WIDTH / 2f
    private var dropCooldownSeconds: Float = 0f
    private var score: Int = 0
    private var bestScore: Int = initialBestScore.coerceAtLeast(0)
    private var watermelonsCreated: Int = 0
    private var highestTier: FruitTier = FruitTier.CHERRY
    private var dropCombo: Int = 0
    private var maxCombo: Int = 0
    private var dropsCount: Int = 0
    private var dangerSeconds: Float = 0f
    private var accumulatorSeconds: Float = 0f

    private var lastMergeSoundTier: Int = 0
    private var mergeSoundRevision: Int = 0
    private var dropSoundRevision: Int = 0
    private var exitConfirmation: Boolean = false
    private var exitRequested: Boolean = false
    private var gameOver: Boolean = false
    private var message: String = ""

    var uiState: WatermelonUiState = WatermelonUiState()
        private set

    init {
        resetInternal(seed = seed, keepBestScore = initialBestScore.coerceAtLeast(0))
    }

    fun reset(seed: Long = nextRng(rngState), bestScoreOverride: Int? = null): WatermelonUiState {
        val best = max(bestScoreOverride ?: bestScore, max(bestScore, score))
        resetInternal(seed = seed, keepBestScore = best)
        return uiState
    }

    private fun resetInternal(seed: Long, keepBestScore: Int) {
        world = WatermelonGame.newBox2dWorld()
        attachContactListener(world)
        bodiesById.clear()
        pendingMerges.clear()
        mergeEvents.clear()

        rngState = normalizeSeed(seed)
        val (first, r1) = drawOpeningFruit(rngState, 0)
        rngState = r1
        val (second, r2) = drawOpeningFruit(rngState, 1)
        rngState = r2

        nextFruitId = 1L
        nextEventId = 1L
        currentTier = first
        nextTier = second
        focus = WatermelonFocus.DropSlot(WatermelonGame.CENTER_SLOT)
        lastDropSlot = WatermelonGame.CENTER_SLOT
        aimX = WatermelonGame.slotX(WatermelonGame.CENTER_SLOT, first.radius)
        dropCooldownSeconds = 0f
        score = 0
        bestScore = keepBestScore
        watermelonsCreated = 0
        highestTier = first
        dropCombo = 0
        maxCombo = 0
        dropsCount = 0
        dangerSeconds = 0f
        accumulatorSeconds = 0f
        lastMergeSoundTier = 0
        mergeSoundRevision = 0
        dropSoundRevision = 0
        exitConfirmation = false
        exitRequested = false
        gameOver = false
        message = ""
        refreshUiState()
    }

    private fun attachContactListener(targetWorld: World) {
        targetWorld.setContactListener(object : ContactListener {
            override fun beginContact(contact: Contact) {
                recordContact(contact)
            }

            override fun endContact(contact: Contact) = Unit

            override fun preSolve(contact: Contact, oldManifold: Manifold) {
                // Also detect same-tier touching on subsequent steps (e.g. chain merge overlap)
                recordContact(contact)
            }

            override fun postSolve(contact: Contact, impulse: ContactImpulse) = Unit
        })
    }

    private fun recordContact(contact: Contact) {
        if (!contact.isTouching) return
        val bodyA = contact.fixtureA?.body ?: return
        val bodyB = contact.fixtureB?.body ?: return
        val metaA = bodyA.userData as? WatermelonGame.FruitBodyMeta
        val metaB = bodyB.userData as? WatermelonGame.FruitBodyMeta

        if (metaA != null) metaA.hasCollided = true
        if (metaB != null) metaB.hasCollided = true

        if (metaA != null && metaB != null && metaA.tier == metaB.tier) {
            contact.getWorldManifold(worldManifold)
            val cx = if (contact.manifold.pointCount > 0) {
                worldManifold.points[0].x * WatermelonGame.PIXELS_PER_METER
            } else {
                (bodyA.position.x + bodyB.position.x) * 0.5f * WatermelonGame.PIXELS_PER_METER
            }
            val cy = if (contact.manifold.pointCount > 0) {
                worldManifold.points[0].y * WatermelonGame.PIXELS_PER_METER
            } else {
                (bodyA.position.y + bodyB.position.y) * 0.5f * WatermelonGame.PIXELS_PER_METER
            }
            pendingMerges.add(PendingMerge(metaA.id, metaB.id, cx, cy))
        }
    }

    fun applyAction(action: WatermelonAction): WatermelonUiState {
        if (exitRequested) return uiState
        when (action) {
            WatermelonAction.REQUEST_EXIT -> {
                if (!exitConfirmation) exitConfirmation = true
            }
            WatermelonAction.CANCEL_EXIT -> {
                exitConfirmation = false
                focus = WatermelonFocus.DropSlot(lastDropSlot)
                aimX = WatermelonGame.slotX(lastDropSlot, currentTier.radius)
            }
            WatermelonAction.CONFIRM_EXIT -> {
                exitConfirmation = false
                exitRequested = true
            }
            WatermelonAction.RESTART -> {
                return reset(seed = nextRng(rngState))
            }
            WatermelonAction.LOOK_LEFT -> {
                if (gameOver || exitConfirmation) return uiState
                when (val current = focus) {
                    is WatermelonFocus.DropSlot -> {
                        if (current.slot > 0) {
                            lastDropSlot = current.slot - 1
                            focus = WatermelonFocus.DropSlot(lastDropSlot)
                            aimX = WatermelonGame.slotX(lastDropSlot, currentTier.radius)
                        } else {
                            focus = WatermelonFocus.Exit
                        }
                    }
                    WatermelonFocus.Exit -> {
                        lastDropSlot = WatermelonGame.SLOT_COUNT - 1
                        focus = WatermelonFocus.DropSlot(lastDropSlot)
                        aimX = WatermelonGame.slotX(lastDropSlot, currentTier.radius)
                    }
                }
            }
            WatermelonAction.LOOK_RIGHT -> {
                if (gameOver || exitConfirmation) return uiState
                when (val current = focus) {
                    is WatermelonFocus.DropSlot -> {
                        if (current.slot < WatermelonGame.SLOT_COUNT - 1) {
                            lastDropSlot = current.slot + 1
                            focus = WatermelonFocus.DropSlot(lastDropSlot)
                            aimX = WatermelonGame.slotX(lastDropSlot, currentTier.radius)
                        } else {
                            focus = WatermelonFocus.Exit
                        }
                    }
                    WatermelonFocus.Exit -> {
                        lastDropSlot = 0
                        focus = WatermelonFocus.DropSlot(0)
                        aimX = WatermelonGame.slotX(0, currentTier.radius)
                    }
                }
            }
            WatermelonAction.BITE -> {
                if (gameOver || exitConfirmation) return uiState
                when (focus) {
                    WatermelonFocus.Exit -> exitConfirmation = true
                    is WatermelonFocus.DropSlot -> dropAtX(aimX)
                }
            }
        }
        refreshUiState()
        return uiState
    }

    fun aimAtX(worldX: Float): WatermelonUiState {
        if (gameOver || exitConfirmation || exitRequested) return uiState
        val clamped = WatermelonGame.clampX(worldX, currentTier.radius)
        val slot = WatermelonGame.nearestSlotForX(clamped, currentTier.radius)
        lastDropSlot = slot
        focus = WatermelonFocus.DropSlot(slot)
        aimX = clamped
        refreshUiState()
        return uiState
    }

    fun aimAndDropAtX(worldX: Float): WatermelonUiState {
        if (gameOver || exitConfirmation || exitRequested) return uiState
        aimAtX(worldX)
        dropAtX(aimX)
        refreshUiState()
        return uiState
    }

    private fun dropAtX(targetX: Float) {
        if (dropCooldownSeconds > 0f) return
        val tier = currentTier
        rngState = nextRng(rngState)
        // Micro-jitter (< 0.4 world unit) breaks perfectly degenerate vertical symmetry naturally
        val microJitter = (((rngState ushr 16) and 0xFFL).toFloat() / 255f - 0.5f) * 0.6f
        val spawnX = WatermelonGame.clampX(targetX + microJitter, tier.radius)
        val meta = WatermelonGame.FruitBodyMeta(
            id = nextFruitId++,
            tier = tier,
            ageSeconds = 0f,
            popSeconds = WatermelonGame.POP_DURATION_SECONDS,
            hasCollided = false
        )
        val body = WatermelonGame.createFruitBody(
            world = world,
            meta = meta,
            worldX = spawnX,
            worldY = WatermelonGame.SPAWN_Y,
            vx = 0f,
            vy = 35f
        )
        bodiesById[meta.id] = body

        dropsCount += 1
        val (upcoming, nextRng) = if (dropsCount < 2) {
            drawOpeningFruit(rngState, dropsCount)
        } else {
            drawSpawnFruit(rngState)
        }
        rngState = nextRng

        if (tier.level > highestTier.level) highestTier = tier
        currentTier = nextTier
        nextTier = upcoming
        aimX = WatermelonGame.clampX(aimX, currentTier.radius)
        dropCooldownSeconds = WatermelonGame.DROP_COOLDOWN_SECONDS
        dropCombo = 0
        dropSoundRevision += 1
    }

    /**
     * Advances Box2D physics using a fixed 1/60s accumulator.
     * Returns true if at least one physics sub-step ran (so Canvas should redraw).
     */
    fun step(dtSeconds: Float): Boolean {
        if (gameOver || exitConfirmation || exitRequested) return false
        val clamped = dtSeconds.coerceIn(0f, 0.05f)
        if (clamped <= 0f) return false

        accumulatorSeconds = min(accumulatorSeconds + clamped, 0.06f)
        var stepped = false
        while (accumulatorSeconds >= WatermelonGame.FIXED_DT) {
            stepFixed(WatermelonGame.FIXED_DT)
            accumulatorSeconds -= WatermelonGame.FIXED_DT
            stepped = true
        }
        if (stepped) refreshUiState()
        return stepped
    }

    private fun stepFixed(dt: Float) {
        pendingMerges.clear()
        WatermelonGame.stepWorld(world)

        // Advance fruit ages & merge pop timers
        for (body in bodiesById.values) {
            val meta = body.userData as? WatermelonGame.FruitBodyMeta ?: continue
            meta.ageSeconds += dt
            if (meta.popSeconds < WatermelonGame.POP_DURATION_SECONDS) {
                meta.popSeconds = min(WatermelonGame.POP_DURATION_SECONDS, meta.popSeconds + dt)
            }
        }

        // Execute queued same-tier merges safely outside world.step()
        if (pendingMerges.isNotEmpty()) {
            val consumed = HashSet<Long>()
            for (merge in pendingMerges) {
                if (merge.idA in consumed || merge.idB in consumed) continue
                val bodyA = bodiesById[merge.idA] ?: continue
                val bodyB = bodiesById[merge.idB] ?: continue
                val metaA = bodyA.userData as? WatermelonGame.FruitBodyMeta ?: continue
                val metaB = bodyB.userData as? WatermelonGame.FruitBodyMeta ?: continue
                if (metaA.tier != metaB.tier) continue

                consumed.add(merge.idA)
                consumed.add(merge.idB)

                val posAX = bodyA.position.x * WatermelonGame.PIXELS_PER_METER
                val posAY = bodyA.position.y * WatermelonGame.PIXELS_PER_METER
                val posBX = bodyB.position.x * WatermelonGame.PIXELS_PER_METER
                val posBY = bodyB.position.y * WatermelonGame.PIXELS_PER_METER
                val avgVx = (bodyA.linearVelocity.x + bodyB.linearVelocity.x) * 0.35f * WatermelonGame.PIXELS_PER_METER
                val avgVy = (bodyA.linearVelocity.y + bodyB.linearVelocity.y) * 0.25f * WatermelonGame.PIXELS_PER_METER
                val midX = (posAX + posBX) * 0.5f
                val midY = (posAY + posBY) * 0.5f

                bodiesById.remove(merge.idA)
                bodiesById.remove(merge.idB)
                world.destroyBody(bodyA)
                world.destroyBody(bodyB)

                dropCombo += 1
                if (dropCombo > maxCombo) maxCombo = dropCombo

                val nextTier = metaA.tier.next()
                if (nextTier != null) {
                    val comboBonus = if (dropCombo >= 2) (nextTier.mergeScore * (dropCombo - 1)) / 2 else 0
                    val gained = nextTier.mergeScore + comboBonus
                    score += gained
                    if (score > bestScore) bestScore = score
                    if (nextTier.level > highestTier.level) highestTier = nextTier
                    if (nextTier == FruitTier.WATERMELON) watermelonsCreated += 1

                    val spawnX = midX.coerceIn(nextTier.radius + 1f, WatermelonGame.WORLD_WIDTH - nextTier.radius - 1f)
                    val spawnY = midY.coerceIn(nextTier.radius + 1f, WatermelonGame.WORLD_HEIGHT - nextTier.radius - 1f)
                    val newMeta = WatermelonGame.FruitBodyMeta(
                        id = nextFruitId++,
                        tier = nextTier,
                        ageSeconds = min(metaA.ageSeconds, metaB.ageSeconds).coerceAtLeast(0.55f),
                        popSeconds = 0f,
                        hasCollided = true
                    )
                    val mergedBody = WatermelonGame.createFruitBody(
                        world = world,
                        meta = newMeta,
                        worldX = spawnX,
                        worldY = spawnY,
                        vx = avgVx,
                        vy = avgVy
                    )
                    bodiesById[newMeta.id] = mergedBody
                    wakeNearbyBodies(spawnX, spawnY, nextTier.radius * 2.2f)

                    mergeEvents.add(
                        WatermelonMergeEvent(
                            id = nextEventId++,
                            x = spawnX,
                            y = spawnY,
                            tier = nextTier,
                            points = gained,
                            combo = dropCombo,
                            isDoubleWatermelon = false,
                            elapsedSeconds = 0f
                        )
                    )
                    lastMergeSoundTier = nextTier.level
                    mergeSoundRevision += 1
                } else {
                    // Double watermelon burst clears huge space + mega bonus!
                    val gained = WatermelonGame.DOUBLE_WATERMELON_BONUS + (dropCombo - 1).coerceAtLeast(0) * 50
                    score += gained
                    if (score > bestScore) bestScore = score
                    watermelonsCreated += 1
                    val burstX = midX.coerceIn(40f, WatermelonGame.WORLD_WIDTH - 40f)
                    val burstY = midY.coerceIn(40f, WatermelonGame.WORLD_HEIGHT - 40f)
                    wakeNearbyBodies(burstX, burstY, 260f)
                    mergeEvents.add(
                        WatermelonMergeEvent(
                            id = nextEventId++,
                            x = burstX,
                            y = burstY,
                            tier = FruitTier.WATERMELON,
                            points = gained,
                            combo = dropCombo,
                            isDoubleWatermelon = true,
                            elapsedSeconds = 0f
                        )
                    )
                    lastMergeSoundTier = FruitTier.WATERMELON.level + 1
                    mergeSoundRevision += 1
                }
            }
            pendingMerges.clear()
        }

        // Age & prune visual merge effects
        val iterator = mergeEvents.listIterator()
        while (iterator.hasNext()) {
            val ev = iterator.next()
            val nextElapsed = ev.elapsedSeconds + dt
            if (nextElapsed >= WatermelonGame.MERGE_EFFECT_DURATION_SECONDS) {
                iterator.remove()
            } else {
                iterator.set(ev.copy(elapsedSeconds = nextElapsed))
            }
        }

        if (dropCooldownSeconds > 0f) {
            dropCooldownSeconds = (dropCooldownSeconds - dt).coerceAtLeast(0f)
        }

        // Authentic danger-line check: only fruits that have already landed/collided & cleared drop grace
        val inDanger = bodiesById.values.any { body ->
            val meta = body.userData as? WatermelonGame.FruitBodyMeta ?: return@any false
            val topY = body.position.y * WatermelonGame.PIXELS_PER_METER - meta.tier.radius
            (meta.hasCollided && meta.ageSeconds >= 0.70f || meta.ageSeconds >= WatermelonGame.DANGER_GRACE_AGE_SECONDS) &&
                topY < WatermelonGame.DANGER_LINE_Y
        }
        dangerSeconds = if (inDanger) {
            (dangerSeconds + dt).coerceAtMost(WatermelonGame.DANGER_LIMIT_SECONDS)
        } else {
            (dangerSeconds - dt * 2.2f).coerceAtLeast(0f)
        }

        if (dangerSeconds >= WatermelonGame.DANGER_LIMIT_SECONDS) {
            gameOver = true
            message = if (watermelonsCreated > 0) {
                "本局得分 $score · 合成大西瓜 $watermelonsCreated 个"
            } else {
                "本局得分 $score · 最高合成「${highestTier.label}」"
            }
        }
    }

    private fun wakeNearbyBodies(worldX: Float, worldY: Float, radiusWorld: Float) {
        val rSq = radiusWorld * radiusWorld
        for (body in bodiesById.values) {
            val bx = body.position.x * WatermelonGame.PIXELS_PER_METER
            val by = body.position.y * WatermelonGame.PIXELS_PER_METER
            val dx = bx - worldX
            val dy = by - worldY
            if (dx * dx + dy * dy <= rSq) {
                body.isAwake = true
            }
        }
    }

    fun dangerFraction(): Float =
        (dangerSeconds / WatermelonGame.DANGER_LIMIT_SECONDS).coerceIn(0f, 1f)

    fun fruitsSnapshot(): List<WatermelonFruitRender> =
        bodiesById.values.mapNotNull { body ->
            val meta = body.userData as? WatermelonGame.FruitBodyMeta ?: return@mapNotNull null
            WatermelonFruitRender(
                id = meta.id,
                tier = meta.tier,
                x = body.position.x * WatermelonGame.PIXELS_PER_METER,
                y = body.position.y * WatermelonGame.PIXELS_PER_METER,
                angle = body.angle,
                popProgress = (meta.popSeconds / WatermelonGame.POP_DURATION_SECONDS).coerceIn(0f, 1f)
            )
        }

    fun mergeEventsSnapshot(): List<WatermelonMergeEvent> = mergeEvents.toList()

    /**
     * Test helper to place a fruit directly into the Box2D world at a given position.
     */
    internal fun spawnFruitForTest(
        tier: FruitTier,
        x: Float,
        y: Float,
        vx: Float = 0f,
        vy: Float = 0f,
        ageSeconds: Float = 2f,
        hasCollided: Boolean = true
    ): Long {
        val meta = WatermelonGame.FruitBodyMeta(
            id = nextFruitId++,
            tier = tier,
            ageSeconds = ageSeconds,
            popSeconds = WatermelonGame.POP_DURATION_SECONDS,
            hasCollided = hasCollided
        )
        val body = WatermelonGame.createFruitBody(world, meta, x, y, vx, vy)
        bodiesById[meta.id] = body
        refreshUiState()
        return meta.id
    }

    internal fun setScoreForTest(newScore: Int) {
        score = newScore
        if (score > bestScore) bestScore = score
        refreshUiState()
    }

    private fun refreshUiState() {
        val dangerActiveNow = dangerSeconds > 0.15f
        val remainingSec = ceil((WatermelonGame.DANGER_LIMIT_SECONDS - dangerSeconds).coerceAtLeast(0f)).toInt()
        val candidate = WatermelonUiState(
            currentTier = currentTier,
            nextTier = nextTier,
            focus = focus,
            lastDropSlot = lastDropSlot,
            aimX = aimX,
            dropReady = dropCooldownSeconds <= 0f,
            score = score,
            bestScore = bestScore,
            watermelonsCreated = watermelonsCreated,
            highestTier = highestTier,
            fruitCount = bodiesById.size,
            dangerActive = dangerActiveNow,
            dangerRemainingSeconds = remainingSec,
            lastMergeSoundTier = lastMergeSoundTier,
            mergeSoundRevision = mergeSoundRevision,
            dropSoundRevision = dropSoundRevision,
            exitConfirmation = exitConfirmation,
            exitRequested = exitRequested,
            gameOver = gameOver,
            message = message
        )
        if (candidate != uiState) {
            uiState = candidate
        }
    }

    private fun normalizeSeed(seed: Long): Long =
        if (seed == 0L) 0x5EED1234L else seed

    private fun nextRng(state: Long): Long =
        state * 6364136223846793005L + 1442695040888963407L

    private fun drawOpeningFruit(seed: Long, index: Int): Pair<FruitTier, Long> {
        val next = nextRng(seed)
        val pool = if (index == 0) {
            listOf(FruitTier.CHERRY, FruitTier.STRAWBERRY)
        } else {
            listOf(FruitTier.CHERRY, FruitTier.STRAWBERRY, FruitTier.GRAPE)
        }
        val pick = ((next ushr 16) and 0x7FFFFFFFL).toInt() % pool.size
        return pool[pick] to next
    }

    private fun drawSpawnFruit(seed: Long): Pair<FruitTier, Long> {
        val next = nextRng(seed)
        var ticket = ((next ushr 16) and 0x7FFFFFFFL).toInt() % FruitTier.TOTAL_SPAWN_WEIGHT
        for (tier in FruitTier.SPAWNABLE) {
            if (ticket < tier.spawnWeight) return tier to next
            ticket -= tier.spawnWeight
        }
        return FruitTier.CHERRY to next
    }
}
