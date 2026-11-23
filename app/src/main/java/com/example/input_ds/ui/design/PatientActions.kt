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

internal fun patientPath(page: AppDestination, chat: CommunicationState, video: VideoState, parent: AppDestination): List<String> {
    val name = chat.contact?.name.orEmpty()
    val media = if (parent == AppDestination.LIFE_ASSISTANT) "生活助手" else "娱乐"
    if (video.call != null) return if (video.incoming) listOf("来电",video.peerName) else listOf("沟通",video.peerName,"视频通话")
    return when (page) {
        AppDestination.HOME -> listOf("主页")
        AppDestination.COMMUNICATION -> when (chat.stage) {
            CommunicationStage.CONTACT_LIST -> listOf("主页","沟通")
            CommunicationStage.CONTACT_ACTIONS -> listOf("沟通",name)
            CommunicationStage.CHAT_INPUT -> listOf("沟通",name,"文字消息")
            CommunicationStage.PAIRING -> listOf("沟通","邀请家属")
        }
        AppDestination.INPUT_METHOD -> listOf("沟通","当面说话")
        AppDestination.LIFE_ASSISTANT -> listOf("主页","生活助手")
        AppDestination.APP_SETTINGS -> listOf("主页","设置")
        AppDestination.ENTERTAINMENT -> listOf("主页","娱乐")
        AppDestination.TV -> listOf(media,"看电视")
        AppDestination.MUSIC -> listOf(media,"音乐")
        else -> listOf("娱乐",EntertainmentHubModule.entries.firstOrNull { it.destination == page }?.displayName.orEmpty())
    }
}

internal fun communicationHints(chat: CommunicationState, input: InputState): ActionHints = when (chat.stage) {
    CommunicationStage.CONTACT_LIST -> ActionHints.list(chat.contact?.name ?: "返回主页",ScanMode.MANUAL,0)
    CommunicationStage.CONTACT_ACTIONS -> ActionHints("","选「"+listOf("返回","输入法","视频通话")[chat.actionIndex]+"」","")
    CommunicationStage.PAIRING -> ActionHints("","","")
    CommunicationStage.CHAT_INPUT -> inputHints(input, true)
}

internal fun inputHints(s: InputState, chat: Boolean = false): ActionHints {
    if (s.phase == InputPhase.LEVEL_1_SCANNING) {
        val left = s.scanSide == ScanSide.LEFT
        val blocks = if (left) LetterBlockMapping.LEFT_BLOCKS else LetterBlockMapping.RIGHT_BLOCKS
        val label = if (chat && s.highlightedBlockIndex >= 4)
            listOf("返回","发送","删除").getOrNull(s.highlightedBlockIndex-4)
        else LetterBlockMapping.DIGIT_LABELS[blocks.getOrNull(s.highlightedBlockIndex)]
        val bite = if (chat && s.highlightedBlockIndex >= 4) "选「$label」" else if (s.selectedBlocks.isEmpty()) "打开常用语" else "去选拼音"
        return ActionHints(if (left) "选「$label」" else "切到左侧",bite,if (left) "切到右侧" else "选「$label」",if (left) -1 else 1)
    }
    val label = when {
        s.phase == InputPhase.INITIAL_PREDICTION -> SelectionPaging.initialSentenceItems(s.initialCandidates,s.initialPage).getOrNull(s.highlightedInitialIndex)?.label
        s.phase == InputPhase.PREDICTION -> (if (s.isCommonPhraseSelection) SelectionPaging.commonPhraseItems(s.predictionCandidates,s.predictionPage)
            else SelectionPaging.predictionItems(s.predictionCandidates)).getOrNull(s.highlightedPredictionIndex)?.label
        s.phase == InputPhase.LEVEL_2_LETTER_SELECT && !s.isCharFocused -> (listOf("返回") + s.pinyinCombinations + "首字母整句").getOrNull(s.highlightedPinyinIndex)
        else -> SelectionPaging.characterItems(s.charCandidates,s.charPage).getOrNull(s.highlightedCharIndex)?.label
    }
    return ActionHints.list(label.orEmpty(),ScanMode.AUTO,s.charScanDirection)
}
