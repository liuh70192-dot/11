package com.turrit.assistant.account

import android.content.Context

/** Remembers apps the user manually confirmed as Turrit variants. */
object TurritAppRegistry {
    private const val PREFS = "turrit_app_registry"
    private const val KEY_MANUAL_PACKAGES = "manual_packages"

    fun manualPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_MANUAL_PACKAGES, emptySet())
            ?.toSet()
            .orEmpty()

    fun add(context: Context, packageName: String) {
        val updated = manualPackages(context) + packageName
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MANUAL_PACKAGES, updated)
            .apply()
    }
}
