package com.turrit.assistant.diagnostics

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small, private, rotating diagnostic log for support and troubleshooting.
 * Logs are stored in app-internal storage and exported only after the user taps
 * the export button. Never log OTPs, passwords, session strings or bot tokens.
 */
object DiagnosticLog {
    private const val CURRENT_FILE = "turrit-diagnostic.log"
    private const val PREVIOUS_FILE = "turrit-diagnostic.previous.log"
    private const val MAX_FILE_BYTES = 1_500_000L
    private val lock = Any()
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }

    @JvmStatic
    fun info(context: Context, stage: String, message: String) =
        write(context, "INFO", stage, message, null)

    @JvmStatic
    fun warn(context: Context, stage: String, message: String) =
        write(context, "WARN", stage, message, null)

    @JvmStatic
    fun error(context: Context, stage: String, message: String, error: Throwable?) =
        write(context, "ERROR", stage, message, error)

    private fun write(
        context: Context,
        level: String,
        stage: String,
        message: String,
        error: Throwable?
    ) {
        synchronized(lock) {
            runCatching {
                val directory = File(context.applicationContext.filesDir, "diagnostics")
                if (!directory.exists()) directory.mkdirs()
                val current = File(directory, CURRENT_FILE)
                val previous = File(directory, PREVIOUS_FILE)
                val timestamp = timestampFormat.get().format(Date())
                val line = buildString {
                    append(timestamp)
                    append(" [").append(level).append("] [")
                    append(sanitize(stage)).append("] ")
                    append(sanitize(message))
                    append('\n')
                    if (error != null) {
                        append(sanitize(error.stackTraceToString()))
                        append('\n')
                    }
                }
                val bytes = line.toByteArray(StandardCharsets.UTF_8)
                if (current.exists() && current.length() + bytes.size > MAX_FILE_BYTES) {
                    if (previous.exists()) previous.delete()
                    current.renameTo(previous)
                }
                current.appendBytes(bytes)
            }.onFailure { error ->
                Log.e("TurritDiagnosticLog", "无法写入内部诊断日志", error)
            }
        }
    }

    /** Writes the current and previous log files to a user-selected document. */
    @JvmStatic
    fun export(context: Context, destination: Uri): Int {
        synchronized(lock) {
            val directory = File(context.applicationContext.filesDir, "diagnostics")
            val previous = File(directory, PREVIOUS_FILE)
            val current = File(directory, CURRENT_FILE)
            val output = context.contentResolver.openOutputStream(destination, "wt")
                ?: error("无法打开导出目标")
            output.use { stream ->
                OutputStreamWriter(stream, StandardCharsets.UTF_8).use { writer ->
                    writer.appendLine("TURRIT 任务助手运行日志")
                    writer.appendLine("导出时间：${timestampFormat.get().format(Date())}")
                    writer.appendLine("说明：手机号、验证码、密码及 Token 会进行脱敏；日志仅包含应用记录的关键步骤。")
                    writer.appendLine("=".repeat(64))
                    if (previous.exists()) {
                        previous.inputStream().bufferedReader(StandardCharsets.UTF_8).use { it.copyTo(writer) }
                    }
                    if (current.exists()) {
                        current.inputStream().bufferedReader(StandardCharsets.UTF_8).use { it.copyTo(writer) }
                    }
                    if (!previous.exists() && !current.exists()) {
                        writer.appendLine("（暂无运行日志；请先执行一次操作，再导出日志。）")
                    }
                }
            }
            return (if (previous.exists()) previous.length() else 0L)
                .plus(if (current.exists()) current.length() else 0L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private fun sanitize(value: String): String {
        var result = value
        result = result.replace(Regex("\\b\\d{5,}:[A-Za-z0-9_-]{20,}\\b"), "[TOKEN已脱敏]")
        result = result.replace(
            Regex("(?i)(验证码|二级密码|密码|password|token)\\s*[:=：]\\s*[^\\s,，;；]+"),
            "$1=[已脱敏]"
        )
        result = result.replace(
            Regex("(?<![A-Za-z0-9])\\+?\\d[\\d ()-]{5,}\\d(?![A-Za-z0-9])"),
            "[号码已脱敏]"
        )
        return result
    }
}
