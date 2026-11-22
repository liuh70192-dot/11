package com.turrit.assistant.profile

import com.turrit.assistant.profile.model.ProfilePreview

/** Starts a previewed, ordered profile task. UI automation is delegated to the accessibility service. */
class ProfileExecutor {
    fun start(preview: ProfilePreview) {
        ProfileExecutionSession.start(preview)
    }

    fun pause() = ProfileExecutionSession.pause()
    fun resume() = ProfileExecutionSession.resume()
    fun stop() = ProfileExecutionSession.stop()
}
