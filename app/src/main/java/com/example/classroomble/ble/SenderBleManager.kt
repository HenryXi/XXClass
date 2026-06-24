package com.example.classroomble.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.classroomble.model.ClassroomMessage
import com.example.classroomble.model.JsonCodec
import java.util.UUID

class SenderBleManager(
    private val context: Context,
    private val listener: Listener,
) {
    data class BindCandidate(
        val device: BluetoothDevice,
        val address: String,
        val displayName: String,
    )

    interface Listener {
        fun onStatus(status: String)
        fun onBindDevices(devices: List<BindCandidate>)
        fun onSendResult(success: Boolean, message: String)
    }

    private val prefs = context.getSharedPreferences(BleConstants.PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    private var bindScanResults = linkedMapOf<String, BindCandidate>()
    private var isBindScanning = false
    private var connectScanResults = linkedMapOf<String, BindCandidate>()

    private var autoScanCallback: ScanCallback? = null
    private var bindScanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingMsgId: String? = null
    private var pendingPayload: ByteArray? = null
    private var retriesLeft = 0
    private var isConnecting = false
    private var currentAttempt = 0
    private var servicesDiscoveryStarted = false
    private var directFallbackAddress: String? = null
    private var directFallbackName: String? = null
    private val mtuFallbackRunnable = Runnable {
        val g = gatt ?: return@Runnable
        maybeDiscoverServices(g, "mtu-timeout")
    }
    private val directConnectFallbackRunnable = Runnable {
        val address = directFallbackAddress ?: return@Runnable
        val name = directFallbackName.orEmpty()
        if (pendingPayload == null) return@Runnable
        if (autoScanCallback != null) return@Runnable
        if (writeCharacteristic != null || servicesDiscoveryStarted) return@Runnable
        Log.w(TAG, "direct connect fallback -> scan addr=$address")
        scanAndConnect(address, name)
    }
    private val idleDisconnectRunnable = Runnable {
        if (pendingPayload != null || isConnecting || autoScanCallback != null) return@Runnable
        Log.d(TAG, "idle timeout disconnect")
        disconnectGatt()
        listener.onStatus("连接空闲已断开")
    }

    fun hasBoundDevice(): Boolean = getBoundAddress().isNotBlank()

    fun clearBoundDevice() {
        prefs.edit()
            .remove(BleConstants.KEY_BOUND_DEVICE)
            .remove(BleConstants.KEY_BOUND_DEVICE_NAME)
            .apply()
    }

    fun startBindScan() {
        if (!checkPermissions()) {
            listener.onStatus("缺少蓝牙权限")
            return
        }
        if (isBindScanning) return

        val scanner = scanner ?: run {
            listener.onStatus("蓝牙不可用")
            return
        }

        bindScanResults.clear()
        isBindScanning = true
        listener.onStatus("扫描中...")

        bindScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val key = device.address ?: return
                if (!isTargetService(result)) return

                val resolvedName = resolveDisplayName(device, result)
                val old = bindScanResults[key]
                val merged = if (old == null) {
                    BindCandidate(device, key, resolvedName)
                } else {
                    val bestName = when {
                        old.displayName != UNKNOWN_NAME -> old.displayName
                        resolvedName != UNKNOWN_NAME -> resolvedName
                        else -> old.displayName
                    }
                    BindCandidate(device, key, bestName)
                }
                bindScanResults[key] = merged
                listener.onBindDevices(bindScanResults.values.toList())
            }
        }

        // Some OEM stacks are flaky when scanning with only Service UUID filters.
        // Use broad scan here and then do service-UUID filtering in callback.
        scanner.startScan(emptyList(), defaultScanSettings(), bindScanCallback)
        handler.postDelayed({ stopBindScan() }, BIND_SCAN_TIMEOUT_MS)
    }

    fun stopBindScan() {
        if (!isBindScanning) return
        isBindScanning = false
        bindScanCallback?.let { scanner?.stopScan(it) }
        bindScanCallback = null
        listener.onStatus("扫描结束")
    }

    fun bindToDevice(candidate: BindCandidate) {
        stopBindScan()
        saveBoundDevice(candidate.address, candidate.displayName)
        listener.onStatus("已绑定: ${candidate.displayName}")
    }

    fun sendText(text: String) {
        if (!checkPermissions()) {
            listener.onSendResult(false, "缺少蓝牙权限")
            return
        }
        if (adapter?.isEnabled != true) {
            listener.onSendResult(false, "请先开启蓝牙")
            return
        }

        val msgId = UUID.randomUUID().toString()
        val payload = JsonCodec.encodeMessage(
            ClassroomMessage(
                msgId = msgId,
                ts = System.currentTimeMillis(),
                text = text,
            ),
        )

        pendingMsgId = msgId
        pendingPayload = payload
        retriesLeft = MAX_RETRY
        currentAttempt = 0
        handler.removeCallbacks(idleDisconnectRunnable)
        listener.onStatus("正在连接学生平板...")
        val bound = getBoundAddress()
        Log.i(TAG, "sendText start msgId=${msgId.takeLast(6)} textLen=${text.length} boundAddr=$bound boundName=${getBoundName()}")
        if (trySendOnExistingConnection(payload)) {
            return
        }
        if (bound.isBlank()) {
            listener.onStatus("首次发送，自动发现并绑定中...")
            scanForAutoBindAndConnect(triggeredBySend = true)
            return
        }
        connectWithFallback(bound, getBoundName(), preferDirect = true)
    }

    fun warmUpSenderConnection() {
        if (!checkPermissions()) {
            listener.onStatus("缺少蓝牙权限，暂不预连接")
            return
        }
        if (adapter?.isEnabled != true) {
            listener.onStatus("蓝牙未开启，暂不预连接")
            return
        }
        if (pendingPayload != null || isConnecting || autoScanCallback != null) return
        if (gatt != null && writeCharacteristic != null && ackCharacteristic != null) return

        val bound = getBoundAddress()
        if (bound.isBlank()) {
            listener.onStatus("未绑定，后台自动发现设备...")
            scanForAutoBindAndConnect(triggeredBySend = false)
            return
        }
        listener.onStatus("后台预连接中...")
        connectWithFallback(bound, getBoundName(), preferDirect = true)
    }

    private fun trySendOnExistingConnection(payload: ByteArray): Boolean {
        val g = gatt ?: return false
        if (writeCharacteristic == null || ackCharacteristic == null) return false
        listener.onStatus("复用连接发送中...")
        Log.d(TAG, "reuse active gatt for send payloadBytes=${payload.size}")
        return sendPayload(g, payload)
    }

    private fun connectWithFallback(targetAddress: String, targetName: String, preferDirect: Boolean) {
        if (preferDirect && tryDirectConnect(targetAddress, targetName)) {
            return
        }
        scanAndConnect(targetAddress, targetName)
    }

    private fun tryDirectConnect(targetAddress: String, targetName: String): Boolean {
        val device = try {
            adapter?.getRemoteDevice(targetAddress)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) return false

        directFallbackAddress = targetAddress
        directFallbackName = targetName
        handler.removeCallbacks(directConnectFallbackRunnable)
        listener.onStatus("已绑定设备，快速连接中...")
        Log.d(TAG, "tryDirectConnect addr=$targetAddress")
        disconnectGatt()
        connectGattWithDelay(device)
        handler.postDelayed(directConnectFallbackRunnable, DIRECT_CONNECT_FALLBACK_MS)
        return true
    }

    private fun scanForAutoBindAndConnect(triggeredBySend: Boolean) {
        handler.removeCallbacks(directConnectFallbackRunnable)
        directFallbackAddress = null
        directFallbackName = null
        disconnectGatt()

        val scanner = scanner ?: run {
            if (triggeredBySend) {
                failCurrentSend("蓝牙扫描不可用")
            } else {
                listener.onStatus("蓝牙扫描不可用")
            }
            return
        }

        autoScanCallback?.let { scanner.stopScan(it) }
        connectScanResults.clear()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!isTargetService(result)) return

                val address = device.address ?: return
                val displayName = resolveDisplayName(device, result)
                val candidate = BindCandidate(device, address, displayName)
                connectScanResults[address] = candidate

                scanner.stopScan(this)
                autoScanCallback = null
                saveBoundDevice(candidate.address, candidate.displayName)
                listener.onStatus("已自动绑定: ${candidate.displayName}，正在连接")
                connectGattWithDelay(candidate.device)
            }

            override fun onScanFailed(errorCode: Int) {
                autoScanCallback = null
                Log.e(TAG, "auto-bind scan failed errorCode=$errorCode")
                if (triggeredBySend) {
                    failCurrentSend("扫描失败: $errorCode")
                } else {
                    listener.onStatus("后台扫描失败: $errorCode")
                }
            }
        }

        autoScanCallback = callback
        scanner.startScan(emptyList(), defaultScanSettings(), callback)
        handler.postDelayed({
            if (autoScanCallback === callback) {
                scanner.stopScan(callback)
                autoScanCallback = null
                val fallback = connectScanResults.values.firstOrNull()
                if (fallback != null) {
                    saveBoundDevice(fallback.address, fallback.displayName)
                    listener.onStatus("已自动绑定: ${fallback.displayName}，正在连接")
                    connectGattWithDelay(fallback.device)
                    return@postDelayed
                }
                if (triggeredBySend) {
                    failCurrentSend("首次自动连接失败，未发现平板")
                } else {
                    listener.onStatus("后台未发现可连接平板")
                }
            }
        }, CONNECT_SCAN_TIMEOUT_MS)
    }

    private fun scanAndConnect(targetAddress: String, targetName: String) {
        handler.removeCallbacks(directConnectFallbackRunnable)
        directFallbackAddress = null
        directFallbackName = null
        disconnectGatt()
        val scanner = scanner ?: run {
            listener.onSendResult(false, "蓝牙扫描不可用")
            return
        }

        autoScanCallback?.let { scanner.stopScan(it) }

        connectScanResults.clear()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (!isTargetService(result)) return

                val resolvedName = resolveDisplayName(device, result)
                val candidate = BindCandidate(device, device.address ?: "", resolvedName)
                if (candidate.address.isNotBlank()) {
                    connectScanResults[candidate.address] = candidate
                }

                val addressMatched = device.address.equals(targetAddress, ignoreCase = true)
                val nameMatched = targetName.isNotBlank() && resolvedName == targetName
                if (addressMatched || nameMatched) {
                    scanner.stopScan(this)
                    autoScanCallback = null
                    Log.d(TAG, "scan match address=$addressMatched name=$nameMatched addr=${candidate.address} name=${candidate.displayName}")
                    listener.onStatus("已发现平板，正在连接")
                    connectGattWithDelay(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                autoScanCallback = null
                Log.e(TAG, "scan failed errorCode=$errorCode")
                listener.onSendResult(false, "扫描失败: $errorCode")
            }
        }
        autoScanCallback = callback

        // Use broad scan and perform service/match filtering in callback.
        // This avoids failures on devices that rotate BLE addresses.
        scanner.startScan(emptyList(), defaultScanSettings(), callback)

        handler.postDelayed({
            if (autoScanCallback === callback) {
                scanner.stopScan(callback)
                autoScanCallback = null
                val fallback = pickFallbackCandidate(targetAddress, targetName)
                if (fallback != null) {
                    Log.w(TAG, "connect scan fallback to addr=${fallback.address} name=${fallback.displayName}")
                    listener.onStatus("已发现候选平板，正在连接")
                    connectGattWithDelay(fallback.device)
                } else {
                    onAttemptFailed("连接超时，未发现平板")
                }
            }
        }, CONNECT_SCAN_TIMEOUT_MS)
    }

    private fun connectGattWithDelay(device: BluetoothDevice) {
        if (isConnecting) return
        isConnecting = true
        // Keep scan/connect serialized to reduce OEM stack race that often ends with 133.
        autoScanCallback?.let { scanner?.stopScan(it) }
        autoScanCallback = null
        handler.postDelayed({
            connectGatt(device)
        }, PRE_CONNECT_DELAY_MS)
    }

    private fun connectGatt(device: BluetoothDevice) {
        Log.d(TAG, "connectGatt addr=${device.address} name=${device.name}")
        servicesDiscoveryStarted = false
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                val prioritySet = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                Log.d(TAG, "requestConnectionPriority(HIGH) result=$prioritySet")
                listener.onStatus("连接成功，协商链路中")
                val mtuRequested = gatt.requestMtu(DESIRED_MTU)
                Log.d(TAG, "requestMtu($DESIRED_MTU) result=$mtuRequested")
                if (mtuRequested) {
                    handler.postDelayed(mtuFallbackRunnable, MTU_FALLBACK_TIMEOUT_MS)
                } else {
                    maybeDiscoverServices(gatt, "mtu-not-requested")
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                handler.removeCallbacks(mtuFallbackRunnable)
                if (pendingPayload != null && pendingMsgId != null) {
                    // unexpected disconnect before ack.
                    val reason = if (status == GATT_ERROR_133) "连接异常(133)" else "连接断开($status)"
                    Log.w(TAG, "gatt disconnected before ack status=$status")
                    onAttemptFailed(reason)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged status=$status mtu=$mtu")
            handler.removeCallbacks(mtuFallbackRunnable)
            maybeDiscoverServices(gatt, "mtu-changed:$status/$mtu")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val serviceCount = gatt.services?.size ?: -1
            Log.d(TAG, "onServicesDiscovered status=$status serviceCount=$serviceCount")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "discover services failed status=$status")
                onAttemptFailed("服务发现失败($status)")
                return
            }
            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                Log.w(TAG, "service not found")
                onAttemptFailed("未发现接收服务")
                return
            }

            writeCharacteristic = service.getCharacteristic(BleConstants.WRITE_UUID)
            ackCharacteristic = service.getCharacteristic(BleConstants.ACK_UUID)
            if (writeCharacteristic == null || ackCharacteristic == null) {
                Log.w(TAG, "characteristics incomplete")
                onAttemptFailed("服务特征不完整")
                return
            }

            val descriptor = ackCharacteristic?.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor == null) {
                Log.w(TAG, "cccd missing")
                onAttemptFailed("通知描述符缺失")
                return
            }

            val notifyEnabled = gatt.setCharacteristicNotification(ackCharacteristic, true)
            Log.d(TAG, "setCharacteristicNotification result=$notifyEnabled")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val descriptorWriteStarted = gatt.writeDescriptor(descriptor)
            Log.d(TAG, "writeDescriptor started=$descriptorWriteStarted")
            if (!descriptorWriteStarted) {
                onAttemptFailed("启用回执通知失败(start)")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != BleConstants.CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "descriptor write failed status=$status")
                onAttemptFailed("启用回执通知失败($status)")
                return
            }
            val payload = pendingPayload ?: return
            sendPayload(gatt, payload)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != BleConstants.WRITE_UUID) return
            Log.d(TAG, "onCharacteristicWrite status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "write characteristic failed status=$status")
                onAttemptFailed("发送失败($status)")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != BleConstants.ACK_UUID) return
            val ack = JsonCodec.decodeAck(characteristic.value) ?: return
            Log.d(TAG, "onCharacteristicChanged legacy ackId=${ack.msgId.takeLast(6)} ok=${ack.ok}")
            handleAck(ack.msgId, ack.ok)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != BleConstants.ACK_UUID) return
            val ack = JsonCodec.decodeAck(value) ?: return
            Log.d(TAG, "onCharacteristicChanged v33 ackId=${ack.msgId.takeLast(6)} ok=${ack.ok}")
            handleAck(ack.msgId, ack.ok)
        }
    }

    private val ackTimeoutRunnable = Runnable {
        onAttemptFailed("等待回执超时")
    }

    private fun onAttemptFailed(reason: String) {
        disconnectGatt()
        if (retriesLeft > 0 && pendingPayload != null) {
            retriesLeft -= 1
            currentAttempt += 1
            Log.w(TAG, "attempt failed reason=$reason retriesLeft=$retriesLeft")
            listener.onStatus("$reason，正在重试(${MAX_RETRY - retriesLeft}/$MAX_RETRY)")
            val bound = getBoundAddress()
            if (bound.isBlank()) {
                listener.onSendResult(false, "设备丢失绑定")
                return
            }
            val delay = computeRetryDelay(reason, currentAttempt)
            handler.postDelayed(
                {
                    connectWithFallback(bound, getBoundName(), preferDirect = false)
                },
                delay,
            )
            return
        }

        pendingPayload = null
        pendingMsgId = null
        Log.e(TAG, "send failed final reason=$reason")
        listener.onSendResult(false, "$reason，发送失败")
    }

    private fun failCurrentSend(message: String) {
        pendingPayload = null
        pendingMsgId = null
        handler.removeCallbacks(ackTimeoutRunnable)
        disconnectGatt()
        listener.onSendResult(false, message)
    }

    fun release() {
        stopBindScan()
        autoScanCallback?.let { scanner?.stopScan(it) }
        autoScanCallback = null
        disconnectGatt()
        handler.removeCallbacksAndMessages(null)
    }

    private fun disconnectGatt() {
        handler.removeCallbacks(idleDisconnectRunnable)
        handler.removeCallbacks(ackTimeoutRunnable)
        handler.removeCallbacks(mtuFallbackRunnable)
        handler.removeCallbacks(directConnectFallbackRunnable)
        isConnecting = false
        servicesDiscoveryStarted = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Throwable) {
            // ignore
        }
        gatt = null
        ackCharacteristic = null
        writeCharacteristic = null
    }

    private fun defaultScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    private fun checkPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun getBoundAddress(): String = prefs.getString(BleConstants.KEY_BOUND_DEVICE, "") ?: ""

    private fun getBoundName(): String = prefs.getString(BleConstants.KEY_BOUND_DEVICE_NAME, "") ?: ""

    private fun saveBoundDevice(address: String?, displayName: String?) {
        if (address.isNullOrBlank()) return
        prefs.edit()
            .putString(BleConstants.KEY_BOUND_DEVICE, address)
            .putString(BleConstants.KEY_BOUND_DEVICE_NAME, displayName.orEmpty())
            .apply()
    }

    private fun isTargetService(result: ScanResult): Boolean {
        val uuids = result.scanRecord?.serviceUuids ?: return false
        return uuids.any { it.uuid == BleConstants.SERVICE_UUID }
    }

    private fun resolveDisplayName(device: BluetoothDevice, result: ScanResult): String {
        val fromRecord = result.scanRecord?.deviceName?.trim().orEmpty()
        if (fromRecord.isNotEmpty()) return fromRecord
        val fromDevice = device.name?.trim().orEmpty()
        if (fromDevice.isNotEmpty()) return fromDevice
        return UNKNOWN_NAME
    }

    private fun handleAck(msgId: String, ok: Boolean) {
        if (msgId != pendingMsgId || !ok) return
        Log.i(TAG, "ack matched msgId=${msgId.takeLast(6)}")
        handler.removeCallbacks(ackTimeoutRunnable)
        val sentId = pendingMsgId
        pendingMsgId = null
        pendingPayload = null
        val g = gatt
        if (g != null) {
            val lowered = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            Log.d(TAG, "requestConnectionPriority(BALANCED) result=$lowered")
        }
        handler.removeCallbacks(idleDisconnectRunnable)
        handler.postDelayed(idleDisconnectRunnable, IDLE_DISCONNECT_MS)
        listener.onStatus("发送成功")
        listener.onSendResult(true, "已送达")
        AppState.setStatus("最近发送成功: ${sentId?.takeLast(6)}")
    }

    private fun sendPayload(gatt: BluetoothGatt, payload: ByteArray): Boolean {
        val characteristic = writeCharacteristic ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = payload
        listener.onStatus("发送中...")
        Log.d(TAG, "writeCharacteristic start writeType=${characteristic.writeType} payloadBytes=${payload.size}")
        val started = gatt.writeCharacteristic(characteristic)
        if (!started) {
            onAttemptFailed("发包失败")
            return false
        }
        handler.removeCallbacks(ackTimeoutRunnable)
        handler.postDelayed(ackTimeoutRunnable, ACK_TIMEOUT_MS)
        return true
    }

    private fun maybeDiscoverServices(gatt: BluetoothGatt, source: String) {
        if (servicesDiscoveryStarted) {
            Log.d(TAG, "discoverServices skipped source=$source")
            return
        }
        servicesDiscoveryStarted = true
        listener.onStatus("发现服务中...")
        val started = gatt.discoverServices()
        Log.d(TAG, "discoverServices source=$source started=$started")
        if (!started) {
            onAttemptFailed("服务发现启动失败")
        }
    }

    private fun pickFallbackCandidate(targetAddress: String, targetName: String): BindCandidate? {
        if (connectScanResults.isEmpty()) return null
        connectScanResults[targetAddress]?.let { return it }
        if (targetName.isNotBlank()) {
            connectScanResults.values.firstOrNull { it.displayName == targetName }?.let { return it }
        }
        return if (connectScanResults.size == 1) connectScanResults.values.first() else null
    }

    private fun computeRetryDelay(reason: String, attempt: Int): Long {
        if (reason.contains("(133)")) {
            return when (attempt) {
                1 -> 900L
                2 -> 1_800L
                else -> 2_500L
            }
        }
        return RETRY_DELAY_MS
    }

    companion object {
        private const val TAG = "SenderBleManager"
        private const val GATT_ERROR_133 = 133
        private const val DESIRED_MTU = 247
        private const val MTU_FALLBACK_TIMEOUT_MS = 1_000L
        private const val DIRECT_CONNECT_FALLBACK_MS = 1_500L
        private const val IDLE_DISCONNECT_MS = 15_000L
        private const val UNKNOWN_NAME = "未知设备"
        private const val BIND_SCAN_TIMEOUT_MS = 4_000L
        private const val CONNECT_SCAN_TIMEOUT_MS = 3_000L
        private const val PRE_CONNECT_DELAY_MS = 120L
        private const val ACK_TIMEOUT_MS = 1_500L
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_RETRY = 2
    }
}
