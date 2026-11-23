package com.example.input_ds.ui.communication

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.example.input_ds.communication.CommunicationStage
import com.example.input_ds.communication.CommunicationViewModel
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.InputPhase
import com.example.input_ds.personalization.ConversationTurn
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.viewmodel.InputMethodViewModel

@Composable
fun CommunicationScreen(
    chat: CommunicationViewModel,
    input: InputMethodViewModel,
    paused: Boolean = false,
    frozen: Boolean = false,
    onToast: (String, Boolean) -> Unit = { _, _ -> },
    onHome: () -> Unit
) {
    val state by chat.state.collectAsState()
    val draft by input.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(draft.scanIntervalMs) { chat.setScanInterval(draft.scanIntervalMs) }
    LaunchedEffect(state.contact?.conversationId, state.messages) {
        input.updateConversationContext(state.messages.takeLast(8).map { message ->
            ConversationTurn(if (message.senderId == state.myPrincipalId) "user" else "assistant", message.text)
        })
    }
    LaunchedEffect(state.stage, state.contact?.conversationId) {
        val contact = state.contact
        if (state.stage == CommunicationStage.CHAT_INPUT && contact != null) {
            input.openMode(
                mode = InputMode.Chat(contact.conversationId, contact.name),
                onSend = { text ->
                    val trimmed = text.trim()
                    if (trimmed.isEmpty()) {
                        onToast("还没有要发的内容", false)
                    } else if (chat.queue(trimmed)) {
                        input.clearOutputAndReset()
                        onToast("已发送给${contact.name}", true)
                    }
                },
                onBack = { chat.back() }
            )
        } else {
            input.deactivate()
        }
    }
    AuroraBackground {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxHeight().weight(0.26f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                .padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("◉", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("联系人", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = chat::pairing) { Text("+") }
                }
                Spacer(Modifier.height(8.dp))
                if (state.contacts.isEmpty()) Text("暂无联系人。点击 + 生成邀请码，请家属在手机端绑定。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(state.contacts.size) { index ->
                        val contact = state.contacts[index]
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .background(if (state.selectedIndex == index) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                            .clickable { chat.selectContact(index); chat.actions() }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("◉", color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(contact.lastMessage ?: if (contact.online && state.connected) "在线" else "",
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (contact.unread > 0) Text("${contact.unread}", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth().border(
                    if (state.selectedIndex == state.contacts.size && state.stage == CommunicationStage.CONTACT_LIST) 2.dp else 0.dp,
                    MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))) { Text("返回主页") }
            }
            Column(Modifier.fillMaxHeight().weight(0.74f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(20.dp))
                .padding(12.dp)) {
                when (state.stage) {
                    CommunicationStage.CONTACT_LIST -> {
                        Text(state.contact?.name ?: "请选择联系人", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(state.contact?.lastMessage ?: "选择联系人后咬牙进入通讯方式",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (state.contact != null) Button(onClick = chat::actions, modifier = Modifier.fillMaxWidth()) { Text("选择通讯方式") }
                    }
                    CommunicationStage.CONTACT_ACTIONS -> {
                        Text(state.contact?.name ?: "通讯方式", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("返回", "输入法", "视频通话").forEachIndexed { index, label ->
                                val selected = index == state.actionIndex
                                Button(onClick = { chat.chooseAction(index) }, modifier = Modifier
                                    .width(150.dp).height(110.dp)
                                    .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(24.dp))) { Text(label) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    CommunicationStage.CHAT_INPUT -> {
                        Text(state.contact?.name ?: "会话", style = MaterialTheme.typography.titleLarge)
                        if (state.messages.isNotEmpty()) OutlinedButton(onClick = chat::older) { Text("加载更早消息") }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), reverseLayout = true) {
                            items(state.pending.filter { it.conversationId == state.contact?.conversationId &&
                                state.messages.none { message -> message.clientId == it.clientId } }.reversed()) { item ->
                                MessageRow("${item.text} · 待发送", true)
                            }
                            items(state.messages.reversed(), key = { it.id }) { message ->
                                MessageRow("${message.text}  ${message.createdAt.take(16).replace('T', ' ')}",
                                    message.senderId == state.myPrincipalId)
                            }
                        }
                        Box(Modifier.fillMaxWidth().weight(1.35f)) {
                            val phrases = remember(draft.mode, draft.phase, draft.dwellRevision) {
                                input.rankedCommonPhrases(draft.mode)
                            }
                            ComposerScreen(
                                state = draft,
                                phrases = phrases,
                                paused = paused,
                                frozen = frozen,
                                compactWings = true,
                                onBlockClick = input::selectBlockByTouch,
                                onPinyinClick = input::selectPinyinByTouch,
                                onPinyinNavigationClick = input::selectPinyinNavigationByTouch,
                                onCharacterClick = input::selectCharacterByTouch,
                                onCharacterItemClick = input::selectCharacterItemByTouch,
                                onCommonPhraseClick = input::selectCommonPhraseByTouch,
                                onPredictionClick = input::selectPredictionByTouch,
                                onInitialPredictionClick = input::selectInitialPredictionByTouch
                            )
                        }
                    }
                    CommunicationStage.PAIRING -> {
                        Text("邀请家属", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(24.dp))
                        Text("请家属在手机端输入以下邀请码（10 分钟有效）：")
                        Text(state.invitation ?: "正在生成…", style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.primary)
                        if (state.invitation != null) OutlinedButton(onClick = {
                            clipboard.setText(AnnotatedString(state.invitation!!))
                        }) { Text("复制邀请码") }
                        Text(state.invitationExpires ?: "")
                        OutlinedButton(onClick = chat::back) { Text("返回") }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.loading) Text("正在加载…")
            }
        }
    }
}

@Composable
private fun MessageRow(text: String, own: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Text(text, modifier = Modifier.widthIn(max = 480.dp)
            .background(if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(16.dp)).padding(12.dp))
    }
}
