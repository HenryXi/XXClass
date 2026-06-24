package com.example.classroomble.ble

import java.util.UUID

object BleConstants {
    const val PREFS = "ble_prefs"
    const val KEY_BOUND_DEVICE = "bound_device"
    const val KEY_BOUND_DEVICE_NAME = "bound_device_name"
    const val MAX_TEXT_LEN = 16
    const val SERVICE_NAME = "ClassroomText"

    val SERVICE_UUID: UUID = UUID.fromString("e12f5f22-6f4f-4b2f-8266-8e0d3ac9f901")
    val WRITE_UUID: UUID = UUID.fromString("e12f5f22-6f4f-4b2f-8266-8e0d3ac9f902")
    val ACK_UUID: UUID = UUID.fromString("e12f5f22-6f4f-4b2f-8266-8e0d3ac9f903")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
