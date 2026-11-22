package com.turrit.assistant.profile.model

import com.turrit.assistant.account.model.TelegramAccount

data class ProfileField(
    val action: ProfileFieldAction = ProfileFieldAction.KEEP,
    val value: String = ""
) {
    companion object {
        fun fromCell(raw: String?): ProfileField {
            val value = raw?.trim().orEmpty()
            return when {
                value.isEmpty() -> ProfileField(ProfileFieldAction.KEEP)
                value == "【清空】" -> ProfileField(ProfileFieldAction.CLEAR)
                else -> ProfileField(ProfileFieldAction.UPDATE, value)
            }
        }
    }
}

enum class ProfileFieldAction {
    KEEP,
    CLEAR,
    UPDATE
}

data class ProfileRow(
    val sourceRow: Int,
    val firstName: ProfileField = ProfileField(),
    val lastName: ProfileField = ProfileField(),
    val username: ProfileField = ProfileField(),
    val bio: ProfileField = ProfileField(),
    val avatarFileName: String? = null
) {
    fun hasChanges(): Boolean =
        listOf(firstName, lastName, username, bio).any { it.action != ProfileFieldAction.KEEP } ||
            !avatarFileName.isNullOrBlank()
}

data class AvatarAsset(
    val fileName: String,
    val localPath: String,
    val mimeType: String,
    val temporaryDirectoryPath: String? = null
)

enum class ProfileAssignmentState {
    READY,
    MISSING_PROFILE
}

data class ProfileAssignment(
    val account: TelegramAccount,
    val row: ProfileRow?,
    val avatar: AvatarAsset?,
    val state: ProfileAssignmentState,
    val avatarMissing: Boolean = false
)

data class ProfilePreview(
    val assignments: List<ProfileAssignment>,
    val extraProfileRows: Int,
    val extraAvatars: Int,
    val selectedAccountCount: Int,
    val profileRowCount: Int,
    val avatarCount: Int,
    val sourceAvatars: List<AvatarAsset> = emptyList()
)
