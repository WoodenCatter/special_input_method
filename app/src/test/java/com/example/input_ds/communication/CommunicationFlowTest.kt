package com.example.input_ds.communication

import com.example.input_ds.model.AppDestination
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.InputState
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.design.communicationHints
import com.example.input_ds.ui.design.formatChatTime
import com.example.input_ds.ui.design.patientPath
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunicationFlowTest {
    private val mom = ChatContact("p1", "妈妈", "c1", online = true, unread = 2, lastMessage = "中午我给你带粥")

    @Test fun communicationHintsMatchPrototypeAcrossStages() {
        val listSelf = CommunicationState(
            stage = CommunicationStage.CONTACT_LIST,
            contacts = listOf(mom),
            selectedIndex = 0,
            listDirection = 1,
            scanMode = ScanMode.AUTO
        )
        assertEquals("选「当面说话」", communicationHints(listSelf, InputState()).bite)
        assertEquals("向左轮转", communicationHints(listSelf, InputState()).left)

        val listMom = listSelf.copy(selectedIndex = 1, scanMode = ScanMode.MANUAL)
        assertEquals("选「妈妈」", communicationHints(listMom, InputState()).bite)
        assertEquals("上一项", communicationHints(listMom, InputState()).left)

        val actions = listSelf.copy(stage = CommunicationStage.CONTACT_ACTIONS, activeContactId = mom.principalId, actionIndex = 0)
        assertEquals("选「文字消息」", communicationHints(actions, InputState()).bite)
        assertEquals("选「视频通话」", communicationHints(actions.copy(actionIndex = 1), InputState()).bite)
        assertEquals("选「返回联系人」", communicationHints(actions.copy(actionIndex = 2), InputState()).bite)

        val pairing = listSelf.copy(stage = CommunicationStage.PAIRING, configured = true, invitation = "K7Q2M9", pairingIndex = 1)
        assertEquals("选「换一个邀请码」", communicationHints(pairing, InputState()).bite)
    }

    @Test fun patientPathReflectsCommunicationAndVideoStates() {
        val base = CommunicationState(contacts = listOf(mom), selectedIndex = 1, activeContactId = mom.principalId)
        assertEquals(
            listOf("主页", "沟通"),
            patientPath(AppDestination.COMMUNICATION, base.copy(stage = CommunicationStage.CONTACT_LIST), VideoState(), AppDestination.ENTERTAINMENT)
        )
        assertEquals(
            listOf("沟通", "妈妈"),
            patientPath(AppDestination.COMMUNICATION, base.copy(stage = CommunicationStage.CONTACT_ACTIONS), VideoState(), AppDestination.ENTERTAINMENT)
        )
        assertEquals(
            listOf("沟通", "妈妈", "文字消息"),
            patientPath(AppDestination.COMMUNICATION, base.copy(stage = CommunicationStage.CHAT_INPUT), VideoState(), AppDestination.ENTERTAINMENT)
        )
        assertEquals(
            listOf("沟通", "邀请家属"),
            patientPath(AppDestination.COMMUNICATION, base.copy(stage = CommunicationStage.PAIRING), VideoState(), AppDestination.ENTERTAINMENT)
        )
        assertEquals(
            listOf("沟通", "当面说话"),
            patientPath(AppDestination.INPUT_METHOD, base, VideoState(), AppDestination.ENTERTAINMENT, InputMode.Talk)
        )
        val outgoing = VideoState(
            call = VideoCall("1", "me", "我", "ACTIVE", 1L, ""),
            incoming = false,
            peerName = "妈妈"
        )
        assertEquals(
            listOf("沟通", "妈妈", "视频通话"),
            patientPath(AppDestination.COMMUNICATION, base, outgoing, AppDestination.ENTERTAINMENT)
        )
        val incoming = outgoing.copy(incoming = true)
        assertEquals(
            listOf("来电", "妈妈"),
            patientPath(AppDestination.HOME, base, incoming, AppDestination.ENTERTAINMENT)
        )
    }

    @Test fun formatChatTimePreservesShortLabelsAndFormatsIso() {
        assertEquals("09:12", formatChatTime("09:12"))
        assertEquals("昨天", formatChatTime("昨天"))
        assertEquals("周一", formatChatTime("周一"))
        assertEquals("", formatChatTime(""))
        assertEquals(5, formatChatTime("2026-11-23T09:12:00Z").length)
    }
}
