package com.turrit.assistant.profile

import com.turrit.assistant.account.model.TelegramAccount
import com.turrit.assistant.profile.model.AvatarAsset
import com.turrit.assistant.profile.model.ProfileAssignment
import com.turrit.assistant.profile.model.ProfileAssignmentState
import com.turrit.assistant.profile.model.ProfilePreview
import com.turrit.assistant.profile.model.ProfileRow
import java.io.File
import java.util.Locale

/** Keeps account order and file order deterministic; no random allocation is used. */
class ProfileManager {
    fun buildPreview(
        selectedAccounts: List<TelegramAccount>,
        rows: List<ProfileRow>,
        avatars: List<AvatarAsset>
    ): ProfilePreview {
        val byFileName = avatars.associateBy { File(it.fileName).name.lowercase(Locale.ROOT) }
        val assignments = selectedAccounts.mapIndexed { index, account ->
            val row = rows.getOrNull(index)
            val requestedAvatar = row?.avatarFileName?.let {
                byFileName[File(it).name.lowercase(Locale.ROOT)]
            }
            val avatar = when {
                row == null -> null
                !row.avatarFileName.isNullOrBlank() -> requestedAvatar
                else -> avatars.getOrNull(index)
            }
            ProfileAssignment(
                account = account,
                row = row,
                avatar = avatar,
                state = if (row == null) ProfileAssignmentState.MISSING_PROFILE else ProfileAssignmentState.READY,
                avatarMissing = row != null && !row.avatarFileName.isNullOrBlank() && requestedAvatar == null
            )
        }
        return ProfilePreview(
            assignments = assignments,
            extraProfileRows = (rows.size - selectedAccounts.size).coerceAtLeast(0),
            extraAvatars = (avatars.size - selectedAccounts.size).coerceAtLeast(0),
            selectedAccountCount = selectedAccounts.size,
            profileRowCount = rows.size,
            avatarCount = avatars.size,
            sourceAvatars = avatars
        )
    }
}
