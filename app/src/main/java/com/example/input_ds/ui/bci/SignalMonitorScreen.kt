package com.example.input_ds.ui.bci

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.bci.EegScopePlotBuffer
import com.example.input_ds.bci.EegPreprocessor
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.ui.theme.DarkBackground
import com.example.input_ds.ui.theme.ErrorRed
import com.example.input_ds.ui.theme.HighlightOrange
import com.example.input_ds.ui.theme.HighlightYellow
import com.example.input_ds.ui.theme.PrimaryBlue
import com.example.input_ds.ui.theme.SurfaceDark
import com.example.input_ds.ui.theme.TextGray
import com.example.input_ds.ui.theme.TextWhite
import com.example.input_ds.ui.theme.auroraBackground

@Composable
fun BleScanScreen(
    bleManager: NaoyunBleManager,
    onConnected: () -> Unit,
    onBack: () -> Unit = {}
) {
    val state by bleManager.state.collectAsState()
    val devices by bleManager.scanResults.collectAsState()
    val scanError by bleManager.scanError.collectAsState()

    LaunchedEffect(Unit) { bleManager.startScan() }

    Column(Modifier.fillMaxSize().auroraBackground().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = PrimaryBlue) }
            Text(
                "BLE 设备扫描",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue
            )
            Spacer(Modifier.width(64.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("仅显示名称包含“Naoyun Pods BLE”的设备", fontSize = 13.sp, color = TextGray)

        val errorMessage = when (scanError) {
            NaoyunBleManager.ScanError.BT_OFF -> "⚠️ 请先打开手机蓝牙"
            NaoyunBleManager.ScanError.NO_ADAPTER -> "⚠️ 设备不支持蓝牙"
            NaoyunBleManager.ScanError.NO_PERMISSION ->
                "⚠️ 请在系统设置中允许「位置」和「附近的设备」权限"
            NaoyunBleManager.ScanError.LOCATION_OFF -> "⚠️ 请先打开手机位置服务(GPS)"
            NaoyunBleManager.ScanError.NO_SCANNER -> "⚠️ 设备不支持BLE扫描"
            NaoyunBleManager.ScanError.UNKNOWN -> "⚠️ 扫描失败，请重试"
            else -> null
        }
        if (errorMessage != null) {
            Text(
                errorMessage,
                color = ErrorRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { bleManager.startScan() },
            enabled = state != NaoyunBleManager.State.SCANNING
        ) {
            Text(
                if (state == NaoyunBleManager.State.SCANNING) {
                    "扫描中… (${devices.size}个设备)"
                } else {
                    "重新扫描"
                }
            )
        }
        if (state == NaoyunBleManager.State.SCANNING) {
            Text("正在扫描...", fontSize = 13.sp, color = HighlightYellow)
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(devices) { device ->
                Button(
                    onClick = { bleManager.connect(device.device) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceDark
                    )
                ) {
                    Column(Modifier.padding(4.dp)) {
                        Text(device.name, color = TextWhite, fontWeight = FontWeight.Bold)
                        Text(device.address, fontSize = 11.sp, color = TextGray)
                    }
                }
            }
        }

        LaunchedEffect(state) {
            if (state == NaoyunBleManager.State.READY) onConnected()
        }
        if (state == NaoyunBleManager.State.CONNECTING ||
            state == NaoyunBleManager.State.INITIALIZING
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(
                    Modifier.size(24.dp),
                    color = PrimaryBlue
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state == NaoyunBleManager.State.CONNECTING) {
                        "正在连接…"
                    } else {
                        "正在初始化脑电数据流…"
                    },
                    color = TextGray
                )
            }
        }
        if (state == NaoyunBleManager.State.ERROR) {
            Text("连接初始化失败，请重新扫描", color = ErrorRed)
        }
    }
}

@Composable
fun SignalMonitorScreen(
    bleManager: NaoyunBleManager,
    onBack: () -> Unit,
    onStartCollect: () -> Unit = {},
    onDisconnect: () -> Unit = {}
) {
    val deviceInfo by bleManager.deviceInfo.collectAsState()
    val streamDiagnostics by bleManager.streamDiagnostics.collectAsState()

    Column(Modifier.fillMaxSize().auroraBackground().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(
                "双通道实时耳电信号",
                fontSize = 18.sp,
                color = TextWhite,
                fontWeight = FontWeight.Bold
            )
            Row {
                TextButton(onClick = onStartCollect) {
                    Text("采集管理", color = HighlightOrange)
                }
                TextButton(onClick = onDisconnect) {
                    Text("断开耳机", color = ErrorRed)
                }
                TextButton(onClick = onBack) {
                    Text("返回", color = PrimaryBlue)
                }
            }
        }
        Text(
            "设备状态页已屏蔽耳电控制命令，连接、波形和采集保持运行",
            fontSize = 10.sp,
            color = TextGray
        )
        Text(
            "500 Hz | 5 秒窗口 | 固定量程 ±200 µV | 1–45 Hz 带通 | $deviceInfo",
            fontSize = 10.sp,
            color = TextGray
        )
        if (streamDiagnostics.leftLeadOff != null || streamDiagnostics.rightLeadOff != null) {
            Text(
                "电极接触原始值 L:${streamDiagnostics.leftLeadOff ?: "--"} " +
                    "R:${streamDiagnostics.rightLeadOff ?: "--"}（数值突变时优先检查佩戴）",
                fontSize = 10.sp,
                color = TextGray
            )
        }
        if (streamDiagnostics.leftRateHz != null && streamDiagnostics.rightRateHz != null) {
            Text(
                "时钟 L:${"%.2f".format(streamDiagnostics.leftRateHz)} Hz " +
                    "R:${"%.2f".format(streamDiagnostics.rightRateHz)} Hz · " +
                    "拟合P95 ${"%.1f".format(streamDiagnostics.leftClockResidualMs ?: 0.0)}/" +
                    "${"%.1f".format(streamDiagnostics.rightClockResidualMs ?: 0.0)} ms",
                fontSize = 10.sp,
                color = TextGray
            )
        }
        Spacer(Modifier.height(4.dp))

        if (streamDiagnostics.delayed || streamDiagnostics.stalledSide != null) {
            Text(
                "数据延迟 L:${streamDiagnostics.leftAgeMs ?: "--"}ms " +
                    "R:${streamDiagnostics.rightAgeMs ?: "--"}ms " +
                    "配对:${streamDiagnostics.pairAgeMs ?: "--"}ms",
                fontSize = 10.sp,
                color = ErrorRed
            )
        }
        AndroidView(
            factory = { EegStereoView(it, bleManager) },
            modifier = Modifier.weight(1f).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, com.example.input_ds.ui.theme.BlockBorder, RoundedCornerShape(16.dp))
        )
    }
}

/** Aurora glass styled, fixed-range five-second rolling timeline. */
@SuppressLint("ViewConstructor")
internal class EegStereoView(
    context: Context,
    private val bleManager: NaoyunBleManager
) : View(context) {
    private val leftBuffer = EegScopePlotBuffer(BUFFER_SIZE)
    private val rightBuffer = EegScopePlotBuffer(BUFFER_SIZE)
    private var leftSnapshot = leftBuffer.snapshot()
    private var rightSnapshot = rightBuffer.snapshot()
    private val leftPath = Path()
    private val rightPath = Path()
    private var refreshRunning = false

    private val panelBackground = android.graphics.Color.parseColor("#0B1025")
    private val headerBackground = android.graphics.Color.parseColor("#121936")
    private val gridColor = android.graphics.Color.parseColor("#242C51")
    private val zeroLineColor = android.graphics.Color.parseColor("#51608D")
    private val leftColor = android.graphics.Color.parseColor("#B9A7FF")
    private val rightColor = android.graphics.Color.parseColor("#7DD3FC")
    private val textColor = android.graphics.Color.parseColor("#D8DCF4")

    private val backgroundPaint = Paint().apply {
        color = panelBackground
        style = Paint.Style.FILL
    }
    private val headerPaint = Paint().apply {
        color = headerBackground
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = gridColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val zeroPaint = Paint().apply {
        color = zeroLineColor
        strokeWidth = 1.25f
        style = Paint.Style.STROKE
    }
    private val leftPaint = Paint().apply {
        color = leftColor
        strokeWidth = 2.1f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rightPaint = Paint(leftPaint).apply { color = rightColor }
    private val leftNamePaint = Paint().apply {
        color = leftColor
        textSize = 26f
        isAntiAlias = true
    }
    private val rightNamePaint = Paint(leftNamePaint).apply { color = rightColor }
    private val statsPaint = Paint().apply {
        color = textColor
        textSize = 22f
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#8E96BD")
        textSize = 20f
        isAntiAlias = true
    }
    private val emptyPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#AEB5D7")
        textSize = 32f
        isAntiAlias = true
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!refreshRunning || !isAttachedToWindow) return
            requestVisibleWindow()
            postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resetPlot()
        refreshRunning = true
        removeCallbacks(refreshRunnable)
        post(refreshRunnable)
    }

    override fun onDetachedFromWindow() {
        refreshRunning = false
        removeCallbacks(refreshRunnable)
        super.onDetachedFromWindow()
    }

    private fun resetPlot() {
        leftBuffer.reset()
        rightBuffer.reset()
        leftSnapshot = leftBuffer.snapshot()
        rightSnapshot = rightBuffer.snapshot()
        invalidate()
    }

    private fun requestVisibleWindow() {
        // Rebuild the complete visible window on the latest two clock models.
        // Incrementally appending a permanently aligned stream would freeze old
        // timing errors and make the channels drift apart during a long session.
        val raw = bleManager.latestAlignedWindowAtMost(BUFFER_SIZE, MIN_VISIBLE_POINTS) ?: return
        val left = EegPreprocessor.filterForDisplay(raw.left)
        val right = EegPreprocessor.filterForDisplay(raw.right)
        leftBuffer.reset()
        rightBuffer.reset()
        leftBuffer.append(left)
        rightBuffer.append(right)
        leftSnapshot = leftBuffer.snapshot()
        rightSnapshot = rightBuffer.snapshot()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        canvas.drawRect(0f, 0f, width, HEADER_HEIGHT, headerPaint)
        val plotTop = HEADER_HEIGHT
        val plotBottom = height - FOOTER_HEIGHT
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
        val plotCenter = plotTop + plotHeight / 2f

        for (index in 1..9) {
            val x = width * index / 10f
            canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
        }
        for (index in 1..3) {
            val y = plotTop + plotHeight * index / 4f
            canvas.drawLine(0f, y, width, y, gridPaint)
        }
        canvas.drawLine(0f, plotCenter, width, plotCenter, zeroPaint)

        canvas.drawCircle(18f, 27f, 5f, leftPaint)
        canvas.drawText("L 左耳", 32f, 34f, leftNamePaint)
        canvas.drawCircle(138f, 27f, 5f, rightPaint)
        canvas.drawText("R 右耳", 152f, 34f, rightNamePaint)
        val filterLabel = "1–45 Hz 带通"
        canvas.drawText(filterLabel, width - statsPaint.measureText(filterLabel) - 16f, 33f, statsPaint)

        canvas.drawText("+200µV", 6f, plotTop + 24f, axisPaint)
        canvas.drawText("0", 6f, plotCenter + 8f, axisPaint)
        canvas.drawText("-200µV", 6f, plotBottom - 6f, axisPaint)
        canvas.drawText("-5 s", 6f, height - 6f, axisPaint)
        val nowLabel = "现在"
        canvas.drawText(nowLabel, width - axisPaint.measureText(nowLabel) - 6f, height - 6f, axisPaint)

        if (minOf(leftSnapshot.validCount, rightSnapshot.validCount) < 2) {
            canvas.drawText("等待数据…", width / 2f - 80f, plotCenter, emptyPaint)
            return
        }

        canvas.save()
        canvas.clipRect(0f, plotTop, width, plotBottom)
        drawSnapshot(canvas, leftSnapshot, leftPath, leftPaint, width, plotCenter, plotHeight)
        drawSnapshot(canvas, rightSnapshot, rightPath, rightPaint, width, plotCenter, plotHeight)

        canvas.restore()
        when (bleManager.state.value) {
            NaoyunBleManager.State.STREAM_STALLED ->
                canvas.drawText("双耳数据流中断", width / 2f - 110f, plotCenter, emptyPaint)
            NaoyunBleManager.State.RECOVERING ->
                canvas.drawText("正在自动恢复数据流…", width / 2f - 140f, plotCenter, emptyPaint)
            else -> Unit
        }
    }

    private fun drawSnapshot(
        canvas: Canvas,
        snapshot: EegScopePlotBuffer.Snapshot,
        path: Path,
        paint: Paint,
        width: Float,
        plotCenter: Float,
        plotHeight: Float
    ) {
        if (snapshot.validCount < 2) return
        val step = maxOf(
            1,
            (snapshot.validCount + MAX_POINTS_PER_SEGMENT - 1) / MAX_POINTS_PER_SEGMENT
        )
        path.reset()
        var index = 0
        var lastDrawn = -1
        while (index < snapshot.validCount) {
            appendPathPoint(
                path,
                index,
                snapshot.chronologicalValueAt(index),
                width,
                plotCenter,
                plotHeight,
                lastDrawn < 0
            )
            lastDrawn = index
            index += step
        }
        if (lastDrawn != snapshot.validCount - 1) {
            appendPathPoint(
                path,
                snapshot.validCount - 1,
                snapshot.chronologicalValueAt(snapshot.validCount - 1),
                width,
                plotCenter,
                plotHeight,
                false
            )
        }
        canvas.drawPath(path, paint)
    }

    private fun appendPathPoint(
        path: Path,
        index: Int,
        value: Float,
        width: Float,
        plotCenter: Float,
        plotHeight: Float,
        move: Boolean
    ) {
        val x = index.toFloat() / (BUFFER_SIZE - 1) * width
        val clipped = value.coerceIn(-FIXED_Y_RANGE_UV, FIXED_Y_RANGE_UV)
        val y =
            plotCenter - clipped / FIXED_Y_RANGE_UV * plotHeight * PLOT_HEIGHT_RATIO
        if (move) path.moveTo(x, y) else path.lineTo(x, y)
    }

    private companion object {
        const val SAMPLE_RATE = 500
        const val WINDOW_SECONDS = 5
        const val BUFFER_SIZE = SAMPLE_RATE * WINDOW_SECONDS
        const val MIN_VISIBLE_POINTS = 32
        const val FIXED_Y_RANGE_UV = 200f
        const val HEADER_HEIGHT = 52f
        const val FOOTER_HEIGHT = 24f
        const val PLOT_HEIGHT_RATIO = 0.42f
        const val MAX_POINTS_PER_SEGMENT = 800
        const val REFRESH_INTERVAL_MS = 50L
    }
}
