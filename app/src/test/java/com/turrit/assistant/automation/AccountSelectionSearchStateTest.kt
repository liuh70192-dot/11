package com.turrit.assistant.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountSelectionSearchStateTest {
    @Test
    fun `repeated top signature switches direction instead of looping`() {
        val state = AccountSelectionSearchState()

        assertEquals(AccountSelectionAction.SWIPE_TOWARD_TOP, state.observe("page-a"))
        assertEquals(AccountSelectionAction.SWITCH_TO_BOTTOM, state.observe("page-a"))
        assertEquals(AccountSelectionAction.SWIPE_TOWARD_BOTTOM, state.observe("page-b"))
    }

    @Test
    fun `top search is bounded even when every swipe changes the screen`() {
        val state = AccountSelectionSearchState(maxTopSwipes = 2, maxBottomSwipes = 3)

        assertEquals(AccountSelectionAction.SWIPE_TOWARD_TOP, state.observe("a"))
        assertEquals(AccountSelectionAction.SWIPE_TOWARD_TOP, state.observe("b"))
        assertEquals(AccountSelectionAction.SWITCH_TO_BOTTOM, state.observe("c"))
    }

    @Test
    fun `repeated bottom signature ends lookup`() {
        val state = AccountSelectionSearchState()
        state.switchToBottom()

        assertEquals(AccountSelectionAction.SWIPE_TOWARD_BOTTOM, state.observe("bottom"))
        assertEquals(AccountSelectionAction.NOT_FOUND, state.observe("bottom"))
    }

    @Test
    fun `reset starts a fresh top-first search`() {
        val state = AccountSelectionSearchState()
        state.switchToBottom()
        state.reset()

        assertEquals(AccountSelectionAction.SWIPE_TOWARD_TOP, state.observe("new-list"))
    }
}
