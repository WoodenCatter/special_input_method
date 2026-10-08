package com.example.input_ds.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.settings.AppSettingsInputBus
import com.example.input_ds.settings.AppSettingsSignalSink
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.ui.theme.AuroraButton
import com.example.input_ds.ui.theme.AuroraButtonStyle
import com.example.input_ds.ui.theme.AuroraCyan
import com.example.input_ds.ui.theme.AuroraDarkSurfaceStrong
import com.example.input_ds.ui.theme.AuroraOk
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.SelectableGlassPanel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class SettingsFocusMode {
    MENU,
    VOLUME,
    BRIGHTNESS,
    FLOATING_BALL
}

private enum class PatientSetting(
    val title: String,
    val description: String,
    val symbol: String
) {
    VOLUME("调整声音大小", "调节整个 APP 的媒体音量", "♫"),
    BRIGHTNESS("调整屏幕亮度", "调节本 APP 使用期间的屏幕亮度", "☀"),
    FLOATING_BALL("隐藏/显示悬浮球", "隐藏或重新显示测试悬浮球", "●"),
    BACK("返回主页面", "返回主界面继续选择功能", "←")
}

@Composable
fun AppSettingsScreen(
    scanIntervalMs: Long,
    floatingBallVisible: Boolean,
    headsetControlEnabled: Boolean,
    onFloatingBallVisibleChange: (Boolean) -> Unit,
    onHeadsetControlEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnFloatingBallVisibleChange by rememberUpdatedState(onFloatingBallVisibleChange)
    val currentOnHeadsetControlEnabledChange by rememberUpdatedState(onHeadsetControlEnabledChange)

    var focusMode by remember { mutableStateOf(SettingsFocusMode.MENU) }
    var menuIndex by remember { mutableIntStateOf(0) }
    var optionIndex by remember { mutableIntStateOf(0) }
    var scanDirection by remember { mutableIntStateOf(1) }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    var brightness by remember {
        mutableFloatStateOf(AppUiSettings.readScreenBrightness(context))
    }
    val scanDelay = scanIntervalMs.coerceIn(1_100L, 3_000L)

    fun applyBrightness(value: Float) {
        brightness = AppUiSettings.writeScreenBrightness(context, value)
        activity?.let { owner ->
            val attributes = owner.window.attributes
            attributes.screenBrightness = brightness
            owner.window.attributes = attributes
        }
    }

    fun changeVolume(delta: Int) {
        val direction = if (delta < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    fun returnToMenu() {
        focusMode = SettingsFocusMode.MENU
        optionIndex = 0
    }

    fun open(setting: PatientSetting) {
        optionIndex = 0
        focusMode = when (setting) {
            PatientSetting.VOLUME -> SettingsFocusMode.VOLUME
            PatientSetting.BRIGHTNESS -> SettingsFocusMode.BRIGHTNESS
            PatientSetting.FLOATING_BALL -> SettingsFocusMode.FLOATING_BALL
            PatientSetting.BACK -> {
                currentOnBack()
                SettingsFocusMode.MENU
            }
        }
    }

    fun executeOption() {
        when (focusMode) {
            SettingsFocusMode.MENU -> open(PatientSetting.entries[menuIndex])
            SettingsFocusMode.VOLUME -> when (optionIndex) {
                0 -> changeVolume(-1)
                1 -> changeVolume(1)
                else -> returnToMenu()
            }
            SettingsFocusMode.BRIGHTNESS -> when (optionIndex) {
                0 -> applyBrightness(brightness - AppUiSettings.BRIGHTNESS_STEP)
                1 -> applyBrightness(brightness + AppUiSettings.BRIGHTNESS_STEP)
                else -> returnToMenu()
            }
            SettingsFocusMode.FLOATING_BALL -> when (optionIndex) {
                0 -> currentOnFloatingBallVisibleChange(!floatingBallVisible)
                else -> returnToMenu()
            }
        }
    }

    fun optionCount(): Int = when (focusMode) {
        SettingsFocusMode.MENU -> PatientSetting.entries.size
        SettingsFocusMode.VOLUME, SettingsFocusMode.BRIGHTNESS -> 3
        SettingsFocusMode.FLOATING_BALL -> 2
    }

    LaunchedEffect(
        focusMode,
        menuIndex,
        optionIndex,
        scanDirection,
        scanDelay,
        volume,
        brightness,
        floatingBallVisible
    ) {
        delay(scanDelay)
        if (focusMode == SettingsFocusMode.MENU) {
            menuIndex = (menuIndex + scanDirection + PatientSetting.entries.size) %
                PatientSetting.entries.size
        } else {
            val count = optionCount()
            optionIndex = (optionIndex + scanDirection + count) % count
        }
    }

    val signalHandler = rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        when (signal) {
            ControlSignal.LEFT_LOOK -> scanDirection = -1
            ControlSignal.RIGHT_LOOK -> scanDirection = 1
            ControlSignal.BITE -> executeOption()
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }
    val signalSink = remember { AppSettingsSignalSink { signalHandler.value(it) } }

    DisposableEffect(signalSink) {
        AppSettingsInputBus.attach(signalSink)
        onDispose { AppSettingsInputBus.detach(signalSink) }
    }

    BackHandler {
        if (focusMode == SettingsFocusMode.MENU) currentOnBack() else returnToMenu()
    }

    AuroraBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("设置", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "左侧可用耳机操作 · 右侧仅允许医护人员触摸",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = currentOnBack) { Text("返回主页") }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GlassPanel(Modifier.weight(1.15f).fillMaxHeight()) {
                    Text("病人可操作", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "光标自动跳转 ${if (scanDirection < 0) "←" else "→"} · 左看/右看改变方向 · 咬牙进入",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PatientSetting.entries.forEachIndexed { index, setting ->
                        PatientSettingCard(
                            setting = setting,
                            status = when (setting) {
                                PatientSetting.VOLUME -> "$volume / $maxVolume"
                                PatientSetting.BRIGHTNESS -> "${(brightness * 100).roundToInt()}%"
                                PatientSetting.FLOATING_BALL -> if (floatingBallVisible) "当前显示" else "当前隐藏"
                                PatientSetting.BACK -> ""
                            },
                            selected = focusMode == SettingsFocusMode.MENU && menuIndex == index,
                            onClick = {
                                menuIndex = index
                                open(setting)
                            }
                        )
                    }
                }

                GlassPanel(Modifier.weight(.85f).fillMaxHeight()) {
                    Text("医护人员辅助设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "此列不会进入自动扫描，只能用手触摸。耳机信号稳定后再启用控制。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AuroraButton(
                        text = if (headsetControlEnabled) {
                            "耳机控制：已启用\n点击后停用"
                        } else {
                            "耳机控制：已停用\n信号稳定后点击启用"
                        },
                        onClick = {
                            currentOnHeadsetControlEnabledChange(!headsetControlEnabled)
                        },
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                        style = if (headsetControlEnabled) {
                            AuroraButtonStyle.SUCCESS
                        } else {
                            AuroraButtonStyle.DANGER
                        }
                    )
                    Text(
                        if (headsetControlEnabled) {
                            "真实耳机识别结果现在会执行操作；悬浮球始终可用于测试。"
                        } else {
                            "真实耳机仍可连接并等待信号稳定，但识别结果不会触发界面操作。"
                        },
                        color = if (headsetControlEnabled) AuroraOk else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(116.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(AuroraDarkSurfaceStrong),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "医护辅助设置预留区\n后续仅触摸的选项将放在这里",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (focusMode != SettingsFocusMode.MENU) {
            SettingsOverlay(
                mode = focusMode,
                selectedIndex = optionIndex,
                volume = volume,
                maxVolume = maxVolume,
                brightness = brightness,
                floatingBallVisible = floatingBallVisible,
                direction = scanDirection,
                onSelect = { index ->
                    optionIndex = index
                    executeOption()
                }
            )
        }
    }
}

@Composable
private fun PatientSettingCard(
    setting: PatientSetting,
    status: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    SelectableGlassPanel(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(82.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                setting.symbol,
                color = if (selected) AuroraVioletBright else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineMedium
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(setting.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    setting.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (status.isNotBlank()) {
                Text(status, color = AuroraCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SettingsOverlay(
    mode: SettingsFocusMode,
    selectedIndex: Int,
    volume: Int,
    maxVolume: Int,
    brightness: Float,
    floatingBallVisible: Boolean,
    direction: Int,
    onSelect: (Int) -> Unit
) {
    val title: String
    val labels: List<String>
    when (mode) {
        SettingsFocusMode.VOLUME -> {
            title = "声音大小"
            labels = listOf("减小音量", "增大音量", "返回设置")
        }
        SettingsFocusMode.BRIGHTNESS -> {
            title = "屏幕亮度"
            labels = listOf("降低亮度", "提高亮度", "返回设置")
        }
        SettingsFocusMode.FLOATING_BALL -> {
            title = "悬浮球显示"
            labels = listOf(
                if (floatingBallVisible) "隐藏悬浮球" else "显示悬浮球",
                "返回设置"
            )
        }
        SettingsFocusMode.MENU -> return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(50f)
            .background(Color.Black.copy(alpha = .72f))
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        GlassPanel(Modifier.fillMaxWidth(.56f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Text(
                "光标自动跳转 ${if (direction < 0) "←" else "→"} · 左看/右看改变方向 · 咬牙选择",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            if (mode == SettingsFocusMode.VOLUME) {
                Text("音量：$volume / $maxVolume", fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { volume.toFloat() / maxVolume.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.small),
                    color = AuroraCyan,
                    trackColor = AuroraDarkSurfaceStrong
                )
            }
            if (mode == SettingsFocusMode.BRIGHTNESS) {
                Text("亮度：${(brightness * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { brightness },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.small),
                    color = AuroraCyan,
                    trackColor = AuroraDarkSurfaceStrong
                )
            }
            labels.forEachIndexed { index, label ->
                AuroraButton(
                    text = label,
                    onClick = { onSelect(index) },
                    modifier = Modifier.fillMaxWidth(),
                    style = if (index == selectedIndex) {
                        AuroraButtonStyle.PRIMARY
                    } else {
                        AuroraButtonStyle.SECONDARY
                    }
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
