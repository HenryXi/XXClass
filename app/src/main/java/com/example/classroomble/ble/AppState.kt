package com.example.classroomble.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppState {
    private val _receivedText = MutableStateFlow("待接收")
    private val _statusText = MutableStateFlow("未连接")

    val receivedText: StateFlow<String> = _receivedText
    val statusText: StateFlow<String> = _statusText

    fun setReceivedText(value: String) {
        _receivedText.value = value
    }

    fun setStatus(value: String) {
        _statusText.value = value
    }
}
