package com.example.input_ds.bci

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Naoyun BLE-096E 蓝牙管理器（对齐 Python blue_tooth_device.py）
 *
 * 流程：discover → connect → enable 3 notifications → GET_INFO → OPEN_DATA → 数据解析
 */
class NaoyunBleManager(val context: Context) {

    enum class State {
        IDLE, SCANNING, CONNECTING, INITIALIZING, READY,
        STREAM_STALLED, RECOVERING, DISCONNECTED, ERROR
    }
    enum class ScanError { NONE, BT_OFF, NO_ADAPTER, NO_PERMISSION, LOCATION_OFF, NO_SCANNER, UNKNOWN }
    data class BleDevice(val name: String, val address: String, val device: BluetoothDevice)
    data class ConnectedDevice(val name: String, val address: String)
    data class DeviceTelemetry(
        val leftBatteryPercent: Int,
        val rightBatteryPercent: Int,
        val leftWorn: Boolean,
        val rightWorn: Boolean,
        val earMode: String
    )
    data class StereoSamples(
        val left: FloatArray,
        val right: FloatArray,
        val startSample: Int
    )
    data class AlignedWindow(
        val left: FloatArray,
        val right: FloatArray,
        val startTimeUs: Long,
        val endTimeUs: Long,
        val quality: Float,
        val leftRateHz: Double,
        val rightRateHz: Double
    )
    data class StreamDiagnostics(
        val leftAgeMs: Long? = null,
        val rightAgeMs: Long? = null,
        val pairAgeMs: Long? = null,
        val leftLeadOff: Int? = null,
        val rightLeadOff: Int? = null,
        val leftRateHz: Double? = null,
        val rightRateHz: Double? = null,
        val leftClockResidualMs: Double? = null,
        val rightClockResidualMs: Double? = null,
        val stalledSide: String? = null,
        val recoveryAttempt: Int = 0,
        val delayed: Boolean = false
    )

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private val _scanResults = MutableStateFlow<List<BleDevice>>(emptyList())
    val scanResults: StateFlow<List<BleDevice>> = _scanResults
    private val _scanError = MutableStateFlow(ScanError.NONE)
    val scanError: StateFlow<ScanError> = _scanError
    private val _leftSamples = MutableStateFlow(0)
    val leftSamples: StateFlow<Int> = _leftSamples
    private val _rightSamples = MutableStateFlow(0)
    val rightSamples: StateFlow<Int> = _rightSamples
    private val _deviceInfo = MutableStateFlow("")
    val deviceInfo: StateFlow<String> = _deviceInfo
    private val _connectedDevice = MutableStateFlow<ConnectedDevice?>(null)
    /** Read-only UI projection of the active GATT target; connection behavior remains internal. */
    val connectedDevice: StateFlow<ConnectedDevice?> = _connectedDevice
    private val _deviceTelemetry = MutableStateFlow<DeviceTelemetry?>(null)
    /** Parsed status fields exposed without requiring UI code to reinterpret BLE packets. */
    val deviceTelemetry: StateFlow<DeviceTelemetry?> = _deviceTelemetry
    private val _preprocessing =
        MutableStateFlow<Set<EegPreprocessStep>>(EegPreprocessStep.DEFAULT)
    val preprocessing: StateFlow<Set<EegPreprocessStep>> = _preprocessing
    private val _synchronizedDataEpoch = MutableStateFlow(0L)
    /** Changes whenever aligned acquisition resets or crosses a packet-counter gap. */
    val synchronizedDataEpoch: StateFlow<Long> = _synchronizedDataEpoch
    private val _streamDiagnostics = MutableStateFlow(StreamDiagnostics())
    val streamDiagnostics: StateFlow<StreamDiagnostics> = _streamDiagnostics

    fun setPreprocessing(@Suppress("UNUSED_PARAMETER") steps: Set<EegPreprocessStep>) {
        _preprocessing.value = EegPreprocessStep.DEFAULT
    }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val btAdapter = btManager.adapter
    private var gatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    private var reconnectAttempts = 0
    private var manualDisconnect = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private var packetCounter = 0
    private var lastLogTime = 0L
    private var lastAlignmentLogTime = 0L
    private var lastPairedDataTime = 0L
    private var lastPairedLeftSamples = 0L
    private var lastPairedRightSamples = 0L
    private var lastLeftLeadOff: Int? = null
    private var lastRightLeadOff: Int? = null
    private val leftAssembler = EegPacketAssembler()
    private val rightAssembler = EegPacketAssembler()
    private val stereoAligner = EegStereoPacketAligner()
    private val streamLiveness = BleStreamLiveness()
    private val dataPipelineLock = Any()
    private var streamRecoveryAttempts = 0
    private var streamOpenRequestedAt = 0L
    private var connectionPhaseStartedAt = 0L
    private var lastWatchdogLogAt = 0L
    private val scanTimeout = Runnable {
        if (_state.value == State.SCANNING) {
            stopScan()
            _state.value = State.IDLE
        }
    }
    private val streamWatchdog = object : Runnable {
        override fun run() {
            checkStreamLiveness()
            mainHandler.postDelayed(this, STREAM_WATCHDOG_INTERVAL_MS)
        }
    }

    init {
        streamLiveness.reset(android.os.SystemClock.elapsedRealtime())
        mainHandler.post(streamWatchdog)
    }

    // ─── 扫描 ───

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasScanPerm()) { _scanError.value = ScanError.NO_PERMISSION; return }
        if (btAdapter == null) { _scanError.value = ScanError.NO_ADAPTER; return }
        if (!btAdapter.isEnabled) { _scanError.value = ScanError.BT_OFF; return }
        manualDisconnect = true
        mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        currentDevice = null
        _connectedDevice.value = null
        _deviceTelemetry.value = null
        reconnectAttempts = 0
        streamRecoveryAttempts = 0
        streamOpenRequestedAt = 0L
        connectionPhaseStartedAt = 0L
        resetSynchronizedData()

        // Android 11 及以下的 BLE 扫描依赖系统位置服务。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                _scanError.value = ScanError.LOCATION_OFF
                return
            }
        }

        _scanError.value = ScanError.NONE
        _scanResults.value = emptyList()
        stopScan()
        _state.value = State.SCANNING

        val scanner = btAdapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = State.IDLE
            _scanError.value = ScanError.NO_SCANNER
            return
        }

        Log.d("NaoyunBLE", "开始BLE扫描... 蓝牙=${btAdapter.isEnabled} 定位=OK")

        val found = mutableListOf<BleDevice>()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = result.scanRecord?.deviceName ?: d.name ?: "(无名称)"
                if (!name.contains(BleProtocol.DEVICE_NAME_FILTER, ignoreCase = true)) return
                Log.d("NaoyunBLE", "扫描到设备: $name (${d.address}) rssi=${result.rssi}")
                val dev = BleDevice(name, d.address, d)
                if (found.none { it.address == dev.address }) {
                    found.add(dev)
                    _scanResults.value = found.toList()
                }
            }
            override fun onScanFailed(code: Int) {
                Log.e("NaoyunBLE", "扫描失败! errorCode=$code")
                _state.value = State.IDLE
                _scanError.value = ScanError.UNKNOWN
                _deviceInfo.value = "扫描失败 code=$code"
            }
        }
        scanner.startScan(null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        Log.d("NaoyunBLE", "startScan 已调用")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        scanCallback?.let { btAdapter.bluetoothLeScanner?.stopScan(it) }
        scanCallback = null
    }

    // ─── 连接（对齐 BleakClient + start_notify × 3 → write GET_INFO → write OPEN_DATA） ───

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        manualDisconnect = false
        currentDevice = device
        _connectedDevice.value = ConnectedDevice(device.name ?: device.address, device.address)
        _deviceTelemetry.value = null
        reconnectAttempts = 0
        streamRecoveryAttempts = 0
        connectInternal(device)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(device: BluetoothDevice) {
        _state.value = State.CONNECTING
        _deviceInfo.value = if (reconnectAttempts == 0) {
            "正在连接 ${device.name ?: device.address}…"
        } else {
            "连接重试 $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS…"
        }
        setupStep = 0
        streamOpenRequestedAt = 0L
        connectionPhaseStartedAt = android.os.SystemClock.elapsedRealtime()
        val previousGatt = gatt
        gatt = null
        previousGatt?.close()
        resetSynchronizedData()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnect = true
        stopScan()
        mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        currentDevice = null
        _connectedDevice.value = null
        _deviceTelemetry.value = null
        reconnectAttempts = 0
        streamRecoveryAttempts = 0
        streamOpenRequestedAt = 0L
        connectionPhaseStartedAt = 0L
        setupStep = 0
        resetSynchronizedData()
        _state.value = State.DISCONNECTED
    }

    /**
     * Starts another bounded recovery cycle for an active collection session.
     * The collector may request this repeatedly, but each cycle still uses the
     * normal connection and stream timeouts.
     */
    fun requestCurrentDeviceRecovery() {
        mainHandler.post {
            val device = currentDevice ?: return@post
            if (manualDisconnect || _state.value !in setOf(State.ERROR, State.DISCONNECTED)) {
                return@post
            }
            reconnectAttempts = 0
            streamRecoveryAttempts = 0
            _connectedDevice.value = ConnectedDevice(
                device.name ?: device.address,
                device.address
            )
            _state.value = State.RECOVERING
            _deviceInfo.value = "采集等待中，正在重新连接耳机…"
            connectInternal(device)
        }
    }

    /**
     * Starts a new aligned acquisition epoch. Clearing the packet assemblers
     * also prevents a half-received stereo packet from entering a new model
     * window.
     */
    fun resetSynchronizedData() {
        synchronized(dataPipelineLock) {
            leftAssembler.reset()
            rightAssembler.reset()
            stereoAligner.reset()
            lastAlignmentLogTime = 0L
            lastPairedDataTime = 0L
            lastPairedLeftSamples = 0L
            lastPairedRightSamples = 0L
            lastLeftLeadOff = null
            lastRightLeadOff = null
            streamLiveness.reset(android.os.SystemClock.elapsedRealtime())
            _streamDiagnostics.value = StreamDiagnostics(
                leftLeadOff = lastLeftLeadOff,
                rightLeadOff = lastRightLeadOff,
                recoveryAttempt = streamRecoveryAttempts
            )
            _leftSamples.value = 0
            _rightSamples.value = 0
            _synchronizedDataEpoch.value = _synchronizedDataEpoch.value + 1L
            Log.d("NaoyunBLE", "aligned acquisition reset epoch=${_synchronizedDataEpoch.value}")
        }
    }

    /** Invalidates an in-flight consumer window without resetting healthy BLE transport. */
    fun markConsumerDiscontinuity(reason: String) {
        synchronized(dataPipelineLock) {
            _synchronizedDataEpoch.value = _synchronizedDataEpoch.value + 1L
            Log.e(
                "NaoyunBLE",
                "consumer discontinuity: $reason epoch=${_synchronizedDataEpoch.value}"
            )
        }
    }

    /** Preserves history while preventing windows from spanning a packet gap. */
    private fun markPacketDiscontinuity(missingPackets: Int, reason: String) {
        _synchronizedDataEpoch.value = _synchronizedDataEpoch.value + 1L
        Log.w(
            "NaoyunBLE",
            "$reason；缺失 $missingPackets 个包；保留波形历史，开启新采集epoch=" +
                _synchronizedDataEpoch.value
        )
    }

    // ─── GATT 回调 ───

    private var setupStep = 0  // 0=等待, 1=CMD CCCD, 2=LEFT CCCD, 3=RIGHT CCCD, 4=GET_INFO, 5=OPEN_DATA

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== g) {
                g.close()
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("NaoyunBLE", "连接状态错误 status=$status state=$newState")
                g.close()
                if (gatt === g) gatt = null
                resetSynchronizedData()
                scheduleReconnectOrFail(status)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.post {
                    _state.value = State.INITIALIZING
                    _deviceInfo.value = "已连接，正在发现服务…"
                    connectionPhaseStartedAt = android.os.SystemClock.elapsedRealtime()
                    mainHandler.postDelayed({ setupStep = 1; g.discoverServices() }, 300)
                }
            } else {
                g.close()
                if (gatt === g) gatt = null
                resetSynchronizedData()
                if (!manualDisconnect) {
                    scheduleReconnectOrFail(status)
                } else {
                    mainHandler.post {
                        _deviceInfo.value = "设备已断开"
                        _state.value = State.DISCONNECTED
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (this@NaoyunBleManager.gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post { beginHardStreamRecovery("服务发现失败 GATT $status") }
                return
            }
            Log.d("NaoyunBLE", "服务已发现，开始逐步启用通知…")
            // 逐步启用：CMD → LEFT → RIGHT（串行，等待 onDescriptorWrite 回调）
            enableNotifyStep(g, BleProtocol.CMD_NOTIFY_UUID)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            if (this@NaoyunBleManager.gatt !== g) return
            Log.d("NaoyunBLE", "CCCD写入 step=$setupStep status=$status uuid=${desc.characteristic.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("NaoyunBLE", "CCCD写入失败! step=$setupStep")
                mainHandler.post { beginHardStreamRecovery("通知初始化失败 step=$setupStep GATT $status") }
                return
            }
            when (setupStep) {
                1 -> { setupStep = 2; enableNotifyStep(g, BleProtocol.DATA_LEFT_NOTIFY_UUID) }
                2 -> { setupStep = 3; enableNotifyStep(g, BleProtocol.DATA_RIGHT_NOTIFY_UUID) }
                3 -> {
                    // 三个 CCCD 全部就绪，发送命令
                    setupStep = 4
                    _deviceInfo.value = "已连接，正在初始化数据流…"
                    writeCmd(g, BleProtocol.GET_INFO_CMD)
                    mainHandler.postDelayed({
                        if (this@NaoyunBleManager.gatt !== g || _state.value != State.INITIALIZING) {
                            return@postDelayed
                        }
                        streamOpenRequestedAt = android.os.SystemClock.elapsedRealtime()
                        writeCmd(g, BleProtocol.OPEN_DATA_CMD)
                        _deviceInfo.value = "等待脑电数据流…"
                        setupStep = 5
                    }, 1200)
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (this@NaoyunBleManager.gatt !== g) return
            @Suppress("DEPRECATION")
            val data = ch.value ?: return
            handleCharacteristicData(ch, data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (this@NaoyunBleManager.gatt !== gatt) return
            handleCharacteristicData(characteristic, value)
        }

        private fun handleCharacteristicData(ch: BluetoothGattCharacteristic, data: ByteArray) {
            val uuid = ch.uuid.toString().lowercase()
            val now = System.currentTimeMillis()
            val callbackTimeUs = android.os.SystemClock.elapsedRealtimeNanos() / 1_000L
            if (now - lastLogTime > 2000) {
                val preview = data.take(8).joinToString(" ") {
                    "%02X".format(it.toInt() and 0xFF)
                }
                Log.d(
                    "NaoyunBLE",
                    "收到数据 step=$setupStep uuid=${uuid.take(8)} len=${data.size} head=$preview"
                )
                lastLogTime = now
            }

            if (uuidContains(uuid, BleProtocol.DATA_LEFT_NOTIFY_UUID)) {
                synchronized(dataPipelineLock) {
                    appendCallbackPackets(
                        leftAssembler.append(data).mapNotNull {
                            EegDataParser.parseEegData(it, "left")
                        },
                        callbackTimeUs
                    )
                }
            } else if (uuidContains(uuid, BleProtocol.DATA_RIGHT_NOTIFY_UUID)) {
                synchronized(dataPipelineLock) {
                    appendCallbackPackets(
                        rightAssembler.append(data).mapNotNull {
                            EegDataParser.parseEegData(it, "right")
                        },
                        callbackTimeUs
                    )
                }
            } else if (uuidContains(uuid, BleProtocol.CMD_NOTIFY_UUID)) {
                handleCmdResponse(data)
            }
        }
    }

    /** A batched callback is timestamped backwards so its newest packet ends at callback time. */
    private fun appendCallbackPackets(
        packets: List<EegDataParser.EegPacket>,
        callbackTimeUs: Long
    ) {
        if (packets.isEmpty()) return
        val packetEndTimesUs = EegCallbackTimestampPolicy.packetEndTimesUs(
            callbackTimeUs,
            packets.map { it.samples.size },
            SAMPLE_PERIOD_US
        )
        for (index in packets.indices) {
            val packet = packets[index]
            if (packet.earSide == "left") lastLeftLeadOff = packet.leadOff
            else lastRightLeadOff = packet.leadOff
            val nowMs = callbackTimeUs / 1_000L
            if (packet.earSide == "left") streamLiveness.onLeft(nowMs)
            else streamLiveness.onRight(nowMs)
            val appended = stereoAligner.appendAtTimeUs(packet, packetEndTimesUs[index])
            appended.discontinuity?.let {
                markPacketDiscontinuity(it.missingPackets, it.reason)
            }
        }
        val stats = stereoAligner.stats()
        _leftSamples.value = stats.leftSamplesReceived.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        _rightSamples.value = stats.rightSamplesReceived.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (
            stereoAligner.hasDualCoverage() &&
            stats.leftSamplesReceived > lastPairedLeftSamples &&
            stats.rightSamplesReceived > lastPairedRightSamples
        ) {
            val nowMs = callbackTimeUs / 1_000L
            lastPairedLeftSamples = stats.leftSamplesReceived
            lastPairedRightSamples = stats.rightSamplesReceived
            lastPairedDataTime = nowMs
            streamLiveness.onPair(nowMs)
            markReadyWhenDataArrives()
        }
        logAlignmentStatsIfDue(callbackTimeUs / 1_000L)
    }

    private fun logAlignmentStatsIfDue(now: Long) {
        if (now - lastAlignmentLogTime < ALIGNMENT_LOG_INTERVAL_MS) return
        lastAlignmentLogTime = now
        val stats = stereoAligner.stats()
        val lastPairAge = if (lastPairedDataTime == 0L) {
            "none"
        } else {
            "${now - lastPairedDataTime}ms"
        }
        Log.d(
            "NaoyunBLE",
            "双耳共同时间轴: recvPackets=${stats.leftPacketsReceived}/" +
                "${stats.rightPacketsReceived} counters=${stats.lastLeftCounter}/" +
                "${stats.lastRightCounter} samples=${stats.leftSamplesReceived}/" +
                "${stats.rightSamplesReceived} " +
                "droppedPackets=${stats.leftDroppedPackets}/${stats.rightDroppedPackets} " +
                "missingPackets=${stats.leftMissingPackets}/${stats.rightMissingPackets} " +
                "pendingSamples=${stats.pendingLeftSamples}/${stats.pendingRightSamples} " +
                "rateHz=${stats.leftRateHz?.let { "%.2f".format(it) } ?: "--"}/" +
                "${stats.rightRateHz?.let { "%.2f".format(it) } ?: "--"} " +
                "clockP95ms=${stats.leftClockResidualP95Ms?.let { "%.1f".format(it) } ?: "--"}/" +
                "${stats.rightClockResidualP95Ms?.let { "%.1f".format(it) } ?: "--"} " +
                "arrivalSkewMs=${stats.latestArrivalSkewMs?.let { "%.1f".format(it) } ?: "none"} " +
                "leadOff=${lastLeftLeadOff ?: "--"}/${lastRightLeadOff ?: "--"} " +
                "timelineRestarts=${stats.timelineRestarts} " +
                "lastPairAgo=$lastPairAge"
        )
    }

    fun latestAlignedWindow(points: Int): AlignedWindow? = synchronized(dataPipelineLock) {
        stereoAligner.latestAlignedSnapshot(points)?.toPublicWindow()
    }

    fun latestAlignedWindowAtMost(maxPoints: Int, minPoints: Int = 2): AlignedWindow? =
        synchronized(dataPipelineLock) {
            stereoAligner.latestAlignedSnapshotAtMost(maxPoints, minPoints)?.toPublicWindow()
        }

    fun alignedWindow(startTimeUs: Long, endTimeUs: Long, points: Int): AlignedWindow? =
        synchronized(dataPipelineLock) {
            stereoAligner.alignedSnapshot(startTimeUs, endTimeUs, points)?.toPublicWindow()
        }

    fun commonAlignedTimeUs(): Long? = synchronized(dataPipelineLock) {
        stereoAligner.commonLatestTimeUs()
    }

    fun rawSampleCounts(): Pair<Long, Long> = synchronized(dataPipelineLock) {
        stereoAligner.stats().let { it.leftSamplesReceived to it.rightSamplesReceived }
    }

    private fun EegStereoPacketAligner.AlignedSnapshot.toPublicWindow() = AlignedWindow(
        left, right, startTimeUs, endTimeUs, quality, leftRateHz, rightRateHz
    )

    // ─── 内部 ───

    /** 逐步启用通知（仅 setNotification + writeDescriptor，不等待回调） */
    @SuppressLint("MissingPermission")
    private fun enableNotifyStep(g: BluetoothGatt, uuid: String) {
        val ch = findCharacteristic(g, uuid) ?: run { Log.w("NaoyunBLE", "未找到特征: ${uuid.take(8)}..."); return }
        val ok = g.setCharacteristicNotification(ch, true)
        Log.d("NaoyunBLE", "setNotification ${uuid.take(8)}... = $ok, 写入CCCD...")
        val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (cccd != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        } else {
            Log.w("NaoyunBLE", "未找到CCCD: ${uuid.take(8)}...")
        }
    }

    /** @deprecated 使用 enableNotifyStep 替代 */
    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, uuid: String) {
        enableNotifyStep(g, uuid)
    }

    /** 在 CMD_SERVICE 和 DATA_SERVICE 中查找特征 */
    private fun findCharacteristic(g: BluetoothGatt, uuidStr: String): BluetoothGattCharacteristic? {
        val target = UUID.fromString(uuidStr)
        // 先在命令服务找
        g.getService(UUID.fromString(BleProtocol.CMD_SERVICE_UUID))
            ?.getCharacteristic(target)?.let { return it }
        // 再在数据服务找
        g.getService(UUID.fromString(BleProtocol.DATA_SERVICE_UUID))
            ?.getCharacteristic(target)?.let { return it }
        // 遍历所有服务找
        for (svc in g.services) {
            svc.getCharacteristic(target)?.let { return it }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun writeCmd(g: BluetoothGatt, cmd: ByteArray) {
        val svc = g.getService(UUID.fromString(BleProtocol.CMD_SERVICE_UUID)) ?: return
        val ch = svc.getCharacteristic(UUID.fromString(BleProtocol.CMD_WRITE_UUID)) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, cmd, WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = cmd
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    /** 对齐 Python _handle_cmd */
    private fun handleCmdResponse(data: ByteArray) {
        if (data.size >= 8 && data[0] == 0xAA.toByte() && data[1] == 0x55.toByte()
            && data[2] == 0x01.toByte() && data[3] == 0x01.toByte()) {
            _deviceInfo.value = "数据流命令已确认，等待数据…"
            return
        }
        // 解析设备信息
        val telemetry = parseDeviceInfo(data)
        if (telemetry != null) {
            _deviceTelemetry.value = telemetry
            _deviceInfo.value = telemetry.asDisplayText()
        }
    }

    private fun parseDeviceInfo(data: ByteArray): DeviceTelemetry? {
        if (data.size < 16 || data[0] != 0xAA.toByte() || data[1] != 0x55.toByte()) return null
        val cmd = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (cmd != 0x00E0) return null
        val ear = when (data[5].toInt() and 0xFF) { 1->"左耳" 2->"右耳" 3->"双耳左" 4->"双耳右" else->"?" }
        return DeviceTelemetry(
            leftBatteryPercent = data[6].toInt() and 0xFF,
            rightBatteryPercent = data[7].toInt() and 0xFF,
            leftWorn = (data[8].toInt() and 0xFF) == 1,
            rightWorn = (data[9].toInt() and 0xFF) == 1,
            earMode = ear
        )
    }

    private fun DeviceTelemetry.asDisplayText(): String =
        "电量 L:$leftBatteryPercent% R:$rightBatteryPercent% | " +
            "佩戴 L:${if (leftWorn) "✓" else "✗"} R:${if (rightWorn) "✓" else "✗"} | $earMode"

    private fun uuidContains(uuid: String, ref: String) = uuid.contains(ref.lowercase())

    private fun checkStreamLiveness() {
        if (manualDisconnect || gatt == null) return
        val currentState = _state.value
        if (currentState !in setOf(
                State.INITIALIZING,
                State.CONNECTING,
                State.READY,
                State.STREAM_STALLED,
                State.RECOVERING
            )
        ) return

        val now = android.os.SystemClock.elapsedRealtime()
        val snapshot = streamLiveness.snapshot(now)
        val alignmentStats = synchronized(dataPipelineLock) { stereoAligner.stats() }
        _streamDiagnostics.value = StreamDiagnostics(
            leftAgeMs = snapshot.leftAgeMs,
            rightAgeMs = snapshot.rightAgeMs,
            pairAgeMs = snapshot.pairAgeMs,
            leftLeadOff = lastLeftLeadOff,
            rightLeadOff = lastRightLeadOff,
            leftRateHz = alignmentStats.leftRateHz,
            rightRateHz = alignmentStats.rightRateHz,
            leftClockResidualMs = alignmentStats.leftClockResidualP95Ms,
            rightClockResidualMs = alignmentStats.rightClockResidualP95Ms,
            stalledSide = snapshot.stalledSide,
            recoveryAttempt = streamRecoveryAttempts,
            delayed = snapshot.health == BleStreamLiveness.Health.WARNING
        )
        if (now - lastWatchdogLogAt >= STREAM_STATUS_LOG_INTERVAL_MS) {
            lastWatchdogLogAt = now
            Log.d(
                "NaoyunBLE",
                "stream health=${snapshot.health} leftAge=${snapshot.leftAgeMs}ms " +
                    "rightAge=${snapshot.rightAgeMs}ms pairAge=${snapshot.pairAgeMs}ms " +
                    "state=$currentState recovery=$streamRecoveryAttempts"
            )
        }

        if (
            snapshot.health == BleStreamLiveness.Health.STALLED &&
            bothEarsFresh(snapshot) &&
            currentState in setOf(
                State.INITIALIZING,
                State.READY,
                State.STREAM_STALLED,
                State.RECOVERING
            )
        ) {
            beginLocalPairRecovery(snapshot)
            return
        }
        if (
            currentState in setOf(State.READY, State.STREAM_STALLED) &&
            snapshot.health == BleStreamLiveness.Health.STALLED
        ) {
            beginSoftStreamRecovery(snapshot)
            return
        }
        if (
            currentState in setOf(State.CONNECTING, State.INITIALIZING) &&
            streamOpenRequestedAt == 0L &&
            connectionPhaseStartedAt > 0L &&
            now - connectionPhaseStartedAt >= CONNECTION_PHASE_TIMEOUT_MS
        ) {
            beginHardStreamRecovery("连接或通知初始化超时")
            return
        }
        if (
            currentState in setOf(State.INITIALIZING, State.STREAM_STALLED, State.RECOVERING) &&
            streamOpenRequestedAt > 0L &&
            now - streamOpenRequestedAt >= HARD_RECOVERY_AFTER_MS
        ) {
            beginHardStreamRecovery("OPEN_DATA 后仍无双耳数据")
        }
    }

    private fun bothEarsFresh(snapshot: BleStreamLiveness.Snapshot): Boolean =
        snapshot.leftAgeMs != null &&
            snapshot.rightAgeMs != null &&
            snapshot.leftAgeMs < BleStreamLiveness.STALLED_AFTER_MS &&
            snapshot.rightAgeMs < BleStreamLiveness.STALLED_AFTER_MS

    private fun beginLocalPairRecovery(snapshot: BleStreamLiveness.Snapshot) {
        val now = android.os.SystemClock.elapsedRealtime()
        _state.value = State.STREAM_STALLED
        streamOpenRequestedAt = 0L
        markConsumerDiscontinuity("双耳持续到达但共同时间窗停滞，重建时钟模型")
        synchronized(dataPipelineLock) {
            stereoAligner.restartTimeline()
        }
        streamLiveness.deferPairDeadline(now)
        _deviceInfo.value = "双耳数据持续到达，正在本地重新对齐…"
        Log.w(
            "NaoyunBLE",
            "local timeline recovery: leftAge=${snapshot.leftAgeMs}ms " +
                "rightAge=${snapshot.rightAgeMs}ms; clear interpolation history, keep GATT"
        )
    }

    private fun beginSoftStreamRecovery(snapshot: BleStreamLiveness.Snapshot) {
        if (_state.value !in setOf(State.READY, State.STREAM_STALLED)) return
        streamRecoveryAttempts = 1
        _state.value = State.STREAM_STALLED
        _deviceInfo.value = "${snapshot.stalledSide ?: "双耳"}数据中断，正在恢复数据流…"
        markConsumerDiscontinuity("${snapshot.stalledSide ?: "双耳"}超过阈值无配对数据")
        val activeGatt = gatt
        if (activeGatt == null) {
            beginHardStreamRecovery("GATT 已丢失")
            return
        }
        _state.value = State.RECOVERING
        streamOpenRequestedAt = android.os.SystemClock.elapsedRealtime()
        Log.w("NaoyunBLE", "soft stream recovery: resend OPEN_DATA")
        writeCmd(activeGatt, BleProtocol.OPEN_DATA_CMD)
    }

    @SuppressLint("MissingPermission")
    private fun beginHardStreamRecovery(reason: String) {
        if (manualDisconnect) return
        val device = currentDevice
        if (device == null || streamRecoveryAttempts >= MAX_STREAM_RECOVERY_ATTEMPTS) {
            gatt?.close()
            gatt = null
            _state.value = State.ERROR
            _deviceInfo.value = "数据流恢复失败，请检查耳机佩戴、距离和电量"
            Log.e("NaoyunBLE", "stream recovery exhausted: $reason")
            return
        }
        streamRecoveryAttempts++
        streamOpenRequestedAt = 0L
        connectionPhaseStartedAt = 0L
        _state.value = State.RECOVERING
        _deviceInfo.value = "数据流恢复 $streamRecoveryAttempts/$MAX_STREAM_RECOVERY_ATTEMPTS…"
        Log.w("NaoyunBLE", "hard stream recovery attempt=$streamRecoveryAttempts reason=$reason")
        val previous = gatt
        gatt = null
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
        resetSynchronizedData()
        mainHandler.postDelayed(
            { if (!manualDisconnect && currentDevice == device) connectInternal(device) },
            HARD_RECONNECT_DELAY_MS
        )
    }

    private fun markReadyWhenDataArrives() {
        if (_state.value != State.READY) {
            reconnectAttempts = 0
            streamRecoveryAttempts = 0
            streamOpenRequestedAt = 0L
            connectionPhaseStartedAt = 0L
            _state.value = State.READY
            _deviceInfo.value = "脑电数据流已就绪 ✓"
        }
    }

    fun hasScanPerm(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    fun hasConnectPerm(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasPermissions(): Boolean = hasScanPerm() && hasConnectPerm()

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val MAX_RECONNECT_ATTEMPTS = 2
        private const val RECONNECT_DELAY_MS = 800L
        private const val ALIGNMENT_LOG_INTERVAL_MS = 2_000L
        private const val STREAM_WATCHDOG_INTERVAL_MS = 250L
        private const val STREAM_STATUS_LOG_INTERVAL_MS = 1_000L
        private const val HARD_RECOVERY_AFTER_MS = 3_000L
        private const val CONNECTION_PHASE_TIMEOUT_MS = 10_000L
        private const val HARD_RECONNECT_DELAY_MS = 300L
        private const val MAX_STREAM_RECOVERY_ATTEMPTS = 3
        private const val SAMPLE_PERIOD_US = 2_000L
        private val RECONNECT_TOKEN = Any()
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnectOrFail(status: Int) {
        val device = currentDevice
        if (!manualDisconnect && device != null && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            mainHandler.postAtTime(
                { if (!manualDisconnect && currentDevice == device) connectInternal(device) },
                RECONNECT_TOKEN,
                android.os.SystemClock.uptimeMillis() + RECONNECT_DELAY_MS
            )
        } else {
            mainHandler.post {
                _connectedDevice.value = null
                _deviceTelemetry.value = null
                _deviceInfo.value = "连接失败（GATT $status）"
                _state.value = State.ERROR
            }
        }
    }
}
