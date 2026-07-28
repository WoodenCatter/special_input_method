package com.example.input_ds.ui.bci

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Choreographer
import android.view.View
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.input_ds.bci.EegScopePlotBuffer
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.bci.RealtimeEegDisplayFilter

private val BgColor = Color(0xFF090F1C)
private val MutedColor = Color(0xFF94A3B8)

@Composable
fun BleScanScreen(bleManager: NaoyunBleManager, onConnected: () -> Unit) {
    val state by bleManager.state.collectAsState()
    val devices by bleManager.scanResults.collectAsState()
    val scanError by bleManager.scanError.collectAsState()

    LaunchedEffect(Unit) { bleManager.startScan() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "BLE 设备扫描",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF5B8DEF)
        )
        Spacer(Modifier.height(8.dp))
        Text("找到含「Naoyun」的设备，点击连接", fontSize = 13.sp, color = MutedColor)

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
                color = Color(0xFFEF5350),
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
            Text("正在扫描...", fontSize = 13.sp, color = Color(0xFFFFD740))
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(devices) { device ->
                Button(
                    onClick = { bleManager.connect(device.device) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E1E2E)
                    )
                ) {
                    Column(Modifier.padding(4.dp)) {
                        Text(device.name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(device.address, fontSize = 11.sp, color = MutedColor)
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
                    color = Color(0xFF5B8DEF)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state == NaoyunBleManager.State.CONNECTING) {
                        "正在连接…"
                    } else {
                        "正在初始化脑电数据流…"
                    },
                    color = MutedColor
                )
            }
        }
        if (state == NaoyunBleManager.State.ERROR) {
            Text("连接初始化失败，请重新扫描", color = Color(0xFFEF5350))
        }
    }
}

@Composable
fun SignalMonitorScreen(
    bleManager: NaoyunBleManager,
    onBack: () -> Unit,
    onStartCollect: () -> Unit = {},
    onStartControl: () -> Unit = {}
) {
    val deviceInfo by bleManager.deviceInfo.collectAsState()

    Column(Modifier.fillMaxSize().background(BgColor).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(
                "双通道实时耳电信号",
                fontSize = 18.sp,
                color = Color(0xFFE2E8F0),
                fontWeight = FontWeight.Bold
            )
            Row {
                TextButton(onClick = onStartCollect) {
                    Text("采集", color = Color(0xFFFF9800))
                }
                TextButton(onClick = onStartControl) {
                    Text("BCI控制", color = Color(0xFF4CAF50))
                }
                TextButton(onClick = onBack) {
                    Text("断开", color = Color(0xFFEF5350))
                }
            }
        }
        Text(
            "500 Hz | 0.01–100 Hz 实时带通 | 5 秒环形扫描 | 固定量程 ±200 µV | $deviceInfo",
            fontSize = 10.sp,
            color = MutedColor
        )
        Spacer(Modifier.height(4.dp))

        AndroidView(
            factory = { EegChannelView(it, true, bleManager) },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        AndroidView(
            factory = { EegChannelView(it, false, bleManager) },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

/**
 * 对齐 Ear_EEG_2/EEGPlotter 的示波器式绘图：
 * 固定 5 秒画布，只覆盖刷新线之后的新数据，不移动历史波形。
 */
@SuppressLint("ViewConstructor")
internal class EegChannelView(
    context: Context,
    private val isLeft: Boolean,
    private val bleManager: NaoyunBleManager
) : View(context) {
    private val scopeBuffer = EegScopePlotBuffer(BUFFER_SIZE)
    private val displayFilter = RealtimeEegDisplayFilter()
    private var snapshot = scopeBuffer.snapshot()
    private var lastConsumedCount = -1
    private val drawPath = Path()

    private val panelBackground = android.graphics.Color.WHITE
    private val gridColor = android.graphics.Color.parseColor("#DDDDDD")
    private val zeroLineColor = android.graphics.Color.parseColor("#AAAAAA")
    private val lineColor = android.graphics.Color.parseColor(
        if (isLeft) "#E53935" else "#1E88E5"
    )
    private val textColor = android.graphics.Color.parseColor("#333333")
    private val refreshLineColor = android.graphics.Color.parseColor("#222222")

    private val backgroundPaint = Paint().apply {
        color = panelBackground
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply {
        color = gridColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val zeroPaint = Paint().apply {
        color = zeroLineColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint().apply {
        color = lineColor
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val refreshLinePaint = Paint().apply {
        color = refreshLineColor
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val namePaint = Paint().apply {
        color = lineColor
        textSize = 36f
        isAntiAlias = true
        isFakeBoldText = true
    }
    private val statsPaint = Paint().apply {
        color = textColor
        textSize = 24f
        isAntiAlias = true
    }
    private val axisPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#777777")
        textSize = 22f
        isAntiAlias = true
    }
    private val emptyPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#94A3B8")
        textSize = 40f
        isAntiAlias = true
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            consumeAvailableSamples()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resetPlot()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun resetPlot() {
        scopeBuffer.reset()
        displayFilter.reset()
        snapshot = scopeBuffer.snapshot()
        lastConsumedCount = -1
        invalidate()
    }

    private fun consumeAvailableSamples() {
        // Both plots advance only on the stereo samples already received by
        // both ears. The two refresh cursors therefore share one time axis.
        val currentCount = bleManager.buffer.synchronizedCount()

        if (lastConsumedCount < 0) {
            lastConsumedCount = (currentCount - BUFFER_SIZE).coerceAtLeast(0)
        } else if (currentCount < lastConsumedCount) {
            // BCI 控制启动或设备重连会清空共享采样缓冲区。
            scopeBuffer.reset()
            displayFilter.reset()
            lastConsumedCount = 0
        } else if (currentCount - lastConsumedCount > BUFFER_SIZE) {
            // 页面长时间不可见时只恢复最近 5 秒，避免追赶过期数据。
            scopeBuffer.reset()
            displayFilter.reset()
            lastConsumedCount = currentCount - BUFFER_SIZE
        }

        if (currentCount <= lastConsumedCount) return
        val raw = if (isLeft) {
            bleManager.buffer.getLeftRange(lastConsumedCount, currentCount)
        } else {
            bleManager.buffer.getRightRange(lastConsumedCount, currentCount)
        }
        lastConsumedCount = currentCount
        if (raw.isEmpty()) return

        scopeBuffer.appendAligned(lastConsumedCount - raw.size, displayFilter.process(raw))
        snapshot = scopeBuffer.snapshot()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
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

        val label = if (isLeft) "左耳 / Channel 0" else "右耳 / Channel 1"
        canvas.drawText(label, 12f, 38f, namePaint)
        val stats = "当前 ${snapshot.current.format(1)} µV  " +
                "均值 ${snapshot.mean.format(1)} µV  " +
                "峰峰值 ${snapshot.peakToPeak.format(1)} µV  " +
                "点数 ${snapshot.validCount}"
        canvas.drawText(stats, namePaint.measureText(label) + 36f, 34f, statsPaint)

        canvas.drawText("+200µV", 6f, plotTop + 24f, axisPaint)
        canvas.drawText("0", 6f, plotCenter + 8f, axisPaint)
        canvas.drawText("-200µV", 6f, plotBottom - 6f, axisPaint)
        canvas.drawText("0s", 12f, height - 6f, axisPaint)
        canvas.drawText("5s", width - 36f, height - 6f, axisPaint)

        if (snapshot.validCount < 2) {
            canvas.drawText("等待数据…", width / 2f - 80f, plotCenter, emptyPaint)
            return
        }

        canvas.save()
        canvas.clipRect(0f, plotTop, width, plotBottom)
        if (snapshot.validCount < BUFFER_SIZE) {
            drawSegment(
                canvas,
                snapshot.values,
                0,
                snapshot.validCount,
                width,
                plotCenter,
                plotHeight
            )
        } else if (snapshot.writePosition == 0) {
            drawSegment(
                canvas,
                snapshot.values,
                0,
                BUFFER_SIZE,
                width,
                plotCenter,
                plotHeight
            )
        } else {
            // 在新旧两轮数据交界处断开，避免画出不真实的竖直连接线。
            drawSegment(
                canvas,
                snapshot.values,
                0,
                snapshot.writePosition,
                width,
                plotCenter,
                plotHeight
            )
            drawSegment(
                canvas,
                snapshot.values,
                snapshot.writePosition,
                BUFFER_SIZE,
                width,
                plotCenter,
                plotHeight
            )
        }

        val sharedWritePosition =
            Math.floorMod(bleManager.buffer.synchronizedCount(), BUFFER_SIZE)
        val refreshX =
            sharedWritePosition.toFloat() / (BUFFER_SIZE - 1) * width
        canvas.drawLine(
            refreshX,
            plotTop,
            refreshX,
            plotBottom,
            refreshLinePaint
        )
        canvas.restore()
    }

    private fun drawSegment(
        canvas: Canvas,
        values: FloatArray,
        start: Int,
        endExclusive: Int,
        width: Float,
        plotCenter: Float,
        plotHeight: Float
    ) {
        if (endExclusive - start < 2) return
        val step = maxOf(1, (endExclusive - start) / MAX_POINTS_PER_SEGMENT)
        drawPath.reset()
        var index = start
        var lastDrawn = -1
        while (index < endExclusive) {
            appendPathPoint(
                index,
                values[index],
                width,
                plotCenter,
                plotHeight,
                lastDrawn < 0
            )
            lastDrawn = index
            index += step
        }
        if (lastDrawn != endExclusive - 1) {
            appendPathPoint(
                endExclusive - 1,
                values[endExclusive - 1],
                width,
                plotCenter,
                plotHeight,
                false
            )
        }
        canvas.drawPath(drawPath, linePaint)
    }

    private fun appendPathPoint(
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
        if (move) drawPath.moveTo(x, y) else drawPath.lineTo(x, y)
    }

    private companion object {
        const val SAMPLE_RATE = 500
        const val WINDOW_SECONDS = 5
        const val BUFFER_SIZE = SAMPLE_RATE * WINDOW_SECONDS
        const val FIXED_Y_RANGE_UV = 200f
        const val HEADER_HEIGHT = 52f
        const val FOOTER_HEIGHT = 24f
        const val PLOT_HEIGHT_RATIO = 0.42f
        const val MAX_POINTS_PER_SEGMENT = 800
    }
}

private fun Float.format(decimals: Int): String =
    String.format("%.${decimals}f", this)
