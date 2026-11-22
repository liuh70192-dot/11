package com.turrit.assistant.account

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountNameRulesTest {
    @Test
    fun `accepts numeric-only display names from Turrit`() {
        assertTrue(AccountNameRules.isValid("6012222"))
        assertTrue(AccountNameRules.isValid("6011"))
    }

    @Test
    fun `accepts names containing trailing digits`() {
        assertTrue(AccountNameRules.isValid("lisettespore 60"))
    }

    @Test
    fun `rejects account controls and counter labels`() {
        assertFalse(AccountNameRules.isValid("添加账号"))
        assertFalse(AccountNameRules.isValid("5/10"))
        assertFalse(AccountNameRules.isValid("@username"))
        assertFalse(AccountNameRules.isValid("设置"))
    }
}
