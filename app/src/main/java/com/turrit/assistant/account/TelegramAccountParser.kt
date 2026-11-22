package com.turrit.assistant.account

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.turrit.assistant.account.model.TelegramAccount
import java.util.Locale

data class AccountSwitcherSnapshot(
    val accounts: List<TelegramAccount>,
    val expectedCount: Int?,
    val listTop: Int,
    val addAccountTop: Int,
    val lastRowCenterY: Int,
    val visibleSignature: String,
    val firstRowCenterY: Int = -1
)

/** Turrit account parser. The Add Account row is the lower boundary, never an account. */
object TelegramAccountParser {
    private val phonePattern = Regex("""(?<![A-Za-z0-9])\+?\d[\d\s().-]{5,}\d(?![A-Za-z0-9])""")
    private val usernamePattern = Regex("""@([A-Za-z0-9_]{4,32})""")
    private val accountCounterPattern = Regex("""^(\d{1,2})\s*/\s*10$""")
    private val addAccountLabels = setOf("添加账号", "添加账户", "add account", "add an account")
    private val ignoredLabels = setOf(
        "我的", "me", "聊天", "chats", "联系人", "contacts", "发现", "discover",
        "下载", "downloads", "缓存", "cache", "实用工具", "tools", "收藏夹", "favorites",
        "设置", "settings", "客服", "help", "账号", "账户", "账号列表", "账号管理",
        "切换账号", "切换账户", "个人资料", "我的资料", "编辑资料", "退出登录",
        "登录", "返回", "取消", "确定", "搜索", "telegram", "turrit", "account",
        "accounts", "profile", "my profile", "saved messages", "switch account",
        "cancel", "done", "back", "在线", "online"
    ) + addAccountLabels

    private data class TextEntry(
        val value: String,
        val node: AccessibilityNodeInfo,
        val bounds: Rect
    )

    fun parse(
        root: AccessibilityNodeInfo,
        packageName: String,
        appName: String,
        verifiedProfileName: String? = null
    ): List<TelegramAccount> {
        val accounts = linkedMapOf<String, TelegramAccount>()
        visitPhones(root, packageName, appName, accounts)

        // The phone node's parent can expose several neighbouring labels (bio,
        // username, buttons). If exactly one phone account is present, bind it to
        // the separately verified large profile heading instead of guessing from
        // nearby text. With multiple phone candidates, keep the original parser
        // output because a single heading cannot safely identify all of them.
        return AccountNameSync.bindVerifiedProfileName(
            accounts.values.toList(),
            verifiedProfileName
        )
    }

    fun parseVisibleAccountSwitcher(
        root: AccessibilityNodeInfo,
        packageName: String,
        appName: String,
        screenWidth: Int,
        screenHeight: Int
    ): AccountSwitcherSnapshot? {
        if (screenWidth <= 0 || screenHeight <= 0) return null

        val entries = mutableListOf<TextEntry>()
        collectTextLeaves(root, entries)

        val addEntry = entries
            .filter { entry ->
                val value = normalize(entry.value).lowercase(Locale.ROOT)
                addAccountLabels.any { label -> value == label || value.contains(label) }
            }
            .maxByOrNull { it.bounds.top }

        val counterEntry = entries.firstOrNull { entry ->
            val rect = entry.bounds
            accountCounterPattern.matches(normalize(entry.value)) &&
                rect.centerX() > screenWidth * 0.70f &&
                rect.centerY() > screenHeight * 0.25f &&
                rect.centerY() < screenHeight * 0.83f
        }

        // Match active-jisou: only @username establishes the lower edge of the heading area.
        val headerName = profileHeaderLabel(entries, screenWidth, screenHeight)
        val headerEntry = headerName?.let { name ->
            entries.asSequence()
                .filter {
                    normalize(it.value).equals(name, ignoreCase = true) &&
                        it.bounds.centerY() in (screenHeight * 0.135f).toInt()..(screenHeight * 0.295f).toInt() &&
                        it.bounds.height() >= screenHeight * 0.031f
                }
                .maxByOrNull { it.bounds.height() }
        }

        // Faithful port of active-jisou accountSnapshot():
        // the visible @username node defines the lower edge of the profile heading.
        // Keep this rule identical in discovery and target-account row lookup.
        var listTop = (screenHeight * 0.085f).toInt()
        entries.forEach { entry ->
            if (normalize(entry.value).matches(Regex("""@[A-Za-z0-9_]{3,32}""")) &&
                entry.bounds.centerY() < screenHeight * 0.34f
            ) {
                listTop = maxOf(
                    listTop,
                    entry.bounds.bottom + (screenHeight * 0.025f).toInt()
                )
            }
        }

        // Require a real list boundary; do not infer accounts from profile/menu text.
        if (addEntry == null && counterEntry == null) return null

        val boundaryTop = addEntry?.bounds?.top ?: counterEntry?.bounds?.top ?: return null
        val expectedCount = counterEntry?.let {
            accountCounterPattern.matchEntire(normalize(it.value))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 10)
        }

        val rows = entries.asSequence()
            .filter { entry ->
                val rect = entry.bounds
                rect.width() > 0 && rect.height() > 0 &&
                    rect.centerY() > listTop &&
                    rect.centerY() < boundaryTop - screenHeight * 0.010f &&
                    rect.centerX() > screenWidth * 0.13f &&
                    rect.centerX() < screenWidth * 0.80f &&
                    isValidAccountName(entry.value)
            }
            .sortedWith(compareBy<TextEntry>({ it.bounds.centerY() }, { it.bounds.left }))
            .toList()

        val accounts = linkedMapOf<String, TelegramAccount>()
        fun addName(rawName: String) {
            val name = normalize(rawName)
            if (!isValidAccountName(name)) return
            val key = name.lowercase(Locale.ROOT)
            val stableKey = key.replace(Regex("""[^\p{L}\p{N}]+"""), "_")
            accounts.putIfAbsent(
                key,
                TelegramAccount(
                    id = "$packageName:name:$stableKey",
                    packageName = packageName,
                    appName = appName,
                    phoneNumber = "",
                    displayName = name,
                    username = ""
                )
            )
        }

        headerName?.let(::addName)
        val visibleRows = mutableListOf<String>()
        var firstRowCenterY = -1
        var lastRowCenterY = -1
        rows.forEach { entry ->
            val name = normalize(entry.value)
            addName(name)
            visibleRows += name + "@" + (entry.bounds.centerY() / 18)
            if (firstRowCenterY < 0 || entry.bounds.centerY() < firstRowCenterY) {
                firstRowCenterY = entry.bounds.centerY()
            }
            lastRowCenterY = maxOf(lastRowCenterY, entry.bounds.centerY())
        }

        return AccountSwitcherSnapshot(
            accounts = accounts.values.toList(),
            expectedCount = expectedCount,
            listTop = listTop,
            addAccountTop = boundaryTop,
            lastRowCenterY = lastRowCenterY,
            visibleSignature = visibleRows.distinct().joinToString("|") + "|$boundaryTop",
            firstRowCenterY = firstRowCenterY
        )
    }

    /** Returns only the large current-account heading, never a similarly named switcher row. */
    fun profileHeaderName(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): String? {
        val entries = mutableListOf<TextEntry>()
        collectTextLeaves(root, entries)
        return profileHeaderLabel(entries, screenWidth, screenHeight)
    }

    /** Finds the clickable text node for the verified current-account heading. */
    fun findProfileHeaderNode(
        root: AccessibilityNodeInfo,
        targetName: String,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val entries = mutableListOf<TextEntry>()
        collectTextLeaves(root, entries)
        val header = profileHeaderLabel(entries, screenWidth, screenHeight) ?: return null
        if (!header.equals(normalize(targetName), ignoreCase = true)) return null

        return entries.asSequence()
            .filter { entry ->
                val rect = entry.bounds
                normalize(entry.value).equals(targetName.trim(), ignoreCase = true) &&
                    rect.left >= screenWidth * 0.18f &&
                    rect.centerX() <= screenWidth * 0.83f &&
                    rect.centerY() >= screenHeight * 0.135f &&
                    rect.centerY() <= screenHeight * 0.295f &&
                    rect.height() >= screenHeight * 0.020f
            }
            .maxByOrNull { it.bounds.height() }
            ?.node
    }

    /** Matches an exact account row strictly between the profile header and Add Account/count boundary. */
    fun findAccountRow(
        root: AccessibilityNodeInfo,
        targetName: String,
        snapshot: AccountSwitcherSnapshot,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val entries = mutableListOf<TextEntry>()
        collectTextLeaves(root, entries)
        return entries.asSequence()
            .filter { entry ->
                val rect = entry.bounds
                normalize(entry.value).equals(targetName.trim(), ignoreCase = true) &&
                    isValidAccountName(entry.value) &&
                    rect.centerY() > snapshot.listTop &&
                    rect.centerY() < snapshot.addAccountTop - screenHeight * 0.010f &&
                    rect.centerX() > screenWidth * 0.13f &&
                    rect.centerX() < screenWidth * 0.80f
            }
            .maxByOrNull { it.bounds.height() }
            ?.node
    }

    fun hasProfileHeader(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Boolean {
        val entries = mutableListOf<TextEntry>()
        collectTextLeaves(root, entries)
        return profileHeaderLabel(entries, screenWidth, screenHeight) != null
    }

    private fun profileHeaderLabel(
        entries: List<TextEntry>,
        screenWidth: Int,
        screenHeight: Int
    ): String? {
        val username = entries.firstOrNull { entry ->
            normalize(entry.value).matches(Regex("""@[A-Za-z0-9_]{3,32}""")) &&
                entry.bounds.centerY() > screenHeight * 0.10f &&
                entry.bounds.centerY() < screenHeight * 0.34f &&
                entry.bounds.centerX() < screenWidth * 0.79f
        }

        if (username != null) {
            val userY = username.bounds.centerY()
            val selected = entries.asSequence()
                .filter { entry ->
                    val distance = userY - entry.bounds.centerY()
                    distance > 0 && distance < screenHeight * 0.10f &&
                        entry.bounds.centerX() > screenWidth * 0.18f &&
                        entry.bounds.centerX() < screenWidth * 0.75f &&
                        isValidAccountName(entry.value)
                }
                .minByOrNull { userY - it.bounds.centerY() }
            if (selected != null) return normalize(selected.value)
        }

        return entries.asSequence()
            .filter { entry ->
                val rect = entry.bounds
                isValidAccountName(entry.value) &&
                    rect.left >= screenWidth * 0.265f &&
                    rect.centerX() <= screenWidth * 0.83f &&
                    rect.centerY() >= screenHeight * 0.135f &&
                    rect.centerY() <= screenHeight * 0.295f &&
                    rect.height() >= screenHeight * 0.031f &&
                    (entry.node.className?.toString()?.contains("TextView") == true ||
                        entry.node.childCount == 0)
            }
            .maxByOrNull {
                it.bounds.height() * 4f -
                    kotlin.math.abs(it.bounds.centerY() - screenHeight * 0.20f)
            }
            ?.value?.let(::normalize)
    }

    private fun visitPhones(
        node: AccessibilityNodeInfo,
        packageName: String,
        appName: String,
        output: MutableMap<String, TelegramAccount>
    ) {
        val visibleTexts = listOfNotNull(
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty),
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)
        ).distinct()

        visibleTexts.forEach { text ->
            phonePattern.findAll(text).forEach { match ->
                val rawPhone = match.value.trim()
                val digits = rawPhone.filter(Char::isDigit)
                val hasPrefix = rawPhone.startsWith("+") || rawPhone.startsWith("00")
                val hasFormatting = rawPhone.any {
                    it.isWhitespace() || it == '-' || it == '(' || it == ')'
                }
                if (digits.length in 7..15 &&
                    (hasPrefix || hasFormatting || digits.length >= 10)
                ) {
                    val phone = if (rawPhone.startsWith("+")) "+$digits" else digits
                    val allTexts = (listOf(text) + nearbyTexts(node)).distinct()
                    val displayName = findDisplayName(allTexts, rawPhone)
                    val username = allTexts.asSequence()
                        .mapNotNull { usernamePattern.find(it)?.groupValues?.getOrNull(1) }
                        .firstOrNull().orEmpty()
                    val id = "$packageName:$phone"
                    output[id] = TelegramAccount(
                        id = id,
                        packageName = packageName,
                        appName = appName,
                        phoneNumber = phone,
                        displayName = displayName,
                        username = username
                    )
                }
            }
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { visitPhones(it, packageName, appName, output) }
        }
    }

    private fun collectTextLeaves(node: AccessibilityNodeInfo, output: MutableList<TextEntry>) {
        val className = node.className?.toString().orEmpty()
        val isTextNode = node.childCount == 0 || className.contains("TextView")
        if (node.isVisibleToUser && isTextNode) {
            // Same precedence as active-jisou: text first, contentDescription only as fallback.
            val value = node.text?.toString()?.takeIf(String::isNotBlank)
                ?: node.contentDescription?.toString()?.takeIf(String::isNotBlank)
            if (value != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    output += TextEntry(normalize(value), node, bounds)
                }
            }
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectTextLeaves(it, output) }
        }
    }

    private fun isValidAccountName(rawValue: String): Boolean =
        AccountNameRules.isValid(normalize(rawValue))

    private fun normalize(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim()

    private fun nearbyTexts(node: AccessibilityNodeInfo): List<String> {
        val collected = mutableListOf<String>()
        var parent = node.parent
        repeat(3) {
            val current = parent ?: return@repeat
            collectText(current, collected)
            parent = current.parent
        }
        return collected.distinct()
    }

    private fun collectText(node: AccessibilityNodeInfo, output: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
        node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(output::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectText(it, output) }
        }
    }

    private fun findDisplayName(texts: List<String>, phone: String): String =
        texts.asSequence()
            .flatMap { it.lines().asSequence() }
            .map { it.replace(phone, "").trim().trim('-', '·', '|', ':', '：', ' ') }
            .firstOrNull { line ->
                line.length in 2..32 &&
                    line.any(Char::isLetter) &&
                    !line.contains('@') &&
                    !line.equals("未命名", ignoreCase = true) &&
                    line.lowercase(Locale.ROOT) !in ignoredLabels &&
                    phonePattern.containsMatchIn(line).not()
            }.orEmpty()
}
