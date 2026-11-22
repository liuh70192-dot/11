package com.turrit.assistant.account

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.turrit.assistant.account.model.TelegramAccount
import java.util.Locale

internal class AccountDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_ACCOUNTS (
                id TEXT PRIMARY KEY NOT NULL,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                phone_number TEXT NOT NULL,
                display_name TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '',
                is_selected INTEGER NOT NULL DEFAULT 0,
                is_enabled INTEGER NOT NULL DEFAULT 1,
                last_seen_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep existing account records when the schema is extended in a later version.
    }

    fun removeInvalidNameOnlyAccounts(): Int = writableDatabase.delete(
        TABLE_ACCOUNTS,
        "phone_number = '' AND LOWER(TRIM(display_name)) IN (?, ?, ?, ?)",
        arrayOf("添加账号", "添加账户", "add account", "add an account")
    )

    fun readAll(): List<TelegramAccount> {
        val result = mutableListOf<TelegramAccount>()
        readableDatabase.query(
            TABLE_ACCOUNTS, null, null, null, null, null,
            "app_name COLLATE NOCASE ASC, display_name COLLATE NOCASE ASC, phone_number COLLATE NOCASE ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toTelegramAccount()
        }
        return result
    }

    fun findById(id: String): TelegramAccount? = readableDatabase.query(
        TABLE_ACCOUNTS, null, "id = ?", arrayOf(id), null, null, null
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toTelegramAccount() else null
    }

    private fun findNameOnlyByPackageAndName(packageName: String, displayName: String): TelegramAccount? =
        readableDatabase.query(
            TABLE_ACCOUNTS, null,
            "package_name = ? AND display_name = ? COLLATE NOCASE AND phone_number = ''",
            arrayOf(packageName, displayName), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTelegramAccount() else null
        }

    private fun findPhoneAccountByPackageAndName(packageName: String, displayName: String): TelegramAccount? =
        readableDatabase.query(
            TABLE_ACCOUNTS, null,
            "package_name = ? AND display_name = ? COLLATE NOCASE AND phone_number != ''",
            arrayOf(packageName, displayName), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toTelegramAccount() else null
        }

    fun upsertDetected(account: TelegramAccount): Boolean {
        val exact = findById(account.id)
        val phoneMatch = if (account.phoneNumber.isBlank() && account.displayName.isNotBlank()) {
            findPhoneAccountByPackageAndName(account.packageName, account.displayName)
        } else null
        if (exact == null && phoneMatch != null) return false

        val nameOnlyMatch = if (account.phoneNumber.isNotBlank() && account.displayName.isNotBlank()) {
            findNameOnlyByPackageAndName(account.packageName, account.displayName)
        } else null
        val previous = exact ?: nameOnlyMatch
        val merged = account.copy(
            phoneNumber = account.phoneNumber.ifBlank { previous?.phoneNumber.orEmpty() },
            isSelected = previous?.isSelected ?: account.isSelected,
            isEnabled = previous?.isEnabled ?: account.isEnabled,
            displayName = account.displayName.ifBlank { previous?.displayName.orEmpty() },
            username = account.username.ifBlank { previous?.username.orEmpty() }
        )

        if (previous != null &&
            previous.appName == merged.appName &&
            previous.phoneNumber == merged.phoneNumber &&
            previous.displayName == merged.displayName &&
            previous.username == merged.username
        ) return false

        if (previous != null && previous.id != merged.id) {
            writableDatabase.delete(TABLE_ACCOUNTS, "id = ?", arrayOf(previous.id))
        }
        writableDatabase.insertWithOnConflict(
            TABLE_ACCOUNTS, null, merged.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE
        )
        return true
    }

    fun replaceDetectedForPackage(packageName: String, detected: List<TelegramAccount>) {
        val current = detected.filter { it.packageName == packageName }
        if (current.isEmpty()) return

        val existing = readAll().filter { it.packageName == packageName }
        val byName = existing.filter { it.displayName.isNotBlank() }
            .associateBy { normalizeName(it.displayName) }
        val byPhone = existing.filter { it.phoneNumber.isNotBlank() }
            .associateBy { it.phoneNumber }
        val db = writableDatabase

        db.beginTransaction()
        try {
            db.delete(TABLE_ACCOUNTS, "package_name = ?", arrayOf(packageName))
            current.forEach { account ->
                val previous = account.phoneNumber.takeIf { it.isNotBlank() }?.let(byPhone::get)
                    ?: account.displayName.takeIf { it.isNotBlank() }?.let { byName[normalizeName(it)] }
                val id = if (
                    account.phoneNumber.isBlank() &&
                    previous != null &&
                    previous.phoneNumber.isNotBlank()
                ) {
                    previous.id
                } else {
                    account.id
                }
                val merged = account.copy(
                    id = id,
                    appName = account.appName.ifBlank { previous?.appName.orEmpty() },
                    phoneNumber = account.phoneNumber.ifBlank { previous?.phoneNumber.orEmpty() },
                    displayName = account.displayName.ifBlank { previous?.displayName.orEmpty() },
                    username = account.username.ifBlank { previous?.username.orEmpty() },
                    isSelected = previous?.isSelected ?: account.isSelected,
                    isEnabled = previous?.isEnabled ?: account.isEnabled
                )
                db.insertWithOnConflict(
                    TABLE_ACCOUNTS, null, merged.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun normalizeName(value: String): String =
        value.trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

    fun updateSelected(id: String, selected: Boolean) {
        writableDatabase.update(
            TABLE_ACCOUNTS,
            ContentValues().apply { put("is_selected", if (selected) 1 else 0) },
            "id = ?", arrayOf(id)
        )
    }

    fun updateAllSelected(selected: Boolean) {
        writableDatabase.execSQL("UPDATE $TABLE_ACCOUNTS SET is_selected = ?", arrayOf(if (selected) 1 else 0))
    }

    fun updateEnabled(id: String, enabled: Boolean) {
        writableDatabase.update(
            TABLE_ACCOUNTS,
            ContentValues().apply { put("is_enabled", if (enabled) 1 else 0) },
            "id = ?", arrayOf(id)
        )
    }

    private fun android.database.Cursor.toTelegramAccount() = TelegramAccount(
        id = getString(getColumnIndexOrThrow("id")),
        packageName = getString(getColumnIndexOrThrow("package_name")),
        appName = getString(getColumnIndexOrThrow("app_name")),
        phoneNumber = getString(getColumnIndexOrThrow("phone_number")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        username = getString(getColumnIndexOrThrow("username")),
        isSelected = getInt(getColumnIndexOrThrow("is_selected")) == 1,
        isEnabled = getInt(getColumnIndexOrThrow("is_enabled")) == 1,
        lastSeenAt = getLong(getColumnIndexOrThrow("last_seen_at"))
    )

    private fun TelegramAccount.toContentValues() = ContentValues().apply {
        put("id", id)
        put("package_name", packageName)
        put("app_name", appName)
        put("phone_number", phoneNumber)
        put("display_name", displayName)
        put("username", username)
        put("is_selected", if (isSelected) 1 else 0)
        put("is_enabled", if (isEnabled) 1 else 0)
        put("last_seen_at", lastSeenAt)
    }

    companion object {
        private const val DATABASE_NAME = "turrit_assistant.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_ACCOUNTS = "telegram_accounts"
    }
}
