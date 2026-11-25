package com.example.input_ds.ui.news

import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode

internal enum class NewsFocusMode {
    /** Direct 3-command headline browsing: left = prev article, right = next article, bite = open menu. */
    BROWSE,
    /** Compact action menu over browse view: read full / continue / switch source / directory / refresh / back. */
    BROWSE_MENU,
    /** 3x3 RSS source selector. */
    SOURCES,
    /** Slow-scanned headline directory. */
    ARTICLES,
    /** Direct 3-command full-article reader: left = prev page, right = next page (or next article), bite = open menu. */
    READING,
    /** Compact action menu inside full-article reader: continue / read aloud / next article / exit reading. */
    READING_MENU
}

internal data class NewsSelectionResult(
    val slotIndex: Int,
    val targetIndex: Int,
    val compensated: Boolean
)

/**
 * Pure §6 scan-cursor state machine for [NewsBriefingScreen].
 *
 * Keeps direct left/right navigation free of auto-scan distractions in both [NewsFocusMode.BROWSE]
 * and [NewsFocusMode.READING], while driving uniform §6 auto/manual scanning in menus, source picker,
 * and headline directory.
 */
internal data class NewsScanState(
    val focusMode: NewsFocusMode = NewsFocusMode.BROWSE,
    val browseMenuCursor: ScanCursor = ScanCursor(size = 3, index = 0, direction = 1),
    val sourceCursor: ScanCursor = ScanCursor(size = 1, index = 0, direction = 1),
    val articleCursor: ScanCursor = ScanCursor(size = 1, index = 0, direction = 1),
    val readingMenuCursor: ScanCursor = ScanCursor(size = 4, index = 0, direction = 1),
    val articleCount: Int = 0,
    val sourceCount: Int = 0,
    val dwellRevision: Int = 0
) {
    val isDirectNavigationMode: Boolean
        get() = focusMode == NewsFocusMode.BROWSE || focusMode == NewsFocusMode.READING

    fun enabledBrowseMenuTargets(): List<Int> = enabledBrowseTargetsFor(articleCount)

    fun enabledReadingMenuTargets(): List<Int> = if (articleCount > 1) {
        listOf(READING_CONTINUE, READING_SPEAK, READING_NEXT_ARTICLE, READING_EXIT)
    } else {
        listOf(READING_CONTINUE, READING_SPEAK, READING_EXIT)
    }

    fun activeCursor(): ScanCursor = when (focusMode) {
        NewsFocusMode.BROWSE, NewsFocusMode.READING -> ScanCursor(size = 1, index = 0, direction = 1)
        NewsFocusMode.BROWSE_MENU -> browseMenuCursor
        NewsFocusMode.SOURCES -> sourceCursor
        NewsFocusMode.ARTICLES -> articleCursor
        NewsFocusMode.READING_MENU -> readingMenuCursor
    }

    fun activeTargetIndex(): Int = targetIndexForSlot(activeCursor().index)

    fun targetIndexForSlot(slotIndex: Int): Int = when (focusMode) {
        NewsFocusMode.BROWSE_MENU -> {
            val enabled = enabledBrowseMenuTargets()
            enabled.getOrElse(slotIndex) { enabled.first() }
        }
        NewsFocusMode.READING_MENU -> {
            val enabled = enabledReadingMenuTargets()
            enabled.getOrElse(slotIndex) { enabled.first() }
        }
        else -> slotIndex
    }

    fun syncCounts(newArticleCount: Int, newSourceCount: Int): NewsScanState {
        val safeArticles = newArticleCount.coerceAtLeast(0)
        val safeSources = newSourceCount.coerceAtLeast(0)
        if (safeArticles == articleCount && safeSources == sourceCount) return this

        val prevMenuTarget = targetIndexForBrowseSlot(browseMenuCursor.index, articleCount)
        val nextBrowseEnabled = enabledBrowseTargetsFor(safeArticles)
        val preferredTarget = when {
            articleCount == 0 && safeArticles > 0 -> BROWSE_READ_FULL
            else -> prevMenuTarget
        }
        val nextBrowseSlot = nextBrowseEnabled.indexOf(preferredTarget)
            .takeIf { it >= 0 }
            ?: nextBrowseEnabled.indexOf(BROWSE_READ_FULL).takeIf { it >= 0 }
            ?: nextBrowseEnabled.indexOf(BROWSE_SOURCES).takeIf { it >= 0 }
            ?: 0
        val nextBrowseMenuCursor = if (browseMenuCursor.size != nextBrowseEnabled.size || (articleCount == 0 && safeArticles > 0)) {
            browseMenuCursor.reset(newSize = nextBrowseEnabled.size, initial = nextBrowseSlot)
        } else {
            browseMenuCursor
        }

        val nextSourceSize = safeSources + 1
        val nextSourceCursor = if (sourceCursor.size != nextSourceSize) {
            sourceCursor.reset(
                newSize = nextSourceSize,
                initial = sourceCursor.index.coerceIn(0, nextSourceSize - 1)
            )
        } else {
            sourceCursor
        }

        val nextArticleSize = if (safeArticles == 0) 1 else safeArticles + 1
        val nextArticleCursor = if (articleCursor.size != nextArticleSize) {
            articleCursor.reset(
                newSize = nextArticleSize,
                initial = articleCursor.index.coerceIn(0, nextArticleSize - 1)
            )
        } else {
            articleCursor
        }

        val nextReadingMenuSize = if (safeArticles > 1) 4 else 3
        val nextReadingMenuCursor = if (readingMenuCursor.size != nextReadingMenuSize) {
            readingMenuCursor.reset(
                newSize = nextReadingMenuSize,
                initial = readingMenuCursor.index.coerceIn(0, nextReadingMenuSize - 1)
            )
        } else {
            readingMenuCursor
        }

        val nextFocusMode = when {
            safeArticles == 0 && focusMode in setOf(
                NewsFocusMode.ARTICLES,
                NewsFocusMode.READING,
                NewsFocusMode.READING_MENU
            ) -> NewsFocusMode.BROWSE_MENU
            articleCount == 0 && safeArticles > 0 && focusMode == NewsFocusMode.BROWSE_MENU ->
                NewsFocusMode.BROWSE
            else -> focusMode
        }

        return copy(
            focusMode = nextFocusMode,
            browseMenuCursor = nextBrowseMenuCursor,
            sourceCursor = nextSourceCursor,
            articleCursor = nextArticleCursor,
            readingMenuCursor = nextReadingMenuCursor,
            articleCount = safeArticles,
            sourceCount = safeSources,
            dwellRevision = if (nextFocusMode != focusMode || nextBrowseMenuCursor != browseMenuCursor) {
                dwellRevision + 1
            } else {
                dwellRevision
            }
        )
    }

    fun look(step: Int, mode: ScanMode): NewsScanState {
        if (isDirectNavigationMode) return this
        return withActiveCursor { it.look(step, mode) }.copy(dwellRevision = dwellRevision + 1)
    }

    fun advance(nowMs: Long, mode: ScanMode): NewsScanState {
        if (isDirectNavigationMode) return this
        return withActiveCursor { it.advance(nowMs, mode) }
    }

    fun selectOnBite(nowMs: Long, graceMs: Long, mode: ScanMode): NewsSelectionResult? {
        if (isDirectNavigationMode) return null
        val cursor = activeCursor()
        if (cursor.size <= 0) return null
        val slot = cursor.selected(nowMs, graceMs, mode)
        return NewsSelectionResult(
            slotIndex = slot,
            targetIndex = targetIndexForSlot(slot),
            compensated = slot != cursor.index
        )
    }

    fun clearActiveGrace(): NewsScanState {
        if (isDirectNavigationMode) return this
        return withActiveCursor { it.copy(previousIndex = -1, advancedAtMs = 0L) }
    }

    fun resetForPauseBoundary(): NewsScanState {
        if (isDirectNavigationMode) return this
        return withActiveCursor { it.reset() }.copy(dwellRevision = dwellRevision + 1)
    }

    fun enterBrowse(): NewsScanState = copy(
        focusMode = NewsFocusMode.BROWSE,
        dwellRevision = dwellRevision + 1
    )

    fun enterBrowseMenu(preferredTarget: Int = DEFAULT_BROWSE_MENU_TARGET): NewsScanState {
        val enabled = enabledBrowseMenuTargets()
        val slot = enabled.indexOf(preferredTarget)
            .takeIf { it >= 0 }
            ?: enabled.indexOf(BROWSE_SOURCES).takeIf { it >= 0 }
            ?: 0
        return copy(
            focusMode = NewsFocusMode.BROWSE_MENU,
            browseMenuCursor = ScanCursor(size = enabled.size, index = slot, direction = 1),
            dwellRevision = dwellRevision + 1
        )
    }

    fun enterSources(activeSourceIndex: Int): NewsScanState {
        val size = (sourceCount + 1).coerceAtLeast(1)
        val initialSlot = (activeSourceIndex + 1).coerceIn(0, size - 1)
        return copy(
            focusMode = NewsFocusMode.SOURCES,
            sourceCursor = ScanCursor(size = size, index = initialSlot, direction = 1),
            dwellRevision = dwellRevision + 1
        )
    }

    fun enterArticles(activeArticleIndex: Int): NewsScanState {
        if (articleCount <= 0) return enterBrowseMenu(BROWSE_SOURCES)
        val size = articleCount + 1
        val initialSlot = (activeArticleIndex + 1).coerceIn(0, size - 1)
        return copy(
            focusMode = NewsFocusMode.ARTICLES,
            articleCursor = ScanCursor(size = size, index = initialSlot, direction = 1),
            dwellRevision = dwellRevision + 1
        )
    }

    fun enterReading(): NewsScanState {
        if (articleCount <= 0) return enterBrowseMenu(BROWSE_SOURCES)
        return copy(
            focusMode = NewsFocusMode.READING,
            dwellRevision = dwellRevision + 1
        )
    }

    fun enterReadingMenu(initialTarget: Int = READING_CONTINUE): NewsScanState {
        val enabled = enabledReadingMenuTargets()
        val slot = enabled.indexOf(initialTarget).takeIf { it >= 0 } ?: 0
        return copy(
            focusMode = NewsFocusMode.READING_MENU,
            readingMenuCursor = ScanCursor(size = enabled.size, index = slot, direction = 1),
            dwellRevision = dwellRevision + 1
        )
    }

    private inline fun withActiveCursor(transform: (ScanCursor) -> ScanCursor): NewsScanState = when (focusMode) {
        NewsFocusMode.BROWSE, NewsFocusMode.READING -> this
        NewsFocusMode.BROWSE_MENU -> copy(browseMenuCursor = transform(browseMenuCursor))
        NewsFocusMode.SOURCES -> copy(sourceCursor = transform(sourceCursor))
        NewsFocusMode.ARTICLES -> copy(articleCursor = transform(articleCursor))
        NewsFocusMode.READING_MENU -> copy(readingMenuCursor = transform(readingMenuCursor))
    }

    companion object {
        const val BROWSE_READ_FULL = 0
        const val BROWSE_CONTINUE = 1
        const val BROWSE_SOURCES = 2
        const val BROWSE_DIRECTORY = 3
        const val BROWSE_REFRESH = 4
        const val BROWSE_BACK = 5

        const val READING_CONTINUE = 0
        const val READING_SPEAK = 1
        const val READING_NEXT_ARTICLE = 2
        const val READING_EXIT = 3

        const val DEFAULT_BROWSE_MENU_TARGET = BROWSE_READ_FULL

        private fun enabledBrowseTargetsFor(articleCount: Int): List<Int> = if (articleCount <= 0) {
            listOf(BROWSE_SOURCES, BROWSE_REFRESH, BROWSE_BACK)
        } else {
            listOf(
                BROWSE_READ_FULL,
                BROWSE_CONTINUE,
                BROWSE_SOURCES,
                BROWSE_DIRECTORY,
                BROWSE_REFRESH,
                BROWSE_BACK
            )
        }

        private fun targetIndexForBrowseSlot(slot: Int, articleCount: Int): Int {
            val enabled = enabledBrowseTargetsFor(articleCount)
            return enabled.getOrElse(slot) { enabled.first() }
        }
    }
}
