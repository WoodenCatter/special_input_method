package com.example.input_ds.assistant

import java.io.Serializable

private const val MINUTE_MS = 60_000L

internal enum class SimulatedDevice(val id: String, val label: String) {
    MAIN_LIGHT("main_light", "大灯"),
    BEDSIDE_LIGHT("bedside_light", "床头灯"),
    CURTAIN("curtain", "窗帘"),
    AIR_CONDITIONER("air_conditioner", "空调")
}

internal enum class AirConditionerMode(val label: String) {
    COOL("制冷"), HEAT("制热"), AUTO("自动"), DRY("除湿"), FAN("送风")
}

internal enum class AirConditionerSwing(val label: String) {
    AUTO("自动摆风"), UP("向上"), DOWN("向下"), FIXED("固定")
}

internal data class AirConditionerState(
    val powerOn: Boolean = true,
    val temperatureC: Int = 26,
    val mode: AirConditionerMode = AirConditionerMode.COOL,
    val swing: AirConditionerSwing = AirConditionerSwing.AUTO,
    val offAtMillis: Long? = null
) : Serializable {
    fun remainingMinutes(now: Long): Int {
        val remaining = (offAtMillis ?: return 0) - now
        return ((remaining.coerceAtLeast(0L) + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }
}

internal data class SimulatedHome(
    val mainLightOn: Boolean = false,
    val bedsideLightOn: Boolean = false,
    val curtainPositionPercent: Int = 0,
    val airConditioner: AirConditionerState = AirConditionerState()
) : Serializable {
    fun expireTimer(now: Long): SimulatedHome {
        val deadline = airConditioner.offAtMillis ?: return this
        return if (now < deadline) this else copy(
            airConditioner = airConditioner.copy(powerOn = false, offAtMillis = null)
        )
    }

    fun apply(action: HomeAction, now: Long): SimulatedHome? {
        val current = expireTimer(now)
        return when (action.device) {
            SimulatedDevice.MAIN_LIGHT -> current.copy(mainLightOn = action.powerOn ?: return null)
            SimulatedDevice.BEDSIDE_LIGHT -> current.copy(bedsideLightOn = action.powerOn ?: return null)
            SimulatedDevice.CURTAIN -> current.copy(curtainPositionPercent = action.position ?: return null)
            SimulatedDevice.AIR_CONDITIONER -> {
                val ac = current.airConditioner
                val powerOn = action.powerOn ?: ac.powerOn
                val timer = action.offTimerMinutes
                if (!powerOn && timer != null && timer > 0) return null
                val offAt = when {
                    !powerOn || timer == 0 -> null
                    timer != null -> now + timer * MINUTE_MS
                    else -> ac.offAtMillis
                }
                current.copy(airConditioner = ac.copy(
                    powerOn = powerOn,
                    temperatureC = action.temperatureC ?: ac.temperatureC,
                    mode = action.mode ?: ac.mode,
                    swing = action.swing ?: ac.swing,
                    offAtMillis = offAt
                ))
            }
        }
    }
}

internal data class HomeAction(
    val device: SimulatedDevice,
    val powerOn: Boolean? = null,
    val temperatureC: Int? = null,
    val mode: AirConditionerMode? = null,
    val swing: AirConditionerSwing? = null,
    val offTimerMinutes: Int? = null,
    val position: Int? = null
) : Serializable {
    fun describe(home: SimulatedHome): String = buildList {
        powerOn?.let { add("${if (it) "打开" else "关闭"}${device.label}") }
        temperatureC?.let {
            val verb = if (it < home.airConditioner.temperatureC) "降温到" else "设温到"
            add("$verb $it°C")
        }
        mode?.let { add("切换到${it.label}") }
        swing?.let { add("设为${if (it == AirConditionerSwing.AUTO) it.label else "风向${it.label}"}") }
        offTimerMinutes?.let { add(if (it == 0) "取消定时关机" else "定时 $it 分钟后关机") }
        position?.let {
            add(when (it) {
                0 -> "关闭窗帘"
                100 -> "完全打开窗帘"
                else -> "把窗帘打开到 $it%"
            })
        }
    }.joinToString("，")

    companion object {
        fun parse(deviceId: String, settings: Map<String, String>): HomeAction? {
            val device = SimulatedDevice.entries.firstOrNull { it.id == deviceId } ?: return null
            val allowed = when (device) {
                SimulatedDevice.MAIN_LIGHT, SimulatedDevice.BEDSIDE_LIGHT -> setOf("power")
                SimulatedDevice.CURTAIN -> setOf("position_percent")
                SimulatedDevice.AIR_CONDITIONER ->
                    setOf("power", "temperature_c", "mode", "swing", "off_timer_minutes")
            }
            if (settings.isEmpty() || !allowed.containsAll(settings.keys)) return null

            val power = settings["power"]?.let {
                when (it) {
                    "on" -> true
                    "off" -> false
                    else -> return null
                }
            }
            val temperature = settings["temperature_c"]?.let { it.validInteger(16..30) ?: return null }
            val mode = settings["mode"]?.let { id ->
                AirConditionerMode.entries.firstOrNull { it.name.lowercase() == id } ?: return null
            }
            val swing = settings["swing"]?.let { id ->
                AirConditionerSwing.entries.firstOrNull { it.name.lowercase() == id } ?: return null
            }
            val timer = settings["off_timer_minutes"]?.let { it.validInteger(0..720) ?: return null }
            val position = settings["position_percent"]?.let { it.validInteger(0..100) ?: return null }

            return HomeAction(device, power, temperature, mode, swing, timer, position)
        }

        private fun String.validInteger(range: IntRange): Int? =
            toIntOrNull()?.takeIf { it in range && it.toString() == this }
    }
}
