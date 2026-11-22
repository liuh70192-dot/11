package com.turrit.assistant.profile

import java.security.SecureRandom

/** Telegram username validation and collision retry policy. */
object UsernameRetryPolicy {
    const val MIN_LENGTH = 5
    const val MAX_LENGTH = 32
    private const val RANDOM_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun normalize(raw: String): String {
        var value = raw.trim()
        val prefixes = listOf("https://t.me/", "http://t.me/", "t.me/")
        val prefix = prefixes.firstOrNull { value.startsWith(it, ignoreCase = true) }
        if (prefix != null) value = value.substring(prefix.length)
        if (value.startsWith("@")) value = value.drop(1)
        return value.trim()
    }

    fun isValid(value: String): Boolean =
        value.length in MIN_LENGTH..MAX_LENGTH &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

    fun appendRandomSuffix(value: String, random: SecureRandom = SecureRandom()): String? {
        if (!isValid(value) || value.length >= MAX_LENGTH) return null
        return value + RANDOM_CHARACTERS[random.nextInt(RANDOM_CHARACTERS.length)]
    }

    fun appendSuffix(value: String, suffix: Char): String? {
        if (!isValid(value) || value.length >= MAX_LENGTH) return null
        if (suffix !in 'a'..'z' && suffix !in 'A'..'Z' && suffix !in '0'..'9') return null
        return value + suffix
    }
}