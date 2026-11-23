package com.example.input_ds.ui.design

import androidx.compose.runtime.*
import com.example.input_ds.communication.*
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.model.*

val LocalActionHints = staticCompositionLocalOf<(ActionHints) -> Unit> { {} }

@Composable
fun ReportActionHints(hints: ActionHints) {
    val publish = LocalActionHints.current
    SideEffect { publish(hints) }
}

internal fun confirmHints(label: String, mode: ScanMode): ActionHints =
    if (mode == ScanMode.MANUAL) ActionHints("上一项", "选「$label」", "下一项")
    else ActionHints("多停一会", "选「$label」", "多停一会")

internal fun patientPath(
    page: AppDestination,
    chat: CommunicationState,
    video: VideoState,
    parent: AppDestination,
    inputMode: InputMode = InputMode.Talk
): List<String> {
    val name = chat.contact?.name.orEmpty()
    val media = if (parent == AppDestination.LIFE_ASSISTANT) "生活助手" else "娱乐"
    if (video.call != null) return if (video.incoming) listOf("来电", video.peerName) else listOf("沟通", video.peerName, "视频通话")
    return when (page) {
        AppDestination.HOME -> listOf("主页")
        AppDestination.COMMUNICATION -> when (chat.stage) {
            CommunicationStage.CONTACT_LIST -> listOf("主页", "沟通")
            CommunicationStage.CONTACT_ACTIONS -> listOf("沟通", name)
            CommunicationStage.CHAT_INPUT -> listOf("沟通", name, "文字消息")
            CommunicationStage.PAIRING -> listOf("沟通", "邀请家属")
        }
        AppDestination.INPUT_METHOD -> when (inputMode) {
            is InputMode.Talk -> listOf("沟通", "当面说话")
            is InputMode.Chat -> listOf("沟通", inputMode.contactName, "文字消息")
            is InputMode.Assistant -> listOf("生活助手", "输入")
        }
        AppDestination.LIFE_ASSISTANT -> listOf("主页", "生活助手")
        AppDestination.APP_SETTINGS -> listOf("主页", "设置")
        AppDestination.ENTERTAINMENT -> listOf("主页", "娱乐")
        AppDestination.TV -> listOf(media, "看电视")
        AppDestination.MUSIC -> listOf(media, "音乐")
        else -> listOf("娱乐", EntertainmentHubModule.entries.firstOrNull { it.destination == page }?.displayName.orEmpty())
    }
}

internal fun communicationHints(chat: CommunicationState, input: InputState): ActionHints = when (chat.stage) {
    CommunicationStage.CONTACT_LIST -> {
        val label = when {
            chat.isSelfFocused -> "当面说话"
            chat.isInviteFocused -> "邀请家属"
            chat.isHomeFocused -> "返回主页"
            else -> chat.contact?.name.orEmpty()
        }
        ActionHints.list(label, chat.scanMode, chat.listDirection)
    }
    CommunicationStage.CONTACT_ACTIONS -> {
        val label = listOf("文字消息", "视频通话", "返回联系人").getOrElse(chat.actionIndex) { "" }
        ActionHints.list(label, chat.scanMode, chat.actionDirection)
    }
    CommunicationStage.PAIRING -> {
        val items = if (chat.canRegenerateInvite) listOf("返回联系人", "换一个邀请码") else listOf("返回联系人")
        val label = items.getOrElse(chat.pairingIndex) { items.first() }
        ActionHints.list(label, chat.scanMode, chat.pairingDirection)
    }
    CommunicationStage.CHAT_INPUT -> inputHints(input)
}

internal fun inputHints(s: InputState): ActionHints {
    if (s.phase == InputPhase.LEVEL_1_SCANNING) {
        val left = s.scanSide == ScanSide.LEFT
        val blocks = if (left) LetterBlockMapping.LEFT_BLOCKS else LetterBlockMapping.RIGHT_BLOCKS
        val block = blocks.getOrNull(s.highlightedBlockIndex)
        val label = block?.let { LetterBlockMapping.labelFor(it, s.mode is InputMode.Talk) }.orEmpty()
        val bite = if (s.selectedBlocks.isEmpty()) "打开常用语" else "去选拼音"
        return ActionHints(
            left = if (left) "选「$label」" else "切到左侧",
            bite = bite,
            right = if (left) "切到右侧" else "选「$label」",
            direction = if (left) -1 else 1
        )
    }
    val label = when {
        s.phase == InputPhase.INITIAL_PREDICTION ->
            SelectionPaging.initialSentenceItems(s.initialCandidates, s.initialPage).getOrNull(s.highlightedInitialIndex)?.label
        s.phase == InputPhase.PREDICTION -> (
            if (s.isCommonPhraseSelection) SelectionPaging.commonPhraseItems(s.predictionCandidates, s.predictionPage)
            else SelectionPaging.predictionItems(s.predictionCandidates, s.mode.sendLabel)
            ).getOrNull(s.highlightedPredictionIndex)?.label
        s.phase == InputPhase.LEVEL_2_LETTER_SELECT && !s.isCharFocused ->
            (listOf("返回") + s.pinyinCombinations + if (s.hasInitialPredictionOption) listOf("首字母整句") else emptyList())
                .getOrNull(s.highlightedPinyinIndex)
        else ->
            SelectionPaging.characterItems(s.charCandidates, s.charPage).getOrNull(s.highlightedCharIndex)?.label
    }
    return ActionHints.list(label.orEmpty(), s.scanMode, s.charScanDirection)
}
