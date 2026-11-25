package com.example.input_ds.game

import java.util.Calendar
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class LifeRestartPhase {
    PICK_TALENTS,
    ALLOCATE,
    PLAYING,
    SUMMARY
}

enum class LifeAllocationPreset(val label: String, val subtitle: String) {
    RANDOM("随机分配", "命运掷骰"),
    BALANCED("均衡发展", "四维平分"),
    CHARM("天选颜值", "颜值拉满"),
    INTELLIGENCE("绝顶聪明", "智力拉满"),
    STRENGTH("钢铁体魄", "体质拉满"),
    MONEY("豪门开局", "家境拉满")
}

data class LifeProps(
    val age: Int = -1,
    val charm: Int = 0,
    val intelligence: Int = 0,
    val strength: Int = 0,
    val money: Int = 0,
    val spirit: Int = 5
) {
    fun applyDelta(
        age: Int = 0,
        charm: Int = 0,
        intelligence: Int = 0,
        strength: Int = 0,
        money: Int = 0,
        spirit: Int = 0
    ): LifeProps = LifeProps(
        age = this.age + age,
        charm = this.charm + charm,
        intelligence = this.intelligence + intelligence,
        strength = this.strength + strength,
        money = this.money + money,
        spirit = this.spirit + spirit
    )

    fun maxWith(other: LifeProps): LifeProps = LifeProps(
        age = max(age, other.age),
        charm = max(charm, other.charm),
        intelligence = max(intelligence, other.intelligence),
        strength = max(strength, other.strength),
        money = max(money, other.money),
        spirit = max(spirit, other.spirit)
    )

    fun minWith(other: LifeProps): LifeProps = LifeProps(
        age = min(age, other.age),
        charm = min(charm, other.charm),
        intelligence = min(intelligence, other.intelligence),
        strength = min(strength, other.strength),
        money = min(money, other.money),
        spirit = min(spirit, other.spirit)
    )
}

data class LifeJudgeResult(
    val key: String,
    val label: String,
    val value: Int,
    val ratingText: String,
    val grade: Int
)

data class LifeYearLog(
    val age: Int,
    val index: Int,
    val talents: List<LifeTalent>,
    val eventLines: List<LifeEventLine>,
    val achievements: List<LifeAchievement>
) {
    fun speakableText(): String = buildString {
        append("${age}岁。")
        talents.forEach { append("天赋${it.name}生效。") }
        eventLines.forEach {
            append(it.text)
            if (!it.text.endsWith("。") && !it.text.endsWith("！") && !it.text.endsWith("？")) append("。")
        }
        achievements.forEach { append("解锁成就${it.name}。") }
    }
}

data class LifeEventLine(
    val text: String,
    val grade: Int
)

data class LifeRestartProfile(
    val times: Int = 0,
    val lockedTalentId: Int? = null,
    val talents: Set<Int> = emptySet(),
    val events: Set<Int> = emptySet(),
    val achievements: Set<Int> = emptySet()
)

data class LifeRestartState(
    val phase: LifeRestartPhase = LifeRestartPhase.PICK_TALENTS,
    val profile: LifeRestartProfile = LifeRestartProfile(),
    val pulledTalents: List<LifeTalent> = emptyList(),
    val selectedTalentIds: List<Int> = emptyList(),
    val activeTalents: List<LifeTalent> = emptyList(),
    val replacementNotices: List<String> = emptyList(),
    val totalPoints: Int = LifeRestartRules.BASE_POINTS,
    val allocCharm: Int = 5,
    val allocIntelligence: Int = 5,
    val allocStrength: Int = 5,
    val allocMoney: Int = 5,
    val currentProps: LifeProps = LifeProps(),
    val highestProps: LifeProps = LifeProps(),
    val lowestProps: LifeProps = LifeProps(),
    val life: Int = 1,
    val ended: Boolean = false,
    val autoPlay: Boolean = true,
    val readAloud: Boolean = false,
    val narrationPaused: Boolean = false,
    val logs: List<LifeYearLog> = emptyList(),
    val runAchievements: List<LifeAchievement> = emptyList(),
    val summaryScore: Int = 0,
    val summaryJudges: List<LifeJudgeResult> = emptyList(),
    val pendingLockTalentId: Int? = null,
    val latestAchievementBanner: LifeAchievement? = null,
    val bannerRevision: Int = 0,
    val speakRevision: Int = 0,
    val spokenCompletedRevision: Int = 0,
    val lastSpokenText: String = ""
) {
    val usedPoints: Int
        get() = allocCharm + allocIntelligence + allocStrength + allocMoney

    val remainingPoints: Int
        get() = totalPoints - usedPoints
}

object LifeRestartRules {
    const val BASE_POINTS = 20
    const val ALLOC_LIMIT = 10
    const val DEFAULT_SPIRIT = 5
    const val PICK_COUNT = 3
    const val PULL_COUNT = 10

    private val pullRateBase = mapOf(0 to 889.0, 1 to 100.0, 2 to 10.0, 3 to 1.0)

    private val judgeLineMap: Map<String, IntArray> = mapOf(
        "age" to intArrayOf(0, 1, 10, 18, 40, 60, 70, 80, 90, 95, 100, 500),
        "summary" to intArrayOf(0, 41, 50, 60, 80, 100, 110, 120),
        "times" to intArrayOf(0, 10, 30, 50, 70, 100),
        "achievements" to intArrayOf(0, 10, 30, 50, 70, 100),
        "charm" to intArrayOf(0, 1, 2, 4, 7, 9, 11),
        "money" to intArrayOf(0, 1, 2, 4, 7, 9, 11),
        "spirit" to intArrayOf(0, 1, 2, 4, 7, 9, 11),
        "intelligence" to intArrayOf(0, 1, 2, 4, 7, 9, 11, 21, 131, 501),
        "strength" to intArrayOf(0, 1, 2, 4, 7, 9, 11, 21, 101, 401, 1001, 2001)
    )

    private val judgeGradeMap: Map<String, IntArray> = mapOf(
        "age" to intArrayOf(0, 5, 7, 9),
        "summary" to intArrayOf(0, 4, 5, 6),
        "times" to intArrayOf(0, 1, 3, 5),
        "achievements" to intArrayOf(0, 1, 3, 5),
        "charm" to intArrayOf(0, 4, 5, 6),
        "money" to intArrayOf(0, 4, 5, 6),
        "spirit" to intArrayOf(0, 4, 5, 6),
        "intelligence" to intArrayOf(0, 4, 5, 6),
        "strength" to intArrayOf(0, 4, 5, 6)
    )

    private val baseLabels = listOf("地狱", "折磨", "不佳", "普通", "优秀", "罕见", "逆天", "传说")
    private val judgeTextMap: Map<String, List<String>> = mapOf(
        "summary" to baseLabels,
        "charm" to baseLabels,
        "money" to baseLabels,
        "spirit" to listOf("地狱", "折磨", "不幸", "普通", "幸福", "极乐", "天命"),
        "intelligence" to (baseLabels + listOf("识海", "元神", "仙魂")),
        "strength" to (baseLabels + listOf("凝气", "筑基", "金丹", "元婴", "仙体")),
        "age" to listOf("胎死腹中", "早夭", "少年", "盛年", "中年", "花甲", "古稀", "杖朝", "南山", "不老", "修仙", "仙寿")
    )

    private val propNames: Map<String, String> = mapOf(
        "charm" to "颜值",
        "intelligence" to "智力",
        "strength" to "体质",
        "money" to "家境",
        "spirit" to "快乐",
        "age" to "享年",
        "summary" to "总评"
    )

    fun judgeLevel(key: String, value: Int): Int {
        val arr = judgeLineMap[key] ?: return 0
        for (i in arr.lastIndex downTo 0) {
            if (value >= arr[i]) return i
        }
        return 0
    }

    fun judgeGrade(key: String, level: Int): Int {
        val arr = judgeGradeMap[key] ?: return 0
        for (i in arr.lastIndex downTo 0) {
            if (level >= arr[i]) return i
        }
        return 0
    }

    fun judge(key: String, value: Int): LifeJudgeResult {
        val level = judgeLevel(key, value)
        val grade = judgeGrade(key, level)
        val texts = judgeTextMap[key] ?: baseLabels
        val text = texts[level.coerceIn(0, texts.lastIndex)]
        return LifeJudgeResult(
            key = key,
            label = propNames[key] ?: key,
            value = value,
            ratingText = text,
            grade = grade
        )
    }

    fun summaryScore(highest: LifeProps): Int {
        val sumOthers = highest.charm + highest.intelligence + highest.strength + highest.money + highest.spirit
        return floor(sumOthers * 2.0 + highest.age / 2.0).toInt()
    }

    fun gradeWeights(profile: LifeRestartProfile): List<Pair<Int, Double>> {
        val timesMult = judgeLevel("times", profile.times) + 1
        val achMult = judgeLevel("achievements", profile.achievements.size) + 1
        return listOf(
            0 to (pullRateBase[0] ?: 889.0),
            1 to (pullRateBase[1] ?: 100.0),
            2 to (pullRateBase[2] ?: 10.0) * timesMult,
            3 to (pullRateBase[3] ?: 1.0) * achMult
        )
    }

    fun rarityLabel(grade: Int): String = when (grade) {
        1 -> "稀有"
        2 -> "罕见"
        3 -> "传说"
        else -> "普通"
    }
}

class LifeRestartEngine(
    private val repository: LifeRestartRepository,
    private val random: Random = Random.Default,
    private val baseYear: Int = Calendar.getInstance().get(Calendar.YEAR)
) {
    private var runTalentIds: MutableSet<Int> = linkedSetOf()
    private var runEventIds: MutableSet<Int> = linkedSetOf()
    private var runAchievementIds: MutableSet<Int> = linkedSetOf()
    private var talentTriggerCounts: MutableMap<Int, Int> = hashMapOf()
    private var summaryAchievementsChecked: Boolean = false
    private var runFinalized: Boolean = false

    var state: LifeRestartState = LifeRestartState()
        private set

    fun startNewSession(profile: LifeRestartProfile, readAloud: Boolean = state.readAloud): LifeRestartState {
        summaryAchievementsChecked = false
        runFinalized = false
        val pulled = pullTalents(profile)
        val preselected = profile.lockedTalentId
            ?.takeIf { id -> pulled.any { it.id == id } }
            ?.let { listOf(it) }
            ?: emptyList()
        state = LifeRestartState(
            phase = LifeRestartPhase.PICK_TALENTS,
            profile = profile,
            pulledTalents = pulled,
            selectedTalentIds = preselected,
            readAloud = readAloud
        )
        return state
    }

    internal fun startSessionWithPulledTalents(
        profile: LifeRestartProfile,
        pulledTalents: List<LifeTalent>,
        readAloud: Boolean = state.readAloud
    ): LifeRestartState {
        summaryAchievementsChecked = false
        runFinalized = false
        state = LifeRestartState(
            phase = LifeRestartPhase.PICK_TALENTS,
            profile = profile,
            pulledTalents = pulledTalents,
            selectedTalentIds = emptyList(),
            readAloud = readAloud
        )
        return state
    }

    fun rerollTalents(): LifeRestartState {
        if (state.phase != LifeRestartPhase.PICK_TALENTS) return state
        return startNewSession(state.profile, state.readAloud)
    }

    fun findExcludedConflict(talentId: Int, selected: List<Int> = state.selectedTalentIds): Int? {
        val target = repository.talents[talentId] ?: return null
        for (otherId in selected) {
            if (otherId == talentId) continue
            if (otherId in target.exclude) return otherId
            val other = repository.talents[otherId]
            if (other != null && talentId in other.exclude) return otherId
        }
        return null
    }

    fun toggleTalent(talentId: Int): Pair<LifeRestartState, String?> {
        if (state.phase != LifeRestartPhase.PICK_TALENTS) return state to null
        val selected = state.selectedTalentIds.toMutableList()
        if (talentId in selected) {
            selected.remove(talentId)
            state = state.copy(selectedTalentIds = selected)
            return state to null
        }
        if (selected.size >= LifeRestartRules.PICK_COUNT) {
            return state to "最多只能选择 ${LifeRestartRules.PICK_COUNT} 个天赋"
        }
        val conflict = findExcludedConflict(talentId, selected)
        if (conflict != null) {
            val conflictName = repository.talents[conflict]?.name ?: "已选天赋"
            return state to "与「$conflictName」互斥"
        }
        selected.add(talentId)
        state = state.copy(selectedTalentIds = selected)
        return state to null
    }

    fun autoFillAndProceedToAllocate(): Pair<LifeRestartState, String?> = proceedToAllocate()

    fun proceedToAllocate(): Pair<LifeRestartState, String?> {
        if (state.phase != LifeRestartPhase.PICK_TALENTS) return state to null
        val selected = state.selectedTalentIds.toMutableList()
        if (selected.size < LifeRestartRules.PICK_COUNT) {
            val candidates = state.pulledTalents.map { it.id }.shuffled(random)
            for (cid in candidates) {
                if (selected.size >= LifeRestartRules.PICK_COUNT) break
                if (cid !in selected && findExcludedConflict(cid, selected) == null) {
                    selected.add(cid)
                }
            }
            if (selected.size < LifeRestartRules.PICK_COUNT) {
                state = state.copy(selectedTalentIds = selected)
                return state to "当前已选天赋与剩余天赋互斥，请取消部分选择或换一批天赋"
            }
            state = state.copy(selectedTalentIds = selected)
        }
        val seenIds = linkedSetOf<Int>()
        seenIds.addAll(state.selectedTalentIds)
        val activeIds = linkedSetOf<Int>()
        val notices = mutableListOf<String>()

        for (origId in state.selectedTalentIds) {
            val chain = resolveChainReplacement(origId, seenIds)
            if (chain.isNotEmpty()) {
                val origName = repository.talents[origId]?.name ?: origId.toString()
                val lastId = chain.last()
                val lastName = repository.talents[lastId]?.name ?: lastId.toString()
                notices += "天赋「$origName」化为了「$lastName」"
                seenIds.addAll(chain)
                activeIds.add(lastId)
            } else {
                activeIds.add(origId)
            }
        }

        val activeTalents = activeIds.mapNotNull { repository.talents[it] }
        val extraPoints = activeTalents.sumOf { it.points }
        val total = (LifeRestartRules.BASE_POINTS + extraPoints).coerceAtLeast(0)
        val (c, i, s, m) = computePresetAllocation(LifeAllocationPreset.BALANCED, total)

        state = state.copy(
            phase = LifeRestartPhase.ALLOCATE,
            activeTalents = activeTalents,
            replacementNotices = notices,
            totalPoints = total,
            allocCharm = c,
            allocIntelligence = i,
            allocStrength = s,
            allocMoney = m
        )
        return state to null
    }

    fun backToPickTalents(): LifeRestartState {
        if (state.phase != LifeRestartPhase.ALLOCATE) return state
        state = state.copy(phase = LifeRestartPhase.PICK_TALENTS)
        return state
    }

    fun applyAllocationPreset(preset: LifeAllocationPreset): LifeRestartState {
        if (state.phase != LifeRestartPhase.ALLOCATE) return state
        val (c, i, s, m) = computePresetAllocation(preset, state.totalPoints)
        state = state.copy(
            allocCharm = c,
            allocIntelligence = i,
            allocStrength = s,
            allocMoney = m
        )
        return state
    }

    fun adjustStat(statKey: String, delta: Int): LifeRestartState {
        if (state.phase != LifeRestartPhase.ALLOCATE) return state
        val maxPerStat = max(LifeRestartRules.ALLOC_LIMIT, (state.totalPoints + 3) / 4)
        var c = state.allocCharm
        var i = state.allocIntelligence
        var s = state.allocStrength
        var m = state.allocMoney
        val current = when (statKey) {
            "charm" -> c
            "intelligence" -> i
            "strength" -> s
            "money" -> m
            else -> return state
        }
        val target = (current + delta).coerceIn(0, maxPerStat)
        val diff = target - current
        if (diff == 0) return state
        if (diff > 0 && state.remainingPoints < diff) return state
        when (statKey) {
            "charm" -> c = target
            "intelligence" -> i = target
            "strength" -> s = target
            "money" -> m = target
        }
        state = state.copy(
            allocCharm = c,
            allocIntelligence = i,
            allocStrength = s,
            allocMoney = m
        )
        return state
    }

    fun beginLife(): LifeRestartState {
        if (state.phase != LifeRestartPhase.ALLOCATE) return state
        // Preserve already-assigned attributes and only distribute any remaining/excess points
        if (state.remainingPoints != 0) {
            distributeRemainingPoints()
        }
        summaryAchievementsChecked = false
        runFinalized = false
        runTalentIds = state.activeTalents.map { it.id }.toMutableSet()
        runEventIds = linkedSetOf()
        runAchievementIds = linkedSetOf()
        talentTriggerCounts = hashMapOf()

        val initial = LifeProps(
            age = -1,
            charm = state.allocCharm,
            intelligence = state.allocIntelligence,
            strength = state.allocStrength,
            money = state.allocMoney,
            spirit = LifeRestartRules.DEFAULT_SPIRIT
        )
        state = state.copy(
            phase = LifeRestartPhase.PLAYING,
            currentProps = initial,
            highestProps = initial,
            lowestProps = initial,
            life = 1,
            ended = false,
            autoPlay = true,
            logs = emptyList(),
            runAchievements = emptyList(),
            summaryScore = 0,
            summaryJudges = emptyList(),
            pendingLockTalentId = null,
            latestAchievementBanner = null,
            speakRevision = 0,
            spokenCompletedRevision = 0,
            lastSpokenText = ""
        )

        val startAchs = triggerAchievements(LifeAchievementOpportunity.START)
        if (startAchs.isNotEmpty()) {
            state = state.copy(
                runAchievements = state.runAchievements + startAchs,
                latestAchievementBanner = startAchs.last(),
                bannerRevision = state.bannerRevision + 1
            )
        }

        // Immediately advance to age 0 so the life opens right away
        return nextYear()
    }

    fun toggleAutoPlay(): LifeRestartState {
        if (state.phase != LifeRestartPhase.PLAYING || state.ended) return state
        val nextAuto = !state.autoPlay
        state = state.copy(autoPlay = nextAuto, narrationPaused = !nextAuto)
        return state
    }

    fun toggleReadAloud(): LifeRestartState {
        val nextRead = !state.readAloud
        state = state.copy(readAloud = nextRead, narrationPaused = if (nextRead) false else state.narrationPaused)
        return state
    }

    fun markSpokenCompleted(revision: Int): LifeRestartState {
        if (revision > state.spokenCompletedRevision) {
            state = state.copy(spokenCompletedRevision = revision)
        }
        return state
    }

    fun nextYear(): LifeRestartState {
        if (state.phase != LifeRestartPhase.PLAYING || state.ended) return state

        // 1. Age + 1
        applyPropsDelta(age = 1)
        val age = state.currentProps.age

        // 2. Trigger talents
        val triggeredTalents = triggerTalentsForYear()

        // 3. Pick weighted event for this age
        val agePool = repository.ages[age]
        var pickedEventId: Int? = null
        if (agePool != null) {
            for (level in agePool.levels) {
                val eligible = level.filter { (eid, _) -> isEventEligible(eid) }
                if (eligible.isNotEmpty()) {
                    pickedEventId = pickWeighted(eligible)
                    break
                }
            }
        }

        val triggeredEventIds = mutableListOf<Int>()
        if (pickedEventId != null) {
            executeEventChain(pickedEventId, triggeredEventIds, depth = 0)
        } else {
            // Graceful fallback if age exceeds pool (e.g. >500)
            state = state.copy(life = 0)
        }

        // 4. Check trajectory achievements
        val yearAchievements = triggerAchievements(LifeAchievementOpportunity.TRAJECTORY)

        val logIndex = state.logs.size
        val eventLines = buildEventLines(triggeredEventIds, logIndex)
        val yearLog = LifeYearLog(
            age = age,
            index = logIndex,
            talents = triggeredTalents,
            eventLines = eventLines,
            achievements = yearAchievements
        )
        val isEnded = state.life < 1

        state = state.copy(
            logs = state.logs + yearLog,
            ended = isEnded,
            autoPlay = if (isEnded) false else state.autoPlay,
            narrationPaused = false,
            runAchievements = state.runAchievements + yearAchievements,
            latestAchievementBanner = yearAchievements.lastOrNull() ?: state.latestAchievementBanner,
            bannerRevision = if (yearAchievements.isNotEmpty()) state.bannerRevision + 1 else state.bannerRevision,
            speakRevision = state.speakRevision + 1,
            lastSpokenText = yearLog.speakableText()
        )
        return state
    }

    private fun ensureSummaryAchievements() {
        if (summaryAchievementsChecked) return
        summaryAchievementsChecked = true
        val summaryAchs = triggerAchievements(LifeAchievementOpportunity.SUMMARY)
        if (summaryAchs.isNotEmpty()) {
            state = state.copy(
                runAchievements = state.runAchievements + summaryAchs,
                latestAchievementBanner = summaryAchs.last(),
                bannerRevision = state.bannerRevision + 1
            )
        }
    }

    fun openSummary(): LifeRestartState {
        if (state.phase != LifeRestartPhase.PLAYING || !state.ended) return state
        ensureSummaryAchievements()
        val score = LifeRestartRules.summaryScore(state.highestProps)
        val h = state.highestProps
        val judges = listOf(
            LifeRestartRules.judge("charm", h.charm),
            LifeRestartRules.judge("intelligence", h.intelligence),
            LifeRestartRules.judge("strength", h.strength),
            LifeRestartRules.judge("money", h.money),
            LifeRestartRules.judge("spirit", h.spirit),
            LifeRestartRules.judge("age", h.age),
            LifeRestartRules.judge("summary", score)
        )
        state = state.copy(
            phase = LifeRestartPhase.SUMMARY,
            summaryScore = score,
            summaryJudges = judges
        )
        return state
    }

    fun toggleLockTalent(talentId: Int): LifeRestartState {
        if (state.phase != LifeRestartPhase.SUMMARY) return state
        val next = if (state.pendingLockTalentId == talentId) null else talentId
        state = state.copy(pendingLockTalentId = next)
        return state
    }

    fun finalizeRunAndUpdateProfile(): LifeRestartProfile {
        if (state.phase != LifeRestartPhase.SUMMARY && !(state.phase == LifeRestartPhase.PLAYING && state.ended)) {
            return state.profile
        }
        if (runFinalized) {
            if (state.profile.lockedTalentId != state.pendingLockTalentId) {
                val synced = state.profile.copy(lockedTalentId = state.pendingLockTalentId)
                state = state.copy(profile = synced)
                return synced
            }
            return state.profile
        }
        ensureSummaryAchievements()
        runFinalized = true
        val baseUpdated = LifeRestartProfile(
            times = state.profile.times + 1,
            lockedTalentId = state.pendingLockTalentId,
            talents = state.profile.talents + runTalentIds,
            events = state.profile.events + runEventIds,
            achievements = state.profile.achievements + runAchievementIds
        )
        state = state.copy(profile = baseUpdated)
        val endAchs = triggerAchievements(LifeAchievementOpportunity.END)
        val finalProfile = if (endAchs.isNotEmpty()) {
            baseUpdated.copy(achievements = baseUpdated.achievements + endAchs.map { it.id })
        } else {
            baseUpdated
        }
        state = state.copy(
            profile = finalProfile,
            runAchievements = state.runAchievements + endAchs,
            latestAchievementBanner = endAchs.lastOrNull() ?: state.latestAchievementBanner,
            bannerRevision = if (endAchs.isNotEmpty()) state.bannerRevision + 1 else state.bannerRevision
        )
        return finalProfile
    }

    fun restartAfterSummary(): LifeRestartState {
        val updatedProfile = finalizeRunAndUpdateProfile()
        return startNewSession(updatedProfile, state.readAloud)
    }

    private fun distributeRemainingPoints() {
        val maxPerStat = max(LifeRestartRules.ALLOC_LIMIT, (state.totalPoints + 3) / 4)
        val arr = intArrayOf(
            state.allocCharm.coerceIn(0, maxPerStat),
            state.allocIntelligence.coerceIn(0, maxPerStat),
            state.allocStrength.coerceIn(0, maxPerStat),
            state.allocMoney.coerceIn(0, maxPerStat)
        )
        var rem = state.totalPoints - arr.sum()
        var guard = 0
        while (rem > 0 && guard < 200) {
            guard++
            val open = (0..3).filter { arr[it] < maxPerStat }
            if (open.isEmpty()) break
            arr[open[random.nextInt(open.size)]]++
            rem--
        }
        guard = 0
        while (rem < 0 && guard < 200) {
            guard++
            val nonZero = (0..3).filter { arr[it] > 0 }
            if (nonZero.isEmpty()) break
            arr[nonZero[random.nextInt(nonZero.size)]]--
            rem++
        }
        state = state.copy(
            allocCharm = arr[0],
            allocIntelligence = arr[1],
            allocStrength = arr[2],
            allocMoney = arr[3]
        )
    }

    private fun buildFlatState(): Map<String, Any?> {
        val cur = state.currentProps
        val hi = state.highestProps
        val lo = state.lowestProps
        val p = state.profile
        return mapOf(
            "AGE" to cur.age,
            "CHR" to cur.charm,
            "INT" to cur.intelligence,
            "STR" to cur.strength,
            "MNY" to cur.money,
            "SPR" to cur.spirit,
            "HAGE" to hi.age,
            "HCHR" to hi.charm,
            "HINT" to hi.intelligence,
            "HSTR" to hi.strength,
            "HMNY" to hi.money,
            "HSPR" to hi.spirit,
            "LAGE" to lo.age,
            "LCHR" to lo.charm,
            "LINT" to lo.intelligence,
            "LSTR" to lo.strength,
            "LMNY" to lo.money,
            "LSPR" to lo.spirit,
            "LIF" to state.life,
            "TLT" to runTalentIds,
            "EVT" to runEventIds,
            "TMS" to p.times,
            "AEVT" to (p.events + runEventIds),
            "ATLT" to (p.talents + runTalentIds),
            "AACH" to (p.achievements + runAchievementIds),
            "SUM" to LifeRestartRules.summaryScore(hi)
        )
    }

    private fun applyPropsDelta(
        age: Int = 0,
        charm: Int = 0,
        intelligence: Int = 0,
        strength: Int = 0,
        money: Int = 0,
        spirit: Int = 0
    ) {
        val next = state.currentProps.applyDelta(
            age = age,
            charm = charm,
            intelligence = intelligence,
            strength = strength,
            money = money,
            spirit = spirit
        )
        state = state.copy(
            currentProps = next,
            highestProps = state.highestProps.maxWith(next),
            lowestProps = state.lowestProps.minWith(next)
        )
    }

    private fun triggerTalentsForYear(): List<LifeTalent> {
        val flat = buildFlatState()
        val triggered = mutableListOf<LifeTalent>()
        for (tid in runTalentIds) {
            val talent = repository.talents[tid] ?: continue
            val count = talentTriggerCounts[tid] ?: 0
            if (count >= talent.max) continue
            if (talent.condition != null && !LifeRestartCondition.check(flat, talent.condition)) continue
            triggered.add(talent)
        }
        if (triggered.isEmpty()) return emptyList()

        for (talent in triggered) {
            talentTriggerCounts[talent.id] = (talentTriggerCounts[talent.id] ?: 0) + 1
            val eff = talent.effect ?: continue
            var chr = eff.chr
            var intV = eff.intVal
            var str = eff.str
            var mny = eff.mny
            var spr = eff.spr
            if (eff.rnd != 0) {
                when (random.nextInt(5)) {
                    0 -> if (mny == 0) mny = eff.rnd
                    1 -> if (str == 0) str = eff.rnd
                    2 -> if (intV == 0) intV = eff.rnd
                    3 -> if (chr == 0) chr = eff.rnd
                    else -> if (spr == 0) spr = eff.rnd
                }
            }
            applyPropsDelta(charm = chr, intelligence = intV, strength = str, money = mny, spirit = spr)
        }
        return triggered
    }

    private fun isEventEligible(eventId: Int): Boolean {
        val ev = repository.events[eventId] ?: return false
        if (ev.noRandom) return false
        val flat = buildFlatState()
        if (ev.exclude != null && LifeRestartCondition.check(flat, ev.exclude)) return false
        if (ev.include != null) return LifeRestartCondition.check(flat, ev.include)
        return true
    }

    private fun executeEventChain(eventId: Int, triggeredOut: MutableList<Int>, depth: Int) {
        if (depth >= 16) return
        val ev = repository.events[eventId] ?: return
        runEventIds.add(eventId)
        triggeredOut.add(eventId)
        ev.effect?.let { eff ->
            if (eff.lif != 0) {
                state = state.copy(life = state.life + eff.lif)
            }
            if (eff.age != 0 || eff.chr != 0 || eff.intVal != 0 || eff.str != 0 || eff.mny != 0 || eff.spr != 0) {
                applyPropsDelta(
                    age = eff.age,
                    charm = eff.chr,
                    intelligence = eff.intVal,
                    strength = eff.str,
                    money = eff.mny,
                    spirit = eff.spr
                )
            }
        }
        if (ev.branches.isNotEmpty()) {
            val flat = buildFlatState()
            for (branch in ev.branches) {
                if (LifeRestartCondition.check(flat, branch.condition)) {
                    executeEventChain(branch.eventId, triggeredOut, depth + 1)
                    break
                }
            }
        }
    }

    private fun buildEventLines(eventIds: List<Int>, logIndex: Int): List<LifeEventLine> {
        val lines = mutableListOf<LifeEventLine>()
        val lastIdx = eventIds.lastIndex
        eventIds.forEachIndexed { idx, eid ->
            val ev = repository.events[eid] ?: return@forEachIndexed
            val mainText = formatEventText(ev.text, ev.format, logIndex)
            if (mainText.isNotBlank()) {
                lines.add(LifeEventLine(mainText, ev.grade))
            }
            if (idx == lastIdx && !ev.postEvent.isNullOrBlank()) {
                val postText = formatEventText(ev.postEvent, ev.format, logIndex)
                if (postText.isNotBlank()) {
                    lines.add(LifeEventLine(postText, ev.grade))
                }
            }
        }
        return lines
    }

    private fun formatEventText(raw: String, needsFormat: Boolean, logIndex: Int): String =
        if (needsFormat) raw.replace("{CurrentYear}", (baseYear + logIndex).toString()) else raw

    private fun triggerAchievements(opportunity: LifeAchievementOpportunity): List<LifeAchievement> {
        val pool = repository.achievementsByOpportunity[opportunity] ?: return emptyList()
        val flat = buildFlatState()
        val newlyUnlocked = mutableListOf<LifeAchievement>()
        for (ach in pool) {
            if (ach.id in runAchievementIds || ach.id in state.profile.achievements) continue
            if (LifeRestartCondition.check(flat, ach.condition)) {
                runAchievementIds.add(ach.id)
                newlyUnlocked.add(ach)
            }
        }
        return newlyUnlocked
    }

    private fun pullTalents(profile: LifeRestartProfile): List<LifeTalent> {
        val weights = LifeRestartRules.gradeWeights(profile)
        val pools = (0..3).associateWith { g ->
            (repository.nonExclusiveTalentsByGrade[g] ?: emptyList()).toMutableList()
        }
        val picked = mutableListOf<Int>()

        profile.lockedTalentId?.let { locked ->
            repository.talents[locked]?.let { t ->
                picked.add(locked)
                pools[t.grade]?.remove(locked)
            }
        }

        var safety = 0
        while (picked.size < LifeRestartRules.PULL_COUNT && safety < 100) {
            safety++
            val grade = pickWeighted(weights) ?: 0
            val candidates = pools[grade]
            if (candidates.isNullOrEmpty()) {
                val fallback = pools.values.firstOrNull { it.isNotEmpty() } ?: break
                val id = fallback.removeAt(random.nextInt(fallback.size))
                picked.add(id)
            } else {
                val id = candidates.removeAt(random.nextInt(candidates.size))
                picked.add(id)
            }
        }
        return picked.mapNotNull { repository.talents[it] }
    }

    private fun resolveChainReplacement(talentId: Int, currentSet: MutableSet<Int>, depth: Int = 0): List<Int> {
        if (depth >= 8) return emptyList()
        val rep = repository.talents[talentId]?.replacement ?: return emptyList()
        val picked: Int? = when {
            rep.talents.isNotEmpty() -> {
                val filtered = rep.talents.filter { (id, _) ->
                    id !in currentSet && findExcludedConflict(id, currentSet.toList()) == null
                }
                if (filtered.isEmpty()) null else pickWeighted(filtered)
            }
            rep.grade != null -> {
                val filtered = (repository.nonExclusiveTalentsByGrade[rep.grade] ?: emptyList()).filter { id ->
                    id !in currentSet && findExcludedConflict(id, currentSet.toList()) == null
                }
                if (filtered.isEmpty()) null else filtered[random.nextInt(filtered.size)]
            }
            else -> null
        }
        if (picked == null) return emptyList()
        val nextSet = currentSet.toMutableSet().apply { add(picked) }
        return listOf(picked) + resolveChainReplacement(picked, nextSet, depth + 1)
    }

    private fun computePresetAllocation(preset: LifeAllocationPreset, totalPoints: Int): IntArray {
        val total = totalPoints.coerceAtLeast(0)
        val cap = max(LifeRestartRules.ALLOC_LIMIT, (total + 3) / 4)
        val arr = IntArray(4)
        when (preset) {
            LifeAllocationPreset.BALANCED -> {
                val base = min(cap, total / 4)
                for (i in 0..3) arr[i] = base
                var rem = total - arr.sum()
                var idx = 0
                while (rem > 0 && idx < 16) {
                    if (arr[idx % 4] < cap) {
                        arr[idx % 4]++
                        rem--
                    }
                    idx++
                }
            }
            LifeAllocationPreset.RANDOM -> {
                var rem = total
                var guard = 0
                while (rem > 0 && guard < 200) {
                    guard++
                    val idx = random.nextInt(4)
                    if (arr[idx] < cap) {
                        arr[idx]++
                        rem--
                    }
                }
            }
            LifeAllocationPreset.CHARM -> fillFocusStat(arr, 0, total, cap)
            LifeAllocationPreset.INTELLIGENCE -> fillFocusStat(arr, 1, total, cap)
            LifeAllocationPreset.STRENGTH -> fillFocusStat(arr, 2, total, cap)
            LifeAllocationPreset.MONEY -> fillFocusStat(arr, 3, total, cap)
        }
        return arr
    }

    private fun fillFocusStat(arr: IntArray, primaryIndex: Int, total: Int, cap: Int) {
        val primary = min(cap, total)
        arr[primaryIndex] = primary
        var rem = total - primary
        val others = (0..3).filter { it != primaryIndex }
        var step = 0
        while (rem > 0 && step < 120) {
            val idx = others[step % others.size]
            if (arr[idx] < cap) {
                arr[idx]++
                rem--
            }
            step++
        }
    }

    private fun <T> pickWeighted(items: List<Pair<T, Double>>): T? {
        if (items.isEmpty()) return null
        val total = items.sumOf { max(0.0, it.second) }
        if (total <= 0.0) return items[random.nextInt(items.size)].first
        var ticket = random.nextDouble() * total
        for ((item, weight) in items) {
            val w = max(0.0, weight)
            if (ticket < w) return item
            ticket -= w
        }
        return items.last().first
    }
}
