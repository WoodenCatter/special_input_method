package com.example.input_ds.communication

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.input_ds.BuildConfig
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.personalization.PersonalizationRepository
import com.example.input_ds.ui.design.currentChatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CommunicationSignalOutcome { HANDLED, OPEN_TALK, EXIT_HOME }

data class CommunicationState(
    val stage: CommunicationStage = CommunicationStage.CONTACT_LIST,
    val contacts: List<ChatContact> = emptyList(),
    /** Row index in `[self] + contacts + [invite, home]`. */
    val selectedIndex: Int = 0,
    val listDirection: Int = 1,
    val activeContactId: String? = null,
    val actionIndex: Int = 0,
    val actionDirection: Int = 1,
    val pairingIndex: Int = 0,
    val pairingDirection: Int = 1,
    val inputActionIndex: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val allMessages: Map<String, List<ChatMessage>> = emptyMap(),
    val pending: List<PendingMessage> = emptyList(),
    val myPrincipalId: String = "",
    val invitation: String? = null,
    val invitationExpires: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val connected: Boolean = false,
    val configured: Boolean = !BuildConfig.DEMO_COMMUNICATION,
    val hasOlder: Boolean = false,
    val scanMode: ScanMode = ScanMode.AUTO,
    val dwellRevision: Int = 0,
    val fakeMode: Boolean = false,
    val fakeNoContacts: Boolean = false
) {
    val contact: ChatContact?
        get() = when (stage) {
            CommunicationStage.CONTACT_LIST ->
                ContactSelectionPolicy.contactIndexForRow(selectedIndex, contacts.size)?.let { contacts.getOrNull(it) }
            else ->
                contacts.firstOrNull { it.principalId == activeContactId }
                    ?: ContactSelectionPolicy.contactIndexForRow(selectedIndex, contacts.size)?.let { contacts.getOrNull(it) }
        }

    val isSelfFocused: Boolean
        get() = stage == CommunicationStage.CONTACT_LIST && selectedIndex == ContactSelectionPolicy.SELF_INDEX

    val isInviteFocused: Boolean
        get() = stage == CommunicationStage.CONTACT_LIST && selectedIndex == ContactSelectionPolicy.inviteIndex(contacts.size)

    val isHomeFocused: Boolean
        get() = stage == CommunicationStage.CONTACT_LIST && selectedIndex == ContactSelectionPolicy.homeIndex(contacts.size)

    val canRegenerateInvite: Boolean
        get() = (configured || fakeMode) && (invitation != null || loading || error == null)

    fun messagesFor(conversationId: String): List<ChatMessage> =
        allMessages[conversationId].orEmpty()
}

class CommunicationViewModel(application: Application) : AndroidViewModel(application) {
    private val profiles = PersonalizationRepository(application)
    private val prefs = application.getSharedPreferences("chat_cache_v1", 0)
    private val _state = MutableStateFlow(CommunicationState())
    val state: StateFlow<CommunicationState> = _state
    private val _videoNotices = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    internal val videoNotices: kotlinx.coroutines.flow.SharedFlow<String> = _videoNotices
    internal val video = VideoSession(application, viewModelScope, { _videoNotices.tryEmit(it) }, preferSpeaker = true)
    internal var requestVideoPermissions: () -> Unit = {}

    private var api: ChatApi? = null
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var scanJob: Job? = null
    private var sessionKey: String? = null
    private var generation = 0
    private var scanIntervalMs = 1_500L
    private var graceMs = 400L
    private var screenActive = false
    private var paused = false
    private var frozen = false
    private var initialFocusApplied = false
    private var onToastCallback: ((String, Boolean) -> Unit)? = null

    private var listCursor = ScanCursor(ContactSelectionPolicy.size(0))
    private var actionCursor = ScanCursor(3)
    private var pairingCursor = ScanCursor(2)

    private val history = mutableMapOf<String, MutableList<ChatMessage>>()
    private val outbox = mutableListOf<PendingMessage>()

    // Debug-only fake contacts backing store (populated exclusively by `fake contacts`).
    private var fakeContactsFull: List<ChatContact> = emptyList()
    private val fakeReplyIndex = mutableMapOf<String, Int>()
    private val fakeReplies = mapOf(
        "fake-conv-mom" to listOf("好的，我马上过来", "收到，你先休息一会儿", "我在路上了，二十分钟到"),
        "fake-conv-yu" to listOf("我晚上七点到", "收到！"),
        "fake-conv-li" to listOf("好的，马上来")
    )
    private var fakeSeq = 100L

    fun setOnToast(callback: ((String, Boolean) -> Unit)?) {
        onToastCallback = callback
    }

    fun setScreenActive(active: Boolean) {
        if (screenActive == active) return
        screenActive = active
        if (!active) {
            stopScanTimer()
        } else {
            resetPreviousIndices()
            restartScanTimer()
        }
    }

    fun setScanInterval(intervalMs: Long) {
        val clamped = intervalMs.coerceIn(1_100L, 3_000L)
        if (scanIntervalMs == clamped) return
        scanIntervalMs = clamped
        restartScanTimer()
    }

    fun setScanMode(mode: ScanMode) {
        if (_state.value.scanMode == mode) return
        resetPreviousIndices()
        _state.value = _state.value.copy(scanMode = mode, dwellRevision = _state.value.dwellRevision + 1)
        restartScanTimer()
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        listCursor = listCursor.copy(direction = 1, previousIndex = -1)
        actionCursor = actionCursor.copy(direction = 1, previousIndex = -1)
        pairingCursor = pairingCursor.copy(direction = 1, previousIndex = -1)
        _state.value = _state.value.copy(listDirection = 1, actionDirection = 1, pairingDirection = 1)
        if (paused) {
            stopScanTimer()
        } else {
            _state.value = _state.value.copy(dwellRevision = _state.value.dwellRevision + 1)
            restartScanTimer()
        }
    }

    fun setFrozen(value: Boolean) {
        if (frozen == value) return
        frozen = value
        resetPreviousIndices()
        if (frozen && _state.value.stage == CommunicationStage.PAIRING && _state.value.fakeMode) {
            _state.value = _state.value.copy(invitation = "K7Q2M9", invitationExpires = "09:51")
        }
        _state.value = _state.value.copy(dwellRevision = _state.value.dwellRevision + 1)
        restartScanTimer()
    }

    fun setGraceMs(ms: Long) {
        graceMs = ms.coerceAtLeast(0L)
    }

    fun open() {
        if (_state.value.fakeMode) return
        if (BuildConfig.DEMO_COMMUNICATION) {
            close()
            _state.value = CommunicationState(configured = false, scanMode = _state.value.scanMode)
            return
        }
        val user = profiles.activeUser()
        val binding = profiles.deviceServerBinding()
        val id = user?.profileId
        if (id == null || binding == null || user.profileEnabled == false) {
            close()
            _state.value = CommunicationState(
                configured = false,
                scanMode = _state.value.scanMode
            )
            return
        }
        val key = "${binding.serverUserId}:$id"
        if (key == sessionKey) return
        close()
        sessionKey = key
        val service = ChatApi(binding, id)
        api = service
        video.attach(key, service::callRequest) { state.value.myPrincipalId }
        restore(key)
        val current = ++generation
        _state.value = _state.value.copy(loading = true, configured = true, pending = outbox.toList())
        viewModelScope.launch {
            try {
                val me = withContext(Dispatchers.IO) { service.me() }
                if (current == generation) { _state.value = _state.value.copy(myPrincipalId = me); video.recover() }
            } catch (_: Exception) { /* Contact loading reports the authorization error. */ }
            refresh(current)
            if (current == generation) connect(current)
        }
    }

    private suspend fun refresh(current: Int) {
        val service = api ?: return
        try {
            val contacts = withContext(Dispatchers.IO) { service.contacts() }
            val conversations = withContext(Dispatchers.IO) { service.conversations() }
            if (current != generation) return
            val s = _state.value
            val prevContactId = s.contact?.principalId
            val newSize = ContactSelectionPolicy.size(contacts.size)
            val newRow = when {
                !initialFocusApplied && s.stage == CommunicationStage.CONTACT_LIST -> {
                    initialFocusApplied = true
                    ContactSelectionPolicy.initialIndex(contacts)
                }
                prevContactId != null -> {
                    val idx = contacts.indexOfFirst { it.principalId == prevContactId }
                    if (idx >= 0) ContactSelectionPolicy.rowForContactIndex(idx)
                    else s.selectedIndex.coerceIn(0, newSize - 1)
                }
                else -> s.selectedIndex.coerceIn(0, newSize - 1)
            }
            listCursor = ScanCursor(size = newSize, index = newRow, direction = listCursor.direction)
            val activeContact = contacts.firstOrNull { it.principalId == s.activeContactId }
                ?: ContactSelectionPolicy.contactIndexForRow(newRow, contacts.size)?.let { contacts.getOrNull(it) }
            _state.value = s.copy(
                contacts = contacts,
                selectedIndex = newRow,
                messages = activeContact?.let { history[it.conversationId]?.toList() }.orEmpty(),
                allMessages = snapshotAllMessages(),
                loading = false,
                error = null,
                configured = true
            )
            persist()
            (contacts.map { it.conversationId } + conversations.map { it.getString("conversation_id") })
                .distinct().forEach { syncMessages(it, current) }
            flushOutbox(current)
        } catch (e: Exception) {
            if (current == generation) _state.value = _state.value.copy(loading = false, error = e.message ?: "连接失败")
        }
    }

    private suspend fun syncMessages(id: String, current: Int) {
        val service = api ?: return
        var seq = history[id]?.maxOfOrNull { it.seq } ?: 0L
        do {
            val (page, more) = withContext(Dispatchers.IO) { service.messages(id, "after_seq", seq) }
            if (current != generation) return
            merge(id, page, liveEvent = false)
            val next = page.maxOfOrNull { it.seq } ?: seq
            if (next <= seq) break
            seq = next
        } while (more)
    }

    private fun merge(id: String, page: List<ChatMessage>, liveEvent: Boolean = false) {
        val list = history.getOrPut(id) { mutableListOf() }
        val ids = list.mapTo(mutableSetOf()) { it.id }
        var addedIncoming = 0
        page.forEach { msg ->
            if (ids.add(msg.id)) {
                list.add(msg)
                if (liveEvent && msg.senderId != _state.value.myPrincipalId) addedIncoming++
            }
        }
        list.sortWith(compareBy<ChatMessage> { it.seq }.thenBy { it.id })
        if (!_state.value.fakeMode) persist()
        val s = _state.value
        val viewingThisInChat = s.stage == CommunicationStage.CHAT_INPUT && s.contact?.conversationId == id
        val updatedContacts = if (addedIncoming > 0 && !viewingThisInChat) {
            s.contacts.map { c ->
                if (c.conversationId == id) c.copy(unread = c.unread + addedIncoming, lastMessage = list.lastOrNull()?.text ?: c.lastMessage)
                else c
            }
        } else if (page.isNotEmpty()) {
            val latestText = list.lastOrNull()?.text
            s.contacts.map { c ->
                if (c.conversationId == id && latestText != null) c.copy(lastMessage = latestText) else c
            }
        } else {
            s.contacts
        }
        val activeMessages = if (s.contact?.conversationId == id) list.toList() else s.messages
        _state.value = s.copy(
            contacts = updatedContacts,
            messages = activeMessages,
            allMessages = snapshotAllMessages()
        )
        if (viewingThisInChat && page.isNotEmpty() && !s.fakeMode) {
            val seq = list.maxOfOrNull { it.seq } ?: 0L
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { api?.markRead(id, seq) } }
            }
        }
    }

    private fun connect(current: Int) {
        val service = api ?: return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            var delayMs = 1_000L
            while (isActive && current == generation) {
                try {
                    withContext(Dispatchers.IO) {
                        socket = service.socket(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                viewModelScope.launch { if (current == generation) { _state.value = _state.value.copy(connected = true); video.recover(); refresh(current) } }
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                                if (event.optString("type") == "ping") { webSocket.send("pong"); return }
                                viewModelScope.launch {
                                    if (current != generation) return@launch
                                    when (event.optString("type")) {
                                        "message.created" -> {
                                            val message = runCatching { ChatMessage.fromJson(event.getJSONObject("payload")) }.getOrNull()
                                            if (message != null) merge(message.conversationId, listOf(message), liveEvent = true)
                                        }
                                        "contacts.updated", "conversation.updated", "presence.changed" -> refresh(current)
                                        else -> video.event(event)
                                    }
                                }
                            }
                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                if (code == 1008) service.invalidateToken()
                                viewModelScope.launch { if (current == generation) _state.value = _state.value.copy(connected = false) }
                            }
                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                if (response?.code == 401 || response?.code == 403) service.invalidateToken()
                                viewModelScope.launch { if (current == generation) _state.value = _state.value.copy(connected = false) }
                            }
                        })
                    }
                    delay(30_000)
                    while (isActive && current == generation && _state.value.connected) delay(5_000)
                    delayMs = 1_000L
                } catch (_: Exception) { _state.value = _state.value.copy(connected = false) }
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Enters contact list with unread-first initial focus (or explicit focusRow on return). */
    fun enterContactList(focusRow: Int? = null) {
        val s = _state.value
        val size = ContactSelectionPolicy.size(s.contacts.size)
        val initial = (focusRow ?: ContactSelectionPolicy.initialIndex(s.contacts)).coerceIn(0, size - 1)
        initialFocusApplied = true
        listCursor = ScanCursor(size = size, index = initial, direction = 1)
        val focusedContact = ContactSelectionPolicy.contactIndexForRow(initial, s.contacts.size)?.let { s.contacts.getOrNull(it) }
        _state.value = s.copy(
            stage = CommunicationStage.CONTACT_LIST,
            selectedIndex = initial,
            listDirection = 1,
            messages = focusedContact?.let { history[it.conversationId]?.toList() }.orEmpty(),
            dwellRevision = s.dwellRevision + 1
        )
        restartScanTimer()
    }

    /** Selects a server contact by 0-based contact index and moves to CONTACT_ACTIONS if requested. */
    fun selectContact(contactIndex: Int) {
        val contacts = _state.value.contacts
        if (contactIndex !in contacts.indices) return
        val contact = contacts[contactIndex]
        val row = ContactSelectionPolicy.rowForContactIndex(contactIndex)
        listCursor = ScanCursor(size = ContactSelectionPolicy.size(contacts.size), index = row, direction = listCursor.direction)
        _state.value = _state.value.copy(
            selectedIndex = row,
            activeContactId = contact.principalId,
            messages = history[contact.conversationId]?.toList().orEmpty()
        )
    }

    fun openContactActions(contactIndex: Int? = null, initialAction: Int = 0) {
        val s = _state.value
        val targetContact = if (contactIndex != null) {
            s.contacts.getOrNull(contactIndex)
        } else {
            s.contact
        } ?: return
        val row = s.contacts.indexOfFirst { it.principalId == targetContact.principalId }
            .takeIf { it >= 0 }
            ?.let { ContactSelectionPolicy.rowForContactIndex(it) }
            ?: s.selectedIndex
        actionCursor = ScanCursor(size = 3, index = initialAction.coerceIn(0, 2), direction = 1)
        _state.value = s.copy(
            stage = CommunicationStage.CONTACT_ACTIONS,
            selectedIndex = row,
            activeContactId = targetContact.principalId,
            actionIndex = actionCursor.index,
            actionDirection = 1,
            messages = history[targetContact.conversationId]?.toList().orEmpty(),
            dwellRevision = s.dwellRevision + 1
        )
        restartScanTimer()
    }

    /** Executes one of the 3 Contact Actions: 0 = 文字消息, 1 = 视频通话, 2 = 返回联系人. */
    fun chooseAction(index: Int) {
        val s = _state.value
        val contact = s.contact ?: return
        when (index) {
            0 -> {
                stopScanTimer()
                val clearedContacts = s.contacts.map { c ->
                    if (c.principalId == contact.principalId && c.unread > 0) c.copy(unread = 0) else c
                }
                _state.value = s.copy(
                    stage = CommunicationStage.CHAT_INPUT,
                    contacts = clearedContacts,
                    activeContactId = contact.principalId,
                    actionIndex = 0,
                    messages = history[contact.conversationId]?.toList().orEmpty()
                )
                if (!s.fakeMode) {
                    val seq = history[contact.conversationId]?.maxOfOrNull { it.seq }
                    if (seq != null) {
                        viewModelScope.launch {
                            runCatching { withContext(Dispatchers.IO) { api?.markRead(contact.conversationId, seq) } }
                        }
                    }
                }
            }
            1 -> {
                actionCursor = ScanCursor(size = 3, index = 1, direction = 1)
                _state.value = s.copy(actionIndex = 1, actionDirection = 1, dwellRevision = s.dwellRevision + 1)
                if (s.fakeMode) {
                    video.injectFakeCall(
                        incoming = false,
                        peerName = contact.name,
                        active = frozen && contact.online,
                        hue = contact.avatarHue ?: 12f,
                        online = contact.online,
                        frozen = frozen
                    )
                } else {
                    video.start(contact.conversationId, contact.name, requestVideoPermissions)
                }
                restartScanTimer()
            }
            2 -> {
                val contactIdx = s.contacts.indexOfFirst { it.principalId == contact.principalId }
                val targetRow = if (contactIdx >= 0) ContactSelectionPolicy.rowForContactIndex(contactIdx) else ContactSelectionPolicy.SELF_INDEX
                enterContactList(focusRow = targetRow)
            }
        }
    }

    fun onVideoCallEnded(wasIncoming: Boolean) {
        if (!wasIncoming && _state.value.stage == CommunicationStage.CONTACT_ACTIONS) {
            actionCursor = ScanCursor(size = 3, index = 1, direction = 1)
            _state.value = _state.value.copy(
                actionIndex = 1,
                actionDirection = 1,
                dwellRevision = _state.value.dwellRevision + 1
            )
            restartScanTimer()
        }
    }

    fun choosePairing(index: Int) {
        when (index) {
            0 -> enterContactList(focusRow = ContactSelectionPolicy.inviteIndex(_state.value.contacts.size))
            1 -> {
                if (!_state.value.canRegenerateInvite) return
                regeneratePairingCode()
            }
        }
    }

    fun back(): Boolean = when (_state.value.stage) {
        CommunicationStage.CHAT_INPUT -> {
            openContactActions(initialAction = 0)
            true
        }
        CommunicationStage.CONTACT_ACTIONS -> {
            val contact = _state.value.contact
            val idx = contact?.let { c -> _state.value.contacts.indexOfFirst { it.principalId == c.principalId } } ?: -1
            val row = if (idx >= 0) ContactSelectionPolicy.rowForContactIndex(idx) else ContactSelectionPolicy.SELF_INDEX
            enterContactList(focusRow = row)
            true
        }
        CommunicationStage.PAIRING -> {
            enterContactList(focusRow = ContactSelectionPolicy.inviteIndex(_state.value.contacts.size))
            true
        }
        CommunicationStage.CONTACT_LIST -> false
    }

    fun activateRow(rowIndex: Int): CommunicationSignalOutcome {
        val s = _state.value
        val count = s.contacts.size
        return when {
            rowIndex == ContactSelectionPolicy.SELF_INDEX -> {
                listCursor = ScanCursor(size = ContactSelectionPolicy.size(count), index = 0, direction = 1)
                _state.value = s.copy(selectedIndex = 0, listDirection = 1)
                CommunicationSignalOutcome.OPEN_TALK
            }
            rowIndex == ContactSelectionPolicy.inviteIndex(count) -> {
                pairing()
                CommunicationSignalOutcome.HANDLED
            }
            rowIndex == ContactSelectionPolicy.homeIndex(count) -> {
                CommunicationSignalOutcome.EXIT_HOME
            }
            else -> {
                val contactIdx = ContactSelectionPolicy.contactIndexForRow(rowIndex, count)
                if (contactIdx != null) openContactActions(contactIdx, initialAction = 0)
                CommunicationSignalOutcome.HANDLED
            }
        }
    }

    fun onSignal(signal: ControlSignal): CommunicationSignalOutcome {
        val s = _state.value
        val now = SystemClock.elapsedRealtime()
        val effectiveGrace = if (frozen) 0L else graceMs
        return when (s.stage) {
            CommunicationStage.CONTACT_LIST -> {
                when (signal) {
                    ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                        val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                        val next = listCursor.look(step, s.scanMode)
                        applyListCursor(next)
                        restartScanTimer()
                        CommunicationSignalOutcome.HANDLED
                    }
                    ControlSignal.BITE -> {
                        val chosen = listCursor.selected(now, effectiveGrace, s.scanMode)
                        if (chosen != listCursor.index && !frozen) {
                            onToastCallback?.invoke("已按上一项执行（延迟补偿）", false)
                        }
                        activateRow(chosen)
                    }
                    else -> CommunicationSignalOutcome.HANDLED
                }
            }
            CommunicationStage.CONTACT_ACTIONS -> {
                when (signal) {
                    ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                        val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                        actionCursor = actionCursor.look(step, s.scanMode)
                        _state.value = s.copy(
                            actionIndex = actionCursor.index,
                            actionDirection = actionCursor.direction,
                            dwellRevision = s.dwellRevision + 1
                        )
                        restartScanTimer()
                        CommunicationSignalOutcome.HANDLED
                    }
                    ControlSignal.BITE -> {
                        val chosen = actionCursor.selected(now, effectiveGrace, s.scanMode)
                        if (chosen != actionCursor.index && !frozen) {
                            onToastCallback?.invoke("已按上一项执行（延迟补偿）", false)
                        }
                        chooseAction(chosen)
                        CommunicationSignalOutcome.HANDLED
                    }
                    else -> CommunicationSignalOutcome.HANDLED
                }
            }
            CommunicationStage.PAIRING -> {
                when (signal) {
                    ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                        val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                        pairingCursor = pairingCursor.look(step, s.scanMode)
                        _state.value = s.copy(
                            pairingIndex = pairingCursor.index,
                            pairingDirection = pairingCursor.direction,
                            dwellRevision = s.dwellRevision + 1
                        )
                        restartScanTimer()
                        CommunicationSignalOutcome.HANDLED
                    }
                    ControlSignal.BITE -> {
                        val chosen = pairingCursor.selected(now, effectiveGrace, s.scanMode)
                        if (chosen != pairingCursor.index && !frozen) {
                            onToastCallback?.invoke("已按上一项执行（延迟补偿）", false)
                        }
                        choosePairing(chosen)
                        CommunicationSignalOutcome.HANDLED
                    }
                    else -> CommunicationSignalOutcome.HANDLED
                }
            }
            CommunicationStage.CHAT_INPUT -> CommunicationSignalOutcome.HANDLED
        }
    }

    fun debugTick() {
        val s = _state.value
        val now = SystemClock.elapsedRealtime()
        when (s.stage) {
            CommunicationStage.CONTACT_LIST -> {
                applyListCursor(listCursor.advance(now, s.scanMode))
            }
            CommunicationStage.CONTACT_ACTIONS -> {
                actionCursor = actionCursor.advance(now, s.scanMode)
                _state.value = s.copy(
                    actionIndex = actionCursor.index,
                    actionDirection = actionCursor.direction,
                    dwellRevision = s.dwellRevision + 1
                )
            }
            CommunicationStage.PAIRING -> {
                pairingCursor = pairingCursor.advance(now, s.scanMode)
                _state.value = s.copy(
                    pairingIndex = pairingCursor.index,
                    pairingDirection = pairingCursor.direction,
                    dwellRevision = s.dwellRevision + 1
                )
            }
            CommunicationStage.CHAT_INPUT -> Unit
        }
    }

    private fun applyListCursor(next: ScanCursor) {
        listCursor = next
        val s = _state.value
        val focusedContact = ContactSelectionPolicy.contactIndexForRow(next.index, s.contacts.size)?.let { s.contacts.getOrNull(it) }
        _state.value = s.copy(
            selectedIndex = next.index,
            listDirection = next.direction,
            messages = focusedContact?.let { history[it.conversationId]?.toList() }.orEmpty(),
            dwellRevision = s.dwellRevision + 1
        )
    }

    private fun restartScanTimer() {
        stopScanTimer()
        val s = _state.value
        if (!screenActive || paused || frozen || s.scanMode != ScanMode.AUTO) return
        if (s.stage == CommunicationStage.CHAT_INPUT) return
        scanJob = viewModelScope.launch {
            runScanTimer(
                interval = { scanIntervalMs },
                onFailure = { android.util.Log.e("ScanCursor", "Communication tick failed", it) }
            ) {
                val cur = _state.value
                val now = SystemClock.elapsedRealtime()
                when (cur.stage) {
                    CommunicationStage.CONTACT_LIST -> applyListCursor(listCursor.advance(now, cur.scanMode))
                    CommunicationStage.CONTACT_ACTIONS -> {
                        actionCursor = actionCursor.advance(now, cur.scanMode)
                        _state.value = cur.copy(
                            actionIndex = actionCursor.index,
                            actionDirection = actionCursor.direction,
                            dwellRevision = cur.dwellRevision + 1
                        )
                    }
                    CommunicationStage.PAIRING -> {
                        pairingCursor = pairingCursor.advance(now, cur.scanMode)
                        _state.value = cur.copy(
                            pairingIndex = pairingCursor.index,
                            pairingDirection = pairingCursor.direction,
                            dwellRevision = cur.dwellRevision + 1
                        )
                    }
                    CommunicationStage.CHAT_INPUT -> Unit
                }
            }
        }
    }

    private fun stopScanTimer() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun resetPreviousIndices() {
        listCursor = listCursor.copy(previousIndex = -1)
        actionCursor = actionCursor.copy(previousIndex = -1)
        pairingCursor = pairingCursor.copy(previousIndex = -1)
    }

    fun queue(text: String): Boolean {
        val contact = _state.value.contact ?: return false
        if (text.isBlank() || text.codePointCount(0, text.length) > 2000) {
            _state.value = _state.value.copy(error = "消息须为 1–2000 个字符")
            return false
        }
        if (_state.value.fakeMode) {
            val nowTime = currentChatTime(frozen)
            val sent = ChatMessage(
                id = "fake-msg-${++fakeSeq}",
                conversationId = contact.conversationId,
                clientId = UUID.randomUUID().toString(),
                seq = fakeSeq,
                senderId = _state.value.myPrincipalId,
                text = text,
                createdAt = nowTime
            )
            merge(contact.conversationId, listOf(sent), liveEvent = false)
            if (contact.online) {
                val replyPool = fakeReplies[contact.conversationId].orEmpty()
                if (replyPool.isNotEmpty()) {
                    val idx = fakeReplyIndex[contact.conversationId] ?: 0
                    fakeReplyIndex[contact.conversationId] = idx + 1
                    val reply = ChatMessage(
                        id = "fake-msg-${++fakeSeq}",
                        conversationId = contact.conversationId,
                        clientId = null,
                        seq = fakeSeq,
                        senderId = contact.principalId,
                        text = replyPool[idx % replyPool.size],
                        createdAt = nowTime
                    )
                    merge(contact.conversationId, listOf(reply), liveEvent = false)
                }
            }
            return true
        }
        val pending = PendingMessage(
            conversationId = contact.conversationId,
            clientId = UUID.randomUUID().toString(),
            text = text,
            createdAt = currentChatTime(frozen = false)
        )
        outbox.add(pending)
        if (!persist()) { outbox.remove(pending); _state.value = _state.value.copy(error = "无法保存待发消息，请重试"); return false }
        _state.value = _state.value.copy(pending = outbox.toList(), error = null)
        viewModelScope.launch { flushOutbox(generation) }
        return true
    }

    private suspend fun flushOutbox(current: Int) {
        val service = api ?: return
        for (item in outbox.toList()) {
            try {
                val result = withContext(Dispatchers.IO) { service.send(item.conversationId, item.clientId, item.text) }
                if (current != generation) return
                merge(item.conversationId, listOf(result))
                outbox.remove(item)
                persist()
                _state.value = _state.value.copy(pending = outbox.toList())
            } catch (_: Exception) { return }
        }
    }

    fun older() {
        if (_state.value.fakeMode) return
        val id = _state.value.contact?.conversationId ?: return
        val first = history[id]?.minOfOrNull { it.seq } ?: return
        viewModelScope.launch {
            val current = generation
            runCatching { withContext(Dispatchers.IO) { api?.messages(id, "before_seq", first) } }
                .getOrNull()?.let { (page, more) -> if (current == generation) { merge(id, page); _state.value = _state.value.copy(hasOlder = more) } }
        }
    }

    fun pairing() {
        val s = _state.value
        if (s.fakeMode) {
            pairingCursor = ScanCursor(size = 2, index = 0, direction = 1)
            _state.value = s.copy(
                stage = CommunicationStage.PAIRING,
                invitation = generateFakeInviteCode(),
                invitationExpires = currentChatTime(frozen, plusMinutes = 10),
                error = null,
                loading = false,
                pairingIndex = 0,
                pairingDirection = 1,
                dwellRevision = s.dwellRevision + 1
            )
            restartScanTimer()
            return
        }
        if (!s.configured) {
            pairingCursor = ScanCursor(size = 1, index = 0, direction = 1)
            _state.value = s.copy(
                stage = CommunicationStage.PAIRING,
                invitation = null,
                invitationExpires = null,
                error = "请照护者在「设备与模型」中配置服务器后再邀请家属",
                loading = false,
                pairingIndex = 0,
                pairingDirection = 1,
                dwellRevision = s.dwellRevision + 1
            )
            restartScanTimer()
            return
        }
        pairingCursor = ScanCursor(size = 2, index = 0, direction = 1)
        _state.value = s.copy(
            stage = CommunicationStage.PAIRING,
            invitation = null,
            invitationExpires = null,
            error = null,
            loading = true,
            pairingIndex = 0,
            pairingDirection = 1,
            dwellRevision = s.dwellRevision + 1
        )
        restartScanTimer()
        fetchInvitation(isRegen = false)
    }

    private fun regeneratePairingCode() {
        val s = _state.value
        if (s.fakeMode) {
            _state.value = s.copy(
                invitation = generateFakeInviteCode(),
                invitationExpires = currentChatTime(frozen, plusMinutes = 10),
                error = null,
                dwellRevision = s.dwellRevision + 1
            )
            if (!frozen) onToastCallback?.invoke("已换成新的邀请码", true)
            return
        }
        _state.value = s.copy(invitation = null, loading = true, error = null)
        fetchInvitation(isRegen = true)
    }

    private fun fetchInvitation(isRegen: Boolean) {
        viewModelScope.launch {
            val current = generation
            runCatching { withContext(Dispatchers.IO) { api?.invitation() } }.onSuccess { json ->
                if (current == generation && json != null) {
                    pairingCursor = ScanCursor(size = 2, index = pairingCursor.index.coerceIn(0, 1), direction = pairingCursor.direction)
                    _state.value = _state.value.copy(
                        invitation = json.getString("code"),
                        invitationExpires = json.optString("expires_at"),
                        loading = false,
                        error = null,
                        pairingIndex = pairingCursor.index,
                        dwellRevision = _state.value.dwellRevision + 1
                    )
                    if (isRegen && !frozen) onToastCallback?.invoke("已换成新的邀请码", true)
                }
            }.onFailure { err ->
                if (current == generation) {
                    pairingCursor = ScanCursor(size = 1, index = 0, direction = 1)
                    _state.value = _state.value.copy(
                        loading = false,
                        error = err.message ?: "生成邀请码失败",
                        pairingIndex = 0,
                        pairingDirection = 1,
                        dwellRevision = _state.value.dwellRevision + 1
                    )
                }
            }
        }
    }

    private fun generateFakeInviteCode(): String {
        if (frozen) return "K7Q2M9"
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return buildString { repeat(6) { append(chars.random()) } }
    }

    /** Debug-only: injects prototype contacts & history, disconnecting real server requests. */
    fun injectFakeContacts(noContacts: Boolean = false) {
        generation++
        reconnectJob?.cancel()
        socket?.close(1000, "debug_fake")
        socket = null
        api = null
        sessionKey = null
        history.clear()
        outbox.clear()
        fakeReplyIndex.clear()
        fakeSeq = 100L

        val me = "fake-me"
        val mom = ChatContact("fake-mom", "妈妈", "fake-conv-mom", online = true, unread = 2, lastMessage = "中午我给你带粥", relation = null, avatarHue = 12f)
        val yu = ChatContact("fake-yu", "小雨", "fake-conv-yu", online = true, unread = 0, lastMessage = "晚上我过来看你", relation = "女儿", avatarHue = 200f)
        val zhang = ChatContact("fake-zhang", "张医生", "fake-conv-zhang", online = false, unread = 0, lastMessage = "明天上午查房", relation = "主治医生", avatarHue = 160f)
        val li = ChatContact("fake-li", "李阿姨", "fake-conv-li", online = true, unread = 0, lastMessage = null, relation = "护工", avatarHue = 300f)

        fakeContactsFull = listOf(mom, yu, zhang, li)
        history[mom.conversationId] = mutableListOf(
            ChatMessage("fm1", mom.conversationId, null, 1L, mom.principalId, "今天感觉怎么样？", "09:12"),
            ChatMessage("fm2", mom.conversationId, null, 2L, mom.principalId, "中午我给你带粥", "09:13")
        )
        history[yu.conversationId] = mutableListOf(
            ChatMessage("fy1", yu.conversationId, null, 1L, me, "想你了", "昨天"),
            ChatMessage("fy2", yu.conversationId, null, 2L, yu.principalId, "晚上我过来看你", "昨天")
        )
        history[zhang.conversationId] = mutableListOf(
            ChatMessage("fz1", zhang.conversationId, null, 1L, zhang.principalId, "明天上午查房", "周一")
        )
        history[li.conversationId] = mutableListOf()

        val visible = if (noContacts) emptyList() else fakeContactsFull
        val initialRow = ContactSelectionPolicy.initialIndex(visible)
        initialFocusApplied = true
        listCursor = ScanCursor(size = ContactSelectionPolicy.size(visible.size), index = initialRow, direction = 1)
        val focusedContact = ContactSelectionPolicy.contactIndexForRow(initialRow, visible.size)?.let { visible.getOrNull(it) }
        _state.value = CommunicationState(
            stage = CommunicationStage.CONTACT_LIST,
            contacts = visible,
            selectedIndex = initialRow,
            listDirection = 1,
            activeContactId = focusedContact?.principalId,
            messages = focusedContact?.let { history[it.conversationId]?.toList() }.orEmpty(),
            allMessages = snapshotAllMessages(),
            myPrincipalId = me,
            connected = true,
            configured = true,
            scanMode = _state.value.scanMode,
            dwellRevision = _state.value.dwellRevision + 1,
            fakeMode = true,
            fakeNoContacts = noContacts
        )
        restartScanTimer()
    }

    /** Debug-only: toggles caregiver sheet "模拟：还没有绑定家属". */
    fun setFakeNoContacts(noContacts: Boolean) {
        if (!_state.value.fakeMode) {
            if (noContacts) injectFakeContacts(noContacts = true)
            return
        }
        val visible = if (noContacts) emptyList() else fakeContactsFull
        val initialRow = ContactSelectionPolicy.initialIndex(visible)
        listCursor = ScanCursor(size = ContactSelectionPolicy.size(visible.size), index = initialRow, direction = 1)
        val focusedContact = ContactSelectionPolicy.contactIndexForRow(initialRow, visible.size)?.let { visible.getOrNull(it) }
        _state.value = _state.value.copy(
            stage = CommunicationStage.CONTACT_LIST,
            contacts = visible,
            selectedIndex = initialRow,
            listDirection = 1,
            activeContactId = focusedContact?.principalId,
            messages = focusedContact?.let { history[it.conversationId]?.toList() }.orEmpty(),
            fakeNoContacts = noContacts,
            dwellRevision = _state.value.dwellRevision + 1
        )
        restartScanTimer()
    }

    /** Debug-only: opens CONTACT_ACTIONS for the first contact. */
    fun debugOpenFirstContactActions() {
        if (_state.value.contacts.isEmpty()) injectFakeContacts()
        openContactActions(contactIndex = 0, initialAction = 0)
    }

    /** Debug-only: opens CHAT_INPUT for the first contact. */
    fun debugOpenFirstContactChat() {
        if (_state.value.contacts.isEmpty()) injectFakeContacts()
        openContactActions(contactIndex = 0, initialAction = 0)
        chooseAction(0)
    }

    /** Debug-only: opens PAIRING with fake/frozen code if in fake mode or unconfigured. */
    fun debugOpenInvite() {
        if (!_state.value.configured && !_state.value.fakeMode) injectFakeContacts()
        pairing()
    }

    /** Debug-only: injects an incoming video call from 妈妈 without media. */
    fun injectFakeIncomingCall() {
        if (video.state.value.call != null) return
        val mom = _state.value.contacts.firstOrNull()
        video.injectFakeCall(
            incoming = true,
            peerName = mom?.name ?: "妈妈",
            active = false,
            hue = mom?.avatarHue ?: 12f,
            online = true,
            frozen = frozen
        )
    }

    private fun snapshotAllMessages(): Map<String, List<ChatMessage>> =
        history.mapValues { (_, list) -> list.toList() }

    private fun restore(key: String) {
        history.clear(); outbox.clear()
        runCatching {
            val root = JSONObject(prefs.getString(key, "{}") ?: "{}")
            val savedContacts = root.optJSONArray("contacts") ?: JSONArray()
            val contacts = (0 until savedContacts.length()).map { ChatContact.fromJson(savedContacts.getJSONObject(it)) }
            val messages = root.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until messages.length()) {
                val message = ChatMessage.fromJson(messages.getJSONObject(i))
                history.getOrPut(message.conversationId) { mutableListOf() }.add(message)
            }
            val pending = root.optJSONArray("pending") ?: JSONArray()
            for (i in 0 until pending.length()) {
                val item = pending.getJSONObject(i)
                outbox.add(
                    PendingMessage(
                        conversationId = item.getString("conversation_id"),
                        clientId = item.getString("client_message_id"),
                        text = item.getString("text"),
                        createdAt = item.optString("created_at")
                    )
                )
            }
            val initialRow = ContactSelectionPolicy.initialIndex(contacts)
            listCursor = ScanCursor(size = ContactSelectionPolicy.size(contacts.size), index = initialRow, direction = 1)
            val focusedContact = ContactSelectionPolicy.contactIndexForRow(initialRow, contacts.size)?.let { contacts.getOrNull(it) }
            _state.value = CommunicationState(
                contacts = contacts,
                selectedIndex = initialRow,
                messages = focusedContact?.let { history[it.conversationId]?.sortedBy(ChatMessage::seq) }.orEmpty(),
                allMessages = snapshotAllMessages(),
                pending = outbox.toList(),
                scanMode = _state.value.scanMode
            )
        }
    }

    private fun persist(): Boolean {
        val key = sessionKey ?: return false
        val root = JSONObject()
        root.put("contacts", JSONArray().apply { _state.value.contacts.forEach { contact ->
            put(JSONObject().put("principal_id", contact.principalId).put("display_name", contact.name)
                .put("conversation_id", contact.conversationId).put("online", false)
                .put("unread_count", contact.unread)
                .apply { if (!contact.relation.isNullOrBlank()) put("relation", contact.relation) })
        } })
        root.put("messages", JSONArray().apply { history.values.flatten().forEach { message ->
            put(JSONObject().put("id", message.id).put("conversation_id", message.conversationId)
                .put("client_message_id", message.clientId).put("server_seq", message.seq)
                .put("sender_principal_id", message.senderId).put("text", message.text).put("created_at", message.createdAt))
        } })
        root.put("pending", JSONArray().apply { outbox.forEach { put(JSONObject().put("conversation_id", it.conversationId)
            .put("client_message_id", it.clientId).put("text", it.text).put("created_at", it.createdAt)) } })
        return prefs.edit().putString(key, root.toString()).commit()
    }

    fun close() {
        generation++
        video.clear()
        stopScanTimer()
        reconnectJob?.cancel(); socket?.close(1000, "leaving")
        socket = null; api = null; sessionKey = null
        history.clear(); outbox.clear()
        initialFocusApplied = false
        listCursor = ScanCursor(ContactSelectionPolicy.size(0))
        actionCursor = ScanCursor(3)
        pairingCursor = ScanCursor(2)
        _state.value = CommunicationState(scanMode = _state.value.scanMode)
    }

    override fun onCleared() { close(); super.onCleared() }
}
