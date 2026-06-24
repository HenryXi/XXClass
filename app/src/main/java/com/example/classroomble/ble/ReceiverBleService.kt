package com.example.classroomble.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.classroomble.R
import com.example.classroomble.model.AckMessage
import com.example.classroomble.model.JsonCodec
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class ReceiverBleService : Service() {
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var subscribedDevice: BluetoothDevice? = null
    private var advertiseWithDeviceName = true
    private var retriedWithoutDeviceName = false

    private val recentMsgIds = ConcurrentHashMap<String, Long>()
    private val incomingBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate receiver service")
        startForeground(NOTIFICATION_ID, buildNotification("接收模式运行中"))
        setupBle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId")
        AppState.setStatus("接收模式已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy receiver service")
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        AppState.setStatus("接收模式已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupBle() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        Log.d(TAG, "setupBle btEnabled=${bluetoothAdapter?.isEnabled}")
        if (bluetoothAdapter?.isEnabled != true) {
            AppState.setStatus("蓝牙未开启")
            stopSelf()
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "bluetoothLeAdvertiser is null")
            AppState.setStatus("设备不支持BLE广播")
            stopSelf()
            return
        }

        setupGattServer()
        startAdvertising()
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        Log.d(TAG, "openGattServer result=${gattServer != null}")
        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val writeCharacteristic = BluetoothGattCharacteristic(
            BleConstants.WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        ackCharacteristic = BluetoothGattCharacteristic(
            BleConstants.ACK_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            BleConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        ackCharacteristic?.addDescriptor(cccd)

        service.addCharacteristic(writeCharacteristic)
        ackCharacteristic?.let(service::addCharacteristic)
        val added = gattServer?.addService(service) ?: false
        Log.d(TAG, "addService result=$added")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange addr=${device.address} status=$status newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                subscribedDevice = device
                AppState.setStatus("已连接: ${device.address}")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                incomingBuffers.remove(device.address)
                if (device.address == subscribedDevice?.address) {
                    subscribedDevice = null
                }
                AppState.setStatus("等待连接")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            Log.d(
                TAG,
                "onCharacteristicWriteRequest addr=${device.address} requestId=$requestId prepared=$preparedWrite responseNeeded=$responseNeeded offset=$offset bytes=${value.size}",
            )
            if (characteristic.uuid != BleConstants.WRITE_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }

            val key = device.address ?: "unknown"
            val buffer = incomingBuffers.getOrPut(key) { ByteArrayOutputStream() }
            if (offset == 0 && !preparedWrite && buffer.size() > 0) {
                buffer.reset()
            }
            if (offset > 0 && offset < buffer.size()) {
                // Device retried/rewound, reset to avoid mixing different frames.
                buffer.reset()
            }
            buffer.write(value)

            val merged = buffer.toByteArray()
            val msg = JsonCodec.decodeMessage(merged)
            if (msg == null) {
                Log.w(TAG, "decodeMessage failed, buffered=${merged.size}")
                if (merged.size > MAX_BUFFER_BYTES) {
                    Log.w(TAG, "buffer overflow, reset")
                    buffer.reset()
                }
                if (responseNeeded) {
                    // Allow peer to continue sending remaining chunks.
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                return
            }
            buffer.reset()
            Log.i(TAG, "recv msgId=${msg.msgId.takeLast(6)} textLen=${msg.text.length}")

            cleanupOldIds()
            val alreadySeen = recentMsgIds.putIfAbsent(msg.msgId, System.currentTimeMillis()) != null
            if (!alreadySeen) {
                AppState.setReceivedText(msg.text)
            }

            val ackBytes = JsonCodec.encodeAck(AckMessage(msg.msgId, ok = true, code = 0))
            ackCharacteristic?.value = ackBytes
            val notified = gattServer?.notifyCharacteristicChanged(device, ackCharacteristic, false) ?: false
            Log.d(TAG, "notifyCharacteristicChanged result=$notified")

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            Log.d(
                TAG,
                "onDescriptorWriteRequest addr=${device.address} requestId=$requestId uuid=${descriptor.uuid} bytes=${value.size} responseNeeded=$responseNeeded",
            )
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                descriptor.value = value
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
                return
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }
    }

    private fun startAdvertising() {
        if (!hasBleRuntimePermissions()) {
            AppState.setStatus("缺少蓝牙权限")
            stopSelf()
            return
        }
        startAdvertisingInternal()
    }

    private fun startAdvertisingInternal() {
        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        if (advertiseWithDeviceName) {
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            Log.d(TAG, "startAdvertising includeDeviceName=true")
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } else {
            Log.d(TAG, "startAdvertising includeDeviceName=false")
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        }
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertise start success mode=${settingsInEffect.mode} tx=${settingsInEffect.txPowerLevel}")
            AppState.setStatus("等待连接")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertise start failure errorCode=$errorCode")
            if (
                errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE &&
                advertiseWithDeviceName &&
                !retriedWithoutDeviceName
            ) {
                retriedWithoutDeviceName = true
                advertiseWithDeviceName = false
                AppState.setStatus("设备名过长，切换兼容广播")
                startAdvertisingInternal()
                return
            }
            AppState.setStatus("广播失败: $errorCode")
        }
    }

    private fun cleanupOldIds() {
        val now = System.currentTimeMillis()
        recentMsgIds.entries.removeIf { now - it.value > 5 * 60_000 }
    }

    private fun hasBleRuntimePermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun buildNotification(content: String): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "BLE Receiver", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("课堂接收模式")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ReceiverBleService"
        private const val MAX_BUFFER_BYTES = 1024
        private const val CHANNEL_ID = "receiver_ble_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
