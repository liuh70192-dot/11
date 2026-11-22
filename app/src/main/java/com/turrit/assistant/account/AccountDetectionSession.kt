package com.turrit.assistant.account

import android.content.Context
import com.turrit.assistant.account.model.InstalledTurritApp
import com.turrit.assistant.diagnostics.DiagnosticLog
import org.json.JSONArray
import java.util.UUID

object AccountDetectionSession {
    private const val PREFS = "account_detection_session"
    private const val KEY_ACTIVE = "active"
    private const val KEY_SCAN_ID = "scan_id"
    private const val KEY_PACKAGE = "target_package"
    private const val KEY_APP_NAME = "target_app_name"
    private const val KEY_NAVIGATION_DONE = "navigation_done"
    private const val KEY_ACCOUNT_LIST_DETECTED = "account_list_detected"
    private const val KEY_EXPECTED_ACCOUNT_COUNT = "expected_account_count"
    private const val KEY_SEEN_ACCOUNT_NAMES = "seen_account_names"
    private const val KEY_SCROLL_ATTEMPTS = "scroll_attempts"
    private const val KEY_PENDING_ERROR = "pending_error"
    private const val KEY_STATUS = "status"

    fun start(context: Context, app: InstalledTurritApp): String {
        val scanId = UUID.randomUUID().toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_SCAN_ID, scanId)
            .putString(KEY_PACKAGE, app.packageName)
            .putString(KEY_APP_NAME, app.appName)
            .putBoolean(KEY_NAVIGATION_DONE, false)
            .putBoolean(KEY_ACCOUNT_LIST_DETECTED, false)
            .putInt(KEY_EXPECTED_ACCOUNT_COUNT, 0)
            .putString(KEY_SEEN_ACCOUNT_NAMES, "[]")
            .putInt(KEY_SCROLL_ATTEMPTS, 0)
            .putString(KEY_STATUS, "正在启动 Turrit")
            .remove(KEY_PENDING_ERROR)
            .apply()
        DiagnosticLog.info(context, "识别会话", "创建新识别任务；应用=${app.appName}，package=${app.packageName}")
        return scanId
    }

    fun currentScanId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SCAN_ID, null)

    fun isCurrentScan(context: Context, scanId: String): Boolean =
        isActive(context) && currentScanId(context) == scanId

    fun stop(context: Context, scanId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (scanId != null && prefs.getString(KEY_SCAN_ID, null) != scanId) return
        DiagnosticLog.warn(context, "识别会话", "识别任务已停止")
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_NAVIGATION_DONE, false)
            .putBoolean(KEY_ACCOUNT_LIST_DETECTED, false)
            .remove(KEY_SCAN_ID)
            .remove(KEY_PACKAGE)
            .remove(KEY_APP_NAME)
            .remove(KEY_PENDING_ERROR)
            .remove(KEY_STATUS)
            .apply()
    }

    fun fail(context: Context, message: String, scanId: String? = null) =
        finish(context, message, scanId)

    fun complete(context: Context, message: String, scanId: String? = null) =
        finish(context, message, scanId)

    private fun finish(context: Context, message: String, scanId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (scanId != null && prefs.getString(KEY_SCAN_ID, null) != scanId) return
        DiagnosticLog.info(context, "识别会话", "识别任务结束；结果=$message")
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_NAVIGATION_DONE, false)
            .putBoolean(KEY_ACCOUNT_LIST_DETECTED, false)
            .remove(KEY_SCAN_ID)
            .remove(KEY_PACKAGE)
            .remove(KEY_APP_NAME)
            .remove(KEY_STATUS)
            .putString(KEY_PENDING_ERROR, message)
            .apply()
    }

    fun setStatus(context: Context, status: String, scanId: String? = null) {
        if (!isActive(context)) return
        if (scanId != null && !isCurrentScan(context, scanId)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_STATUS, "").orEmpty()
        prefs.edit().putString(KEY_STATUS, status).apply()
        if (previous != status) {
            DiagnosticLog.info(context, "识别进度", status)
        }
    }

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, "")
            .orEmpty()

    fun markNavigationDone(context: Context) {
        if (!isActive(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NAVIGATION_DONE, true)
            .apply()
    }

    fun isNavigationDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NAVIGATION_DONE, false)

    fun markAccountListDetected(context: Context, expectedCount: Int?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCOUNT_LIST_DETECTED, true)
        if (expectedCount != null && expectedCount > 0) {
            editor.putInt(KEY_EXPECTED_ACCOUNT_COUNT, expectedCount)
        }
        editor.apply()
    }

    fun isAccountListDetected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACCOUNT_LIST_DETECTED, false)

    fun expectedAccountCount(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_EXPECTED_ACCOUNT_COUNT, 0)
            .takeIf { it > 0 }

    fun addSeenAccountNames(context: Context, names: List<String>): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val combined = linkedSetOf<String>()
        runCatching {
            val old = JSONArray(prefs.getString(KEY_SEEN_ACCOUNT_NAMES, "[]"))
            for (index in 0 until old.length()) {
                old.optString(index).takeIf(String::isNotBlank)?.let(combined::add)
            }
        }
        names.map(String::trim).filter(String::isNotBlank).forEach(combined::add)
        prefs.edit()
            .putString(KEY_SEEN_ACCOUNT_NAMES, JSONArray(combined.toList()).toString())
            .apply()
        return combined.toList()
    }

    fun incrementScrollAttempts(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_SCROLL_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_SCROLL_ATTEMPTS, next).apply()
        return next
    }

    fun consumePendingError(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = prefs.getString(KEY_PENDING_ERROR, null)
        if (message != null) prefs.edit().remove(KEY_PENDING_ERROR).apply()
        return message
    }

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACTIVE, false)

    fun targetPackage(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_ACTIVE, false)) prefs.getString(KEY_PACKAGE, null) else null
    }

    fun targetAppName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP_NAME, null)
}
