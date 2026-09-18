package com.example.input_ds.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.model.HomeModule
import com.example.input_ds.model.HomeSelectionState
import com.example.input_ds.ui.bci.EegStereoView
import com.example.input_ds.ui.theme.AuroraBackground
import com.example.input_ds.ui.theme.AuroraBadge
import com.example.input_ds.ui.theme.AuroraBadgeStyle
import com.example.input_ds.ui.theme.AuroraCyan
import com.example.input_ds.ui.theme.AuroraEmptyState
import com.example.input_ds.ui.theme.AuroraInfo
import com.example.input_ds.ui.theme.AuroraOk
import com.example.input_ds.ui.theme.AuroraVioletBright
import com.example.input_ds.ui.theme.GlassPanel
import com.example.input_ds.ui.theme.SectionHeader
import com.example.input_ds.ui.theme.SelectableGlassPanel

private const val REQUIRED_DEVICE_NAME = "Naoyun Pods BLE"

@Composable
fun HomeScreen(
    selection: HomeSelectionState,
    bleManager: NaoyunBleManager,
    onRequestPermissions: () -> Unit,
    onSelect: (HomeModule) -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onConfirm: () -> Unit
) {
    val bleState by bleManager.state.collectAsState()
    val scanResults by bleManager.scanResults.collectAsState()
    val scanError by bleManager.scanError.collectAsState()
    val connectedDevice by bleManager.connectedDevice.collectAsState()
    val telemetry by bleManager.deviceTelemetry.collectAsState()

    LaunchedEffect(bleState) {
        if (connectedDevice == null && bleState in setOf(
                NaoyunBleManager.State.IDLE,
                NaoyunBleManager.State.DISCONNECTED,
                NaoyunBleManager.State.ERROR
            )
        ) {
            if (bleManager.hasPermissions()) bleManager.startScan() else onRequestPermissions()
        }
    }

    AuroraBackground {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("主页面", style = MaterialTheme.typography.headlineMedium)
            GlassPanel(Modifier.weight(1f).fillMaxWidth()) {
                ModuleStrip(selection, onSelect)
                DeviceStatusLine(bleState, telemetry)
                HomeDataArea(
                    modifier = Modifier.weight(1f),
                    bleManager = bleManager,
                    bleState = bleState,
                    scanResults = scanResults.filter {
                        it.name.contains(REQUIRED_DEVICE_NAME, ignoreCase = true)
                    },
                    scanError = scanError,
                    connectedDevice = connectedDevice,
                    telemetry = telemetry,
                    onRequestPermissions = onRequestPermissions
                )
            }
        }
    }
}

@Composable
private fun DeviceStatusLine(
    state: NaoyunBleManager.State,
    telemetry: NaoyunBleManager.DeviceTelemetry?
) {
    val connected = state in setOf(
        NaoyunBleManager.State.READY,
        NaoyunBleManager.State.STREAM_STALLED,
        NaoyunBleManager.State.RECOVERING
    )
    Text(
        text = buildString {
            append("ᛒ  ")
            append(if (connected) "设备连接" else "设备未连接")
            append("    电量 L:")
            append(telemetry?.leftBatteryPercent?.let { "$it%" } ?: "--")
            append(" R:")
            append(telemetry?.rightBatteryPercent?.let { "$it%" } ?: "--")
            append("  |  佩戴 L:")
            append(telemetry?.leftWorn?.let { if (it) "是" else "否" } ?: "--")
            append(" R:")
            append(telemetry?.rightWorn?.let { if (it) "是" else "否" } ?: "--")
        },
        color = if (connected) AuroraInfo else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun ModuleStrip(selection: HomeSelectionState, onSelect: (HomeModule) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeModule.entries.forEachIndexed { index, module ->
                    ModuleCard(module, index == selection.selectedIndex, { onSelect(module) }, Modifier.weight(1f))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeModule.entries.chunked(2).forEach { modules ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        modules.forEach { module ->
                            ModuleCard(
                                module,
                                HomeModule.entries.indexOf(module) == selection.selectedIndex,
                                { onSelect(module) },
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(module: HomeModule, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val symbol = when (module) {
        HomeModule.REALTIME_COMMUNICATION -> "◌"
        HomeModule.MAZE -> "▦"
        HomeModule.ENTERTAINMENT -> "▷"
        HomeModule.SETTINGS -> "⚙"
    }
    SelectableGlassPanel(
        selected = selected,
        onClick = onClick,
        modifier = modifier.height(132.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                symbol,
                color = if (selected) AuroraVioletBright else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.height(10.dp))
            Text(module.displayName, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun HomeDataArea(
    modifier: Modifier,
    bleManager: NaoyunBleManager,
    bleState: NaoyunBleManager.State,
    scanResults: List<NaoyunBleManager.BleDevice>,
    scanError: NaoyunBleManager.ScanError,
    connectedDevice: NaoyunBleManager.ConnectedDevice?,
    telemetry: NaoyunBleManager.DeviceTelemetry?,
    onRequestPermissions: () -> Unit
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 760.dp
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DevicePanel(
                    Modifier.weight(.38f).fillMaxHeight(), bleManager, bleState, scanResults,
                    scanError, connectedDevice, telemetry, onRequestPermissions
                )
                SignalPanel(Modifier.weight(.62f).fillMaxHeight(), bleManager, bleState)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DevicePanel(
                    Modifier.weight(.46f).fillMaxWidth(), bleManager, bleState, scanResults,
                    scanError, connectedDevice, telemetry, onRequestPermissions
                )
                SignalPanel(Modifier.weight(.54f).fillMaxWidth(), bleManager, bleState)
            }
        }
    }
}

@Composable
private fun DevicePanel(
    modifier: Modifier,
    bleManager: NaoyunBleManager,
    state: NaoyunBleManager.State,
    devices: List<NaoyunBleManager.BleDevice>,
    scanError: NaoyunBleManager.ScanError,
    connected: NaoyunBleManager.ConnectedDevice?,
    telemetry: NaoyunBleManager.DeviceTelemetry?,
    onRequestPermissions: () -> Unit
) {
    GlassPanel(modifier) {
        connected?.let {
            DeviceRow(it.name, true) { bleManager.disconnect() }
        }
        devices.filterNot { it.address == connected?.address }.take(4).forEach { device ->
            DeviceRow(device.name, false) { bleManager.connect(device.device) }
        }
    }
}

@Composable
private fun DeviceRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) AuroraVioletBright else MaterialTheme.colorScheme.outline)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ᛒ", color = if (selected) AuroraCyan else AuroraInfo, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (selected) Text("✓", color = AuroraOk)
        }
    }
}

@Composable
private fun SignalPanel(modifier: Modifier, bleManager: NaoyunBleManager, state: NaoyunBleManager.State) {
    GlassPanel(modifier) {
        Text("实时信号", style = MaterialTheme.typography.titleMedium)
        if (state in setOf(
                NaoyunBleManager.State.READY,
                NaoyunBleManager.State.STREAM_STALLED,
                NaoyunBleManager.State.RECOVERING
            )
        ) {
            AndroidView(
                factory = { EegStereoView(it, bleManager) },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            AuroraEmptyState("连接设备后显示实时信号", Modifier.weight(1f).fillMaxWidth(), symbol = "∿")
        }
    }
}
