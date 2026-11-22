package com.turrit.assistant.profile

import com.turrit.assistant.account.model.TelegramAccount
import com.turrit.assistant.profile.model.AvatarAsset
import com.turrit.assistant.profile.model.ProfileAssignmentState
import com.turrit.assistant.profile.model.ProfileField
import com.turrit.assistant.profile.model.ProfileFieldAction
import com.turrit.assistant.profile.model.ProfileRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileManagerTest {
    private fun account(id: String, name: String) = TelegramAccount(
        id = id,
        packageName = "org.telegram.group",
        appName = "Turrit",
        phoneNumber = "",
        displayName = name,
        username = ""
    )

    @Test
    fun `assigns rows and avatars in selected account order`() {
        val accounts = listOf(account("a", "One"), account("b", "Two"))
        val rows = listOf(
            ProfileRow(2, firstName = ProfileField(ProfileFieldAction.UPDATE, "First")),
            ProfileRow(3, firstName = ProfileField(ProfileFieldAction.UPDATE, "Second"))
        )
        val avatars = listOf(
            AvatarAsset("one.jpg", "/tmp/one.jpg", "image/jpeg"),
            AvatarAsset("two.jpg", "/tmp/two.jpg", "image/jpeg")
        )
        val preview = ProfileManager().buildPreview(accounts, rows, avatars)
        assertEquals("First", preview.assignments[0].row?.firstName?.value)
        assertEquals("Second", preview.assignments[1].row?.firstName?.value)
        assertEquals("one.jpg", preview.assignments[0].avatar?.fileName)
        assertEquals("two.jpg", preview.assignments[1].avatar?.fileName)
    }

    @Test
    fun `marks accounts without data when Excel rows are fewer`() {
        val preview = ProfileManager().buildPreview(
            listOf(account("a", "One"), account("b", "Two")),
            listOf(ProfileRow(2, firstName = ProfileField(ProfileFieldAction.UPDATE, "Only"))),
            emptyList()
        )
        assertEquals(0, preview.extraProfileRows)
        assertEquals(ProfileAssignmentState.MISSING_PROFILE, preview.assignments[1].state)
        assertNull(preview.assignments[1].row)
    }

    @Test
    fun `keeps extra Excel rows when profiles exceed selected accounts`() {
        val preview = ProfileManager().buildPreview(
            listOf(account("a", "One")),
            listOf(
                ProfileRow(2, firstName = ProfileField(ProfileFieldAction.UPDATE, "One")),
                ProfileRow(3, firstName = ProfileField(ProfileFieldAction.UPDATE, "Unused"))
            ),
            emptyList()
        )
        assertEquals(1, preview.extraProfileRows)
        assertEquals(1, preview.assignments.size)
    }

    @Test
    fun `recognizes explicit clear only`() {
        assertEquals(ProfileFieldAction.KEEP, ProfileField.fromCell("").action)
        assertEquals(ProfileFieldAction.CLEAR, ProfileField.fromCell("【清空】").action)
        assertEquals(ProfileFieldAction.UPDATE, ProfileField.fromCell("clear").action)
    }

    @Test
    fun `maps avatar by explicit filename when supplied`() {
        val preview = ProfileManager().buildPreview(
            listOf(account("a", "One")),
            listOf(ProfileRow(2, avatarFileName = "photo.png")),
            listOf(AvatarAsset("photo.png", "/tmp/photo.png", "image/png"))
        )
        assertEquals("photo.png", preview.assignments.first().avatar?.fileName)
        assertTrue(!preview.assignments.first().avatarMissing)
    }
}
