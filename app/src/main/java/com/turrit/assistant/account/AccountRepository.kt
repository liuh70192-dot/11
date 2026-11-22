package com.turrit.assistant.account

import android.content.Context
import com.turrit.assistant.account.model.TelegramAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local-only account storage shared by the app UI and the accessibility service. */
object AccountRepository {
    private val lock = Any()
    private var database: AccountDatabase? = null
    private val mutableAccounts = MutableStateFlow<List<TelegramAccount>>(emptyList())
    val accounts: StateFlow<List<TelegramAccount>> = mutableAccounts.asStateFlow()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (database == null) {
                database = AccountDatabase(context.applicationContext)
            }
            // Remove false positives saved by older builds when "添加账号" was parsed as a person.
            requireDatabase().removeInvalidNameOnlyAccounts()
            refreshLocked()
        }
    }

    fun upsertDetected(account: TelegramAccount) {
        synchronized(lock) {
            val db = requireDatabase()
            if (db.upsertDetected(account)) {
                refreshLocked()
            }
        }
    }

    fun replaceDetectedForPackage(packageName: String, detected: List<TelegramAccount>) {
        synchronized(lock) {
            requireDatabase().replaceDetectedForPackage(packageName, detected)
            refreshLocked()
        }
    }

    fun setSelected(id: String, selected: Boolean) {
        synchronized(lock) {
            requireDatabase().updateSelected(id, selected)
            refreshLocked()
        }
    }

    fun setAllSelected(selected: Boolean) {
        synchronized(lock) {
            requireDatabase().updateAllSelected(selected)
            refreshLocked()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            requireDatabase().updateEnabled(id, enabled)
            refreshLocked()
        }
    }

    private fun requireDatabase(): AccountDatabase =
        checkNotNull(database) { "AccountRepository.initialize() must be called first" }

    private fun refreshLocked() {
        mutableAccounts.value = requireDatabase().readAll()
    }
}
