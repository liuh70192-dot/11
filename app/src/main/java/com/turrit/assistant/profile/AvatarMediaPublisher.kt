package com.turrit.assistant.profile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.turrit.assistant.profile.model.AvatarAsset
import java.io.File
import java.io.IOException

/** Makes an imported avatar visible to Turrit's normal system/gallery photo picker. */
class AvatarMediaPublisher {
    fun publish(context: Context, asset: AvatarAsset): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("头像自动导入需要 Android 10 或更高版本")
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, asset.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, asset.mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/TurritAssistant"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法将头像写入系统图库")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                File(asset.localPath).inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入头像文件")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            return uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
