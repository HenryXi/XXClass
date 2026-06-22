package com.example.classroomble.model

data class AckMessage(
    val msgId: String,
    val ok: Boolean,
    val code: Int = 0,
)
