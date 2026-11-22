package com.turrit.assistant.profile

import com.turrit.assistant.profile.model.AvatarAsset
import java.io.File

/** Deletes only directories created by AvatarImporter after the profile task is finished. */
object AvatarTempFileCleaner {
    fun cleanup(assets: Iterable<AvatarAsset>) {
        val directories = assets.mapNotNull { asset ->
            val explicit = asset.temporaryDirectoryPath?.let(::File)
            val fallback = File(asset.localPath).parentFile
            (explicit ?: fallback)?.takeIf {
                it.name.startsWith("profile-avatars-") && it.isDirectory
            }
        }.distinctBy { it.absolutePath }

        directories.forEach { directory ->
            runCatching { directory.deleteRecursively() }
        }
    }
}