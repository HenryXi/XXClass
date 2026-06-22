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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.classroomble.model.ClassroomMessage
import com.example.classroomble.model.JsonCodec
import java.util.UUID

class SenderBleManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: String)
        fun onBindDevices(devices: List<BluetoothDevice>)
        fun onSendResult(success: Boolean, message: String)
    }

    private val prefs = context.getSharedPreferences(BleConstants.PREFS, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner?
        get() = adapter?.bluetoothLeScanner

    private var bindScanResults = linkedMapOf<String, BluetoothDevice>()
    private var isBindScanning = false

    private var autoScanCallback: ScanCallback? = null
    private var bindScanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingMsgId: String? = null
    private var pendingPayload: ByteArray? = null
    private var retriesLeft = 0

    fun hasBoundDevice(): Boolean = getBoundAddress().isNotBlank()

    fun clearBoundDevice() {
        prefs.edit().remove(BleConstants.KEY_BOUND_DEVICE).apply()
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
                bindScanResults[key] = device
                listener.onBindDevices(bindScanResults.values.toList())
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build(),
        )

        scanner.startScan(filters, defaultScanSettings(), bindScanCallback)
        handler.postDelayed({ stopBindScan() }, BIND_SCAN_TIMEOUT_MS)
    }

    fun stopBindScan() {
        if (!isBindScanning) return
        isBindScanning = false
        bindScanCallback?.let { scanner?.stopScan(it) }
        bindScanCallback = null
        listener.onStatus("扫描结束")
    }

    fun bindToDevice(device: BluetoothDevice) {
        stopBindScan()
        saveBoundAddress(device.address)
        listener.onStatus("已绑定: ${device.name ?: device.address}")
    }

    fun sendText(text: String) {
        val bound = getBoundAddress()
        if (bound.isBlank()) {
            listener.onSendResult(false, "请先扫描并绑定学生平板")
            return
        }
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
        listener.onStatus("正在连接学生平板...")
        scanAndConnect(bound)
    }

    private fun scanAndConnect(targetAddress: String) {
        disconnectGatt()
        val scanner = scanner ?: run {
            listener.onSendResult(false, "蓝牙扫描不可用")
            return
        }

        autoScanCallback?.let { scanner.stopScan(it) }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                if (device.address.equals(targetAddress, ignoreCase = true)) {
                    scanner.stopScan(this)
                    autoScanCallback = null
                    listener.onStatus("已发现平板，正在连接")
                    connectGatt(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                autoScanCallback = null
                listener.onSendResult(false, "扫描失败: $errorCode")
            }
        }
        autoScanCallback = callback

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .setDeviceAddress(targetAddress)
                .build(),
        )
        scanner.startScan(filters, defaultScanSettings(), callback)

        handler.postDelayed({
            if (autoScanCallback === callback) {
                scanner.stopScan(callback)
                autoScanCallback = null
                onAttemptFailed("连接超时，未发现平板")
            }
        }, CONNECT_SCAN_TIMEOUT_MS)
    }

    private fun connectGatt(device: BluetoothDevice) {
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                listener.onStatus("连接成功，发现服务中")
                handler.post { gatt.discoverServices() }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (pendingPayload != null && pendingMsgId != null) {
                    // unexpected disconnect before ack.
                    onAttemptFailed("连接断开")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                onAttemptFailed("未发现接收服务")
                return
            }

            writeCharacteristic = service.getCharacteristic(BleConstants.WRITE_UUID)
            ackCharacteristic = service.getCharacteristic(BleConstants.ACK_UUID)
            if (writeCharacteristic == null || ackCharacteristic == null) {
                onAttemptFailed("服务特征不完整")
                return
            }

            val descriptor = ackCharacteristic?.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor == null) {
                onAttemptFailed("通知描述符缺失")
                return
            }

            gatt.setCharacteristicNotification(ackCharacteristic, true)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid != BleConstants.CCCD_UUID) return
            val payload = pendingPayload ?: return
            val characteristic = writeCharacteristic ?: return
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = payload
            listener.onStatus("发送中...")
            gatt.writeCharacteristic(characteristic)

            handler.postDelayed(ackTimeoutRunnable, ACK_TIMEOUT_MS)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != BleConstants.ACK_UUID) return
            val ack = JsonCodec.decodeAck(characteristic.value) ?: return
            if (ack.msgId == pendingMsgId && ack.ok) {
                handler.removeCallbacks(ackTimeoutRunnable)
                val sentId = pendingMsgId
                pendingMsgId = null
                pendingPayload = null
                disconnectGatt()
                listener.onStatus("发送成功")
                listener.onSendResult(true, "已送达")
                AppState.setStatus("最近发送成功: ${sentId?.takeLast(6)}")
            }
        }
    }

    private val ackTimeoutRunnable = Runnable {
        onAttemptFailed("等待回执超时")
    }

    private fun onAttemptFailed(reason: String) {
        disconnectGatt()
        if (retriesLeft > 0 && pendingPayload != null) {
            retriesLeft -= 1
            listener.onStatus("$reason，正在重试(${MAX_RETRY - retriesLeft}/$MAX_RETRY)")
            val bound = getBoundAddress()
            if (bound.isBlank()) {
                listener.onSendResult(false, "设备丢失绑定")
                return
            }
            handler.postDelayed({ scanAndConnect(bound) }, RETRY_DELAY_MS)
            return
        }

        pendingPayload = null
        pendingMsgId = null
        listener.onSendResult(false, "$reason，发送失败")
    }

    fun release() {
        stopBindScan()
        autoScanCallback?.let { scanner?.stopScan(it) }
        autoScanCallback = null
        disconnectGatt()
        handler.removeCallbacksAndMessages(null)
    }

    private fun disconnectGatt() {
        handler.removeCallbacks(ackTimeoutRunnable)
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

    private fun saveBoundAddress(address: String?) {
        if (address.isNullOrBlank()) return
        prefs.edit().putString(BleConstants.KEY_BOUND_DEVICE, address).apply()
    }

    companion object {
        private const val BIND_SCAN_TIMEOUT_MS = 8_000L
        private const val CONNECT_SCAN_TIMEOUT_MS = 5_000L
        private const val ACK_TIMEOUT_MS = 2_000L
        private const val RETRY_DELAY_MS = 500L
        private const val MAX_RETRY = 2
    }
}
