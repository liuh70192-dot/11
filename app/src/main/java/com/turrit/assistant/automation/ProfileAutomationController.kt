package com.turrit.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.turrit.assistant.account.AccountNameSync
import com.turrit.assistant.account.AccountRepository
import com.turrit.assistant.account.AccountSwitcherSnapshot
import com.turrit.assistant.account.TelegramAccountParser
import com.turrit.assistant.profile.AvatarMediaPublisher
import com.turrit.assistant.profile.ProfileExecutionSession
import com.turrit.assistant.profile.UsernameRetryPolicy
import com.turrit.assistant.profile.model.ProfileAssignment
import com.turrit.assistant.profile.model.ProfileField
import com.turrit.assistant.profile.model.ProfileFieldAction
import java.util.Locale

/**
 * Performs only visible, user-authorized Turrit UI operations.
 * Unknown layouts pause for review instead of guessing at a destructive control.
 */
class ProfileAutomationController(
    private val service: AccessibilityService
) {
    private enum class Step {
        OPEN_MY,
        OPEN_SWITCHER,
        SELECT_ACCOUNT,
        WAIT_SWITCH,
        VERIFY_SWITCH,
        OPEN_PROFILE,
        OPEN_EDIT,
        FILL_FIELDS,
        SAVE_PROFILE,
        WAIT_SAVE,
        OPEN_EDIT_FOR_USERNAME,
        OPEN_USERNAME,
        WAIT_USERNAME_EDITOR,
        FILL_USERNAME,
        SAVE_USERNAME,
        WAIT_USERNAME_RESULT,
        OPEN_AVATAR,
        WAIT_PICKER,
        SELECT_AVATAR,
        WAIT_CROP,
        FINISH
    }

    private val handler = Handler(Looper.getMainLooper())
    private var step = Step.OPEN_MY
    private var activeAccountId: String? = null
    private var nextActionAt = 0L
    private var stepStartedAt = 0L
    private var publishedAvatarFor = ""
    private var publishedAvatarUri: Uri? = null
    private var avatarPickerPollAttempts = 0
    private var usernameCandidate: String? = null
    private var usernameRetryCount = 0
    private var usernameOpenAttempts = 0
    private val accountSearch = AccountSelectionSearchState()
    private var timedStep: Step? = null
    private var panelOpenAttempts = 0
    private var profileTopAttempts = 0
    private var navigationBackAttempts = 0
    private var switchVerifyAttempts = 0
    private var meTabTappedForVerify = false

    fun onAccessibilityEvent() {
        if (!ProfileExecutionSession.isRunning()) return
        processActiveWindow()
    }

    private fun processActiveWindow() {
        if (!ProfileExecutionSession.isRunning()) return
        val assignment = ProfileExecutionSession.current() ?: return
        if (activeAccountId != assignment.account.id) {
            cleanupPublishedAvatar()
            activeAccountId = assignment.account.id
            step = Step.OPEN_MY
            timedStep = null
            stepStartedAt = SystemClock.elapsedRealtime()
            nextActionAt = 0L
            panelOpenAttempts = 0
            profileTopAttempts = 0
            navigationBackAttempts = 0
            switchVerifyAttempts = 0
            meTabTappedForVerify = false
            avatarPickerPollAttempts = 0
            usernameCandidate = null
            usernameRetryCount = 0
            usernameOpenAttempts = 0
            accountSearch.reset()
            publishedAvatarFor = ""
        }
        val now = SystemClock.elapsedRealtime()
        if (timedStep != step) {
            timedStep = step
            stepStartedAt = now
        }
        if (now < nextActionAt) return
        if (now - stepStartedAt > STEP_TIMEOUT_MS) {
            pauseForReview("当前步骤超过 30 秒没有进展：" + step.name + "。请检查 Turrit 界面后跳过或停止")
            return
        }
        val root = service.rootInActiveWindow ?: run {
            schedule(500)
            return
        }
        val packageName = root.packageName?.toString().orEmpty()
        val targetPackage = assignment.account.packageName
        if (packageName != targetPackage &&
            step !in setOf(Step.WAIT_PICKER, Step.SELECT_AVATAR, Step.WAIT_CROP)
        ) {
            schedule(450)
            return
        }

        when (step) {
            Step.OPEN_MY -> openMyTab(root)
            Step.OPEN_SWITCHER -> openAccountSwitcher(root)
            Step.SELECT_ACCOUNT -> selectAccount(root, assignment)
            Step.WAIT_SWITCH -> {
                step = Step.VERIFY_SWITCH
                switchVerifyAttempts = 0
                navigationBackAttempts = 0
                meTabTappedForVerify = false
                schedule(850)
            }
            Step.VERIFY_SWITCH -> verifySwitchedAccount(root, assignment)
            Step.OPEN_PROFILE -> openProfile(root, assignment)
            Step.OPEN_EDIT -> openEditInfo(root)
            Step.FILL_FIELDS -> fillProfileFields(root, assignment)
            Step.SAVE_PROFILE -> saveProfile(root)
            Step.WAIT_SAVE -> {
                val usernameEntryVisible = findUsernameEntry(root) != null
                val profileViewReady =
                    findLabel(root, EDIT_LABELS) != null || usernameEntryVisible
                val usernameAction =
                    assignment.row?.username?.action ?: ProfileFieldAction.KEEP

                // Turrit may return to the account page while the two name fields remain
                // visible. The old "name fields < 2" gate kept WAIT_SAVE looping forever,
                // even though the "Add username" row was already on screen.
                if (profileViewReady &&
                    (usernameEntryVisible || findNameInputPair(root).size < 2)
                ) {
                    step = if (usernameEntryVisible &&
                        usernameAction != ProfileFieldAction.KEEP
                    ) {
                        Step.OPEN_USERNAME
                    } else {
                        nextStepAfterProfileSave(assignment)
                    }
                    schedule(180)
                } else {
                    schedule(450)
                }
            }
            Step.OPEN_EDIT_FOR_USERNAME -> openEditForUsername(root)
            Step.OPEN_USERNAME -> openUsernamePage(root, assignment)
            Step.WAIT_USERNAME_EDITOR -> waitForUsernameEditor(root)
            Step.FILL_USERNAME -> fillUsername(root, assignment)
            Step.SAVE_USERNAME -> saveUsername(root)
            Step.WAIT_USERNAME_RESULT -> waitForUsernameResult(root, assignment)
            Step.OPEN_AVATAR -> openAvatarPicker(root, assignment)
            Step.WAIT_PICKER -> waitForPicker(root, targetPackage)
            Step.SELECT_AVATAR -> selectAvatar(root, assignment)
            Step.WAIT_CROP -> finishAvatarSelection(root, targetPackage)
            Step.FINISH -> finishCurrent(root, assignment)
        }
    }

    private fun openMyTab(root: AccessibilityNodeInfo) {
        // Always follow the reference flow: return to the real bottom navigation first.
        // An Edit Info button on the previous account must never bypass account selection.
        val me = findBottomTab(root, ME_LABELS, rightSide = true)
        val chats = findBottomTab(root, CHAT_LABELS, rightSide = false)
        if (me != null && chats != null) {
            navigationBackAttempts = 0
            if (!clickNode(me)) {
                pauseForReview("无法点击 Turrit 底部「我的」选项")
                return
            }
            step = Step.OPEN_SWITCHER
            panelOpenAttempts = 0
            profileTopAttempts = 0
            schedule(650)
            return
        }

        if (navigationBackAttempts >= MAX_NAVIGATION_BACKS) {
            pauseForReview("连续返回后仍没有找到 Turrit 底部导航，停止操作")
            return
        }
        if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            navigationBackAttempts++
            schedule(550)
        } else {
            pauseForReview("无法关闭当前页面或弹窗，不能安全进入「我的」")
        }
    }

    private fun openAccountSwitcher(root: AccessibilityNodeInfo) {
        val assignment = ProfileExecutionSession.current() ?: return
        val snapshot = readAccountSnapshot(root, assignment)
        if (snapshot != null) {
            // 与活跃 jisou 一样：只要能读到账号边界、5/10 计数或可见账号行，就认定已展开。
            // 绝不再对已经展开的向上箭头执行固定坐标点击。
            accountSearch.reset()
            step = Step.SELECT_ACCOUNT
            schedule(180)
            return
        }

        if (profileTopAttempts < 2 &&
            !TelegramAccountParser.hasProfileHeader(root, screenWidthPx(), screenHeightPx())
        ) {
            profileTopAttempts++
            swipe(
                service,
                screenWidth() * 0.46f,
                screenHeight() * 0.36f,
                screenWidth() * 0.46f,
                screenHeight() * 0.78f
            )
            schedule(620)
            return
        }

        if (findLabel(root, COLLAPSE_LABELS) != null) {
            pauseForReview("检测到账号面板可能已展开，但无障碍树没有返回账号行/数量；为避免误收起列表，已停止点击")
            return
        }
        if (panelOpenAttempts >= 1) {
            pauseForReview("已尝试展开账号列表，但仍未读取到账号边界或 5/10 数量")
            return
        }

        val expand = findLabel(root, EXPAND_LABELS)
        val clicked = if (expand != null) clickNode(expand)
            else if (TelegramAccountParser.hasProfileHeader(root, screenWidthPx(), screenHeightPx())) {
                tap(service, screenWidth() * 0.922f, screenHeight() * 0.212f)
            } else false

        if (!clicked) {
            pauseForReview("无法定位 Turrit 个人页的账号展开按钮")
            return
        }
        panelOpenAttempts++
        schedule(720)
    }

    private fun selectAccount(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        val name = assignment.account.displayName.trim()
        if (name.isBlank()) {
            pauseForReview("当前账号没有可用于匹配的姓名，请先在 Turrit 中确认账号")
            return
        }

        val currentHeader = TelegramAccountParser.profileHeaderName(
            root, screenWidthPx(), screenHeightPx()
        )
        if (currentHeader.equals(name, ignoreCase = true)) {
            step = Step.OPEN_PROFILE
            schedule(180)
            return
        }

        val snapshot = readAccountSnapshot(root, assignment)
        if (snapshot == null) {
            pauseForReview("账号列表已关闭或不可读取，已停止切换")
            return
        }

        val target = TelegramAccountParser.findAccountRow(
            root, name, snapshot, screenWidthPx(), screenHeightPx()
        )
        if (target != null) {
            if (clickNode(target)) {
                step = Step.WAIT_SWITCH
                schedule(850)
            } else {
                pauseForReview("无法点击账号：$name")
            }
            return
        }

        when (accountSearch.observe(snapshot.visibleSignature)) {
            AccountSelectionAction.SWIPE_TOWARD_TOP -> {
                if (swipeAccountListTowardTop(snapshot)) {
                    schedule(600)
                } else {
                    accountSearch.switchToBottom()
                    schedule(120)
                }
            }
            AccountSelectionAction.SWITCH_TO_BOTTOM -> schedule(120)
            AccountSelectionAction.SWIPE_TOWARD_BOTTOM -> {
                if (swipeAccountListTowardBottom(snapshot)) {
                    schedule(600)
                } else {
                    pauseForReview("账号列表已到边界，未找到：$name")
                }
            }
            AccountSelectionAction.NOT_FOUND -> {
                pauseForReview("已按活跃 jisou 的双向查找方式扫描账号列表，仍未找到「$name」；请确认 Turrit 中账号名称未改变")
            }
        }
    }

    private fun verifySwitchedAccount(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        val targetName = assignment.account.displayName.trim()
        if (!hasMainTabBar(root)) {
            if (navigationBackAttempts >= MAX_NAVIGATION_BACKS) {
                pauseForReview("切换账号后连续返回仍未找到 Turrit 主导航")
                return
            }
            if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                navigationBackAttempts++
                schedule(550)
            } else {
                pauseForReview("切换账号后无法返回 Turrit「我的」页面")
            }
            return
        }

        navigationBackAttempts = 0
        val me = findBottomTab(root, ME_LABELS, rightSide = true)
        if (!meTabTappedForVerify && me != null) {
            meTabTappedForVerify = true
            if (!clickNode(me)) {
                pauseForReview("切换后无法重新进入「我的」页面")
                return
            }
            schedule(650)
            return
        }

        val currentHeader = TelegramAccountParser.profileHeaderName(
            root, screenWidthPx(), screenHeightPx()
        )
        if (currentHeader.equals(targetName, ignoreCase = true)) {
            step = Step.OPEN_PROFILE
            schedule(220)
            return
        }

        if (switchVerifyAttempts >= MAX_SWITCH_VERIFY_ATTEMPTS) {
            pauseForReview("切换后顶部账号仍不是「$targetName」，已阻止修改其他账号")
            return
        }

        val snapshot = readAccountSnapshot(root, assignment)
        var moved = snapshot?.let(::swipeAccountListTowardTop) == true
        if (!moved && !TelegramAccountParser.hasProfileHeader(root, screenWidthPx(), screenHeightPx())) {
            moved = swipe(
                service,
                screenWidth() * 0.46f,
                screenHeight() * 0.34f,
                screenWidth() * 0.46f,
                screenHeight() * 0.76f
            )
        }
        if (!moved) {
            pauseForReview("切换后无法确认顶部账号名称，未修改：$targetName")
            return
        }
        switchVerifyAttempts++
        schedule(590)
    }

    private fun readAccountSnapshot(
        root: AccessibilityNodeInfo,
        assignment: ProfileAssignment
    ): AccountSwitcherSnapshot? {
        if (root.packageName?.toString() != assignment.account.packageName) return null
        val appName = assignment.account.appName.ifBlank { assignment.account.packageName }
        return TelegramAccountParser.parseVisibleAccountSwitcher(
            root = root,
            packageName = assignment.account.packageName,
            appName = appName,
            screenWidth = screenWidthPx(),
            screenHeight = screenHeightPx()
        )
    }

    /** Direct port of active-jisou swipeAccountListTowardTop(). */
    private fun swipeAccountListTowardTop(snapshot: AccountSwitcherSnapshot): Boolean {
        val height = screenHeight()
        val start = if (snapshot.lastRowCenterY > 0) {
            maxOf(height * 0.27f, minOf(height * 0.52f, snapshot.lastRowCenterY.toFloat()))
        } else {
            height * 0.34f
        }
        val end = minOf(height * 0.79f, start + height * 0.34f)
        if (end - start < height * 0.16f) return false
        return swipe(service, screenWidth() * 0.45f, start, screenWidth() * 0.45f, end)
    }

    /** Direct port of active-jisou swipeAccountListTowardBottom(). */
    private fun swipeAccountListTowardBottom(snapshot: AccountSwitcherSnapshot): Boolean {
        val height = screenHeight()
        if (snapshot.lastRowCenterY <= 0) return false
        val start = maxOf(
            height * 0.27f,
            minOf(height * 0.66f, snapshot.lastRowCenterY.toFloat())
        )
        val end = maxOf(height * 0.12f, start - height * 0.30f)
        if (start - end < height * 0.16f) return false
        return swipe(service, screenWidth() * 0.45f, start, screenWidth() * 0.45f, end)
    }

    private fun openProfile(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        if (findLabel(root, EDIT_LABELS) != null) {
            step = Step.OPEN_EDIT
            schedule(180)
            return
        }
        val name = assignment.account.displayName.trim()
        val headerNode = TelegramAccountParser.findProfileHeaderNode(
            root, name, screenWidthPx(), screenHeightPx()
        )
        if (headerNode == null) {
            pauseForReview("当前页面顶部账号未核对为「$name」，没有点击资料区域")
            return
        }
        if (clickNode(headerNode)) {
            step = Step.OPEN_EDIT
            schedule(650)
        } else {
            pauseForReview("无法打开已核对账号「$name」的个人资料页")
        }
    }

    private fun openEditInfo(root: AccessibilityNodeInfo) {
        val edit = findLabel(root, EDIT_LABELS)
        if (edit == null) {
            if (findEditableNodes(root).isNotEmpty()) {
                step = Step.FILL_FIELDS
                schedule(150)
            } else {
                pauseForReview("没有找到「编辑信息」按钮")
            }
            return
        }
        if (clickNode(edit)) {
            step = Step.FILL_FIELDS
            schedule(650)
        } else {
            pauseForReview("无法打开资料编辑页面")
        }
    }

    private fun fillProfileFields(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        val row = assignment.row ?: run {
            ProfileExecutionSession.skipCurrent("缺少 Excel 资料行")
            resetForNext()
            return
        }

        if (row.firstName.action == ProfileFieldAction.CLEAR ||
            (row.firstName.action == ProfileFieldAction.UPDATE && row.firstName.value.isBlank())
        ) {
            pauseForReview("名字不能为空。Excel 留空表示保留原名字；只有姓氏允许填写【清空】")
            return
        }

        val needsNameFields = row.firstName.action != ProfileFieldAction.KEEP ||
            row.lastName.action != ProfileFieldAction.KEEP
        val needsBio = row.bio.action != ProfileFieldAction.KEEP
        if (!needsNameFields && !needsBio) {
            step = when {
                row.username.action != ProfileFieldAction.KEEP -> Step.OPEN_USERNAME
                assignment.avatar != null -> Step.OPEN_AVATAR
                else -> Step.FINISH
            }
            schedule(180)
            return
        }
        val nameInputs = if (needsNameFields) findNameInputPair(root) else emptyList()
        if (needsNameFields && nameInputs.size < 2) {
            pauseForReview("没有同时识别到名字和姓氏两个输入框，未执行修改")
            return
        }

        if (row.firstName.action != ProfileFieldAction.KEEP &&
            !setProfileField(nameInputs[0], row.firstName, "名字")
        ) return
        if (row.lastName.action != ProfileFieldAction.KEEP &&
            !setProfileField(nameInputs[1], row.lastName, "姓氏")
        ) return

        if (row.bio.action != ProfileFieldAction.KEEP) {
            val bio = findFieldByAlias(root, setOf("bio", "about", "简介", "关于"))
            if (bio == null) {
                pauseForReview("没有准确识别到「简介」输入框")
                return
            }
            if (!setProfileField(bio, row.bio, "简介")) return
        }

        step = Step.SAVE_PROFILE
        schedule(300)
    }

    /** Turrit name card order is given name first and optional surname second. */
    private fun findNameInputPair(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val label = findLabel(root, NAME_SECTION_LABELS)
        var container: AccessibilityNodeInfo? = label
        repeat(6) {
            val current = container ?: return@repeat
            val fields = findEditableNodes(current)
            if (fields.size >= 2) return fields.take(2)
            container = current.parent
        }
        return findEditableNodes(root).take(2).takeIf { it.size == 2 }.orEmpty()
    }

    private fun setProfileField(
        target: AccessibilityNodeInfo,
        field: ProfileField,
        label: String
    ): Boolean {
        val value = if (field.action == ProfileFieldAction.CLEAR) "" else field.value
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        pauseForReview("填写「" + label + "」失败，请检查 Turrit 当前页面")
        return false
    }

    private fun saveProfile(root: AccessibilityNodeInfo) {
        val save = findLabel(root, SAVE_LABELS)
        if (save != null && clickNode(save)) {
            step = Step.WAIT_SAVE
            schedule(900)
            return
        }

        // Some Turrit builds save the name/bio card when leaving this screen.
        // Only use Back when the known profile form is visible; never tap a guessed coordinate.
        if (findNameInputPair(root).size >= 2) {
            if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                step = Step.WAIT_SAVE
                schedule(750)
            } else {
                pauseForReview("资料页没有保存按钮，且无法返回提交名字和简介")
            }
            return
        }
        pauseForReview("没有找到明确的保存/返回入口，未自动点击其他位置")
    }

    private fun nextStepAfterProfileSave(assignment: ProfileAssignment): Step {
        val usernameAction = assignment.row?.username?.action ?: ProfileFieldAction.KEEP
        return when {
            usernameAction != ProfileFieldAction.KEEP -> Step.OPEN_EDIT_FOR_USERNAME
            assignment.avatar != null -> Step.OPEN_AVATAR
            else -> Step.FINISH
        }
    }

    private fun openEditForUsername(root: AccessibilityNodeInfo) {
        // Saving name/bio returns to the account detail page. Add Username is inside Edit Info.
        if (findNameInputPair(root).size >= 2) {
            step = Step.OPEN_USERNAME
            schedule(180)
            return
        }

        val edit = findLabel(root, EDIT_LABELS)
        if (edit == null) {
            pauseForReview("保存名字和简介后，没有找到「编辑信息」入口，未继续修改用户名")
            return
        }
        if (clickNode(edit)) {
            step = Step.OPEN_USERNAME
            schedule(650)
        } else {
            pauseForReview("保存名字和简介后，无法重新打开「编辑信息」页面")
        }
    }

    private fun nextStepAfterUsername(assignment: ProfileAssignment): Step =
        if (assignment.avatar != null) Step.OPEN_AVATAR else Step.FINISH

    private fun openUsernamePage(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        val field = assignment.row?.username ?: return
        usernameCandidate = if (field.action == ProfileFieldAction.CLEAR) {
            ""
        } else {
            UsernameRetryPolicy.normalize(field.value)
        }
        usernameRetryCount = 0
        usernameOpenAttempts = 0
        if (field.action == ProfileFieldAction.UPDATE &&
            !UsernameRetryPolicy.isValid(usernameCandidate.orEmpty())
        ) {
            pauseForReview("用户名必须为 5–32 位，只能使用英文字母、数字和下划线")
            return
        }
        if (isUsernameEditor(root)) {
            step = Step.FILL_USERNAME
            schedule(180)
            return
        }
        val entry = findUsernameEntry(root) ?: run {
            pauseForReview("没有找到「添加用户名」入口，已停止，未触碰其他资料")
            return
        }
        if (clickUsernameRow(entry)) {
            step = Step.WAIT_USERNAME_EDITOR
            schedule(850)
        } else {
            pauseForReview("无法点击「添加用户名」入口")
        }
    }

    private fun waitForUsernameEditor(root: AccessibilityNodeInfo) {
        if (isUsernameEditor(root)) {
            usernameOpenAttempts = 0
            step = Step.FILL_USERNAME
            schedule(180)
            return
        }
        if (usernameOpenAttempts >= MAX_USERNAME_OPEN_ATTEMPTS) {
            pauseForReview("点击用户名入口后没有进入用户名编辑页，未修改其他资料")
            return
        }
        findUsernameEntry(root)?.let(::clickUsernameRow)
        usernameOpenAttempts++
        schedule(850)
    }

    private fun findUsernameEntry(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val addEntry = findLabel(root, ADD_USERNAME_LABELS)
        if (addEntry != null) {
            val bounds = Rect()
            addEntry.getBoundsInScreen(bounds)
            if (bounds.centerY() > screenHeight() * 0.42f) return addEntry
        }

        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser) {
                val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .map { it.trim() }
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val isUsername = values.any {
                    it.matches(Regex("""@[A-Za-z0-9_]{3,32}""")) ||
                        it.startsWith("t.me/", ignoreCase = true)
                }
                if (isUsername && bounds.centerY() > screenHeight() * 0.42f &&
                    bounds.centerX() < screenWidth() * 0.88f
                ) return node
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = visit(child)
                if (found != null) return found
            }
            return null
        }
        return visit(root)
    }

    private fun fillUsername(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        if (!isUsernameEditor(root)) {
            pauseForReview("当前页面不是用户名设置页，未修改其他资料")
            return
        }
        val field = assignment.row?.username ?: run {
            pauseForReview("缺少用户名资料")
            return
        }
        val candidate = usernameCandidate.orEmpty()
        if (field.action == ProfileFieldAction.UPDATE &&
            !UsernameRetryPolicy.isValid(candidate)
        ) {
            pauseForReview("用户名格式不符合 Telegram 要求，未保存")
            return
        }
        val target = findEditableNodes(root).firstOrNull()
        if (target == null) {
            pauseForReview("没有找到用户名输入框")
            return
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, candidate)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            pauseForReview("填写用户名失败")
            return
        }
        step = Step.SAVE_USERNAME
        schedule(250)
    }

    private fun saveUsername(root: AccessibilityNodeInfo) {
        if (!isUsernameEditor(root)) {
            pauseForReview("没有停留在用户名设置页，未执行保存")
            return
        }
        val save = findLabel(root, SAVE_LABELS)
        if (save == null) {
            pauseForReview("用户名页面没有找到「保存」按钮")
            return
        }
        if (clickNode(save)) {
            step = Step.WAIT_USERNAME_RESULT
            schedule(900)
        } else {
            pauseForReview("点击用户名保存失败")
        }
    }

    private fun waitForUsernameResult(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        if (containsUsernameTaken(root)) {
            if (usernameRetryCount >= MAX_USERNAME_RETRIES) {
                pauseForReview("用户名连续被占用，已尝试 " + MAX_USERNAME_RETRIES + " 次；请更换 Excel 中的基础用户名")
                return
            }
            val next = UsernameRetryPolicy.appendRandomSuffix(usernameCandidate.orEmpty())
            if (next == null) {
                pauseForReview("用户名已达到 32 位上限，无法继续追加随机字符；请缩短 Excel 中的用户名")
                return
            }
            usernameCandidate = next
            usernameRetryCount++
            step = Step.FILL_USERNAME
            schedule(250)
            return
        }

        if (containsTextFragments(root, USERNAME_SUCCESS_LABELS) || !isUsernameEditor(root)) {
            usernameCandidate = null
            usernameRetryCount = 0
            usernameOpenAttempts = 0
            step = nextStepAfterUsername(assignment)
            schedule(250)
        } else {
            schedule(500)
        }
    }

    private fun isUsernameEditor(root: AccessibilityNodeInfo): Boolean {
        if (findEditableNodes(root).isEmpty()) return false
        return findLabel(root, USERNAME_TITLE_LABELS) != null ||
            findLabel(root, USERNAME_INPUT_LABELS) != null
    }

    private fun containsUsernameTaken(root: AccessibilityNodeInfo): Boolean =
        containsTextFragments(root, USERNAME_TAKEN_LABELS)

    private fun containsTextFragments(root: AccessibilityNodeInfo, fragments: Set<String>): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.lowercase(Locale.ROOT) }
            if (node.isVisibleToUser && values.any { value -> fragments.any(value::contains) }) return true
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                if (visit(child)) return true
            }
            return false
        }
        return visit(root)
    }

    private fun openAvatarPicker(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        val avatar = assignment.avatar ?: run {
            step = Step.FINISH
            schedule(100)
            return
        }
        if (publishedAvatarFor != assignment.account.id) {
            cleanupPublishedAvatar()
            val published = runCatching { AvatarMediaPublisher().publish(service, avatar) }
                .getOrElse {
                    pauseForReview(it.message ?: "头像导入系统图库失败")
                    return
                }
            publishedAvatarUri = published
            publishedAvatarFor = assignment.account.id
        }
        val photo = findLabel(root, PHOTO_LABELS)
        if (photo == null) {
            // The username/name editor has no avatar button. Return to the account profile page.
            if (findNameInputPair(root).size >= 2) {
                if (service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                    schedule(650)
                } else {
                    pauseForReview("资料编辑页没有头像入口，且无法返回账号主页")
                }
                return
            }
            pauseForReview("已导入头像，但没有找到 Turrit「设置照片」入口")
            return
        }
        if (clickNode(photo)) {
            avatarPickerPollAttempts = 0
            step = Step.WAIT_PICKER
            schedule(600)
        } else {
            pauseForReview("无法打开头像选择器")
        }
    }

    private fun waitForPicker(root: AccessibilityNodeInfo, targetPackage: String) {
        val allowAccess = findLabel(root, PHOTO_ACCESS_LABELS)
        if (allowAccess != null) {
            if (clickNode(allowAccess)) {
                schedule(750)
            } else {
                pauseForReview("照片权限页面的「允许访问」按钮无法点击")
            }
            return
        }

        val gallery = findLabel(root, GALLERY_LABELS)
        if (gallery != null && clickNode(gallery)) {
            schedule(650)
            return
        }

        if (root.packageName?.toString() != targetPackage ||
            isPhotoPickerVisible(root)
        ) {
            step = Step.SELECT_AVATAR
            avatarPickerPollAttempts = 0
            schedule(250)
        } else {
            schedule(350)
        }
    }

    private fun isPhotoPickerVisible(root: AccessibilityNodeInfo): Boolean =
        findLabel(root, PHOTO_PICKER_TITLE_LABELS) != null ||
            findLabel(root, EMOJI_LABELS) != null ||
            findLabel(root, PHOTO_ACCESS_LABELS) != null

    private fun selectAvatar(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        val allowAccess = findLabel(root, PHOTO_ACCESS_LABELS)
        if (allowAccess != null) {
            if (clickNode(allowAccess)) schedule(750)
            else pauseForReview("照片权限页面的「允许访问」按钮无法点击")
            return
        }

        val fileName = assignment.avatar?.fileName.orEmpty()
        val namedImage = findTextContaining(root, fileName)
        val target = namedImage ?: findAvatarImageCandidate(root)
        if (target != null) {
            if (clickNode(target)) {
                step = Step.WAIT_CROP
                schedule(700)
            } else {
                pauseForReview("无法选择刚导入的头像图片")
            }
            return
        }

        avatarPickerPollAttempts++
        if (avatarPickerPollAttempts >= MAX_AVATAR_PICKER_POLLS) {
            pauseForReview("图库已打开，但没有识别到可点击的照片缩略图；请确认「允许访问照片」已完成")
        } else {
            schedule(400)
        }
    }

    private fun findAvatarImageCandidate(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val emojiBounds = findEmojiTileBounds(root)
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        fun visit(node: AccessibilityNodeInfo) {
            val className = node.className?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val imageLike = className.contains("ImageView", ignoreCase = true) ||
                className.contains("Thumbnail", ignoreCase = true) ||
                description.contains("photo", ignoreCase = true) ||
                description.contains("image", ignoreCase = true)
            val inPickerArea = bounds.centerY() > screenHeight() * 0.35f &&
                bounds.centerY() < screenHeight() * 0.96f
            val largeEnough = bounds.width() >= 80 && bounds.height() >= 80
            val inEmojiTile = emojiBounds?.contains(bounds.centerX(), bounds.centerY()) == true
            if (node.isVisibleToUser && imageLike && inPickerArea && largeEnough && !inEmojiTile) {
                candidates += node to bounds
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                visit(child)
            }
        }
        visit(root)
        return candidates.sortedWith(compareBy({ it.second.top }, { it.second.left }))
            .firstOrNull()?.first
    }

    private fun findEmojiTileBounds(root: AccessibilityNodeInfo): Rect? {
        val label = findLabel(root, EMOJI_LABELS) ?: return null
        var current: AccessibilityNodeInfo? = label
        repeat(5) {
            val node = current ?: return@repeat
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() >= screenWidth() * 0.18f &&
                bounds.height() >= screenHeight() * 0.10f &&
                bounds.width() <= screenWidth() * 0.55f
            ) return bounds
            current = node.parent
        }

        // Some Turrit builds expose the sticker label but not its card container.
        // Reconstruct only that small left-hand tile so it cannot be mistaken for a photo.
        val labelBounds = Rect()
        label.getBoundsInScreen(labelBounds)
        if (labelBounds.isEmpty) return null
        val tileWidth = maxOf(labelBounds.width() * 2.2f, screenWidth() * 0.30f)
            .coerceAtMost(screenWidth() * 0.48f)
        val tileHeight = screenHeight() * 0.24f
        return Rect(
            (labelBounds.centerX() - tileWidth / 2f).toInt().coerceAtLeast(0),
            (labelBounds.top - tileHeight).toInt().coerceAtLeast(0),
            (labelBounds.centerX() + tileWidth / 2f).toInt().coerceAtMost(screenWidth().toInt()),
            (labelBounds.bottom + screenHeight() * 0.03f).toInt().coerceAtMost(screenHeight().toInt())
        )
    }

    private fun finishAvatarSelection(root: AccessibilityNodeInfo, targetPackage: String) {
        if (isPhotoPickerVisible(root)) {
            schedule(350)
            return
        }
        val done = findLabel(root, CROP_DONE_LABELS)
        if (done != null && clickNode(done)) {
            schedule(750)
            return
        }
        if (root.packageName?.toString() == targetPackage &&
            findLabel(root, PHOTO_LABELS) != null
        ) {
            cleanupPublishedAvatar()
            step = Step.FINISH
            schedule(200)
        } else {
            schedule(350)
        }
    }

    private fun cleanupPublishedAvatar() {
        val uri = publishedAvatarUri ?: return
        runCatching { service.contentResolver.delete(uri, null, null) }
        publishedAvatarUri = null
        publishedAvatarFor = ""
    }

    private fun finishCurrent(root: AccessibilityNodeInfo, assignment: ProfileAssignment) {
        cleanupPublishedAvatar()

        val row = assignment.row
        val nameWasEdited = row != null && (
            row.firstName.action != ProfileFieldAction.KEEP ||
                row.lastName.action != ProfileFieldAction.KEEP
            )

        // A rename must be written back against the original account ID. Matching
        // by the old display name is unsafe because that value has just changed.
        // The profile heading is read only after this assignment was selected and
        // verified, so it is the authoritative name for this account.
        val verifiedName = if (nameWasEdited &&
            root.packageName?.toString() == assignment.account.packageName
        ) {
            TelegramAccountParser.profileHeaderName(root, screenWidthPx(), screenHeightPx())
        } else {
            null
        }
        val syncResult = if (nameWasEdited && !verifiedName.isNullOrBlank()) {
            runCatching {
                AccountNameSync.withVerifiedDisplayName(assignment.account, verifiedName)
                    ?.copy(lastSeenAt = System.currentTimeMillis())
                    ?.let(AccountRepository::upsertDetected)
            }
        } else {
            null
        }
        val syncFailed = nameWasEdited &&
            (verifiedName.isNullOrBlank() || syncResult?.isFailure == true)
        val detail = when {
            !nameWasEdited ->
                "已完成 Turrit 界面操作，请抽查该账号资料是否保存"
            verifiedName.isNullOrBlank() ->
                "界面操作已结束，但没有读取到已核验的当前账号新姓名；数据库未改写，请检查该账号资料页"
            syncResult?.isFailure == true ->
                "界面操作已结束，但新姓名写回本地账号记录失败：" +
                    (syncResult?.exceptionOrNull()?.message ?: "未知错误")
            else ->
                "已完成 Turrit 界面操作，并已将核验姓名同步到原账号记录：「$verifiedName」"
        }

        ProfileExecutionSession.completeCurrent(
            result = if (syncFailed) "待核对" else "已提交",
            detail = detail,
            success = !syncFailed
        )
        val next = ProfileExecutionSession.current()
        if (next == null) {
            activeAccountId = null
            returnToAssistant()
            return
        }
        activeAccountId = null
        step = Step.OPEN_MY
        if (next.account.packageName != assignment.account.packageName) {
            launchPackage(next)
            schedule(850)
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            schedule(700)
        }
    }

    private fun resetForNext() {
        cleanupPublishedAvatar()
        activeAccountId = null
        step = Step.OPEN_MY
        ProfileExecutionSession.current()?.let(::launchPackage)
        schedule(700)
    }

    private fun launchPackage(assignment: ProfileAssignment) {
        val intent = service.packageManager.getLaunchIntentForPackage(assignment.account.packageName)
        if (intent == null) {
            ProfileExecutionSession.waitForUser("无法启动 ${assignment.account.appName}")
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(intent) }
            .onFailure { ProfileExecutionSession.waitForUser("启动 ${assignment.account.appName} 失败") }
    }

    fun dispose() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun returnToAssistant() {
        TurritAccessibilityService.requestReturnToAssistant(service)
    }

    private fun pauseForReview(message: String) {
        ProfileExecutionSession.waitForUser(message)
    }

    private fun schedule(delayMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (timedStep != step) {
            timedStep = step
            stepStartedAt = now
        }
        nextActionAt = now + delayMs
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ processActiveWindow() }, delayMs)
    }

    private fun findFieldByAlias(root: AccessibilityNodeInfo, aliases: Set<String>): AccessibilityNodeInfo? {
        val nodes = findEditableNodes(root)
        nodes.firstOrNull { node ->
            val hint = hintOf(node).lowercase(Locale.ROOT)
            aliases.any(hint::contains)
        }?.let { return it }

        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ").lowercase(Locale.ROOT)
            if (node.isVisibleToUser && aliases.any(text::contains)) {
                var parent: AccessibilityNodeInfo? = node.parent
                repeat(4) {
                    val candidate = parent ?: return@repeat
                    findEditableNodes(candidate).firstOrNull()?.let { return it }
                    parent = candidate.parent
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = visit(child)
                if (found != null) return found
            }
            return null
        }
        return visit(root)
    }

    private fun findEditableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser &&
                (node.isEditable || node.className?.toString()?.contains("EditText", true) == true)
            ) result += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        return result.sortedBy { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.top
        }
    }

    private fun hintOf(node: AccessibilityNodeInfo): String =
        node.hintText?.toString() ?: node.contentDescription?.toString().orEmpty()

    private fun hasMainTabBar(root: AccessibilityNodeInfo): Boolean =
        findBottomTab(root, ME_LABELS, rightSide = true) != null &&
            findBottomTab(root, CHAT_LABELS, rightSide = false) != null

    private fun findBottomTab(
        root: AccessibilityNodeInfo,
        labels: Set<String>,
        rightSide: Boolean
    ): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        var bestY = -1

        fun visit(node: AccessibilityNodeInfo) {
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).map { it.trim().lowercase(Locale.ROOT) }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inBottomBar = node.isVisibleToUser &&
                bounds.width() > 0 && bounds.height() > 0 &&
                bounds.centerY() >= screenHeight() * 0.85f
            val onExpectedSide = if (rightSide) {
                bounds.centerX() > screenWidth() * 0.72f
            } else {
                bounds.centerX() < screenWidth() * 0.28f
            }
            if (inBottomBar && onExpectedSide && values.any { it in labels } &&
                bounds.centerY() > bestY
            ) {
                result = node
                bestY = bounds.centerY()
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }
        visit(root)
        return result
    }

    private fun findLabel(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val value = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim().lowercase(Locale.ROOT) }
            if (node.isVisibleToUser && value.any { it in labels }) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = visit(child)
                if (found != null) return found
            }
            return null
        }
        return visit(root)
    }

    private fun findExactText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        findLabel(root, setOf(text.trim().lowercase(Locale.ROOT)))

    private fun findTextInRange(
        root: AccessibilityNodeInfo,
        text: String,
        minY: Float,
        maxY: Float
    ): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val value = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .any { it.trim().equals(text.trim(), ignoreCase = true) }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (value && bounds.centerY() in minY.toInt()..maxY.toInt()) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = visit(child)
                if (found != null) return found
            }
            return null
        }
        return visit(root)
    }

    private fun findTextNearTop(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val node = findExactText(root, text) ?: return null
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return node.takeIf { bounds.centerY() < screenHeight() * 0.42f }
    }

    private fun findTextContaining(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val value = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ")
            if (node.isVisibleToUser && value.contains(text, ignoreCase = true)) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = visit(child)
                if (found != null) return found
            }
            return null
        }
        return visit(root)
    }

    private fun containsAnyText(root: AccessibilityNodeInfo, labels: Set<String>): Boolean =
        findLabel(root, labels) != null

    private fun isSelected(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isSelected || candidate.isChecked) return true
            current = candidate.parent
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(7) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return tapNodeCenter(node)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return !bounds.isEmpty && tap(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun clickUsernameRow(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var rowBounds: Rect? = null
        val minHeight = (screenHeight() * 0.035f).toInt()
        val maxHeight = (screenHeight() * 0.15f).toInt()
        repeat(5) {
            val candidate = current ?: return@repeat
            val bounds = Rect()
            candidate.getBoundsInScreen(bounds)
            val isRow = candidate.isVisibleToUser &&
                bounds.width() >= (screenWidth() * 0.55f).toInt() &&
                bounds.height() in minHeight..maxHeight
            if (isRow) {
                rowBounds = Rect(bounds)
                if (candidate.isClickable &&
                    candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) return true
            }
            current = candidate.parent
        }
        val bounds = rowBounds ?: Rect().also(node::getBoundsInScreen)
        return !bounds.isEmpty &&
            tap(service, bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun scrollForward(root: AccessibilityNodeInfo): Boolean {
        var deepest: AccessibilityNodeInfo? = null
        fun visit(node: AccessibilityNodeInfo) {
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
            if (node.isScrollable) deepest = node
        }
        visit(root)
        return deepest?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
    }

    private fun findFirstImage(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findAvatarImageCandidate(root)

    private fun swipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 350L)
        return service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 90L)
        return service.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun screenWidth(): Float = service.resources.displayMetrics.widthPixels.toFloat()
    private fun screenHeight(): Float = service.resources.displayMetrics.heightPixels.toFloat()
    private fun screenWidthPx(): Int = service.resources.displayMetrics.widthPixels
    private fun screenHeightPx(): Int = service.resources.displayMetrics.heightPixels

    companion object {
        private const val STEP_TIMEOUT_MS = 30_000L
        private const val MAX_NAVIGATION_BACKS = 7
        private const val MAX_SWITCH_VERIFY_ATTEMPTS = 3
        private val ME_LABELS = setOf("我的", "me", "profile", "个人中心", "my profile")
        private val CHAT_LABELS = setOf("聊天", "chat", "chats")
        private val EDIT_LABELS = setOf("编辑信息", "编辑资料", "edit info", "edit profile")
        private val EXPAND_LABELS = setOf("展开", "expand", "show accounts")
        private val COLLAPSE_LABELS = setOf("收起", "collapse", "hide accounts")
        private val ADD_ACCOUNT_LABELS = setOf("添加账号", "添加账户", "add account", "add an account")
        private const val MAX_USERNAME_RETRIES = 12
        private const val MAX_USERNAME_OPEN_ATTEMPTS = 2
        private const val MAX_AVATAR_PICKER_POLLS = 10
        private val NAME_SECTION_LABELS = setOf("你的姓名", "姓名", "your name")
        private val ADD_USERNAME_LABELS = setOf("添加用户名", "add username", "add a username")
        private val USERNAME_TITLE_LABELS = setOf("用户名", "username")
        private val USERNAME_INPUT_LABELS = setOf("设置用户名", "set username")
        private val USERNAME_TAKEN_LABELS = setOf(
            "此用户名已被使用", "用户名已被使用", "this username is taken",
            "username is already taken", "username already taken"
        )
        private val USERNAME_SUCCESS_LABELS = setOf("用户名已更新", "username updated", "username saved")
        private val PHOTO_ACCESS_LABELS = setOf(
            "允许访问", "允许访问照片", "允许访问所有照片", "allow access",
            "allow access to photos", "allow all photos"
        )
        private val PHOTO_PICKER_TITLE_LABELS = setOf(
            "选择图片或视频", "选择照片或视频", "photos and videos", "select photos and videos"
        )
        private val EMOJI_LABELS = setOf("使用表情", "emoji", "use emoji", "stickers")
        private val SAVE_LABELS = setOf("保存", "完成", "save", "done", "✓")
        private val PHOTO_LABELS = setOf("设置照片", "设置头像", "更换照片", "change photo", "set photo", "set profile photo")
        private val GALLERY_LABELS = setOf("从图库选择", "选择照片", "图库", "相册", "gallery", "photos", "choose photos")
        private val CROP_DONE_LABELS = setOf("完成", "使用照片", "done", "use photo", "apply")
    }
}
