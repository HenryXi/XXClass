package com.example.classroomble.model

import org.json.JSONObject

object JsonCodec {
    fun encodeMessage(message: ClassroomMessage): ByteArray {
        val json = JSONObject()
        json.put("v", message.version)
        json.put("msgId", message.msgId)
        json.put("ts", message.ts)
        json.put("text", message.text)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeMessage(bytes: ByteArray): ClassroomMessage? {
        return runCatching {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val text = json.optString("text", "")
            if (text.isBlank()) return null
            ClassroomMessage(
                version = json.optInt("v", 1),
                msgId = json.optString("msgId", ""),
                ts = json.optLong("ts", System.currentTimeMillis()),
                text = text,
            )
        }.getOrNull()
    }

    fun encodeAck(ack: AckMessage): ByteArray {
        val json = JSONObject()
        json.put("msgId", ack.msgId)
        json.put("ok", ack.ok)
        json.put("code", ack.code)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeAck(bytes: ByteArray): AckMessage? {
        return runCatching {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            AckMessage(
                msgId = json.optString("msgId", ""),
                ok = json.optBoolean("ok", false),
                code = json.optInt("code", -1),
            )
        }.getOrNull()
    }
}
