package com.example.classroomble.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.classroomble.model.ClassroomMessage
import com.example.classroomble.model.JsonCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
        fun onNeedSelectDevice(devices: List<BindCandidate>)
        fun onSendResult(success: Boolean, message: String)
    }

    private val prefs = context.getSharedPreferences(BleConstants.PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var bindScanResults = linkedMapOf<String, BindCandidate>()
    private var isDiscoveryRunning = false
    private var isConnecting = false
    private var receiverRegistered = false

    private var socket: BluetoothSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var readThread: Thread? = null
    private val ioLock = Any()

    private var pendingMsgId: String? = null
    private var pendingPayload: ByteArray? = null
    private var retriesLeft = 0
    private var currentAttempt = 0

    private var discoveryPurpose: DiscoveryPurpose = DiscoveryPurpose.NONE
    private var awaitingUserSelection = false

    private enum class DiscoveryPurpose {
        NONE,
        AUTO_BIND_AND_SEND,
        AUTO_BIND_WARMUP,
    }

    private val ackTimeoutRunnable = Runnable {
        onAttemptFailed("等待回执超时")
    }

    private val idleDisconnectRunnable = Runnable {
        if (pendingPayload != null || isConnecting || isDiscoveryRunning) return@Runnable
        disconnectSocket()
        listener.onStatus("连接空闲已断开")
    }

    private val discoveryTimeoutRunnable = Runnable {
        if (!isDiscoveryRunning) return@Runnable
        stopDiscoveryInternal(emitStatus = false)
        handleDiscoveryCompleted()
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val address = device.address ?: return
                    val name = resolveDisplayName(device)
                    val candidate = BindCandidate(device, address, name)
                    bindScanResults[address] = candidate
                    listener.onBindDevices(bindScanResults.values.toList())
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (!isDiscoveryRunning) return
                    handler.removeCallbacks(discoveryTimeoutRunnable)
                    stopDiscoveryInternal(emitStatus = false)
                    handleDiscoveryCompleted()
                }
            }
        }
    }

    init {
        registerReceiverIfNeeded()
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
        if (adapter?.isEnabled != true) {
            listener.onStatus("请先开启蓝牙")
            return
        }
        startDiscovery(DiscoveryPurpose.NONE, "扫描中...")
    }

    fun stopBindScan() {
        discoveryPurpose = DiscoveryPurpose.NONE
        stopDiscoveryInternal(emitStatus = true)
    }

    fun bindToDevice(candidate: BindCandidate) {
        stopBindScan()
        awaitingUserSelection = false
        saveBoundDevice(candidate.address, candidate.displayName)
        listener.onStatus("已选择: ${candidate.displayName}，正在连接")
        if (pendingPayload != null || !isSocketAlive()) {
            connectAndMaybeSend(candidate.device)
        }
    }

    fun cancelDeviceSelection() {
        if (!awaitingUserSelection) return
        awaitingUserSelection = false
        if (pendingPayload != null) {
            failCurrentSend("未选择学生平板，发送已取消")
        } else {
            listener.onStatus("已取消设备选择")
        }
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
        pendingMsgId = msgId
        pendingPayload = JsonCodec.encodeMessage(
            ClassroomMessage(
                msgId = msgId,
                ts = System.currentTimeMillis(),
                text = text,
            ),
        )
        retriesLeft = MAX_RETRY
        currentAttempt = 0
        handler.removeCallbacks(idleDisconnectRunnable)

        if (trySendOnExistingConnection()) {
            return
        }

        val boundAddress = getBoundAddress()
        listener.onStatus("正在连接学生平板...")
        if (boundAddress.isBlank() || !isBoundTargetLikelyReceiver()) {
            if (boundAddress.isNotBlank()) {
                clearBoundDevice()
                listener.onStatus("检测到旧绑定，自动重新发现平板...")
            }
            val taggedBonded = collectBondedCandidates().filter { isLikelyReceiverDevice(it.displayName) }
            if (taggedBonded.size == 1) {
                val candidate = taggedBonded.first()
                saveBoundDevice(candidate.address, candidate.displayName)
                listener.onStatus("已从已配对设备匹配: ${candidate.displayName}，正在连接")
                connectAndMaybeSend(candidate.device)
                return
            }
            if (taggedBonded.size > 1) {
                listener.onStatus("发现多个已配对学生平板，请选择")
                awaitingUserSelection = true
                listener.onNeedSelectDevice(taggedBonded)
                return
            }
            listener.onStatus("首次发送，自动发现并绑定中...")
            startDiscovery(DiscoveryPurpose.AUTO_BIND_AND_SEND, "扫描中...")
            return
        }

        connectToBoundAddress(boundAddress)
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
        if (pendingPayload != null || isConnecting || isDiscoveryRunning) return
        if (isSocketAlive()) return

        val boundAddress = getBoundAddress()
        if (boundAddress.isBlank() || !isBoundTargetLikelyReceiver()) {
            if (boundAddress.isNotBlank()) {
                clearBoundDevice()
                listener.onStatus("检测到旧绑定，后台自动重新发现设备...")
            }
            val taggedBonded = collectBondedCandidates().filter { isLikelyReceiverDevice(it.displayName) }
            if (taggedBonded.isNotEmpty()) {
                val candidate = taggedBonded.first()
                saveBoundDevice(candidate.address, candidate.displayName)
                listener.onStatus("已从已配对设备匹配: ${candidate.displayName}，后台预连接中...")
                connectAndMaybeSend(candidate.device)
                return
            }
            listener.onStatus("未绑定，后台自动发现设备...")
            startDiscovery(DiscoveryPurpose.AUTO_BIND_WARMUP, "后台扫描中...")
            return
        }

        listener.onStatus("后台预连接中...")
        connectToBoundAddress(boundAddress)
    }

    fun release() {
        awaitingUserSelection = false
        stopDiscoveryInternal(emitStatus = false)
        handler.removeCallbacksAndMessages(null)
        disconnectSocket()
        unregisterReceiverIfNeeded()
    }

    private fun connectToBoundAddress(address: String) {
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            onAttemptFailed("绑定设备不可用")
            return
        }
        connectAndMaybeSend(device)
    }

    private fun connectAndMaybeSend(device: BluetoothDevice) {
        if (isConnecting) return
        isConnecting = true
        stopDiscoveryInternal(emitStatus = false)

        Thread {
            try {
                adapter?.cancelDiscovery()
                val newSocket = connectSocketWithFallback(device)
                synchronized(ioLock) {
                    socket = newSocket
                    reader = BufferedReader(InputStreamReader(newSocket.inputStream, Charsets.UTF_8))
                    writer = BufferedWriter(OutputStreamWriter(newSocket.outputStream, Charsets.UTF_8))
                }
                startReadLoop()
                handler.post {
                    isConnecting = false
                    listener.onStatus("连接成功")
                    if (!trySendOnExistingConnection()) {
                        scheduleIdleDisconnect()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "connect failed", t)
                disconnectSocket()
                handler.post {
                    isConnecting = false
                    if (pendingPayload != null) {
                        onAttemptFailed("连接失败")
                    } else {
                        listener.onStatus("预连接失败")
                    }
                }
            }
        }.start()
    }

    private fun connectSocketWithFallback(device: BluetoothDevice): BluetoothSocket {
        val builders = mutableListOf<() -> BluetoothSocket>()
        builders += { device.createRfcommSocketToServiceRecord(BleConstants.SERVICE_UUID) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1) {
            builders += { device.createInsecureRfcommSocketToServiceRecord(BleConstants.SERVICE_UUID) }
        }
        builders += {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }

        var lastError: Throwable? = null
        builders.forEachIndexed { index, create ->
            handler.post { listener.onStatus("连接尝试 ${index + 1}/${builders.size}...") }
            val socket = runCatching { create() }.getOrElse { error ->
                lastError = error
                return@forEachIndexed
            }
            try {
                socket.connect()
                Log.i(TAG, "socket connect success strategy=$index")
                return socket
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "socket connect failed strategy=$index", t)
                runCatching { socket.close() }
            }
        }
        throw IOException("All RFCOMM strategies failed", lastError)
    }

    private fun startReadLoop() {
        readThread?.interrupt()
        readThread = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val localReader = synchronized(ioLock) { reader } ?: break
                    val line = localReader.readLine() ?: break
                    val ack = JsonCodec.decodeAck(line.toByteArray(Charsets.UTF_8)) ?: continue
                    handler.post { handleAck(ack.msgId, ack.ok) }
                }
            } catch (_: Throwable) {
                // ignore and let reconnection handle next send
            }
        }.also { it.start() }
    }

    private fun trySendOnExistingConnection(): Boolean {
        val payload = pendingPayload ?: return false
        if (!isSocketAlive()) return false
        return sendPayload(payload)
    }

    private fun sendPayload(payload: ByteArray): Boolean {
        return try {
            val localWriter = synchronized(ioLock) { writer } ?: return false
            localWriter.write(String(payload, Charsets.UTF_8))
            localWriter.newLine()
            localWriter.flush()
            listener.onStatus("发送中...")
            handler.removeCallbacks(ackTimeoutRunnable)
            handler.postDelayed(ackTimeoutRunnable, ACK_TIMEOUT_MS)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "send payload failed", t)
            onAttemptFailed("发包失败")
            false
        }
    }

    private fun handleAck(msgId: String, ok: Boolean) {
        val expected = pendingMsgId ?: return
        if (!ok || msgId != expected) return
        handler.removeCallbacks(ackTimeoutRunnable)
        val sentId = pendingMsgId
        pendingPayload = null
        pendingMsgId = null
        listener.onStatus("发送成功")
        listener.onSendResult(true, "已送达")
        AppState.setStatus("最近发送成功: ${sentId?.takeLast(6)}")
        scheduleIdleDisconnect()
    }

    private fun onAttemptFailed(reason: String) {
        handler.removeCallbacks(ackTimeoutRunnable)
        disconnectSocket()
        if (reason.contains("连接失败") || reason.contains("绑定设备不可用")) {
            clearBoundDevice()
        }
        if (retriesLeft > 0 && pendingPayload != null) {
            retriesLeft -= 1
            currentAttempt += 1
            listener.onStatus("$reason，正在重试(${MAX_RETRY - retriesLeft}/$MAX_RETRY)")
            val delay = computeRetryDelay(reason, currentAttempt)
            handler.postDelayed({
                val bound = getBoundAddress()
                if (bound.isBlank()) {
                    startDiscovery(DiscoveryPurpose.AUTO_BIND_AND_SEND, "重试中，扫描设备...")
                } else {
                    connectToBoundAddress(bound)
                }
            }, delay)
            return
        }
        failCurrentSend("$reason，发送失败")
    }

    private fun failCurrentSend(message: String) {
        handler.removeCallbacks(ackTimeoutRunnable)
        pendingPayload = null
        pendingMsgId = null
        disconnectSocket()
        listener.onSendResult(false, message)
    }

    private fun startDiscovery(purpose: DiscoveryPurpose, status: String) {
        if (adapter?.isEnabled != true) {
            if (purpose == DiscoveryPurpose.AUTO_BIND_AND_SEND) {
                failCurrentSend("请先开启蓝牙")
            } else {
                listener.onStatus("请先开启蓝牙")
            }
            return
        }

        registerReceiverIfNeeded()
        stopDiscoveryInternal(emitStatus = false)

        bindScanResults.clear()
        awaitingUserSelection = false
        discoveryPurpose = purpose
        isDiscoveryRunning = true
        listener.onStatus(status)
        val started = adapter?.startDiscovery() == true
        if (!started) {
            isDiscoveryRunning = false
            discoveryPurpose = DiscoveryPurpose.NONE
            if (purpose == DiscoveryPurpose.AUTO_BIND_AND_SEND) {
                failCurrentSend("扫描启动失败")
            } else {
                listener.onStatus("扫描启动失败")
            }
            return
        }
        handler.removeCallbacks(discoveryTimeoutRunnable)
        handler.postDelayed(discoveryTimeoutRunnable, DISCOVERY_TIMEOUT_MS)
    }

    private fun stopDiscoveryInternal(emitStatus: Boolean) {
        if (adapter?.isDiscovering == true) {
            adapter.cancelDiscovery()
        }
        handler.removeCallbacks(discoveryTimeoutRunnable)
        if (isDiscoveryRunning && emitStatus) {
            listener.onStatus("扫描结束")
        }
        isDiscoveryRunning = false
    }

    private fun handleDiscoveryCompleted() {
        val purpose = discoveryPurpose
        discoveryPurpose = DiscoveryPurpose.NONE
        val candidates = bindScanResults.values.toList()
        val taggedCandidates = candidates.filter { isLikelyReceiverDevice(it.displayName) }

        if (purpose == DiscoveryPurpose.NONE) {
            listener.onStatus("扫描结束")
            return
        }

        if (candidates.isEmpty()) {
            when (purpose) {
                DiscoveryPurpose.AUTO_BIND_AND_SEND -> failCurrentSend("首次自动连接失败，未发现平板（请确认学生端在接收模式）")
                DiscoveryPurpose.AUTO_BIND_WARMUP -> listener.onStatus("后台未发现可连接平板（学生端需在接收模式）")
                DiscoveryPurpose.NONE -> listener.onStatus("扫描结束")
            }
            return
        }

        if (taggedCandidates.size == 1) {
            val only = taggedCandidates.first()
            saveBoundDevice(only.address, only.displayName)
            listener.onStatus("已自动绑定: ${only.displayName}，正在连接")
            connectAndMaybeSend(only.device)
            return
        }

        if (taggedCandidates.size > 1) {
            if (purpose == DiscoveryPurpose.AUTO_BIND_WARMUP) {
                val pick = taggedCandidates.first()
                saveBoundDevice(pick.address, pick.displayName)
                listener.onStatus("后台自动选择: ${pick.displayName}")
                connectAndMaybeSend(pick.device)
                return
            }
            awaitingUserSelection = true
            listener.onStatus("发现多个学生平板，请选择设备")
            listener.onNeedSelectDevice(taggedCandidates)
            return
        }

        val namedCandidates = candidates.filter { it.displayName != UNKNOWN_NAME }
        if (namedCandidates.isNotEmpty()) {
            if (purpose == DiscoveryPurpose.AUTO_BIND_WARMUP) {
                listener.onStatus("后台扫描未命中特殊标识")
                return
            }
            awaitingUserSelection = true
            listener.onStatus("未发现标识设备，请手动选择学生平板")
            listener.onNeedSelectDevice(namedCandidates)
            return
        }

        when (purpose) {
            DiscoveryPurpose.AUTO_BIND_AND_SEND -> failCurrentSend("首次自动连接失败，未识别到可用设备")
            DiscoveryPurpose.AUTO_BIND_WARMUP -> listener.onStatus("后台未识别到可用设备")
            DiscoveryPurpose.NONE -> listener.onStatus("扫描结束")
        }
    }

    private fun scheduleIdleDisconnect() {
        handler.removeCallbacks(idleDisconnectRunnable)
        handler.postDelayed(idleDisconnectRunnable, IDLE_DISCONNECT_MS)
    }

    private fun disconnectSocket() {
        handler.removeCallbacks(idleDisconnectRunnable)
        readThread?.interrupt()
        readThread = null
        try {
            synchronized(ioLock) {
                reader?.close()
                writer?.close()
                socket?.close()
                reader = null
                writer = null
                socket = null
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun isSocketAlive(): Boolean {
        val s = socket ?: return false
        return s.isConnected && reader != null && writer != null
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)
        receiverRegistered = true
    }

    private fun unregisterReceiverIfNeeded() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(discoveryReceiver) }
        receiverRegistered = false
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

    private fun isBoundTargetLikelyReceiver(): Boolean {
        val name = getBoundName().trim()
        if (name.isBlank()) return false
        return isLikelyReceiverDevice(name)
    }

    private fun saveBoundDevice(address: String?, displayName: String?) {
        if (address.isNullOrBlank()) return
        prefs.edit()
            .putString(BleConstants.KEY_BOUND_DEVICE, address)
            .putString(BleConstants.KEY_BOUND_DEVICE_NAME, displayName.orEmpty())
            .apply()
    }

    private fun collectBondedCandidates(): List<BindCandidate> {
        val paired = adapter?.bondedDevices.orEmpty()
        return paired.mapNotNull { device ->
            val address = device.address ?: return@mapNotNull null
            val name = resolveDisplayName(device)
            BindCandidate(device, address, name)
        }
    }

    private fun resolveDisplayName(device: BluetoothDevice): String {
        val name = try {
            device.name?.trim().orEmpty()
        } catch (_: SecurityException) {
            ""
        }
        return if (name.isNotBlank()) name else UNKNOWN_NAME
    }

    private fun isLikelyReceiverDevice(name: String): Boolean {
        return name.startsWith(BleConstants.RECEIVER_NAME_PREFIX)
    }

    private fun computeRetryDelay(reason: String, attempt: Int): Long {
        if (reason.contains("133")) {
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
        private const val UNKNOWN_NAME = "未知设备"
        private const val DISCOVERY_TIMEOUT_MS = 4_000L
        private const val ACK_TIMEOUT_MS = 1_500L
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_RETRY = 2
        private const val IDLE_DISCONNECT_MS = 15_000L
    }
}
