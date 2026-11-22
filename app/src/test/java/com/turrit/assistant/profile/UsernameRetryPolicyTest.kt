package com.turrit.assistant.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsernameRetryPolicyTest {
    @Test
    fun `normalizes telegram username links and at prefix`() {
        assertEquals("sample_name", UsernameRetryPolicy.normalize("https://t.me/sample_name"))
        assertEquals("sample_name", UsernameRetryPolicy.normalize("@sample_name"))
    }

    @Test
    fun `appends one random letter or digit after collision`() {
        val next = UsernameRetryPolicy.appendSuffix("sample", '7')
        assertEquals("sample7", next)
    }

    @Test
    fun `rejects usernames outside telegram length and character rules`() {
        assertFalse(UsernameRetryPolicy.isValid("abc"))
        assertFalse(UsernameRetryPolicy.isValid("has space"))
        assertFalse(UsernameRetryPolicy.isValid("x".repeat(33)))
    }

    @Test
    fun `does not exceed the maximum username length`() {
        assertNull(UsernameRetryPolicy.appendSuffix("x".repeat(32), 'a'))
        assertEquals("x".repeat(31) + "a", UsernameRetryPolicy.appendSuffix("x".repeat(31), 'a'))
    }

    @Test
    fun `validates normal username`() {
        assertTrue(UsernameRetryPolicy.isValid("sample_2026"))
    }
}