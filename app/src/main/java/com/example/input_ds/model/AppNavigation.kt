package com.example.input_ds.model

enum class AppDestination {
    HOME,
    INPUT_METHOD,
    ASYNC_MAZE,
    ENTERTAINMENT,
    MUSIC,
    TV,
    SETTINGS,
    DEVICE_STATUS,
    COLLECTION
}

enum class HomeModule(val destination: AppDestination, val displayName: String) {
    SETTINGS(AppDestination.SETTINGS, "设置"),
    REALTIME_COMMUNICATION(AppDestination.INPUT_METHOD, "实时沟通"),
    MAZE(AppDestination.ASYNC_MAZE, "迷宫游戏"),
    ENTERTAINMENT(AppDestination.ENTERTAINMENT, "娱乐")
}

data class HomeSelectionState(val selectedIndex: Int = 0) {
    val selectedModule: HomeModule
        get() = HomeModule.entries[selectedIndex.coerceIn(HomeModule.entries.indices)]

    fun moveLeft(): HomeSelectionState =
        copy(selectedIndex = (selectedIndex - 1 + HomeModule.entries.size) % HomeModule.entries.size)

    fun moveRight(): HomeSelectionState =
        copy(selectedIndex = (selectedIndex + 1) % HomeModule.entries.size)
}
