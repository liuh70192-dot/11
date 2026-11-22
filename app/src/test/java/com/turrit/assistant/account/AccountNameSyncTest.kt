package com.turrit.assistant.account

import com.turrit.assistant.account.model.TelegramAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class AccountNameSyncTest {
    private fun account(id: String = "com.example.turrit:+911234567890") = TelegramAccount(
        id = id,
        packageName = "com.example.turrit",
        appName = "Turrit",
        phoneNumber = "+911234567890",
        displayName = "60",
        username = "old_user",
        isSelected = true,
        isEnabled = false,
        lastSeenAt = 123L
    )

    @Test
    fun `verified rename keeps stable id and existing account metadata`() {
        val original = account()
        val renamed = AccountNameSync.withVerifiedDisplayName(original, "Wuchen")!!

        assertEquals(original.id, renamed.id)
        assertEquals(original.phoneNumber, renamed.phoneNumber)
        assertEquals(original.username, renamed.username)
        assertEquals(original.isSelected, renamed.isSelected)
        assertEquals(original.isEnabled, renamed.isEnabled)
        assertEquals("Wuchen", renamed.displayName)
    }

    @Test
    fun `verified profile heading labels a single phone record`() {
        val original = account().copy(displayName = "Nearby label")
        val result = AccountNameSync.bindVerifiedProfileName(listOf(original), "Wuchen")

        assertEquals(1, result.size)
        assertEquals(original.id, result.single().id)
        assertEquals("Wuchen", result.single().displayName)
    }

    @Test
    fun `one profile heading does not relabel multiple phone records`() {
        val first = account()
        val second = account("com.example.turrit:+919999999999")
            .copy(phoneNumber = "+919999999999", displayName = "Another")
        val result = AccountNameSync.bindVerifiedProfileName(listOf(first, second), "Wuchen")

        assertSame(first, result[0])
        assertSame(second, result[1])
    }

    @Test
    fun `blank or invalid verified name is ignored`() {
        assertNull(AccountNameSync.withVerifiedDisplayName(account(), ""))
        assertNull(AccountNameSync.withVerifiedDisplayName(account(), "添加账号"))
    }

    @Test
    fun `unchanged display name does not produce a database update`() {
        assertNull(AccountNameSync.withVerifiedDisplayName(account(), "60"))
    }
}
