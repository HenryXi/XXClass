package com.example.classroomble.ui

import com.example.classroomble.ble.BleConstants

object TextValidator {
    private val allowedRegex = Regex("^[\\p{L}\\p{N}\\s+\\-*/=()<>.,:;?!'\"_]+$")

    fun validate(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return "文本不能为空"
        if (value.length > BleConstants.MAX_TEXT_LEN) return "最多${BleConstants.MAX_TEXT_LEN}个字符"
        if (!allowedRegex.matches(value)) return "仅支持中英数字和常见符号"
        return null
    }
}
