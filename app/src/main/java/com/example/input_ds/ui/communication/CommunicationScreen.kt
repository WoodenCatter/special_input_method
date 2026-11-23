package com.example.input_ds.ui.communication

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.R
import com.example.input_ds.communication.ChatContact
import com.example.input_ds.communication.ChatMessage
import com.example.input_ds.communication.CommunicationSignalOutcome
import com.example.input_ds.communication.CommunicationStage
import com.example.input_ds.communication.CommunicationState
import com.example.input_ds.communication.CommunicationViewModel
import com.example.input_ds.communication.ContactSelectionPolicy
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.ScanMode
import com.example.input_ds.personalization.ConversationTurn
import com.example.input_ds.ui.design.ChatBubble
import com.example.input_ds.ui.design.ContactAvatar
import com.example.input_ds.ui.design.contactAvatarHue
import com.example.input_ds.ui.design.currentChatTime
import com.example.input_ds.ui.design.formatChatTime
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import com.example.input_ds.viewmodel.InputMethodViewModel

@Composable
fun CommunicationScreen(
    chat: CommunicationViewModel,
    input: InputMethodViewModel,
    paused: Boolean = false,
    frozen: Boolean = false,
    onToast: (String, Boolean) -> Unit = { _, _ -> },
    onOpenTalk: () -> Unit = {},
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
                        if (!frozen) onToast("已发送给${contact.name}", true)
                    }
                },
                onBack = { chat.back() }
            )
        } else {
            input.deactivate()
        }
    }

    val stageDwellKey = when (state.stage) {
        CommunicationStage.CONTACT_LIST -> "list:${state.selectedIndex}:${state.listDirection}"
        CommunicationStage.CONTACT_ACTIONS -> "act:${state.actionIndex}:${state.actionDirection}"
        CommunicationStage.PAIRING -> "pair:${state.pairingIndex}:${state.pairingDirection}"
        CommunicationStage.CHAT_INPUT -> "chat"
    }
    val dwellProgress = remember { Animatable(0f) }
    val autoScan = state.scanMode == ScanMode.AUTO && state.stage != CommunicationStage.CHAT_INPUT
    LaunchedEffect(stageDwellKey, state.dwellRevision, draft.scanIntervalMs, autoScan, paused, frozen) {
        when {
            frozen -> dwellProgress.snapTo(if (autoScan) 0.5f else 0f)
            paused || !autoScan -> dwellProgress.snapTo(0f)
            else -> {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(draft.scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }
    val progressProvider: () -> Float? = { if (autoScan) dwellProgress.value else null }

    when (state.stage) {
        CommunicationStage.CONTACT_LIST -> ContactListLayout(
            state = state,
            sleepList = false,
            progress = progressProvider,
            onRowClick = { row ->
                when (chat.activateRow(row)) {
                    CommunicationSignalOutcome.OPEN_TALK -> onOpenTalk()
                    CommunicationSignalOutcome.EXIT_HOME -> onHome()
                    CommunicationSignalOutcome.HANDLED -> Unit
                }
            }
        ) {
            ContactListPreviewPanel(state = state)
        }

        CommunicationStage.CONTACT_ACTIONS -> {
            val contact = state.contact
            if (contact == null) {
                LaunchedEffect(Unit) { chat.enterContactList() }
            } else {
                ContactListLayout(
                    state = state,
                    sleepList = true,
                    progress = progressProvider,
                    onRowClick = { row ->
                        val contactIdx = ContactSelectionPolicy.contactIndexForRow(row, state.contacts.size)
                        if (row == ContactSelectionPolicy.SELF_INDEX) {
                            chat.activateRow(row)
                            onOpenTalk()
                        } else if (contactIdx != null) {
                            chat.openContactActions(contactIdx, initialAction = 0)
                        }
                    }
                ) {
                    ContactActionsPanel(
                        state = state,
                        contact = contact,
                        progress = progressProvider,
                        onChoose = chat::chooseAction
                    )
                }
            }
        }

        CommunicationStage.CHAT_INPUT -> {
            val contact = state.contact
            if (contact == null) {
                LaunchedEffect(Unit) { chat.enterContactList() }
            } else {
                ChatInputLayout(
                    state = state,
                    contact = contact,
                    draft = draft,
                    input = input,
                    paused = paused,
                    frozen = frozen,
                    onOlder = chat::older
                )
            }
        }

        CommunicationStage.PAIRING -> PairingLayout(
            state = state,
            frozen = frozen,
            progress = progressProvider,
            onCopy = { code ->
                clipboard.setText(AnnotatedString(code))
                onToast("已复制邀请码", true)
            },
            onChoose = chat::choosePairing
        )
    }
}

@Composable
private fun ContactListLayout(
    state: CommunicationState,
    sleepList: Boolean,
    progress: () -> Float?,
    onRowClick: (Int) -> Unit,
    sideContent: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val c = LocalBciColors.current
    val listState = rememberLazyListState()
    val contactCount = state.contacts.size
    val inviteRow = ContactSelectionPolicy.inviteIndex(contactCount)
    val homeRow = ContactSelectionPolicy.homeIndex(contactCount)
    val dir = if (state.scanMode == ScanMode.AUTO) state.listDirection else 0

    LaunchedEffect(state.selectedIndex, sleepList, contactCount) {
        if (!sleepList && state.selectedIndex in 0..contactCount) {
            listState.animateScrollToItem(state.selectedIndex)
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(
            modifier = Modifier
                .width(500.dp)
                .fillMaxHeight()
                .alpha(if (sleepList) 0.42f else 1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.loading && state.contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "正在加载联系人…",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 22.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "self") {
                        val focused = !sleepList && state.selectedIndex == ContactSelectionPolicy.SELF_INDEX
                        ContactRowItem(
                            name = "当面说话",
                            relation = null,
                            subtitle = "打字后朗读，并放大给身边的人看",
                            avatarText = "说",
                            self = true,
                            online = false,
                            hue = 24f,
                            unread = 0,
                            focused = focused,
                            selectedBorder = false,
                            direction = dir,
                            progress = progress,
                            onClick = { onRowClick(ContactSelectionPolicy.SELF_INDEX) }
                        )
                    }
                    items(count = contactCount, key = { state.contacts[it].principalId }) { idx ->
                        val contact = state.contacts[idx]
                        val rowIdx = ContactSelectionPolicy.rowForContactIndex(idx)
                        val focused = !sleepList && state.selectedIndex == rowIdx
                        val msgs = state.messagesFor(contact.conversationId)
                        val sub = msgs.lastOrNull()?.text
                            ?: contact.lastMessage?.takeIf { it.isNotBlank() }
                            ?: if (contact.online) "在线" else "离线"
                        ContactRowItem(
                            name = contact.name,
                            relation = contact.relation,
                            subtitle = sub,
                            avatarText = contact.name,
                            self = false,
                            online = contact.online,
                            hue = contactAvatarHue(contact.principalId, contact.avatarHue),
                            unread = contact.unread,
                            focused = focused,
                            selectedBorder = sleepList && state.activeContactId == contact.principalId,
                            direction = dir,
                            progress = progress,
                            onClick = { onRowClick(rowIdx) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val inviteFocused = !sleepList && state.selectedIndex == inviteRow
                val homeFocused = !sleepList && state.selectedIndex == homeRow
                NavBottomCell(
                    label = "邀请家属",
                    iconRes = R.drawable.ic_lucide_user_plus,
                    focused = inviteFocused,
                    direction = dir,
                    progress = progress,
                    enabled = !sleepList,
                    onClick = { if (!sleepList) onRowClick(inviteRow) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                NavBottomCell(
                    label = "返回主页",
                    iconRes = R.drawable.ic_lucide_house,
                    focused = homeFocused,
                    direction = dir,
                    progress = progress,
                    enabled = !sleepList,
                    onClick = { if (!sleepList) onRowClick(homeRow) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }

        val sideShape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(c.mag, sideShape)
                .border(2.dp, c.line, sideShape)
                .clip(sideShape)
                .padding(horizontal = 30.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            sideContent()
        }
    }
}

@Composable
private fun ContactRowItem(
    name: String,
    relation: String?,
    subtitle: String,
    avatarText: String,
    self: Boolean,
    online: Boolean,
    hue: Float,
    unread: Int,
    focused: Boolean,
    selectedBorder: Boolean,
    direction: Int,
    progress: () -> Float?,
    onClick: () -> Unit
) {
    val c = LocalBciColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .scanItem(
                focused = focused,
                direction = direction,
                picked = false,
                progress = if (focused) progress else null,
                customBorderColor = if (selectedBorder) c.focus else null
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            text = avatarText,
            size = 56.dp,
            fontSize = 24,
            self = self,
            online = online,
            hue = hue
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = name,
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(800),
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                if (!relation.isNullOrBlank()) {
                    Text(
                        text = relation,
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight(500),
                        fontSize = 17.sp,
                        maxLines = 1,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .widthIn(min = 36.dp)
                    .height(36.dp)
                    .background(c.focus, RoundedCornerShape(18.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = unread.toString(),
                    color = c.bg,
                    fontFamily = BciType.latin,
                    fontWeight = FontWeight(800),
                    fontSize = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun NavBottomCell(
    label: String,
    iconRes: Int,
    focused: Boolean,
    direction: Int,
    progress: () -> Float?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    val fg = if (focused) c.text else c.muted
    Row(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = direction,
                picked = false,
                progress = if (focused) progress else null,
                navigation = true
            )
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = fg,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight.Normal,
            fontSize = 22.sp,
            maxLines = 1,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun ContactHeader(
    name: String,
    avatarText: String,
    statusLine: String,
    self: Boolean,
    online: Boolean,
    hue: Float
) {
    val c = LocalBciColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            text = avatarText,
            size = 84.dp,
            fontSize = 36,
            self = self,
            online = online,
            hue = hue
        )
        Column {
            Text(
                text = name,
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 48.sp,
                lineHeight = 53.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = statusLine,
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

private fun contactStatusLine(contact: ChatContact): String = buildList {
    contact.relation?.takeIf { it.isNotBlank() }?.let { add(it) }
    add(if (contact.online) "在线" else "离线")
    if (contact.unread > 0) add("${contact.unread} 条未读")
}.joinToString(" · ")

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ContactListPreviewPanel(
    state: CommunicationState
) {
    val c = LocalBciColors.current
    when {
        state.isInviteFocused -> {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = "邀请家属",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 40.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = if (state.configured || state.fakeMode) {
                        "生成一个 6 位邀请码。家属在手机端输入后，就能给你发消息、打视频。邀请码 10 分钟内有效。"
                    } else {
                        "请照护者在「设备与模型」中配置服务器后再邀请家属。"
                    },
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 24.sp,
                    lineHeight = 37.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }

        state.isHomeFocused -> {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = "返回主页",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 40.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = "没发出去的文字会按联系人分别保留。",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 24.sp,
                    lineHeight = 37.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }

        state.isSelfFocused -> {
            ContactHeader(
                name = "当面说话",
                avatarText = "说",
                statusLine = "给身边的人看和听",
                self = true,
                online = false,
                hue = 24f
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = "打好字以后选「说出」，平板会朗读出来，并把这句话放大到整个屏幕。",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 24.sp,
                    lineHeight = 37.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                if (!state.configured && !state.fakeMode) {
                    Text(
                        text = "请照护者在「设备与模型」中配置服务器后再邀请家属",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 24.sp,
                        lineHeight = 37.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                } else if (state.contacts.isEmpty()) {
                    Text(
                        text = "还没有绑定家属。选「邀请家属」生成邀请码，交给家属在手机上输入。",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 24.sp,
                        lineHeight = 37.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                state.error?.takeIf { it.isNotBlank() }?.let { err ->
                    Text(
                        text = err,
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 20.sp,
                        lineHeight = 30.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
            Text(
                text = "咬牙开始打字",
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 20.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        else -> {
            val contact = state.contact ?: return
            ContactHeader(
                name = contact.name,
                avatarText = contact.name,
                statusLine = contactStatusLine(contact),
                self = false,
                online = contact.online,
                hue = contactAvatarHue(contact.principalId, contact.avatarHue)
            )
            val recent = buildDisplayBubbles(state, contact, limit = 3)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
            ) {
                if (recent.isEmpty()) {
                    Text(
                        text = state.error ?: "还没有消息",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 20.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                } else {
                    recent.forEach { item ->
                        ChatBubble(
                            text = item.text,
                            meta = item.meta,
                            own = item.own,
                            fontSize = 23,
                            horizontalPadding = 18.dp,
                            verticalPadding = 11.dp,
                            maxWidth = 530.dp
                        )
                    }
                }
            }
            Text(
                text = "咬牙选择：发文字消息，或视频通话",
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 20.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ContactActionsPanel(
    state: CommunicationState,
    contact: ChatContact,
    progress: () -> Float?,
    onChoose: (Int) -> Unit
) {
    val c = LocalBciColors.current
    val dir = if (state.scanMode == ScanMode.AUTO) state.actionDirection else 0

    ContactHeader(
        name = contact.name,
        avatarText = contact.name,
        statusLine = contactStatusLine(contact),
        self = false,
        online = contact.online,
        hue = contactAvatarHue(contact.principalId, contact.avatarHue)
    )

    val recent = buildDisplayBubbles(state, contact, limit = 2)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
    ) {
        recent.forEach { item ->
            ChatBubble(
                text = item.text,
                meta = item.meta,
                own = item.own,
                fontSize = 23,
                horizontalPadding = 18.dp,
                verticalPadding = 11.dp,
                maxWidth = 530.dp
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ActionBigCard(
            title = "文字消息",
            subtitle = "打字发给${contact.name}",
            iconRes = R.drawable.ic_lucide_message_square_text,
            focused = state.actionIndex == 0,
            direction = dir,
            progress = progress,
            onClick = { onChoose(0) },
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        ActionBigCard(
            title = "视频通话",
            subtitle = if (contact.online) "${contact.name}在线，可以直接呼叫" else "对方离线，可能无人接听",
            iconRes = R.drawable.ic_lucide_video,
            focused = state.actionIndex == 1,
            direction = dir,
            progress = progress,
            onClick = { onChoose(1) },
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }

    val backFocused = state.actionIndex == 2
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .scanItem(
                focused = backFocused,
                direction = dir,
                picked = false,
                progress = if (backFocused) progress else null,
                navigation = true
            )
            .clickable { onChoose(2) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "← 返回联系人",
            color = if (backFocused) c.text else c.muted,
            fontFamily = BciType.chinese,
            fontSize = 22.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun ActionBigCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    focused: Boolean,
    direction: Int,
    progress: () -> Float?,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    Column(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = direction,
                picked = false,
                progress = if (focused) progress else null,
                cornerRadius = 20.dp
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (focused) c.focus else c.muted,
            modifier = Modifier.size(46.dp)
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = title,
            color = c.text,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight(900),
            fontSize = 38.sp,
            lineHeight = 42.sp,
            maxLines = 1,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
        Text(
            text = subtitle,
            color = c.muted,
            fontFamily = BciType.chinese,
            fontSize = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun ChatInputLayout(
    state: CommunicationState,
    contact: ChatContact,
    draft: com.example.input_ds.model.InputState,
    input: InputMethodViewModel,
    paused: Boolean,
    frozen: Boolean,
    onOlder: () -> Unit
) {
    val c = LocalBciColors.current
    val histShape = RoundedCornerShape(18.dp)
    val bubbles = buildDisplayBubbles(state, contact, limit = 7)

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(c.panel, histShape)
                .border(2.dp, c.line, histShape)
                .clip(histShape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = contact.name,
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight(900),
                        fontSize = 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = if (contact.online) "在线" else "离线",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight(500),
                        fontSize = 15.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                if (state.hasOlder) {
                    Text(
                        text = "更早",
                        modifier = Modifier
                            .border(1.dp, c.line, RoundedCornerShape(8.dp))
                            .clickable(onClick = onOlder)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 14.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
            ) {
                if (bubbles.isEmpty()) {
                    Text(
                        text = "还没有消息",
                        color = c.muted,
                        fontFamily = BciType.chinese,
                        fontSize = 20.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                } else {
                    bubbles.forEach { item ->
                        ChatBubble(
                            text = item.text,
                            meta = item.meta,
                            own = item.own,
                            fontSize = 19,
                            horizontalPadding = 14.dp,
                            verticalPadding = 9.dp,
                            maxWidth = 246.dp
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
}

@Composable
private fun PairingLayout(
    state: CommunicationState,
    frozen: Boolean,
    progress: () -> Float?,
    onCopy: (String) -> Unit,
    onChoose: (Int) -> Unit
) {
    val c = LocalBciColors.current
    val dir = if (state.scanMode == ScanMode.AUTO) state.pairingDirection else 0
    val codeChars = remember(state.invitation, frozen) {
        val raw = when {
            frozen && state.invitation != null -> state.invitation
            state.invitation != null -> state.invitation
            else -> "······"
        }
        List(6) { idx -> raw.getOrNull(idx)?.toString() ?: "·" }
    }
    val expiresDisplay = remember(state.invitationExpires, frozen) {
        if (frozen) "09:51"
        else formatChatTime(state.invitationExpires).ifEmpty { currentChatTime(false, plusMinutes = 10) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "邀请家属",
            color = c.text,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight(900),
            fontSize = 40.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
        Text(
            text = "请家属打开手机端，在「兑换邀请码」里输入下面 6 位字符",
            color = c.muted,
            fontFamily = BciType.chinese,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )

        Row(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            codeChars.forEachIndexed { index, ch ->
                if (index == 3) Spacer(Modifier.width(18.dp))
                val shape = RoundedCornerShape(18.dp)
                Box(
                    modifier = Modifier
                        .size(width = 104.dp, height = 128.dp)
                        .background(c.panel, shape)
                        .border(2.dp, c.line, shape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = ch,
                        color = c.focus,
                        fontFamily = BciType.latin,
                        fontWeight = FontWeight(800),
                        fontSize = 76.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }

        val statusText = when {
            !state.error.isNullOrBlank() -> state.error
            state.loading || state.invitation == null -> "正在生成…"
            else -> "10 分钟内有效（$expiresDisplay 前）· 只能使用一次"
        }
        Text(
            text = statusText,
            color = c.muted,
            fontFamily = BciType.chinese,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )

        val code = state.invitation
        if (!code.isNullOrBlank()) {
            DashedTouchButton(
                text = "复制邀请码（照护者触摸）",
                onClick = { onCopy(code) }
            )
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .width(760.dp)
                .height(100.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val backFocused = state.pairingIndex == 0
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .scanItem(
                        focused = backFocused,
                        direction = dir,
                        picked = false,
                        progress = if (backFocused) progress else null
                    )
                    .clickable { onChoose(0) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "返回联系人",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            if (state.canRegenerateInvite) {
                val regenFocused = state.pairingIndex == 1
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .scanItem(
                            focused = regenFocused,
                            direction = dir,
                            picked = false,
                            progress = if (regenFocused) progress else null
                        )
                        .clickable { onChoose(1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "换一个邀请码",
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

@Composable
private fun DashedTouchButton(text: String, onClick: () -> Unit) {
    val c = LocalBciColors.current
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = c.muted,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(stroke * 4f, stroke * 3f))
                )
            )
        }
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = c.muted,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight(500),
            fontSize = 17.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

private data class DisplayBubble(val text: String, val meta: String, val own: Boolean)

private fun buildDisplayBubbles(
    state: CommunicationState,
    contact: ChatContact,
    limit: Int
): List<DisplayBubble> {
    val committed: List<ChatMessage> = state.messagesFor(contact.conversationId)
    val committedClientIds = committed.mapNotNullTo(mutableSetOf()) { it.clientId }
    val committedItems = committed.map { msg ->
        val own = msg.senderId == state.myPrincipalId
        val time = formatChatTime(msg.createdAt)
        val meta = if (own) {
            if (time.isNotEmpty()) "$time · 已送达" else "已送达"
        } else {
            time
        }
        DisplayBubble(msg.text, meta, own)
    }
    val pendingItems = state.pending
        .filter { it.conversationId == contact.conversationId && it.clientId !in committedClientIds }
        .map { p ->
            val time = formatChatTime(p.createdAt)
            val suffix = if (state.connected) "发送中…" else "待发送 · 网络恢复后自动发出"
            val meta = if (time.isNotEmpty()) "$time · $suffix" else suffix
            DisplayBubble(p.text, meta, own = true)
        }
    return (committedItems + pendingItems).takeLast(limit)
}
