package com.example.input_ds.model

enum class AppDestination {
    HOME,
    ENTERTAINMENT,
    INPUT_METHOD,
    COMMUNICATION,
    LIFE_ASSISTANT,
    ASYNC_MAZE,
    SNAKE_CLIMB,
    WATERMELON,
    LIFE_RESTART,
    WIZARD_GAME,
    CHINESE_CHESS,
    DOUDIZHU,
    MAHJONG,
    TV,
    MUSIC,
    NEWS_BRIEFING,
    APP_SETTINGS,
    ENTERTAINMENT_MARKET,
    SPEED_PRACTICE,
    SETTINGS,
    DEVICE_STATUS,
    COLLECTION
}

enum class HomeModule(
    val destination: AppDestination,
    val displayName: String,
    val subtitle: String
) {
    COMMUNICATION(AppDestination.COMMUNICATION, "沟通", "消息 · 视频 · 当面说"),
    LIFE_ASSISTANT(AppDestination.LIFE_ASSISTANT, "生活助手", "问答 · 点播 · 家居"),
    ENTERTAINMENT(AppDestination.ENTERTAINMENT, "娱乐", "游戏 · 音乐 · 电视"),
    APP_SETTINGS(AppDestination.APP_SETTINGS, "设置", "音量 · 亮度 · 扫描"),
    CALL_NURSE(AppDestination.HOME, "呼叫护士", "紧急求助");

    fun resolveDestination(@Suppress("UNUSED_PARAMETER") demoCommunication: Boolean): AppDestination = destination
}

data class HomeSelectionState(
    val selectedIndex: Int = 0,
    val scanDirection: Int = 1
) {
    val selectedModule: HomeModule
        get() = HomeModule.entries[selectedIndex.coerceIn(HomeModule.entries.indices)]

    private fun cursor(): ScanCursor =
        ScanCursor(HomeModule.entries.size, selectedIndex.coerceIn(HomeModule.entries.indices), scanDirection)

    fun moveLeft(): HomeSelectionState = onDirection(-1, ScanMode.MANUAL)

    fun moveRight(): HomeSelectionState = onDirection(1, ScanMode.MANUAL)

    fun advance(): HomeSelectionState = cursor().advance(0L, ScanMode.AUTO).let {
        copy(selectedIndex = it.index, scanDirection = it.direction)
    }

    fun changeDirection(direction: Int): HomeSelectionState = onDirection(direction, ScanMode.AUTO)

    fun onDirection(direction: Int, mode: ScanMode): HomeSelectionState =
        cursor().look(if (direction < 0) -1 else 1, mode).let {
            copy(selectedIndex = it.index, scanDirection = it.direction)
        }
}

enum class EntertainmentCategory(
    val id: String,
    val displayName: String,
    val hue: Float
) {
    MEDIA("media", "视听资讯", 214f),
    BOARD("board", "棋牌", 12f),
    GAMES("games", "游戏", 148f);
}

enum class EntertainmentHubModule(
    val id: String,
    val destination: AppDestination,
    val displayName: String,
    val hue: Float,
    val category: EntertainmentCategory? = null,
    val isNavigation: Boolean = false
) {
    BACK("back", AppDestination.HOME, "返回主页", 220f, category = null, isNavigation = true),
    MAZE("maze", AppDestination.ASYNC_MAZE, "迷宫", 198f, category = EntertainmentCategory.GAMES),
    SNAKE_CLIMB("snake", AppDestination.SNAKE_CLIMB, "向上贪吃蛇", 132f, category = EntertainmentCategory.GAMES),
    WATERMELON("watermelon", AppDestination.WATERMELON, "合成大西瓜", 148f, category = EntertainmentCategory.GAMES),
    LIFE_RESTART("liferestart", AppDestination.LIFE_RESTART, "人生重开", 48f, category = EntertainmentCategory.GAMES),
    WIZARD_GAME("wizard", AppDestination.WIZARD_GAME, "魔法师", 262f, category = EntertainmentCategory.GAMES),
    CHINESE_CHESS("chess", AppDestination.CHINESE_CHESS, "中国象棋", 12f, category = EntertainmentCategory.BOARD),
    DOUDIZHU("ddz", AppDestination.DOUDIZHU, "斗地主", 345f, category = EntertainmentCategory.BOARD),
    MAHJONG("mj", AppDestination.MAHJONG, "麻将", 165f, category = EntertainmentCategory.BOARD),
    TELEVISION("tv", AppDestination.TV, "看电视", 214f, category = EntertainmentCategory.MEDIA),
    MUSIC("music", AppDestination.MUSIC, "音乐", 34f, category = EntertainmentCategory.MEDIA),
    NEWS_BRIEFING("news", AppDestination.NEWS_BRIEFING, "看新闻", 232f, category = EntertainmentCategory.MEDIA);

    companion object {
        val subFeatures: List<EntertainmentHubModule> = entries.filter { !it.isNavigation }
        val allSubFeatureIds: Set<String> = subFeatures.map { it.id }.toSet()

        fun modulesIn(category: EntertainmentCategory): List<EntertainmentHubModule> =
            entries.filter { it.category == category }

        fun initialIndexForEntry(previous: AppDestination): Int =
            entries.indexOfFirst { !it.isNavigation && it.destination == previous }
                .takeIf { it >= 0 } ?: MAZE.ordinal
    }
}

data class EntertainmentHubSelectionState(
    val selectedIndex: Int = EntertainmentHubModule.MAZE.ordinal,
    val scanDirection: Int = 1
) {
    val selectedModule: EntertainmentHubModule
        get() = EntertainmentHubModule.entries[selectedIndex.coerceIn(EntertainmentHubModule.entries.indices)]

    private fun cursor(): ScanCursor =
        ScanCursor(
            EntertainmentHubModule.entries.size,
            selectedIndex.coerceIn(EntertainmentHubModule.entries.indices),
            scanDirection
        )

    fun moveLeft(): EntertainmentHubSelectionState = onDirection(-1, ScanMode.MANUAL)

    fun moveRight(): EntertainmentHubSelectionState = onDirection(1, ScanMode.MANUAL)

    fun advance(): EntertainmentHubSelectionState = cursor().advance(0L, ScanMode.AUTO).let {
        copy(selectedIndex = it.index, scanDirection = it.direction)
    }

    fun changeDirection(direction: Int): EntertainmentHubSelectionState = onDirection(direction, ScanMode.AUTO)

    fun onDirection(direction: Int, mode: ScanMode): EntertainmentHubSelectionState =
        cursor().look(if (direction < 0) -1 else 1, mode).let {
            copy(selectedIndex = it.index, scanDirection = it.direction)
        }
}
