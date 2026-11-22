package com.turrit.assistant.profile

import com.turrit.assistant.profile.model.AvatarAsset
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarTempFileCleanerTest {
    @Test
    fun `deletes extracted avatar directory after task`() {
        val directory = createTempDir(prefix = "profile-avatars-")
        val image = File(directory, "0000_photo.jpg")
        image.writeBytes(byteArrayOf(1, 2, 3))

        AvatarTempFileCleaner.cleanup(
            listOf(AvatarAsset("photo.jpg", image.absolutePath, "image/jpeg", directory.absolutePath))
        )

        assertFalse(directory.exists())
    }

    @Test
    fun `does not delete unrelated parent directory`() {
        val directory = createTempDir(prefix = "keep-this-")
        val image = File(directory, "photo.jpg")
        image.writeBytes(byteArrayOf(1, 2, 3))

        AvatarTempFileCleaner.cleanup(
            listOf(AvatarAsset("photo.jpg", image.absolutePath, "image/jpeg", directory.absolutePath))
        )

        assertTrue(directory.exists())
        directory.deleteRecursively()
    }
}