package com.turrit.assistant.account

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.turrit.assistant.account.model.InstalledTurritApp
import java.util.concurrent.TimeUnit

/** Finds Turrit-labelled launcher apps and also checks installed packages when MuMu Root is available. */
class TurritDetector(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun findInstalledTurritApps(): List<InstalledTurritApp> {
        val manualPackages = TurritAppRegistry.manualPackages(appContext)
        return findLaunchableApps().filter { app ->
            isLikelyTurrit(app) || app.packageName in manualPackages
        }.distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    /** Launcher apps visible to PackageManager plus Turrit-like packages returned by su/pm on rooted emulators. */
    @Suppress("DEPRECATION")
    fun findLaunchableApps(): List<InstalledTurritApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        val launcherApps = activities.mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val packageName = activityInfo.packageName
            if (packageName == appContext.packageName) return@mapNotNull null
            InstalledTurritApp(
                appName = resolveInfo.loadLabel(packageManager).toString(),
                packageName = packageName
            )
        }
        return (launcherApps + rootTurritPackages())
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    fun launch(app: InstalledTurritApp): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return false
        // Bring an existing background Turrit task back to its launcher activity.
        // CLEAR_TOP avoids resuming a stale nested screen; SINGLE_TOP prevents a
        // duplicate launcher activity when the task is already in front.
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        appContext.startActivity(launchIntent)
        return true
    }

    private fun rootTurritPackages(): List<InstalledTurritApp> = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm list packages -3"))
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            return@runCatching emptyList()
        }
        val packageNames = process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                line.trim().removePrefix("package:").takeIf { it.isNotBlank() }
            }.toList()
        }
        packageNames.mapNotNull { packageName ->
            if (packageName == appContext.packageName) return@mapNotNull null
            val appInfo = runCatching { packageManager.getApplicationInfo(packageName, 0) }
                .getOrNull() ?: return@mapNotNull null
            val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                .getOrDefault(packageName)
            InstalledTurritApp(label, packageName).takeIf(::isLikelyTurrit)
        }
    }.getOrDefault(emptyList())

    private fun isLikelyTurrit(app: InstalledTurritApp): Boolean =
        app.appName.contains("turrit", ignoreCase = true) ||
            app.packageName.contains("turrit", ignoreCase = true) ||
            app.packageName.equals("org.telegram.group", ignoreCase = true)
}
