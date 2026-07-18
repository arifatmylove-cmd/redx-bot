package com.redxbot.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val imageUri: String? = null,
    val isLoading: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Role { USER, ASSISTANT }
}
