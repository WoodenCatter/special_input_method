package com.example.input_ds.ui.bci

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.bci.EegPreprocessStep
import com.example.input_ds.personalization.BrainApiClient
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.CollectionSettings
import com.example.input_ds.personalization.ContinuousSessionCollector
import com.example.input_ds.personalization.LocalUser
import com.example.input_ds.personalization.ModelRun
import com.example.input_ds.personalization.PersonalizationRepository
import com.example.input_ds.personalization.PreprocessingPreset
import com.example.input_ds.personalization.ProfileSyncManager
import com.example.input_ds.personalization.TrainingSession
import com.example.input_ds.personalization.TrainingWorkScheduler
import com.example.input_ds.personalization.UserModelManager
import com.example.input_ds.model.ScanSettings
import com.example.input_ds.ui.theme.AccentGreen
import com.example.input_ds.ui.theme.DarkBackground
import com.example.input_ds.ui.theme.ErrorRed
import com.example.input_ds.ui.theme.HighlightYellow
import com.example.input_ds.ui.theme.PrimaryBlue
import com.example.input_ds.ui.theme.SurfaceElevated
import com.example.input_ds.ui.theme.TextGray
import com.example.input_ds.ui.theme.TextWhite
import com.example.input_ds.ui.theme.auroraBackground
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.SelectableGlassPanel
import com.example.input_ds.ui.theme.AuroraButton
import com.example.input_ds.ui.theme.AuroraButtonStyle
import com.example.input_ds.ui.theme.AuroraTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SettingsSection(val label: String, val symbol: String) {
    SCANNING("扫描设置", "◴"),
    SERVER("绑定服务器", "⌁"),
    USERS("用户选择", "♙"),
    PARAMETERS("采集模型与参数", "≛"),
    RECORDS("采集与训练记录", "▤")
}

@Composable
fun CollectionScreen(
    bleManager: NaoyunBleManager,
    onBack: () -> Unit,
    scanIntervalMs: Long = ScanSettings.DEFAULT_INTERVAL_MS,
    onScanIntervalChange: (Long) -> Unit = {},
    onTrainingActivated: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { PersonalizationRepository(context) }
    val collector = remember { ContinuousSessionCollector(bleManager, repository) }
    val coroutineScope = rememberCoroutineScope()
    val bleState by bleManager.state.collectAsState()

    var users by remember { mutableStateOf(repository.listUsers()) }
    var selectedUserId by remember { mutableStateOf(repository.activeUserId() ?: users.firstOrNull()?.localId) }
    var deviceBinding by remember { mutableStateOf(repository.deviceServerBinding()) }
    var newUserName by remember { mutableStateOf("") }
    var serverUserId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var roundsText by remember { mutableStateOf("20") }
    var trainingEpochsText by remember { mutableStateOf("50") }
    var actionSecondsText by remember { mutableStateOf("1") }
    var scanIntervalText by remember(scanIntervalMs) { mutableStateOf(scanIntervalMs.toString()) }
    var scanIntervalError by remember { mutableStateOf<String?>(null) }
    var creatingUser by remember { mutableStateOf(false) }
    var selectedProtocol by remember { mutableStateOf(ClassificationProtocol.FOUR_CLASS) }
    var selectedModels by remember { mutableStateOf(setOf("csanet")) }
    var models by remember {
        mutableStateOf(
            listOf(
                BrainApiClient.ServerModel("wheelchair-eegnet", "Wheelchair EEGNet", true),
                BrainApiClient.ServerModel("csanet", "CSANet", true)
            )
        )
    }
    var sessions by remember { mutableStateOf(selectedUserId?.let(repository::listSessions).orEmpty()) }
    var activeModelFile by remember {
        mutableStateOf(selectedUserId?.let { UserModelManager.activeModel(context, it)?.modelFile })
    }
    var status by remember { mutableStateOf("请选择或创建本地用户") }
    var progressText by remember { mutableStateOf("") }
    var collectionProgress by remember { mutableStateOf<ContinuousSessionCollector.Progress?>(null) }
    var running by remember { mutableStateOf(false) }
    var binding by remember { mutableStateOf(false) }
    var syncingProfile by remember { mutableStateOf(false) }
    var legacyProfiles by remember { mutableStateOf<List<BrainApiClient.ServerProfile>>(emptyList()) }
    var deleteCandidate by remember { mutableStateOf<LocalUser?>(null) }
    var deleteModelCandidate by remember { mutableStateOf<Pair<TrainingSession, ModelRun>?>(null) }
    var settingsSection by remember { mutableStateOf(SettingsSection.SERVER) }
    var awaitedSessionId by remember { mutableStateOf<String?>(null) }
    val selectedUser = users.firstOrNull { it.localId == selectedUserId }

    fun refreshUsers(preferredId: String? = selectedUserId) {
        users = repository.listUsers()
        selectedUserId = preferredId?.takeIf { id -> users.any { it.localId == id } }
            ?: users.firstOrNull()?.localId
        selectedUserId?.let(repository::setActiveUser)
        sessions = selectedUserId?.let(repository::listSessions).orEmpty()
        activeModelFile = selectedUserId?.let { UserModelManager.activeModel(context, it)?.modelFile }
    }

    fun createUserFromInput() {
        runCatching { repository.createUser(newUserName) }
            .onSuccess { created ->
                newUserName = ""
                creatingUser = false
                refreshUsers(created.localId)
                status = "已创建本地用户 ${created.displayName}"
                if (deviceBinding != null) {
                    coroutineScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                ProfileSyncManager(context).synchronize(created.localId)
                            }
                        }.onSuccess {
                            refreshUsers(created.localId)
                            status = "已创建并同步用户 ${it.displayName}"
                        }.onFailure {
                            refreshUsers(created.localId)
                            status = "本地用户已创建，profile 同步失败：${it.message}"
                        }
                    }
                }
            }
            .onFailure { status = it.message ?: "创建失败" }
    }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { BrainApiClient("", "").listModels() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { models = it }
    }

    LaunchedEffect(selectedProtocol, models) {
        val compatible = models.filter { selectedProtocol in it.supportedProtocols }.map { it.key }.toSet()
        selectedModels = selectedModels.intersect(compatible)
        if (selectedModels.isEmpty() && compatible.isNotEmpty()) {
            selectedModels = setOf(compatible.first())
        }
    }

    LaunchedEffect(selectedUserId) {
        sessions = selectedUserId?.let(repository::listSessions).orEmpty()
        activeModelFile = selectedUserId?.let { UserModelManager.activeModel(context, it)?.modelFile }
        while (true) {
            delay(2_000)
            sessions = selectedUserId?.let(repository::listSessions).orEmpty()
            activeModelFile = selectedUserId?.let { UserModelManager.activeModel(context, it)?.modelFile }
            val targetSession = awaitedSessionId?.let { id -> sessions.firstOrNull { it.sessionId == id } }
            val active = selectedUserId?.let { UserModelManager.activeModel(context, it) }
            if (targetSession != null &&
                targetSession.activeModelKey != null &&
                active != null &&
                active.modelKey == targetSession.activeModelKey &&
                targetSession.modelRuns.any {
                    it.modelKey == active.modelKey && it.modelFile == active.modelFile && it.status == "succeeded"
                }
            ) {
                awaitedSessionId = null
                onTrainingActivated()
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(deviceBinding, selectedUserId) {
        legacyProfiles = if (deviceBinding != null && selectedUser?.profileId == null) {
            runCatching {
                withContext(Dispatchers.IO) {
                    ProfileSyncManager(context).listUnassociatedLegacyProfiles()
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    DisposableEffect(collector) {
        collector.onStateChange = { status = it }
        collector.onProgress = {
            collectionProgress = it
            val action = CollectionSettings.LABEL_DISPLAY[it.label].orEmpty()
            val round = if (it.round > 0) "第${it.round}轮 · " else ""
            progressText = "${it.completedActions}/${it.totalActions} · $round${it.phase} $action".trim()
        }
        collector.onCompleted = { session ->
            running = false
            sessions = repository.listSessions(session.localUserId)
            awaitedSessionId = session.sessionId
            settingsSection = SettingsSection.RECORDS
            TrainingWorkScheduler.enqueue(context, session)
            status = if (repository.deviceServerBinding() == null) {
                "采集已保存；此设备绑定服务端账号后会自动上传训练"
            } else {
                "采集已保存，已进入自动上传与训练队列"
            }
        }
        collector.onError = { message ->
            running = false
            status = message
        }
        onDispose { collector.stop() }
    }

    deleteCandidate?.let { user ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除本地用户？") },
            text = { Text("将删除“${user.displayName}”以及本机保存的采集数据和模型；服务端数据不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteLocalUser(user.localId)
                    deleteCandidate = null
                    refreshUsers(null)
                    status = "本地用户与本机数据已删除"
                }) { Text("确认删除", color = ErrorRed) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } }
        )
    }

    deleteModelCandidate?.let { (session, run) ->
        val siblingModels = session.modelRuns.count { it.modelFile != null } - 1
        AlertDialog(
            onDismissRequest = { deleteModelCandidate = null },
            title = {
                Text(if (run.modelFile != null) "删除本地模型和采集数据？" else "删除失败记录和采集数据？")
            },
            text = {
                Text(
                    buildString {
                        if (run.modelFile != null) {
                            append("将永久删除 ${run.modelKey} 的本地 ONNX，以及训练它所用的本地采集数据。")
                        } else {
                            append("该任务没有生成 ONNX。将永久删除 ${run.modelKey} 的本地失败记录和本次采集数据。")
                        }
                        if (siblingModels > 0) {
                            append(" 同一批数据训练的其他 $siblingModels 个本地 ONNX 会保留，但原始采集数据将不再可用。")
                        }
                        append(" 服务器端数据和模型不会删除。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        UserModelManager(context).deleteLocalRunAndTrainingData(session, run)
                    }.onSuccess { result ->
                        deleteModelCandidate = null
                        sessions = repository.listSessions(session.localUserId)
                        activeModelFile = UserModelManager.activeModel(context, session.localUserId)?.modelFile
                        if (result.activeModelDeleted) {
                            bleManager.setPreprocessing(EegPreprocessStep.DEFAULT)
                        }
                        status = if (result.sessionRemoved) {
                            if (run.modelFile != null) {
                                "模型及其本地采集记录已删除"
                            } else {
                                "失败记录及其本地采集数据已删除"
                            }
                        } else {
                            if (run.modelFile != null) {
                                "模型和原始采集数据已删除；同源的其他本地模型已保留"
                            } else {
                                "失败记录和原始采集数据已删除；同源的其他本地模型已保留"
                            }
                        }
                    }.onFailure {
                        deleteModelCandidate = null
                        status = "删除失败：${it.message}"
                    }
                }) { Text("确认删除", color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteModelCandidate = null }) { Text("取消") }
            }
        )
    }

    if (running) {
        FocusedCollectionScreen(
            progress = collectionProgress,
            onStop = {
                collector.stop()
                running = false
                collectionProgress = null
                status = "已停止本次采集，未完成数据不会上传"
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().auroraBackground().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("☰", style = MaterialTheme.typography.titleLarge)
            Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(40.dp))
        }
        Spacer(Modifier.height(8.dp))

        GlassPanel(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
        ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsNavigation(
                selected = settingsSection,
                onSelect = { settingsSection = it },
                modifier = Modifier.width(220.dp).fillMaxHeight()
            )
            if (settingsSection != SettingsSection.RECORDS) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (settingsSection == SettingsSection.SCANNING) {
                    SectionCard("扫描设置") {
                        Text(
                            "设置实时沟通中每个扫描项的停留时间。",
                            color = TextGray,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        AuroraTextField(
                            value = scanIntervalText,
                            onValueChange = {
                                scanIntervalText = it.filter(Char::isDigit).take(4)
                                scanIntervalError = null
                            },
                            label = "扫描时间（ms）",
                            modifier = Modifier.fillMaxWidth(),
                            isError = scanIntervalError != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = scanIntervalError
                                ?: "范围 ${ScanSettings.MIN_INTERVAL_MS}–${ScanSettings.MAX_INTERVAL_MS} ms"
                        )
                        AuroraButton(
                            text = "保存扫描设置",
                            onClick = {
                                val interval = scanIntervalText.toLongOrNull()
                                if (interval == null || interval !in ScanSettings.MIN_INTERVAL_MS..ScanSettings.MAX_INTERVAL_MS) {
                                    scanIntervalError = "请输入有效的扫描时间"
                                } else {
                                    onScanIntervalChange(interval)
                                    scanIntervalText = interval.toString()
                                    scanIntervalError = null
                                    status = "扫描时间已设置为 ${interval}ms"
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style = AuroraButtonStyle.PRIMARY
                        )
                    }
                }
                if (settingsSection == SettingsSection.USERS) {
                SectionCard("") {
                    users.forEach { user ->
                        SelectableGlassPanel(
                            selected = user.localId == selectedUserId,
                            onClick = {
                                selectedUserId = user.localId
                                repository.setActiveUser(user.localId)
                                sessions = repository.listSessions(user.localId)
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("♙", color = PrimaryBlue)
                                Spacer(Modifier.width(10.dp))
                                Text(user.displayName, modifier = Modifier.weight(1f))
                                AuroraButton(
                                    text = "同步",
                                    enabled = deviceBinding != null && !syncingProfile,
                                    onClick = {
                                        syncingProfile = true
                                        coroutineScope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    ProfileSyncManager(context).synchronize(user.localId)
                                                }
                                            }.onSuccess {
                                                refreshUsers(user.localId)
                                                repository.listSessions(user.localId)
                                                    .filter { it.status !in PersonalizationRepository.TERMINAL_STATUSES }
                                                    .forEach { TrainingWorkScheduler.enqueue(context, it) }
                                            }
                                            syncingProfile = false
                                        }
                                    },
                                    minHeight = 36.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                AuroraButton(
                                    text = "删除",
                                    onClick = { deleteCandidate = user },
                                    style = AuroraButtonStyle.DANGER,
                                    minHeight = 36.dp
                                )
                            }
                        }
                    }
                    if (!creatingUser) {
                        SelectableGlassPanel(
                            selected = false,
                            onClick = { creatingUser = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("＋", color = PrimaryBlue, style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(12.dp))
                                Text("创建新用户", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    } else {
                        GlassPanel(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)
                        ) {
                            Text("创建新用户", style = MaterialTheme.typography.titleMedium)
                            AuroraTextField(
                                value = newUserName,
                                onValueChange = { newUserName = it },
                                label = "用户名称",
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AuroraButton(
                                    "取消",
                                    onClick = { creatingUser = false; newUserName = "" },
                                    modifier = Modifier.weight(1f)
                                )
                                AuroraButton(
                                    "创建",
                                    onClick = ::createUserFromInput,
                                    modifier = Modifier.weight(1f),
                                    style = AuroraButtonStyle.PRIMARY,
                                    enabled = newUserName.isNotBlank()
                                )
                            }
                        }
                    }
                }
                }

                if (settingsSection == SettingsSection.SERVER) {
                    SectionCard("") {
                        Text(
                            if (deviceBinding == null) "此设备未绑定" else "此设备已绑定：${deviceBinding?.serverUserId}",
                            color = if (deviceBinding == null) HighlightYellow else AccentGreen,
                            fontSize = 12.sp
                        )
                        if (deviceBinding == null) {
                            AuroraTextField(
                                value = serverUserId,
                                onValueChange = { serverUserId = it },
                                label = "服务端 user_id",
                                modifier = Modifier.fillMaxWidth()
                            )
                            AuroraTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = "API Key",
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                                supportingText = "仅加密保存在本机"
                            )
                            AuroraButton(
                                text = if (binding) "验证中…" else "验证并绑定",
                                enabled = !binding && serverUserId.isNotBlank() && apiKey.isNotBlank(),
                                onClick = {
                                    binding = true
                                    status = "正在验证账号…"
                                    coroutineScope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                 BrainApiClient(serverUserId.trim(), apiKey).authenticate()
                                                repository.bindDeviceServer(serverUserId, apiKey)
                                            }
                                        }.onSuccess {
                                            apiKey = ""
                                            serverUserId = ""
                                            binding = false
                                            deviceBinding = repository.deviceServerBinding()
                                            status = "设备账号已绑定，正在同步本地用户…"
                                            coroutineScope.launch {
                                                val localUsers = repository.listUsers()
                                                val historical = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        ProfileSyncManager(context).listUnassociatedLegacyProfiles()
                                                    }.getOrDefault(emptyList())
                                                }
                                                if (historical.isNotEmpty() && localUsers.any { it.profileId == null }) {
                                                    legacyProfiles = historical
                                                    refreshUsers(selectedUserId)
                                                    status = "发现未关联的历史数据，请先为对应本地用户选择关联"
                                                } else {
                                                    val synced = withContext(Dispatchers.IO) {
                                                        localUsers.count { localUser ->
                                                            runCatching {
                                                                ProfileSyncManager(context).synchronize(localUser.localId)
                                                            }.isSuccess
                                                        }
                                                    }
                                                    refreshUsers(selectedUserId)
                                                    repository.listIncompleteSessions()
                                                        .forEach { TrainingWorkScheduler.enqueue(context, it) }
                                                    status = "设备账号绑定成功，已同步 $synced/${localUsers.size} 个用户"
                                                }
                                            }
                                        }.onFailure {
                                            binding = false
                                            status = "绑定失败：${it.message}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                style = AuroraButtonStyle.PRIMARY
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AuroraButton(
                                    text = "解除设备绑定",
                                    onClick = {
                                    repository.unbindDeviceServer()
                                    deviceBinding = null
                                    status = "已解除此设备的服务端绑定，本地用户和数据均保留"
                                    },
                                    style = AuroraButtonStyle.DANGER
                                )
                            }
                        }
                    }
                }

                selectedUser?.let { user ->
                    if (settingsSection == SettingsSection.PARAMETERS) {
                    SectionCard("") {
                        Text("采集模式", fontSize = 12.sp, color = TextGray)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingChoice(
                                text = "四分类",
                                selected = selectedProtocol == ClassificationProtocol.FOUR_CLASS,
                                onClick = { if (!running) selectedProtocol = ClassificationProtocol.FOUR_CLASS },
                                modifier = Modifier.weight(1f)
                            )
                            SettingChoice(
                                text = "六分类",
                                selected = selectedProtocol == ClassificationProtocol.SIX_ACTION,
                                onClick = { if (!running) selectedProtocol = ClassificationProtocol.SIX_ACTION },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AuroraTextField(
                                value = roundsText,
                                onValueChange = { roundsText = it.filter(Char::isDigit).take(3) },
                                label = "采样轮次",
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            AuroraTextField(
                                value = trainingEpochsText,
                                onValueChange = { trainingEpochsText = it.filter(Char::isDigit).take(2) },
                                label = "训练轮次",
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            AuroraTextField(
                                value = actionSecondsText,
                                onValueChange = { candidate ->
                                    if (candidate.matches(Regex("\\d?(\\.\\d?)?"))) {
                                        actionSecondsText = candidate
                                    }
                                },
                                label = "动作时间（s）",
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                supportingText = "0.8–5.0"
                            )
                        }
                        Text("模型选择", fontSize = 12.sp, color = TextGray)
                        val compatibleModels = models.filter { selectedProtocol in it.supportedProtocols }
                        val compatibleModelKeys = compatibleModels.mapTo(mutableSetOf()) { it.key }
                        val hasCompatibleSelection = selectedModels.isNotEmpty() &&
                            selectedModels.all { it in compatibleModelKeys }
                        if (compatibleModels.isEmpty()) {
                            Text("服务端暂无支持当前采集模式的训练模型", color = ErrorRed, fontSize = 11.sp)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            compatibleModels.forEach { model ->
                                SettingChoice(
                                    text = if (model.key == "wheelchair-eegnet") "EEGNet" else model.name,
                                    selected = model.key in selectedModels,
                                    onClick = { selectedModels = setOf(model.key) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        AuroraButton(
                            text = if (running) "采集中…" else "开始采集",
                            enabled = !running && hasCompatibleSelection && bleState == NaoyunBleManager.State.READY,
                            onClick = startCollection@{
                                if (!hasCompatibleSelection) {
                                    status = "请选择当前采集模式支持的训练模型"
                                    return@startCollection
                                }
                                val rounds = roundsText.toIntOrNull()
                                if (rounds == null || rounds !in 1..100) {
                                    status = "轮次必须在 1–100 之间"
                                    return@startCollection
                                }
                                val trainingEpochs = trainingEpochsText.toIntOrNull()
                                if (trainingEpochs == null || trainingEpochs !in 1..50) {
                                    status = "训练轮次必须在 1–50 之间"
                                    return@startCollection
                                }
                                val actionSeconds = actionSecondsText.toFloatOrNull()
                                if (actionSeconds == null || actionSeconds !in 0.8f..5f) {
                                    status = "动作时间必须在 0.8–5.0 秒之间"
                                    return@startCollection
                                }
                                val settings = runCatching {
                                    CollectionSettings(
                                        rounds = rounds,
                                        trainingEpochs = trainingEpochs,
                                        actionSeconds = actionSeconds,
                                        protocol = selectedProtocol,
                                        selectedModels = selectedModels,
                                        presetByModel = selectedModels.associateWith {
                                            PreprocessingPreset.EEGNET
                                        }
                                    )
                                }.getOrElse {
                                    status = it.message ?: "采集参数无效"
                                    return@startCollection
                                }
                                running = true
                                collectionProgress = null
                                progressText = ""
                                status = "即将开始，请注视屏幕并按提示完成动作"
                                collector.start(user.localId, settings)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style = AuroraButtonStyle.PRIMARY
                        )
                        if (running) {
                            TextButton(onClick = { collector.stop(); running = false }) { Text("停止本次采集", color = ErrorRed) }
                        }
                    }
                    }
                }
            }
            }

            if (settingsSection == SettingsSection.RECORDS) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (sessions.isEmpty()) Text("暂无记录", color = TextGray)
                sessions.forEach { session ->
                    SessionCard(
                        session = session,
                        activeModelFile = activeModelFile,
                        onActivate = { run ->
                            runCatching {
                                UserModelManager(context).activate(session.localUserId, session, run)
                                repository.setActiveUser(session.localUserId)
                                bleManager.setPreprocessing(run.preset.displaySteps())
                            }.onSuccess {
                                sessions = repository.listSessions(session.localUserId)
                                activeModelFile = UserModelManager.activeModel(context, session.localUserId)?.modelFile
                                status = "已手动启用 ${run.modelKey}；实时绘图固定使用 1–45Hz 带通"
                            }.onFailure { status = "模型启用失败：${it.message}" }
                        },
                        onDelete = { run -> deleteModelCandidate = session to run }
                    )
                }
            }
            }
        }
        }
    }
}

@Composable
private fun SettingsNavigation(
    selected: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassPanel(modifier) {
        Text("设置导航", style = MaterialTheme.typography.titleMedium)
        SettingsSection.entries.forEach { section ->
            SelectableGlassPanel(
                selected = section == selected,
                onClick = { onSelect(section) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(section.symbol, color = if (section == selected) PrimaryBlue else TextGray)
                    Text(section.label, fontWeight = if (section == selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
private fun SettingChoice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    SelectableGlassPanel(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = if (selected) PrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun FocusedCollectionScreen(
    progress: ContinuousSessionCollector.Progress?,
    onStop: () -> Unit
) {
    BackHandler(onBack = onStop)
    val prompt = when (progress?.phase) {
        "准备" -> "准备 · ${CollectionSettings.LABEL_DISPLAY[progress.label] ?: progress.label}"
        "执行" -> "执行 · ${CollectionSettings.LABEL_DISPLAY[progress.label] ?: progress.label}"
        "休息" -> "休息"
        "检测" -> "检测双耳数据流"
        "重采" -> "本次无效 · 即将重采"
        else -> "即将开始"
    }
    val completed = progress?.completedActions ?: 0
    val total = progress?.totalActions ?: 1
    val fraction = (completed.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)

    Box(
        Modifier.fillMaxSize().auroraBackground().padding(horizontal = 56.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(30.dp),
                contentAlignment = Alignment.Center
            ) {
                progress?.detail?.takeIf(String::isNotBlank)?.let { detail ->
                    Text(
                        detail,
                        color = HighlightYellow,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
            Text(
                prompt,
                color = TextWhite,
                fontSize = 58.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(0.6f).height(5.dp),
                color = PrimaryBlue,
                trackColor = SurfaceElevated
            )
            Text("$completed / $total", color = TextGray, fontSize = 13.sp)
            TextButton(onClick = onStop) { Text("停止采集", color = TextGray) }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title.isNotBlank()) {
                Text(title, color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            content()
        }
    }
}

@Composable
private fun SessionCard(
    session: TrainingSession,
    activeModelFile: String?,
    onActivate: (ModelRun) -> Unit,
    onDelete: (ModelRun) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${if (session.protocol == ClassificationProtocol.SIX_ACTION) "六分类" else "四分类"} · " +
                    "${session.collectedAtIso} · 采集${session.rounds}轮 · 训练${session.trainingEpochs}轮 · ${session.actionSeconds}秒",
                fontSize = 11.sp,
                color = TextGray
            )
            Text("状态：${session.status.name}", fontSize = 12.sp, color = TextGray)
            session.modelRuns.forEach { run ->
                val isActive = run.modelFile != null && run.modelFile == activeModelFile
                HorizontalDivider()
                Text(
                    "${when (run.modelKey) { "csanet" -> "CSANet"; "wheelchair-eegnet" -> "EEGNet"; else -> run.modelKey }} / ${run.preset.displayName} · ${run.status} · ${(run.progress * 100).roundToInt()}%" +
                        (run.accuracy?.let { " · accuracy ${"%.1f".format(it * 100)}%" } ?: "") +
                        if (isActive) " · 当前启用" else "",
                    fontSize = 11.sp,
                    color = if (isActive) AccentGreen else MaterialTheme.colorScheme.onSurface
                )
                run.error?.let { Text(it, fontSize = 10.sp, color = ErrorRed) }
                if ((run.status == "succeeded" && run.modelFile != null) || run.status == "failed") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (run.status == "succeeded" && run.modelFile != null && !isActive) {
                            TextButton(onClick = { onActivate(run) }) { Text("启用此模型") }
                        }
                        TextButton(onClick = { onDelete(run) }) {
                            Text("删除", color = ErrorRed)
                        }
                    }
                }
            }
            session.error?.let { Text(it, fontSize = 10.sp, color = HighlightYellow) }
        }
    }
}
