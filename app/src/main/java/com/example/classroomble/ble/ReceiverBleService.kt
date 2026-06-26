package com.example.classroomble.ble

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.classroomble.R
import com.example.classroomble.model.AckMessage
import com.example.classroomble.model.JsonCodec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap

class ReceiverBleService : Service() {
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var originalAdapterName: String? = null

    @Volatile
    private var running = false

    private var acceptThread: Thread? = null

    private val recentMsgIds = ConcurrentHashMap<String, Long>()
    private val prefs by lazy { getSharedPreferences(BleConstants.PREFS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate receiver service")
        startForeground(NOTIFICATION_ID, buildNotification("接收模式运行中"))
        setupClassicBluetooth()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId")
        AppState.setStatus("接收模式已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy receiver service")
        running = false
        closeClientSocket()
        closeServerSocket()
        restoreAdapterName()
        AppState.setStatus("接收模式已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun setupClassicBluetooth() {
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter?.isEnabled != true) {
            AppState.setStatus("蓝牙未开启")
            stopSelf()
            return
        }

        if (!hasClassicRuntimePermissions()) {
            AppState.setStatus("缺少蓝牙权限")
            stopSelf()
            return
        }

        ensureReceiverDeviceName()
        startAcceptLoop()
    }

    private fun ensureReceiverDeviceName() {
        val adapter = bluetoothAdapter ?: return
        val current = adapter.name.orEmpty()
        if (current.startsWith(BleConstants.RECEIVER_NAME_PREFIX)) return
        originalAdapterName = current
        val suffix = runCatching { adapter.address?.takeLast(4).orEmpty() }.getOrDefault("")
        val target = if (suffix.isNotBlank()) {
            "${BleConstants.RECEIVER_NAME_PREFIX}-$suffix"
        } else {
            BleConstants.RECEIVER_NAME_PREFIX
        }
        runCatching { adapter.name = target }
    }

    private fun restoreAdapterName() {
        val adapter = bluetoothAdapter ?: return
        val backup = originalAdapterName ?: return
        if (backup.isBlank()) return
        runCatching { adapter.name = backup }
    }

    private fun startAcceptLoop() {
        val adapter = bluetoothAdapter ?: run {
            AppState.setStatus("蓝牙不可用")
            stopSelf()
            return
        }

        running = true
        acceptThread = Thread {
            while (running) {
                try {
                    closeServerSocket()
                    serverSocket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD_MR1) {
                        adapter.listenUsingInsecureRfcommWithServiceRecord(
                            BleConstants.SERVICE_NAME,
                            BleConstants.SERVICE_UUID,
                        )
                    } else {
                        adapter.listenUsingRfcommWithServiceRecord(
                            BleConstants.SERVICE_NAME,
                            BleConstants.SERVICE_UUID,
                        )
                    }
                    AppState.setStatus("等待连接")
                    val socket = serverSocket?.accept() ?: break
                    closeClientSocket()
                    clientSocket = socket
                    val remoteAddress = socket.remoteDevice?.address.orEmpty()
                    if (remoteAddress.isNotBlank()) {
                        prefs.edit().putString(BleConstants.KEY_LAST_SENDER_DEVICE, remoteAddress).apply()
                    }
                    AppState.setStatus("已连接: $remoteAddress")
                    processClientSocket(socket)
                } catch (t: Throwable) {
                    if (!running) break
                    Log.w(TAG, "accept loop error", t)
                    AppState.setStatus("连接异常，等待重连")
                    sleepQuietly(500)
                }
            }
        }.also { it.start() }
    }

    private fun processClientSocket(socket: BluetoothSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8))
            while (running && socket.isConnected) {
                val line = reader.readLine() ?: break
                val msg = JsonCodec.decodeMessage(line.toByteArray(Charsets.UTF_8)) ?: continue

                cleanupOldIds()
                val alreadySeen = recentMsgIds.putIfAbsent(msg.msgId, System.currentTimeMillis()) != null
                if (!alreadySeen) {
                    AppState.setReceivedText(msg.text)
                }

                val ackBytes = JsonCodec.encodeAck(AckMessage(msg.msgId, ok = true, code = 0))
                writer.write(String(ackBytes, Charsets.UTF_8))
                writer.newLine()
                writer.flush()
            }
        } catch (t: Throwable) {
            if (running) {
                Log.w(TAG, "client socket error", t)
            }
        } finally {
            closeClientSocket()
            if (running) {
                AppState.setStatus("等待连接")
            }
        }
    }

    private fun closeClientSocket() {
        try {
            clientSocket?.close()
        } catch (_: Throwable) {
            // ignore
        }
        clientSocket = null
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
            // ignore
        }
        serverSocket = null
    }

    private fun cleanupOldIds() {
        val now = System.currentTimeMillis()
        recentMsgIds.entries.removeIf { now - it.value > 5 * 60_000 }
    }

    private fun hasClassicRuntimePermissions(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyList()
        }
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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
        private const val CHANNEL_ID = "receiver_ble_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
