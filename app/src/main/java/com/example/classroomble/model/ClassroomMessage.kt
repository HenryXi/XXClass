package com.example.classroomble.model

data class ClassroomMessage(
    val version: Int = 1,
    val msgId: String,
    val ts: Long,
    val text: String,
)
