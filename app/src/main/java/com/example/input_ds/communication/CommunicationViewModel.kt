package com.example.input_ds.communication

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.personalization.PersonalizationRepository
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

data class CommunicationState(
    val stage: CommunicationStage = CommunicationStage.CONTACT_LIST,
    val contacts: List<ChatContact> = emptyList(),
    val selectedIndex: Int = 0,
    val actionIndex: Int = 0,
    val inputActionIndex: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    val pending: List<PendingMessage> = emptyList(),
    val myPrincipalId: String = "",
    val invitation: String? = null,
    val invitationExpires: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val connected: Boolean = false,
    val hasOlder: Boolean = false
) {
    val contact: ChatContact? get() = contacts.getOrNull(selectedIndex)
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
    private val history = mutableMapOf<String, MutableList<ChatMessage>>()
    private val outbox = mutableListOf<PendingMessage>()

    fun open() {
        val user = profiles.activeUser()
        val binding = profiles.deviceServerBinding()
        val id = user?.profileId
        if (id == null || binding == null || user.profileEnabled == false) {
            close()
            _state.value = CommunicationState(error = "请先在耳机设置中配置用户及服务器")
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
        _state.value = _state.value.copy(loading = true, pending = outbox.toList())
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
            val selected = _state.value.contact?.principalId
            val index = contacts.indexOfFirst { it.principalId == selected }.takeIf { it >= 0 } ?: 0
            _state.value = _state.value.copy(contacts = contacts, selectedIndex = index,
                messages = contacts.getOrNull(index)?.let { history[it.conversationId]?.toList() }.orEmpty(),
                loading = false, error = null)
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
            merge(id, page)
            val next = page.maxOfOrNull { it.seq } ?: seq
            if (next <= seq) break
            seq = next
        } while (more)
    }

    private fun merge(id: String, page: List<ChatMessage>) {
        val list = history.getOrPut(id) { mutableListOf() }
        val ids = list.mapTo(mutableSetOf()) { it.id }
        page.forEach { if (ids.add(it.id)) list.add(it) }
        list.sortWith(compareBy<ChatMessage> { it.seq }.thenBy { it.id })
        persist()
        if (_state.value.contact?.conversationId == id) {
            _state.value = _state.value.copy(messages = list.toList())
            if (_state.value.stage == CommunicationStage.CHAT_INPUT && page.isNotEmpty()) {
                val seq = list.maxOfOrNull { it.seq } ?: 0L
                viewModelScope.launch {
                    runCatching { withContext(Dispatchers.IO) { api?.markRead(id, seq) } }
                }
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
                                            if (message != null) merge(message.conversationId, listOf(message))
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

    fun selectContact(index: Int) {
        if (index !in _state.value.contacts.indices) return
        val contact = _state.value.contacts[index]
        _state.value = _state.value.copy(selectedIndex = index,
            messages = history[contact.conversationId]?.toList().orEmpty())
    }
    fun actions() { if (_state.value.contact != null) { _state.value = _state.value.copy(stage = CommunicationStage.CONTACT_ACTIONS, actionIndex = 0); startActionScan() } }
    fun setScanInterval(intervalMs: Long) { scanIntervalMs = intervalMs.coerceIn(1_100L, 3_000L) }
    private fun startActionScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            while (isActive && _state.value.stage == CommunicationStage.CONTACT_ACTIONS) {
                delay(scanIntervalMs)
                _state.value = _state.value.copy(actionIndex = (_state.value.actionIndex + 1) % 3)
            }
        }
    }
    fun chooseAction(index: Int) {
        scanJob?.cancel()
        when (index) {
            0 -> _state.value = _state.value.copy(stage = CommunicationStage.CONTACT_LIST)
            1 -> {
                _state.value = _state.value.copy(stage = CommunicationStage.CHAT_INPUT, inputActionIndex = 0)
                val id = _state.value.contact?.conversationId
                val seq = _state.value.messages.maxOfOrNull { it.seq }
                if (id != null && seq != null) viewModelScope.launch {
                    runCatching { withContext(Dispatchers.IO) { api?.markRead(id, seq) } }
                }
            }
            2 -> {
                _state.value.contact?.let { video.start(it.conversationId, it.name, requestVideoPermissions) }
                startActionScan()
            }
        }
    }
    fun back(): Boolean = when (_state.value.stage) {
        CommunicationStage.CHAT_INPUT -> { _state.value = _state.value.copy(stage = CommunicationStage.CONTACT_ACTIONS); startActionScan(); true }
        CommunicationStage.CONTACT_ACTIONS, CommunicationStage.PAIRING -> { scanJob?.cancel(); _state.value = _state.value.copy(stage = CommunicationStage.CONTACT_LIST); true }
        CommunicationStage.CONTACT_LIST -> false
    }
    fun onSignal(signal: ControlSignal): Boolean {
        val s = _state.value
        if (s.stage == CommunicationStage.CONTACT_LIST) {
            when (signal) {
                ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                    val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                    val next = ContactSelectionPolicy.move(s.selectedIndex, s.contacts.size, step)
                    if (next == s.contacts.size) _state.value = s.copy(selectedIndex = next)
                    else selectContact(next)
                }
                ControlSignal.BITE -> if (s.selectedIndex == s.contacts.size) return false else actions()
                else -> Unit
            }
            return true
        }
        if (s.stage == CommunicationStage.CONTACT_ACTIONS && signal == ControlSignal.BITE) chooseAction(s.actionIndex)
        if (s.stage == CommunicationStage.CHAT_INPUT) {
            when (signal) {
                ControlSignal.LEFT_LOOK -> _state.value = s.copy(inputActionIndex = (s.inputActionIndex + 3) % 4)
                ControlSignal.RIGHT_LOOK -> _state.value = s.copy(inputActionIndex = (s.inputActionIndex + 1) % 4)
                else -> Unit
            }
        }
        return true
    }
    fun queue(text: String): Boolean {
        val contact = _state.value.contact ?: return false
        if (text.isBlank() || text.codePointCount(0, text.length) > 2000) {
            _state.value = _state.value.copy(error = "消息须为 1–2000 个字符")
            return false
        }
        val pending = PendingMessage(contact.conversationId, UUID.randomUUID().toString(), text)
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
        val id = _state.value.contact?.conversationId ?: return
        val first = history[id]?.minOfOrNull { it.seq } ?: return
        viewModelScope.launch {
            val current = generation
            runCatching { withContext(Dispatchers.IO) { api?.messages(id, "before_seq", first) } }
                .getOrNull()?.let { (page, more) -> if (current == generation) { merge(id, page); _state.value = _state.value.copy(hasOlder = more) } }
        }
    }
    fun pairing() {
        _state.value = _state.value.copy(stage = CommunicationStage.PAIRING, invitation = null)
        viewModelScope.launch {
            val current = generation
            runCatching { withContext(Dispatchers.IO) { api?.invitation() } }.onSuccess { json ->
                if (current == generation && json != null) _state.value = _state.value.copy(invitation = json.getString("code"), invitationExpires = json.optString("expires_at"))
            }.onFailure { if (current == generation) _state.value = _state.value.copy(error = it.message) }
        }
    }
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
                outbox.add(PendingMessage(item.getString("conversation_id"), item.getString("client_message_id"), item.getString("text")))
            }
            _state.value = CommunicationState(contacts = contacts,
                messages = contacts.firstOrNull()?.let { history[it.conversationId]?.sortedBy(ChatMessage::seq) }.orEmpty(),
                pending = outbox.toList())
        }
    }
    private fun persist(): Boolean {
        val key = sessionKey ?: return false
        val root = JSONObject()
        root.put("contacts", JSONArray().apply { _state.value.contacts.forEach { contact ->
            put(JSONObject().put("principal_id", contact.principalId).put("display_name", contact.name)
                .put("conversation_id", contact.conversationId).put("online", false)
                .put("unread_count", contact.unread))
        } })
        root.put("messages", JSONArray().apply { history.values.flatten().forEach { message ->
            put(JSONObject().put("id", message.id).put("conversation_id", message.conversationId)
                .put("client_message_id", message.clientId).put("server_seq", message.seq)
                .put("sender_principal_id", message.senderId).put("text", message.text).put("created_at", message.createdAt))
        } })
        root.put("pending", JSONArray().apply { outbox.forEach { put(JSONObject().put("conversation_id", it.conversationId)
            .put("client_message_id", it.clientId).put("text", it.text)) } })
        return prefs.edit().putString(key, root.toString()).commit()
    }
    fun close() {
        generation++
        video.clear()
        scanJob?.cancel(); reconnectJob?.cancel(); socket?.close(1000, "leaving")
        socket = null; api = null; sessionKey = null
        history.clear(); outbox.clear()
        _state.value = CommunicationState()
    }
    override fun onCleared() { close(); super.onCleared() }
}
