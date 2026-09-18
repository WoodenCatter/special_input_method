package com.example.input_ds.model

enum class AppDestination {
    HOME,
    INPUT_METHOD,
    ASYNC_MAZE,
    SNAKE_CLIMB,
    WIZARD_GAME,
    CHINESE_CHESS,
    DOUDIZHU,
    MAHJONG,
    TELEVISION,
    MUSIC,
    SETTINGS,
    DEVICE_STATUS,
    COLLECTION
}

enum class HomeModule(val destination: AppDestination, val displayName: String) {
    SETTINGS(AppDestination.SETTINGS, "设置"),
    REALTIME_COMMUNICATION(AppDestination.INPUT_METHOD, "实时沟通"),
    MAZE(AppDestination.ASYNC_MAZE, "迷宫游戏"),
    SNAKE_CLIMB(AppDestination.SNAKE_CLIMB, "向上贪吃蛇"),
    WIZARD_GAME(AppDestination.WIZARD_GAME, "魔法师游戏"),
    CHINESE_CHESS(AppDestination.CHINESE_CHESS, "中国象棋"),
    DOUDIZHU(AppDestination.DOUDIZHU, "斗地主"),
    MAHJONG(AppDestination.MAHJONG, "打麻将"),
    TELEVISION(AppDestination.TELEVISION, "看电视"),
    MUSIC(AppDestination.MUSIC, "音乐")
}

data class HomeSelectionState(val selectedIndex: Int = 0) {
    val selectedModule: HomeModule
        get() = HomeModule.entries[selectedIndex.coerceIn(HomeModule.entries.indices)]

    fun moveLeft(): HomeSelectionState =
        copy(selectedIndex = (selectedIndex - 1 + HomeModule.entries.size) % HomeModule.entries.size)

    fun moveRight(): HomeSelectionState =
        copy(selectedIndex = (selectedIndex + 1) % HomeModule.entries.size)
}
