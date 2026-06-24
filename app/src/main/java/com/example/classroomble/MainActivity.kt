package com.example.classroomble

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.example.classroomble.ble.AppState
import com.example.classroomble.ble.ReceiverBleService
import com.example.classroomble.ble.SenderBleManager
import com.example.classroomble.databinding.ActivityMainBinding
import com.example.classroomble.ui.PermissionHelper
import com.example.classroomble.ui.TextValidator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), SenderBleManager.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var senderManager: SenderBleManager
    private var isReceiverMode: Boolean = true
    private val warmUpRunnable = Runnable {
        if (!isReceiverMode) {
            senderManager.warmUpSenderConnection()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updateStatus("权限结果已更新")
        if (!isReceiverMode && hasAllPermissions()) {
            scheduleSenderWarmUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        senderManager = SenderBleManager(this, this)
        ensurePermissions()
        setupUi()
        observeState()

        isReceiverMode = savedInstanceState?.getBoolean(KEY_IS_RECEIVER_MODE) ?: true
        binding.modeToggle.check(if (isReceiverMode) R.id.btnReceiverMode else R.id.btnSenderMode)
        switchMode(isReceiver = isReceiverMode)
    }

    private fun setupUi() {
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val targetReceiver = checkedId == R.id.btnReceiverMode
            if (targetReceiver == isReceiverMode) return@addOnButtonCheckedListener
            isReceiverMode = targetReceiver
            switchMode(isReceiver = targetReceiver)
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
            binding.root.removeCallbacks(warmUpRunnable)
            binding.textDisplay.rotation = 90f
            startReceiverService()
            updateStatus("接收模式运行中")
        } else {
            binding.textDisplay.rotation = 0f
            stopReceiverService()
            updateStatus("发送模式，准备自动连接...")
            if (ensurePermissions()) {
                scheduleSenderWarmUp()
            }
        }
    }

    private fun scheduleSenderWarmUp() {
        binding.root.removeCallbacks(warmUpRunnable)
        binding.root.postDelayed(warmUpRunnable, 300L)
        binding.root.postDelayed(warmUpRunnable, 1_200L)
    }

    private fun observeState() {
        lifecycleScope.launch {
            AppState.receivedText.collect {
                binding.textDisplay.text = it.replace('\n', ' ')
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
        // We are in foreground Activity; using startService avoids foreground-service
        // timeout race when rapidly switching modes/orientation.
        startService(intent)
    }

    private fun stopReceiverService() {
        stopService(Intent(this, ReceiverBleService::class.java))
    }

    private fun ensurePermissions(): Boolean {
        if (hasAllPermissions()) return true
        val permissions = PermissionHelper.requiredPermissions()
        val denied = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        permissionLauncher.launch(denied.toTypedArray())
        return false
    }

    private fun hasAllPermissions(): Boolean {
        val permissions = PermissionHelper.requiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateStatus(value: String) {
        AppState.setStatus(value)
    }

    override fun onStatus(status: String) {
        runOnUiThread { updateStatus(status) }
    }

    override fun onBindDevices(devices: List<SenderBleManager.BindCandidate>) {
        if (devices.isNotEmpty()) {
            updateStatus("发现设备: ${devices.first().displayName}")
        } else {
            updateStatus("扫描到0个设备")
        }
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
        binding.root.removeCallbacks(warmUpRunnable)
        senderManager.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IS_RECEIVER_MODE, isReceiverMode)
    }

    companion object {
        private const val KEY_IS_RECEIVER_MODE = "key_is_receiver_mode"
    }
}
