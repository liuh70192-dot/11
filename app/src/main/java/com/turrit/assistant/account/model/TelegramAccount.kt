package com.turrit.assistant.account.model

data class TelegramAccount(
    val id: String,
    val packageName: String,
    val appName: String,
    val phoneNumber: String,
    val displayName: String,
    val username: String,
    val isSelected: Boolean = false,
    val isEnabled: Boolean = true,
    val lastSeenAt: Long = System.currentTimeMillis()
)
