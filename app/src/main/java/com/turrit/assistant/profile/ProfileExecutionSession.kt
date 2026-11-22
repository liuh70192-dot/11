package com.turrit.assistant.profile

import com.turrit.assistant.profile.model.ProfileAssignment
import com.turrit.assistant.profile.model.ProfileAssignmentState
import com.turrit.assistant.profile.model.ProfilePreview
import com.turrit.assistant.profile.model.AvatarAsset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProfileRunState {
    IDLE,
    RUNNING,
    PAUSED,
    WAITING_USER,
    COMPLETED,
    STOPPED
}

data class ProfileExecutionLog(
    val accountName: String,
    val result: String,
    val detail: String
)

data class ProfileProgress(
    val state: ProfileRunState = ProfileRunState.IDLE,
    val total: Int = 0,
    val currentIndex: Int = 0,
    val currentAccount: String = "",
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val message: String = "",
    val logs: List<ProfileExecutionLog> = emptyList()
)

object ProfileExecutionSession {
    private val mutableProgress = MutableStateFlow(ProfileProgress())
    val progress: StateFlow<ProfileProgress> = mutableProgress.asStateFlow()
    private var queue: List<ProfileAssignment> = emptyList()
    private var sourceAvatars: List<AvatarAsset> = emptyList()
    private var index = 0

    @Synchronized
    fun start(preview: ProfilePreview) {
        val skipped = preview.assignments
            .filter { it.state == ProfileAssignmentState.MISSING_PROFILE }
            .map {
                ProfileExecutionLog(
                    accountName = displayName(it),
                    result = "跳过",
                    detail = "缺少 Excel 资料行"
                )
            }
        queue = preview.assignments.filter {
            it.state == ProfileAssignmentState.READY && it.row != null
        }
        sourceAvatars = preview.sourceAvatars.ifEmpty {
            preview.assignments.mapNotNull { it.avatar }.distinctBy { it.localPath }
        }
        index = 0
        mutableProgress.value = ProfileProgress(
            state = if (queue.isEmpty()) ProfileRunState.COMPLETED else ProfileRunState.RUNNING,
            total = preview.selectedAccountCount,
            currentIndex = skipped.size,
            currentAccount = queue.firstOrNull()?.let(::displayName).orEmpty(),
            skippedCount = skipped.size,
            message = if (queue.isEmpty()) "没有可执行的资料行" else "准备开始"
        ).copy(logs = skipped)
        if (queue.isEmpty()) cleanupTemporaryAvatars()
    }

    @Synchronized
    fun current(): ProfileAssignment? = queue.getOrNull(index)

    fun isActive(): Boolean = mutableProgress.value.state in setOf(
        ProfileRunState.RUNNING,
        ProfileRunState.PAUSED,
        ProfileRunState.WAITING_USER
    )

    fun isRunning(): Boolean = mutableProgress.value.state == ProfileRunState.RUNNING

    @Synchronized
    fun pause() {
        if (mutableProgress.value.state == ProfileRunState.RUNNING) {
            mutableProgress.value = mutableProgress.value.copy(
                state = ProfileRunState.PAUSED,
                message = "任务已暂停"
            )
        }
    }

    @Synchronized
    fun resume() {
        if (mutableProgress.value.state == ProfileRunState.PAUSED) {
            mutableProgress.value = mutableProgress.value.copy(
                state = ProfileRunState.RUNNING,
                message = "继续执行"
            )
        }
    }

    @Synchronized
    fun waitForUser(message: String) {
        if (mutableProgress.value.state == ProfileRunState.RUNNING) {
            mutableProgress.value = mutableProgress.value.copy(
                state = ProfileRunState.WAITING_USER,
                message = message
            )
        }
    }

    @Synchronized
    fun completeCurrent(result: String, detail: String, success: Boolean) {
        val current = current() ?: return
        val old = mutableProgress.value
        index++
        val next = queue.getOrNull(index)
        val logs = (old.logs + ProfileExecutionLog(displayName(current), result, detail)).takeLast(100)
        val complete = next == null
        mutableProgress.value = old.copy(
            state = if (complete) ProfileRunState.COMPLETED else old.state,
            currentIndex = old.currentIndex + 1,
            currentAccount = next?.let(::displayName).orEmpty(),
            successCount = old.successCount + if (success) 1 else 0,
            failedCount = old.failedCount + if (!success && result != "跳过") 1 else 0,
            skippedCount = old.skippedCount + if (result == "跳过") 1 else 0,
            message = if (complete) "资料任务完成" else "正在处理下一账号",
            logs = logs
        )
        if (complete) cleanupTemporaryAvatars()
    }

    @Synchronized
    fun skipCurrent(reason: String) {
        completeCurrent("跳过", reason, success = false)
        if (mutableProgress.value.state != ProfileRunState.COMPLETED) {
            mutableProgress.value = mutableProgress.value.copy(state = ProfileRunState.RUNNING)
        }
    }

    @Synchronized
    fun stop() {
        if (isActive()) {
            mutableProgress.value = mutableProgress.value.copy(
                state = ProfileRunState.STOPPED,
                message = "任务已停止"
            )
            cleanupTemporaryAvatars()
        }
    }

    private fun cleanupTemporaryAvatars() {
        AvatarTempFileCleaner.cleanup(sourceAvatars)
        sourceAvatars = emptyList()
    }

    private fun displayName(assignment: ProfileAssignment): String =
        assignment.account.displayName.ifBlank {
            assignment.account.phoneNumber.ifBlank { assignment.account.appName }
        }
}
