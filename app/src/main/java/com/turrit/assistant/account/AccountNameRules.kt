package com.turrit.assistant.account

/**
 * Account-name filter copied from active-jisou's validAccountName().
 *
 * Numeric-only display names are valid Telegram account names; they must not be
 * rejected by the phone-number detector used for extracting phone metadata.
 */
object AccountNameRules {
    private val accountCounterPattern = Regex("""\d+\s*/\s*\d+""")

    fun isValid(rawValue: String): Boolean {
        val value = rawValue.trim()
        if (value.isEmpty() || value.length > 40 || value.contains("\n") || value.startsWith("@")) {
            return false
        }
        if (accountCounterPattern.matches(value) || value.contains("添加账号")) {
            return false
        }
        return when (value) {
            "我的", "Me", "添加账号", "Add Account",
            "聊天", "联系人", "发现", "下载", "缓存",
            "实用工具", "收藏夹", "设置", "客服" -> false
            else -> true
        }
    }
}
