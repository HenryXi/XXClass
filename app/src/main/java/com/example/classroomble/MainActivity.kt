package com.example.classroomble

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.classroomble.ble.AppState
import com.example.classroomble.ble.BleConstants
import com.example.classroomble.ble.ReceiverBleService
import com.example.classroomble.ble.SenderBleManager
import com.example.classroomble.databinding.ActivityMainBinding
import com.example.classroomble.ui.PermissionHelper
import com.example.classroomble.ui.TextValidator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SenderBleManager.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var senderManager: SenderBleManager

    private val bindCandidates = mutableListOf<android.bluetooth.BluetoothDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updateStatus("权限结果已更新")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        senderManager = SenderBleManager(this, this)
        ensurePermissions()
        setupUi()
        observeState()

        val defaultReceiver = resources.configuration.smallestScreenWidthDp >= 600
        if (defaultReceiver) {
            binding.modeToggle.check(R.id.btnReceiverMode)
            switchMode(isReceiver = true)
        } else {
            binding.modeToggle.check(R.id.btnSenderMode)
            switchMode(isReceiver = false)
        }
    }

    private fun setupUi() {
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            switchMode(isReceiver = checkedId == R.id.btnReceiverMode)
        }

        binding.btnBind.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            bindCandidates.clear()
            senderManager.startBindScan()
            binding.root.postDelayed({ showBindDialog() }, 8_200L)
        }

        binding.btnClearBind.setOnClickListener {
            senderManager.clearBoundDevice()
            updateStatus("已清除绑定")
        }

        binding.btnSend.setOnClickListener {
            if (!ensurePermissions()) return@setOnClickListener
            val input = binding.inputText.text?.toString().orEmpty()
            val error = TextValidator.validate(input)
            if (error != null) {
                binding.inputText.error = error
                return@setOnClickListener
            }
            binding.inputText.error = null
            binding.btnSend.isEnabled = false
            senderManager.sendText(input.trim())
        }
    }

    private fun switchMode(isReceiver: Boolean) {
        binding.senderPanel.isVisible = !isReceiver
        binding.receiverPanel.isVisible = isReceiver

        if (isReceiver) {
            startReceiverService()
            updateStatus("接收模式运行中")
        } else {
            stopReceiverService()
            updateStatus("发送模式")
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            AppState.receivedText.collect {
                binding.textDisplay.text = it
            }
        }

        lifecycleScope.launch {
            AppState.statusText.collect {
                binding.textStatus.text = "状态：$it"
            }
        }
    }

    private fun startReceiverService() {
        val intent = Intent(this, ReceiverBleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun stopReceiverService() {
        stopService(Intent(this, ReceiverBleService::class.java))
    }

    private fun showBindDialog() {
        senderManager.stopBindScan()
        if (bindCandidates.isEmpty()) {
            updateStatus("未扫描到平板，请确认平板处于接收模式")
            return
        }

        val names = bindCandidates.map { "${it.name ?: "未知设备"} (${it.address})" }
        AlertDialog.Builder(this)
            .setTitle("选择学生平板")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names)) { _, which ->
                senderManager.bindToDevice(bindCandidates[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun ensurePermissions(): Boolean {
        val permissions = PermissionHelper.requiredPermissions()
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            permissionLauncher.launch(denied.toTypedArray())
            return false
        }
        return true
    }

    private fun updateStatus(value: String) {
        AppState.setStatus(value)
    }

    override fun onStatus(status: String) {
        runOnUiThread { updateStatus(status) }
    }

    override fun onBindDevices(devices: List<android.bluetooth.BluetoothDevice>) {
        bindCandidates.clear()
        bindCandidates.addAll(devices)
        updateStatus("扫描到${devices.size}个设备")
    }

    override fun onSendResult(success: Boolean, message: String) {
        runOnUiThread {
            binding.btnSend.isEnabled = true
            updateStatus(message)
            if (success) {
                binding.inputText.text?.clear()
            }
        }
    }

    override fun onDestroy() {
        senderManager.release()
        super.onDestroy()
    }
}
