package com.example.input_ds.game

import android.content.res.AssetManager
import java.io.InputStream

data class LifeTalentEffect(
    val chr: Int = 0,
    val intVal: Int = 0,
    val str: Int = 0,
    val mny: Int = 0,
    val spr: Int = 0,
    val rnd: Int = 0
)

data class LifeTalentReplacement(
    val grade: Int? = null,
    val talents: List<Pair<Int, Double>> = emptyList()
)

data class LifeTalent(
    val id: Int,
    val name: String,
    val description: String,
    val condition: String? = null,
    val max: Int = 1,
    val grade: Int = 0,
    val exclusive: Boolean = false,
    val points: Int = 0,
    val effect: LifeTalentEffect? = null,
    val replacement: LifeTalentReplacement? = null,
    val exclude: List<Int> = emptyList()
)

data class LifeEventEffect(
    val chr: Int = 0,
    val intVal: Int = 0,
    val str: Int = 0,
    val mny: Int = 0,
    val spr: Int = 0,
    val lif: Int = 0,
    val age: Int = 0
)

data class LifeEventBranch(
    val condition: String,
    val eventId: Int
)

data class LifeEvent(
    val id: Int,
    val text: String,
    val grade: Int = 0,
    val format: Boolean = false,
    val postEvent: String? = null,
    val effect: LifeEventEffect? = null,
    val noRandom: Boolean = false,
    val include: String? = null,
    val exclude: String? = null,
    val branches: List<LifeEventBranch> = emptyList()
)

data class LifeAgePool(
    val age: Int,
    val levels: List<List<Pair<Int, Double>>>
)

enum class LifeAchievementOpportunity {
    START,
    TRAJECTORY,
    SUMMARY,
    END
}

data class LifeAchievement(
    val id: Int,
    val name: String,
    val description: String,
    val grade: Int = 0,
    val condition: String,
    val hide: Boolean = false,
    val opportunity: LifeAchievementOpportunity = LifeAchievementOpportunity.TRAJECTORY
)

class LifeRestartRepository(
    val talents: Map<Int, LifeTalent>,
    val events: Map<Int, LifeEvent>,
    val ages: Map<Int, LifeAgePool>,
    val achievements: Map<Int, LifeAchievement>
) {
    val nonExclusiveTalentsByGrade: Map<Int, List<Int>> =
        talents.values
            .filter { !it.exclusive }
            .groupBy { it.grade }
            .mapValues { (_, list) -> list.map { it.id } }

    val achievementsByOpportunity: Map<LifeAchievementOpportunity, List<LifeAchievement>> =
        achievements.values.groupBy { it.opportunity }

    companion object {
        @Volatile
        private var cached: LifeRestartRepository? = null

        fun loadFromAssets(assets: AssetManager): LifeRestartRepository {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val repo = load(
                    talentsStream = assets.open("liferestart/talents.json"),
                    eventsStream = assets.open("liferestart/events.json"),
                    agesStream = assets.open("liferestart/ages.json"),
                    achievementsStream = assets.open("liferestart/achievements.json")
                )
                cached = repo
                return repo
            }
        }

        fun load(
            talentsStream: InputStream,
            eventsStream: InputStream,
            agesStream: InputStream,
            achievementsStream: InputStream
        ): LifeRestartRepository {
            val talentsJson = talentsStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val eventsJson = eventsStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val agesJson = agesStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val achievementsJson = achievementsStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(talentsJson, eventsJson, agesJson, achievementsJson)
        }

        fun parse(
            talentsJson: String,
            eventsJson: String,
            agesJson: String,
            achievementsJson: String
        ): LifeRestartRepository {
            val talentsMap = LinkedHashMap<Int, LifeTalent>()
            for (raw in MiniJson.parseArray(talentsJson)) {
                val o = raw.asObject()
                val id = o.int("id")
                val eff = o.objOrNull("effect")?.let { e ->
                    LifeTalentEffect(
                        chr = e.int("CHR", 0),
                        intVal = e.int("INT", 0),
                        str = e.int("STR", 0),
                        mny = e.int("MNY", 0),
                        spr = e.int("SPR", 0),
                        rnd = e.int("RND", 0)
                    )
                }
                val rep = o.objOrNull("replacement")?.let { r ->
                    val grade = if (r.containsKey("grade") && r["grade"] != null) r.int("grade") else null
                    val tList = r.listOrNull("talent").orEmpty().map { entry ->
                        val pair = entry.asList()
                        pair[0].asInt() to (pair.getOrNull(1)?.asDouble() ?: 1.0)
                    }
                    LifeTalentReplacement(grade = grade, talents = tList)
                }
                val excludes = o.listOrNull("exclude").orEmpty().map { it.asInt() }
                talentsMap[id] = LifeTalent(
                    id = id,
                    name = o.str("name"),
                    description = o.str("description"),
                    condition = o.strOrNull("condition"),
                    max = o.int("max", 1).coerceAtLeast(1),
                    grade = o.int("grade", 0),
                    exclusive = o.bool("exclusive", false),
                    points = o.int("points", 0),
                    effect = eff,
                    replacement = rep,
                    exclude = excludes
                )
            }

            val eventsMap = LinkedHashMap<Int, LifeEvent>()
            for (raw in MiniJson.parseArray(eventsJson)) {
                val o = raw.asObject()
                val id = o.int("id")
                val eff = o.objOrNull("effect")?.let { e ->
                    LifeEventEffect(
                        chr = e.int("CHR", 0),
                        intVal = e.int("INT", 0),
                        str = e.int("STR", 0),
                        mny = e.int("MNY", 0),
                        spr = e.int("SPR", 0),
                        lif = e.int("LIF", 0),
                        age = e.int("AGE", 0)
                    )
                }
                val branches = o.listOrNull("branch").orEmpty().map { entry ->
                    val b = entry.asObject()
                    LifeEventBranch(
                        condition = b.str("condition"),
                        eventId = b.int("event")
                    )
                }
                eventsMap[id] = LifeEvent(
                    id = id,
                    text = o.str("event"),
                    grade = o.int("grade", 0),
                    format = o.bool("format", false),
                    postEvent = o.strOrNull("postEvent"),
                    effect = eff,
                    noRandom = o.bool("noRandom", false),
                    include = o.strOrNull("include"),
                    exclude = o.strOrNull("exclude"),
                    branches = branches
                )
            }

            val agesMap = LinkedHashMap<Int, LifeAgePool>()
            for (raw in MiniJson.parseArray(agesJson)) {
                val o = raw.asObject()
                val age = o.int("age")
                val levels = o.listOrNull("levels").orEmpty().map { lvlRaw ->
                    lvlRaw.asList().map { pairRaw ->
                        val pair = pairRaw.asList()
                        pair[0].asInt() to (pair.getOrNull(1)?.asDouble() ?: 1.0)
                    }
                }
                agesMap[age] = LifeAgePool(age = age, levels = levels)
            }

            val achMap = LinkedHashMap<Int, LifeAchievement>()
            for (raw in MiniJson.parseArray(achievementsJson)) {
                val o = raw.asObject()
                val id = o.int("id")
                val opp = runCatching {
                    LifeAchievementOpportunity.valueOf(o.str("opportunity", "TRAJECTORY").uppercase())
                }.getOrDefault(LifeAchievementOpportunity.TRAJECTORY)
                achMap[id] = LifeAchievement(
                    id = id,
                    name = o.str("name"),
                    description = o.str("description"),
                    grade = o.int("grade", 0),
                    condition = o.str("condition"),
                    hide = o.bool("hide", false),
                    opportunity = opp
                )
            }

            return LifeRestartRepository(
                talents = talentsMap,
                events = eventsMap,
                ages = agesMap,
                achievements = achMap
            )
        }
    }
}

/**
 * Minimal pure-Kotlin RFC-8259 JSON parser so LifeRestart assets load identically
 * on Android devices and JVM unit tests without `org.json` mocking stubs.
 */
private class MiniJson(private val src: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWs()
        if (pos >= src.length) return null
        return when (val ch = src[pos]) {
            '{' -> parseObject()
            '[' -> parseList()
            '"' -> parseString()
            't' -> { pos += 4; true }
            'f' -> { pos += 5; false }
            'n' -> { pos += 4; null }
            else -> if (ch == '-' || ch in '0'..'9') parseNumber() else error("Unexpected JSON char '$ch' at $pos")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        pos++ // '{'
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (pos >= src.length) error("Unterminated JSON object at EOF")
        if (src[pos] == '}') {
            pos++
            return map
        }
        while (true) {
            skipWs()
            if (pos >= src.length || src[pos] != '"') error("Expected JSON object key at $pos")
            val key = parseString()
            skipWs()
            if (pos >= src.length || src[pos] != ':') error("Expected ':' after key '$key' at $pos")
            pos++
            val value = parseValue()
            map[key] = value
            skipWs()
            if (pos >= src.length) error("Unterminated JSON object after key '$key'")
            when (src[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }
                else -> error("Expected ',' or '}' in JSON object at $pos, found '${src[pos]}'")
            }
        }
    }

    private fun parseList(): List<Any?> {
        pos++ // '['
        val list = ArrayList<Any?>()
        skipWs()
        if (pos >= src.length) error("Unterminated JSON array at EOF")
        if (src[pos] == ']') {
            pos++
            return list
        }
        while (true) {
            list.add(parseValue())
            skipWs()
            if (pos >= src.length) error("Unterminated JSON array at EOF")
            when (src[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> error("Expected ',' or ']' in JSON array at $pos, found '${src[pos]}'")
            }
        }
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            val ch = src[pos++]
            if (ch == '"') return sb.toString()
            if (ch == '\\') {
                if (pos >= src.length) error("Unterminated JSON escape at EOF")
                when (val esc = src[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > src.length) error("Truncated unicode escape at $pos")
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(ch)
            }
        }
        error("Unterminated JSON string at EOF")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (src[pos] == '-') pos++
        var isFloat = false
        while (pos < src.length) {
            val ch = src[pos]
            if (ch in '0'..'9') {
                pos++
            } else if (ch == '.' || ch == 'e' || ch == 'E' || ch == '+' || ch == '-') {
                isFloat = true
                pos++
            } else {
                break
            }
        }
        val token = src.substring(start, pos)
        return if (isFloat) token.toDouble() else token.toLong()
    }

    private fun skipWs() {
        while (pos < src.length && src[pos] <= ' ') pos++
    }

    fun expectEof() {
        skipWs()
        if (pos < src.length) error("Unexpected trailing JSON char '${src[pos]}' at $pos")
    }

    companion object {
        fun parseArray(json: String): List<Any?> {
            val parser = MiniJson(json)
            val result = parser.parseValue().asList()
            parser.expectEof()
            return result
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asObject(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Any?.asList(): List<Any?> = this as List<Any?>

private fun Any?.asInt(default: Int = 0): Int = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull() ?: default
    else -> default
}

private fun Any?.asDouble(default: Double = 0.0): Double = when (this) {
    is Number -> toDouble()
    is String -> toDoubleOrNull() ?: default
    else -> default
}

private fun Map<String, Any?>.int(key: String, default: Int = 0): Int = this[key].asInt(default)

private fun Map<String, Any?>.bool(key: String, default: Boolean = false): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    else -> default
}

private fun Map<String, Any?>.str(key: String, default: String = ""): String =
    (this[key] as? String) ?: default

private fun Map<String, Any?>.strOrNull(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.objOrNull(key: String): Map<String, Any?>? =
    this[key] as? Map<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.listOrNull(key: String): List<Any?>? =
    this[key] as? List<Any?>
