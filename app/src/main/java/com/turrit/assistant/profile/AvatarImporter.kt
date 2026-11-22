package com.turrit.assistant.profile

import android.content.Context
import android.net.Uri
import com.turrit.assistant.profile.model.AvatarAsset
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

/** Extracts supported image files in their original ZIP entry order. */
class AvatarImporter {
    fun extract(context: Context, uri: Uri): List<AvatarAsset> {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开头像 ZIP")
        val folder = File(context.cacheDir, "profile-avatars-${System.currentTimeMillis()}").apply {
            if (!mkdirs()) throw IOException("无法创建头像临时目录")
        }
        val result = mutableListOf<AvatarAsset>()
        var totalBytes = 0L
        try {
            input.use { source ->
            ZipInputStream(source).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val safeName = entry.name.substringAfterLast('/').trim()
                        val extension = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val mime = when (extension) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "webp" -> "image/webp"
                            else -> null
                        }
                        if (mime != null && safeName.isNotBlank() &&
                            !safeName.startsWith(".") && !safeName.startsWith("__MACOSX")
                        ) {
                            val target = File(folder, "${result.size.toString().padStart(4, '0')}_${safeName}")
                            var entryBytes = 0L
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    entryBytes += read
                                    totalBytes += read
                                    if (entryBytes > MAX_IMAGE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                        target.delete()
                                        throw IOException("头像压缩包过大，单张最多 20 MB，总计最多 100 MB")
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                            if (entryBytes > 0) {
                                result += AvatarAsset(
                                    fileName = safeName,
                                    localPath = target.absolutePath,
                                    mimeType = mime,
                                    temporaryDirectoryPath = folder.absolutePath
                                )
                            } else target.delete()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            }
            if (result.isEmpty()) throw IOException("ZIP 中没有 JPG、PNG 或 WEBP 图片")
            return result
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
    }
}
