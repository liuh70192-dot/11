package com.turrit.assistant.account

import java.util.LinkedHashMap
import java.util.Locale

/** Accumulates account names across multiple visible pages of the Turrit switcher. */
class AccountScanProgress {
    private val names = LinkedHashMap<String, String>()

    var expectedCount: Int? = null
        private set

    val count: Int
        get() = names.size

    fun reset(expected: Int? = null) {
        names.clear()
        expectedCount = expected?.takeIf { it > 0 }
    }

    fun setExpectedCount(expected: Int?) {
        if (expected != null && expected > 0) expectedCount = expected
    }

    fun addName(rawName: String) {
        val name = rawName.replace(Regex("""\s+"""), " ").trim()
        if (name.isEmpty()) return
        names.putIfAbsent(name.lowercase(Locale.ROOT), name)
    }

    fun addAll(rawNames: Iterable<String>) {
        rawNames.forEach(::addName)
    }

    fun snapshot(): List<String> = names.values.toList()

    fun hasReachedExpectedCount(): Boolean =
        expectedCount?.let { count == it } == true
}
