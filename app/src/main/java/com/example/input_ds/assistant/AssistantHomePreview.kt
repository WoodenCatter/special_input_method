package com.example.input_ds.assistant

internal data class HomeStatusRow(
    val key: String,
    val label: String,
    val value: String
)

internal data class HomePreviewRow(
    val key: String,
    val label: String,
    val currentValue: String,
    val nextValue: String?
)

internal object AssistantHomePreview {
    fun rows(home: SimulatedHome, nowMillis: Long, frozen: Boolean = false): List<HomeStatusRow> {
        val effective = if (frozen) home else home.expireTimer(nowMillis)
        val ac = effective.airConditioner
        val curtainText = when (effective.curtainPositionPercent) {
            0 -> "关闭"
            100 -> "全开"
            else -> "${effective.curtainPositionPercent}%"
        }
        val acText = if (ac.powerOn) "${ac.mode.label} ${ac.temperatureC}°C" else "关"
        val timerText = when {
            ac.offAtMillis == null -> "无"
            frozen -> "1 分钟后"
            else -> {
                val sec = ((ac.offAtMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1000L
                if (sec >= 60L) "${(sec + 59L) / 60L} 分钟后" else "$sec 秒后"
            }
        }
        return listOf(
            HomeStatusRow("main", "大灯", if (effective.mainLightOn) "开" else "关"),
            HomeStatusRow("bed", "床头灯", if (effective.bedsideLightOn) "开" else "关"),
            HomeStatusRow("curtain", "窗帘", curtainText),
            HomeStatusRow("ac", "空调", acText),
            HomeStatusRow("swing", "风向", ac.swing.label),
            HomeStatusRow("timer", "定时关机", timerText)
        )
    }

    /** Returns cancel-first confirmation labels: `[取消, option0, option1, ...]`. */
    fun confirmationLabels(options: List<HomeAction>, home: SimulatedHome): List<String> =
        listOf("取消") + options.map { it.describe(home) }

    /** Computes preview diff rows when `focusedOption` is highlighted during confirmation. */
    fun previewRows(
        home: SimulatedHome,
        focusedOption: HomeAction?,
        nowMillis: Long,
        frozen: Boolean = false
    ): List<HomePreviewRow> {
        val current = rows(home, nowMillis, frozen)
        val projected = focusedOption?.let { home.apply(it, nowMillis) }?.let { rows(it, nowMillis, frozen) }
        return current.mapIndexed { idx, row ->
            val nextVal = projected?.getOrNull(idx)?.value?.takeIf { it != row.value }
            HomePreviewRow(
                key = row.key,
                label = row.label,
                currentValue = row.value,
                nextValue = nextVal
            )
        }
    }

    /** Returns row keys whose displayed value changed between `before` and `after`. */
    fun changedKeys(before: SimulatedHome, after: SimulatedHome, nowMillis: Long, frozen: Boolean = false): Set<String> {
        val b = rows(before, nowMillis, frozen)
        val a = rows(after, nowMillis, frozen)
        return a.mapIndexedNotNull { idx, row ->
            if (b.getOrNull(idx)?.value != row.value) row.key else null
        }.toSet()
    }

    /**
     * Debug-only prototype keyword reply helper (`fake assistant` command per spec §11.2).
     * Never used in production mode when real `AssistantService` is active.
     */
    fun fakeReply(
        prompt: String,
        home: SimulatedHome,
        channels: List<AssistantCatalogItem>,
        tracks: List<AssistantCatalogItem>
    ): AssistantResult {
        fun has(vararg keys: String) = keys.any { prompt.contains(it) }
        val ac = home.airConditioner
        val acOn = HomeAction(
            device = SimulatedDevice.AIR_CONDITIONER,
            powerOn = true,
            temperatureC = 26,
            mode = AirConditionerMode.COOL
        )
        if (has("体育")) {
            return AssistantResult.Message(
                "有两个体育频道：CCTV-5 体育、CCTV-5+ 体育赛事，想看哪一个？",
                clarify = true
            )
        }
        if (has("新闻", "央视")) {
            val target = channels.firstOrNull { it.id == "cctv13" } ?: channels.firstOrNull()
            if (target != null) return AssistantResult.PlayTv(target.id)
        }
        if (has("电视")) {
            val target = channels.firstOrNull { it.id == "cctv1" } ?: channels.firstOrNull()
            if (target != null) return AssistantResult.PlayTv(target.id)
        }
        if (has("演员")) {
            val target = tracks.firstOrNull { it.name.contains("演员") } ?: tracks.firstOrNull()
            if (target != null) return AssistantResult.PlayMusic(target.id)
        }
        if (has("音乐", "歌")) {
            val target = tracks.firstOrNull()
            if (target != null) return AssistantResult.PlayMusic(target.id)
        }
        if (has("分钟后关")) {
            return if (ac.powerOn) {
                AssistantResult.ConfirmHome(listOf(HomeAction(SimulatedDevice.AIR_CONDITIONER, offTimerMinutes = 1)))
            } else {
                AssistantResult.Message("空调现在是关着的，不需要定时关机。", clarify = false)
            }
        }
        if (has("关空调")) {
            return if (ac.powerOn) {
                AssistantResult.ConfirmHome(listOf(HomeAction(SimulatedDevice.AIR_CONDITIONER, powerOn = false)))
            } else {
                AssistantResult.Message("空调已经是关着的。", clarify = false)
            }
        }
        if (has("开空调")) {
            return if (ac.powerOn) {
                AssistantResult.Message("空调已经开着：${ac.mode.label} ${ac.temperatureC}°C。", clarify = false)
            } else {
                AssistantResult.ConfirmHome(listOf(acOn))
            }
        }
        if (has("热", "降温")) {
            return when {
                !ac.powerOn -> AssistantResult.ConfirmHome(listOf(acOn))
                ac.mode == AirConditionerMode.COOL -> AssistantResult.ConfirmHome(
                    listOf(HomeAction(SimulatedDevice.AIR_CONDITIONER, temperatureC = (ac.temperatureC - 1).coerceAtLeast(16)))
                )
                else -> AssistantResult.ConfirmHome(
                    listOf(HomeAction(SimulatedDevice.AIR_CONDITIONER, mode = AirConditionerMode.COOL, temperatureC = 26))
                )
            }
        }
        if (has("一半")) {
            return AssistantResult.ConfirmHome(listOf(HomeAction(SimulatedDevice.CURTAIN, position = 50)))
        }
        if (has("窗帘")) {
            return if (has("关")) {
                if (home.curtainPositionPercent > 0) AssistantResult.ConfirmHome(listOf(HomeAction(SimulatedDevice.CURTAIN, position = 0)))
                else AssistantResult.Message("窗帘已经关好了。", clarify = false)
            } else {
                if (home.curtainPositionPercent < 100) AssistantResult.ConfirmHome(listOf(HomeAction(SimulatedDevice.CURTAIN, position = 100)))
                else AssistantResult.Message("窗帘已经全开了。", clarify = false)
            }
        }
        if (has("床头灯")) {
            return if (has("关")) {
                if (home.bedsideLightOn) AssistantResult.ConfirmHome(listOf(HomeAction(SimulatedDevice.BEDSIDE_LIGHT, powerOn = false)))
                else AssistantResult.Message("床头灯已经是关着的。", clarify = false)
            } else {
                if (home.bedsideLightOn) AssistantResult.Message("床头灯已经开着了。", clarify = false)
                else AssistantResult.ConfirmHome(listOf(HomeAction(SimulatedDevice.BEDSIDE_LIGHT, powerOn = true)))
            }
        }
        if (has("关灯")) {
            val opts = buildList {
                if (home.mainLightOn) add(HomeAction(SimulatedDevice.MAIN_LIGHT, powerOn = false))
                if (home.bedsideLightOn) add(HomeAction(SimulatedDevice.BEDSIDE_LIGHT, powerOn = false))
            }
            return if (opts.isNotEmpty()) AssistantResult.ConfirmHome(opts)
            else AssistantResult.Message("灯都已经关着了。", clarify = false)
        }
        if (has("开灯")) {
            val opts = buildList {
                if (!home.mainLightOn) add(HomeAction(SimulatedDevice.MAIN_LIGHT, powerOn = true))
                if (!home.bedsideLightOn) add(HomeAction(SimulatedDevice.BEDSIDE_LIGHT, powerOn = true))
            }
            return if (opts.isNotEmpty()) AssistantResult.ConfirmHome(opts)
            else AssistantResult.Message("灯都已经开着了。", clarify = false)
        }
        if (has("天空", "蓝色")) {
            return AssistantResult.Message(
                "阳光里的蓝光波长短，更容易被空气分子散射到各个方向，所以白天的天空看起来是蓝色的。",
                clarify = false
            )
        }
        return AssistantResult.Message("收到：「$prompt」", clarify = false)
    }
}
