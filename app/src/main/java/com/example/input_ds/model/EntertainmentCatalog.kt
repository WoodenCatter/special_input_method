package com.example.input_ds.model

sealed interface EntertainmentGalleryEntry {
    val id: String
    val displayName: String
    val hue: Float
    val isNavigation: Boolean

    data object BackHome : EntertainmentGalleryEntry {
        override val id: String = "back"
        override val displayName: String = "返回主页"
        override val hue: Float = 220f
        override val isNavigation: Boolean = true
    }

    data object BackCategories : EntertainmentGalleryEntry {
        override val id: String = "back_categories"
        override val displayName: String = "返回娱乐"
        override val hue: Float = 220f
        override val isNavigation: Boolean = true
    }

    data class Category(
        val category: EntertainmentCategory,
        val modules: List<EntertainmentHubModule>
    ) : EntertainmentGalleryEntry {
        override val id: String = "cat_${category.id}"
        override val displayName: String = category.displayName
        override val hue: Float = category.hue
        override val isNavigation: Boolean = false
    }

    data class Module(
        val module: EntertainmentHubModule
    ) : EntertainmentGalleryEntry {
        override val id: String = module.id
        override val displayName: String = module.displayName
        override val hue: Float = module.hue
        override val isNavigation: Boolean = false
    }

    data class Market(
        val enabledCount: Int,
        val totalCount: Int
    ) : EntertainmentGalleryEntry {
        override val id: String = "market"
        override val displayName: String = "功能市场"
        override val hue: Float = 205f
        override val isNavigation: Boolean = false
    }
}

enum class EntertainmentMarketPreset(
    val label: String,
    val enabledIds: Set<String>
) {
    ALL("全部开启", EntertainmentHubModule.allSubFeatureIds),
    ELDER(
        "长辈常用",
        setOf(
            EntertainmentHubModule.TELEVISION.id,
            EntertainmentHubModule.MUSIC.id,
            EntertainmentHubModule.NEWS_BRIEFING.id,
            EntertainmentHubModule.CHINESE_CHESS.id,
            EntertainmentHubModule.MAHJONG.id
        )
    ),
    MEDIA_ONLY(
        "极简视听",
        setOf(
            EntertainmentHubModule.TELEVISION.id,
            EntertainmentHubModule.MUSIC.id,
            EntertainmentHubModule.NEWS_BRIEFING.id
        )
    )
}

object EntertainmentCatalog {
    const val FLAT_THRESHOLD = 4

    /** All sub-features ordered by category (视听资讯 -> 棋牌 -> 游戏). */
    val orderedSubFeatures: List<EntertainmentHubModule> =
        EntertainmentCategory.entries.flatMap { EntertainmentHubModule.modulesIn(it) }

    fun enabledModules(enabledIds: Set<String>): List<EntertainmentHubModule> =
        orderedSubFeatures.filter { it.id in enabledIds }

    fun shouldFlatten(enabledIds: Set<String>): Boolean {
        val enabled = enabledModules(enabledIds)
        val nonEmptyCategories = EntertainmentCategory.entries.count { cat ->
            enabled.any { it.category == cat }
        }
        return enabled.size <= FLAT_THRESHOLD || nonEmptyCategories <= 1
    }

    fun resolveCategory(
        requested: EntertainmentCategory?,
        enabledIds: Set<String>
    ): EntertainmentCategory? {
        if (requested == null || shouldFlatten(enabledIds)) return null
        return if (enabledModules(enabledIds).any { it.category == requested }) requested else null
    }

    fun entriesFor(
        activeCategory: EntertainmentCategory?,
        enabledIds: Set<String>
    ): List<EntertainmentGalleryEntry> {
        val total = EntertainmentHubModule.subFeatures.size
        val enabled = enabledModules(enabledIds)
        val effectiveCategory = resolveCategory(activeCategory, enabledIds)
        if (effectiveCategory != null) {
            val catModules = enabled.filter { it.category == effectiveCategory }
            return listOf(EntertainmentGalleryEntry.BackCategories) +
                catModules.map { EntertainmentGalleryEntry.Module(it) }
        }
        if (shouldFlatten(enabledIds)) {
            return listOf(EntertainmentGalleryEntry.BackHome) +
                enabled.map { EntertainmentGalleryEntry.Module(it) } +
                listOf(EntertainmentGalleryEntry.Market(enabled.size, total))
        }
        val categoryCards = EntertainmentCategory.entries.mapNotNull { category ->
            val mods = enabled.filter { it.category == category }
            if (mods.isNotEmpty()) EntertainmentGalleryEntry.Category(category, mods) else null
        }
        return listOf(EntertainmentGalleryEntry.BackHome) +
            categoryCards +
            listOf(EntertainmentGalleryEntry.Market(enabled.size, total))
    }

    fun indexForCategory(
        category: EntertainmentCategory,
        enabledIds: Set<String>
    ): Int {
        val rootEntries = entriesFor(null, enabledIds)
        return rootEntries.indexOfFirst {
            (it as? EntertainmentGalleryEntry.Category)?.category == category
        }.takeIf { it >= 0 } ?: if (rootEntries.size > 1) 1 else 0
    }

    fun entryContextFor(
        previous: AppDestination,
        currentCategory: EntertainmentCategory?,
        enabledIds: Set<String>
    ): Pair<EntertainmentCategory?, Int> {
        if (previous == AppDestination.ENTERTAINMENT_MARKET) {
            val resolved = resolveCategory(currentCategory, enabledIds)
            val entries = entriesFor(resolved, enabledIds)
            val marketIndex = entries.indexOfFirst { it is EntertainmentGalleryEntry.Market }
            val index = if (marketIndex >= 0) marketIndex else if (entries.size > 1) 1 else 0
            return resolved to index
        }
        val returnedModule = EntertainmentHubModule.subFeatures.firstOrNull {
            it.destination == previous && it.id in enabledIds
        }
        if (returnedModule != null) {
            val targetCategory = if (shouldFlatten(enabledIds)) null else returnedModule.category
            val entries = entriesFor(targetCategory, enabledIds)
            val idx = entries.indexOfFirst {
                (it as? EntertainmentGalleryEntry.Module)?.module == returnedModule
            }.takeIf { it >= 0 } ?: if (entries.size > 1) 1 else 0
            return targetCategory to idx
        }
        val entries = entriesFor(null, enabledIds)
        return null to if (entries.size > 1) 1 else 0
    }
}
