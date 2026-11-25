package com.example.input_ds.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.BuildConfig
import com.example.input_ds.assistant.AssistantConfig
import com.example.input_ds.assistant.AssistantConnection
import com.example.input_ds.assistant.AssistantService
import com.example.input_ds.assistant.AssistantServiceException
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.ui.bci.EegStereoView
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import kotlinx.coroutines.launch
import java.io.IOException

internal data class CaregiverInfo(
    val deviceSummary: String,
    val batteryWearingSummary: String,
    val modelSummary: String,
    val communicationSummary: String
)

@Composable
internal fun CaregiverSheet(
    open: Boolean,
    frozen: Boolean,
    headsetControlEnabled: Boolean,
    feedbackSound: Boolean,
    floatingEegVisible: Boolean,
    displayMode: AppUiSettings.DisplayMode = AppUiSettings.DisplayMode.AUTO,
    info: CaregiverInfo,
    assistantConfig: AssistantConfig,
    assistantDraft: String,
    allowHttpAssistant: Boolean,
    debugNoContacts: Boolean,
    bleManager: NaoyunBleManager,
    actionLog: List<String>,
    onClose: () -> Unit,
    onHeadsetControlChange: (Boolean) -> Unit,
    onFeedbackSoundChange: (Boolean) -> Unit,
    onFloatingEegVisibleChange: (Boolean) -> Unit,
    onDisplayModeChange: (AppUiSettings.DisplayMode) -> Unit = {},
    onSaveAssistantConfig: (AssistantConnection) -> Unit,
    onAssistantDraftChange: (String) -> Unit,
    onAssistantNotice: (String, Boolean) -> Unit,
    onDebugNoContactsChange: (Boolean) -> Unit,
    onDebugIncomingCall: () -> Unit,
    onOpenDeviceAndModel: () -> Unit
) {
    val c = LocalBciColors.current
    val backdropInteraction = remember { MutableInteractionSource() }
    val drawerInteraction = remember { MutableInteractionSource() }

    if (open) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = backdropInteraction, indication = null) {}
        )
    }

    AnimatedVisibility(
        visible = open,
        modifier = Modifier.fillMaxSize(),
        enter = if (frozen) EnterTransition.None else slideInHorizontally(
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            initialOffsetX = { it }
        ),
        exit = if (frozen) ExitTransition.None else slideOutHorizontally(
            animationSpec = tween(280, easing = FastOutSlowInEasing),
            targetOffsetX = { it }
        )
    ) {
        var assistantExpanded by remember { mutableStateOf(false) }
        var addressDraft by remember(assistantConfig.address) { mutableStateOf(assistantConfig.address) }
        var codeDraft by remember(assistantConfig.accessCode) { mutableStateOf(assistantConfig.accessCode) }
        var checkStatus by remember { mutableStateOf("") }
        var checking by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun testConnection(targetAddress: String, targetCode: String) {
            val connection = AssistantConnection.parse(targetAddress, targetCode, allowHttpAssistant)
            if (connection == null) {
                val msg = "请先点击「生活助手服务」填写有效地址和连接码"
                checkStatus = msg
                onAssistantNotice(msg, false)
                return
            }
            checking = true
            checkStatus = "正在测试…"
            scope.launch {
                try {
                    val configured = AssistantService.checkConnection(connection)
                    val msg = if (configured) {
                        "已连上电脑，已配置 DeepSeek Key"
                    } else {
                        "已连上电脑，请在电脑端配置 DeepSeek Key"
                    }
                    checkStatus = msg
                    onAssistantNotice(msg, configured)
                } catch (error: AssistantServiceException) {
                    val msg = error.message.orEmpty().ifEmpty { "连接测试失败" }
                    checkStatus = msg
                    onAssistantNotice(msg, false)
                } catch (_: IOException) {
                    val msg = "无法连接电脑服务"
                    checkStatus = msg
                    onAssistantNotice(msg, false)
                } finally {
                    checking = false
                }
            }
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                modifier = Modifier
                    .width(480.dp)
                    .fillMaxHeight()
                    .background(c.careBg)
                    .clickable(interactionSource = drawerInteraction, indication = null) {}
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 26.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "照护面板",
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        color = c.careText,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    CareSmallButton("关闭", fontSize = 16, onClick = onClose)
                }

                CareRow("耳机控制") {
                    CareSwitch(headsetControlEnabled, frozen) { onHeadsetControlChange(!headsetControlEnabled) }
                }
                CareRow("动作提示音") {
                    CareSwitch(feedbackSound, frozen) { onFeedbackSoundChange(!feedbackSound) }
                }
                CareRow("悬浮波形窗口") {
                    CareSwitch(floatingEegVisible, frozen) { onFloatingEegVisibleChange(!floatingEegVisible) }
                }
                CareRow("日夜模式") {
                    CareSegmented(
                        options = listOf(
                            AppUiSettings.DisplayMode.AUTO to "自动",
                            AppUiSettings.DisplayMode.LIGHT to "日间",
                            AppUiSettings.DisplayMode.DARK to "夜间"
                        ),
                        selected = displayMode,
                        onSelect = onDisplayModeChange
                    )
                }
                CareRow("设备") {
                    CareBoldValue(info.deviceSummary)
                }
                CareRow("电量 / 佩戴") {
                    CareBoldValue(info.batteryWearingSummary)
                }
                CareRow("模型") {
                    CareBoldValue(info.modelSummary)
                }
                CareRow("沟通服务") {
                    CareBoldValue(info.communicationSummary)
                }

                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { assistantExpanded = !assistantExpanded }
                            .padding(vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "生活助手服务",
                            fontFamily = BciType.chinese,
                            fontSize = 16.sp,
                            color = c.careText,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CareBoldValue(assistantConfig.hostSummary())
                            CareSmallButton(
                                label = if (checking) "测试中…" else "测试连接",
                                enabled = !checking,
                                onClick = {
                                    if (assistantExpanded) testConnection(addressDraft, codeDraft)
                                    else testConnection(assistantConfig.address, assistantConfig.accessCode)
                                }
                            )
                        }
                    }
                    if (assistantExpanded) {
                        val parsed = AssistantConnection.parse(addressDraft, codeDraft, allowHttpAssistant)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .border(1.dp, c.careLine, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CareField(
                                label = "服务地址",
                                value = addressDraft,
                                enabled = !checking,
                                keyboardType = KeyboardType.Uri,
                                onValueChange = { addressDraft = it; checkStatus = "" }
                            )
                            CareField(
                                label = "连接码",
                                value = codeDraft,
                                enabled = !checking,
                                secret = true,
                                onValueChange = { codeDraft = it; checkStatus = "" }
                            )
                            if (checkStatus.isNotEmpty()) {
                                Text(
                                    checkStatus,
                                    fontFamily = BciType.chinese,
                                    fontSize = 14.sp,
                                    color = c.careText.copy(alpha = 0.85f)
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                            ) {
                                CareSmallButton(
                                    label = "测试连接",
                                    enabled = parsed != null && !checking,
                                    onClick = { testConnection(addressDraft, codeDraft) }
                                )
                                CareSmallButton(
                                    label = "保存",
                                    enabled = parsed != null && !checking,
                                    primary = true,
                                    onClick = {
                                        val valid = parsed ?: return@CareSmallButton
                                        onSaveAssistantConfig(valid)
                                        checkStatus = "已保存"
                                        onAssistantNotice("生活助手服务已保存", true)
                                    }
                                )
                            }
                            Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.careLine))
                            CareField(
                                label = "助手草稿",
                                value = assistantDraft,
                                enabled = true,
                                singleLine = false,
                                onValueChange = onAssistantDraftChange
                            )
                        }
                    }
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.careLine))
                }

                if (BuildConfig.DEBUG) {
                    CareRow("模拟：还没有绑定家属") {
                        CareSwitch(debugNoContacts, frozen) { onDebugNoContactsChange(!debugNoContacts) }
                    }
                    CareRow("模拟：妈妈发起视频来电") {
                        CareSmallButton("来电", onClick = onDebugIncomingCall)
                    }
                }

                AndroidView(
                    update = { it.setPalette(com.example.input_ds.ui.theme.Night) },
                    factory = { EegStereoView(it, bleManager, compactMode = true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, c.careLine, RoundedCornerShape(10.dp))
                )

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "最近识别到的动作",
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = c.careText,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    Text(
                        text = buildAnnotatedString {
                            val text = if (actionLog.isEmpty()) "暂无" else actionLog.joinToString("\n")
                            for (ch in text) {
                                withStyle(
                                    SpanStyle(
                                        fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese
                                    )
                                ) { append(ch) }
                            }
                        },
                        fontSize = 14.sp,
                        lineHeight = 23.8.sp,
                        color = c.careText.copy(alpha = 0.85f),
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.careBtn)
                        .clickable(onClick = onOpenDeviceAndModel)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "设备与模型",
                        fontFamily = BciType.chinese,
                        fontSize = 16.sp,
                        color = c.careBtnText,
                        textAlign = TextAlign.Center,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

@Composable
private fun CareRow(
    label: String,
    trailing: @Composable () -> Unit
) {
    val c = LocalBciColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
                fontFamily = BciType.chinese,
                fontSize = 16.sp,
                color = c.careText,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            trailing()
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.careLine))
    }
}

@Composable
private fun CareBoldValue(text: String) {
    val c = LocalBciColors.current
    Text(
        text = buildAnnotatedString {
            for (ch in text) {
                withStyle(
                    SpanStyle(fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese)
                ) { append(ch) }
            }
        },
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = c.careText,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    )
}

@Composable
private fun CareSmallButton(
    label: String,
    fontSize: Int = 15,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (primary) c.careBtn.copy(alpha = if (enabled) 1f else 0.4f) else Color.Transparent)
            .border(1.dp, c.careLine, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight(500),
            fontSize = fontSize.sp,
            color = (if (primary) c.careBtnText else c.careText).copy(alpha = if (enabled) 1f else 0.45f),
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun <T> CareSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, c.careLine, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { idx, (value, label) ->
            val active = value == selected
            if (idx > 0) {
                Spacer(Modifier.width(1.dp).height(24.dp).background(c.careLine))
            }
            Box(
                modifier = Modifier
                    .background(if (active) c.careBtn else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 13.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontFamily = BciType.chinese,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp,
                    color = if (active) c.careBtnText else c.careText,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun CareSwitch(
    checked: Boolean,
    frozen: Boolean,
    onToggle: () -> Unit
) {
    val c = LocalBciColors.current
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 34.dp else 4.dp,
        animationSpec = tween(if (frozen) 0 else 200),
        label = "careSwitchThumb"
    )
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (checked) c.ok else c.careOff)
            .clickable(onClick = onToggle)
    ) {
        Spacer(
            modifier = Modifier
                .offset(x = thumbOffset, y = 4.dp)
                .size(26.dp)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
private fun CareField(
    label: String,
    value: String,
    enabled: Boolean,
    secret: Boolean = false,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val c = LocalBciColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            fontFamily = BciType.chinese,
            fontSize = 13.sp,
            color = c.careText.copy(alpha = 0.72f)
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 3,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(
                fontFamily = BciType.chinese,
                fontSize = 15.sp,
                color = c.careText
            ),
            cursorBrush = SolidColor(c.careText),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, c.careLine, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}
