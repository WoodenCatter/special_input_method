package com.example.input_ds.model

enum class AppDestination {
    HOME,
    ENTERTAINMENT,
    INPUT_METHOD,
    ASYNC_MAZE,
    SNAKE_CLIMB,
    WIZARD_GAME,
    CHINESE_CHESS,
    DOUDIZHU,
    MAHJONG,
    TV,
    MUSIC,
    APP_SETTINGS,
    SETTINGS,
    DEVICE_STATUS,
    COLLECTION
}

enum class HomeModule(
    val destination: AppDestination,
    val displayName: String,
    val scanEnabled: Boolean = true
) {
    APP_SETTINGS(AppDestination.APP_SETTINGS, "设置"),
    REALTIME_COMMUNICATION(AppDestination.INPUT_METHOD, "实时沟通"),
    ENTERTAINMENT(AppDestination.ENTERTAINMENT, "娱乐"),
    HEADSET_SETTINGS(AppDestination.SETTINGS, "耳机设置", scanEnabled = false)
}

data class HomeSelectionState(
    val selectedIndex: Int = 0,
    val scanDirection: Int = 1
) {
    val selectedModule: HomeModule
        get() = HomeModule.entries[selectedIndex.coerceIn(HomeModule.entries.indices)]

    private fun move(offset: Int): HomeSelectionState {
        val candidates = HomeModule.entries.indices.filter { HomeModule.entries[it].scanEnabled }
        val position = candidates.indexOf(selectedIndex).takeIf { it >= 0 } ?: 0
        val nextPosition = (position + offset + candidates.size) % candidates.size
        return copy(selectedIndex = candidates[nextPosition])
    }

    fun moveLeft(): HomeSelectionState = move(-1)

    fun moveRight(): HomeSelectionState = move(1)

    fun advance(): HomeSelectionState = if (scanDirection < 0) moveLeft() else moveRight()

    fun changeDirection(direction: Int): HomeSelectionState =
        copy(scanDirection = if (direction < 0) -1 else 1)
}

enum class EntertainmentHubModule(val destination: AppDestination, val displayName: String, val symbol: String) {
    MAZE(AppDestination.ASYNC_MAZE, "迷宫游戏", "▦"),
    SNAKE_CLIMB(AppDestination.SNAKE_CLIMB, "向上贪吃蛇", "蛇"),
    WIZARD_GAME(AppDestination.WIZARD_GAME, "魔法师游戏", "✦"),
    CHINESE_CHESS(AppDestination.CHINESE_CHESS, "中国象棋", "楚"),
    DOUDIZHU(AppDestination.DOUDIZHU, "斗地主", "斗"),
    MAHJONG(AppDestination.MAHJONG, "打麻将", "麻"),
    TELEVISION(AppDestination.TV, "看电视", "视"),
    MUSIC(AppDestination.MUSIC, "音乐", "♪"),
    BACK(AppDestination.HOME, "返回主页", "←")
}

data class EntertainmentHubSelectionState(
    val selectedIndex: Int = 0,
    val scanDirection: Int = 1
) {
    val selectedModule: EntertainmentHubModule
        get() = EntertainmentHubModule.entries[selectedIndex.coerceIn(EntertainmentHubModule.entries.indices)]

    fun moveLeft(): EntertainmentHubSelectionState =
        copy(selectedIndex = (selectedIndex - 1 + EntertainmentHubModule.entries.size) % EntertainmentHubModule.entries.size)

    fun moveRight(): EntertainmentHubSelectionState =
        copy(selectedIndex = (selectedIndex + 1) % EntertainmentHubModule.entries.size)

    fun advance(): EntertainmentHubSelectionState = if (scanDirection < 0) moveLeft() else moveRight()

    fun changeDirection(direction: Int): EntertainmentHubSelectionState =
        copy(scanDirection = if (direction < 0) -1 else 1)
}
