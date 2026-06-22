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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.classroomble.R
import com.example.classroomble.model.AckMessage
import com.example.classroomble.model.JsonCodec
import java.util.concurrent.ConcurrentHashMap

class ReceiverBleService : Service() {
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var subscribedDevice: BluetoothDevice? = null

    private val recentMsgIds = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification("接收模式运行中"))
        setupBle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppState.setStatus("接收模式已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAdvertising()
        gattServer?.close()
        gattServer = null
        AppState.setStatus("接收模式已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupBle() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        if (bluetoothAdapter?.isEnabled != true) {
            AppState.setStatus("蓝牙未开启")
            stopSelf()
            return
        }

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            AppState.setStatus("设备不支持BLE广播")
            stopSelf()
            return
        }

        setupGattServer()
        startAdvertising()
    }

    private fun setupGattServer() {
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
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
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                subscribedDevice = device
                AppState.setStatus("已连接: ${device.address}")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
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
            if (characteristic.uuid != BleConstants.WRITE_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }

            val msg = JsonCodec.decodeMessage(value)
            if (msg == null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
                }
                return
            }

            cleanupOldIds()
            val alreadySeen = recentMsgIds.putIfAbsent(msg.msgId, System.currentTimeMillis()) != null
            if (!alreadySeen) {
                AppState.setReceivedText(msg.text)
            }

            val ackBytes = JsonCodec.encodeAck(AckMessage(msg.msgId, ok = true, code = 0))
            ackCharacteristic?.value = ackBytes
            gattServer?.notifyCharacteristicChanged(device, ackCharacteristic, false)

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

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private fun stopAdvertising() {
        advertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            AppState.setStatus("等待连接")
        }

        override fun onStartFailure(errorCode: Int) {
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
        private const val CHANNEL_ID = "receiver_ble_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
