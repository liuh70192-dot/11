package com.turrit.assistant.group.model

/** Source metadata is retained; it is not an identity to impersonate. */
data class GroupMessage(
    val sourceGroup: String,
    val sourceSender: String?,
    val messageId: String?,
    val messageText: String?,
    val messageType: String,
    val originalTimestamp: String?,
    val mediaReference: String? = null
)
