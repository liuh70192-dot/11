package com.turrit.assistant.account

import com.turrit.assistant.account.model.TelegramAccount

/**
 * Keeps a detected account's existing identity and metadata when its visible
 * Telegram profile name changes.
 */
object AccountNameSync {
    fun withVerifiedDisplayName(
        account: TelegramAccount,
        rawName: String?
    ): TelegramAccount? {
        val name = normalizeVerifiedName(rawName) ?: return null
        if (name == account.displayName.trim()) return null
        return account.copy(displayName = name)
    }

    /**
     * A profile heading can safely label a phone record only when the current
     * accessibility tree contains exactly one phone-backed account.
     */
    fun bindVerifiedProfileName(
        accounts: List<TelegramAccount>,
        rawName: String?
    ): List<TelegramAccount> {
        if (accounts.size != 1) return accounts
        val name = normalizeVerifiedName(rawName) ?: return accounts
        return listOf(accounts.single().copy(displayName = name))
    }

    private fun normalizeVerifiedName(rawName: String?): String? {
        val name = rawName
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            .orEmpty()
        return name.takeIf(AccountNameRules::isValid)
    }
}
