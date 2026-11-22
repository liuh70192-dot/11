package com.turrit.assistant.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountScanProgressTest {
    @Test
    fun `deduplicates names across visible pages without changing first-seen order`() {
        val progress = AccountScanProgress()
        progress.addAll(listOf("David Reeder", "Jennifer Chalifour"))
        progress.addAll(listOf("david reeder", "Lloyd Schoof"))

        assertEquals(3, progress.count)
        assertEquals(
            listOf("David Reeder", "Jennifer Chalifour", "Lloyd Schoof"),
            progress.snapshot()
        )
    }

    @Test
    fun `does not complete until the unique account count reaches the displayed total`() {
        val progress = AccountScanProgress()
        progress.reset(expected = 5)
        progress.addAll(listOf("David Reeder", "Jennifer Chalifour", "David Reeder"))

        assertEquals(2, progress.count)
        assertFalse(progress.hasReachedExpectedCount())

        progress.addAll(listOf("Lloyd Schoof", "Ronald Hazelip", "Shaurya Pawar"))
        assertTrue(progress.hasReachedExpectedCount())
    }

    @Test
    fun `accepts expected count when it becomes visible after scanning starts`() {
        val progress = AccountScanProgress()
        progress.addAll(listOf("One", "Two"))
        progress.setExpectedCount(2)

        assertTrue(progress.hasReachedExpectedCount())
    }

    @Test
    fun `does not complete when the scan contains more names than Turrit reports`() {
        val progress = AccountScanProgress()
        progress.reset(expected = 2)
        progress.addAll(listOf("One", "Two", "Unrelated menu label"))

        assertFalse(progress.hasReachedExpectedCount())
    }
}
