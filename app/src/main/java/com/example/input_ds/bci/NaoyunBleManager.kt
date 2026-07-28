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

    enum class State { IDLE, SCANNING, CONNECTING, INITIALIZING, READY, DISCONNECTED, ERROR }
    enum class ScanError { NONE, BT_OFF, NO_ADAPTER, NO_PERMISSION, LOCATION_OFF, NO_SCANNER, UNKNOWN }
    data class BleDevice(val name: String, val address: String, val device: BluetoothDevice)

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

    val buffer = EegRingBuffer(capacitySeconds = 30)

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
    private val leftAssembler = EegPacketAssembler()
    private val rightAssembler = EegPacketAssembler()
    private val stereoAligner = EegStereoPacketAligner()
    private val scanTimeout = Runnable {
        if (_state.value == State.SCANNING) {
            stopScan()
            _state.value = State.IDLE
        }
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
        reconnectAttempts = 0

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
        reconnectAttempts = 0
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
        buffer.reset()
        leftAssembler.reset()
        rightAssembler.reset()
        stereoAligner.reset()
        _leftSamples.value = 0
        _rightSamples.value = 0
        gatt?.close()
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
        reconnectAttempts = 0
        setupStep = 0
        buffer.reset()
        leftAssembler.reset()
        rightAssembler.reset()
        stereoAligner.reset()
        _leftSamples.value = 0
        _rightSamples.value = 0
        _state.value = State.DISCONNECTED
    }

    /**
     * Starts a new aligned acquisition epoch. Clearing the packet assemblers
     * also prevents a half-received stereo packet from entering a new model
     * window.
     */
    fun resetSynchronizedData() {
        buffer.reset()
        leftAssembler.reset()
        rightAssembler.reset()
        stereoAligner.reset()
        _leftSamples.value = 0
        _rightSamples.value = 0
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
                scheduleReconnectOrFail(status)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mainHandler.post {
                    _state.value = State.INITIALIZING
                    _deviceInfo.value = "已连接，正在发现服务…"
                    mainHandler.postDelayed({ setupStep = 1; g.discoverServices() }, 300)
                }
            } else {
                g.close()
                if (gatt === g) gatt = null
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                mainHandler.post {
                    _deviceInfo.value = "服务发现失败（GATT $status）"
                    _state.value = State.ERROR
                }
                return
            }
            Log.d("NaoyunBLE", "服务已发现，开始逐步启用通知…")
            // 逐步启用：CMD → LEFT → RIGHT（串行，等待 onDescriptorWrite 回调）
            enableNotifyStep(g, BleProtocol.CMD_NOTIFY_UUID)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            Log.d("NaoyunBLE", "CCCD写入 step=$setupStep status=$status uuid=${desc.characteristic.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("NaoyunBLE", "CCCD写入失败! step=$setupStep")
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
                        writeCmd(g, BleProtocol.OPEN_DATA_CMD)
                        _deviceInfo.value = "等待脑电数据流…"
                        setupStep = 5
                    }, 1200)
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val data = ch.value ?: return
            handleCharacteristicData(ch, data)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicData(characteristic, value)
        }

        private fun handleCharacteristicData(ch: BluetoothGattCharacteristic, data: ByteArray) {
            val uuid = ch.uuid.toString().lowercase()
            val now = System.currentTimeMillis()
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
                for (packetData in leftAssembler.append(data)) {
                    val pkt = EegDataParser.parseEegData(packetData, "left") ?: continue
                    appendAlignedPacket(pkt)
                }
            } else if (uuidContains(uuid, BleProtocol.DATA_RIGHT_NOTIFY_UUID)) {
                for (packetData in rightAssembler.append(data)) {
                    val pkt = EegDataParser.parseEegData(packetData, "right") ?: continue
                    appendAlignedPacket(pkt)
                }
            } else if (uuidContains(uuid, BleProtocol.CMD_NOTIFY_UUID)) {
                handleCmdResponse(data)
            }
        }
    }

    private fun appendAlignedPacket(packet: EegDataParser.EegPacket) {
        val stereo = stereoAligner.append(packet) ?: return
        buffer.pushStereo(stereo.left, stereo.right)
        val count = buffer.synchronizedCount()
        _leftSamples.value = count
        _rightSamples.value = count
        markReadyWhenDataArrives()
    }

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
        val info = parseDeviceInfo(data)
        if (info != null) _deviceInfo.value = info
    }

    private fun parseDeviceInfo(data: ByteArray): String? {
        if (data.size < 16 || data[0] != 0xAA.toByte() || data[1] != 0x55.toByte()) return null
        val cmd = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (cmd != 0x00E0) return null
        val ear = when (data[5].toInt() and 0xFF) { 1->"左耳" 2->"右耳" 3->"双耳左" 4->"双耳右" else->"?" }
        val wearL = if ((data[8].toInt() and 0xFF) == 1) "✓" else "✗"
        val wearR = if ((data[9].toInt() and 0xFF) == 1) "✓" else "✗"
        return "电量 L:${data[6].toInt() and 0xFF}% R:${data[7].toInt() and 0xFF}% | 佩戴 L:$wearL R:$wearR | $ear"
    }

    private fun uuidContains(uuid: String, ref: String) = uuid.contains(ref.lowercase())

    private fun markReadyWhenDataArrives() {
        if (_state.value != State.READY) {
            reconnectAttempts = 0
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
                _deviceInfo.value = "连接失败（GATT $status）"
                _state.value = State.ERROR
            }
        }
    }
}
