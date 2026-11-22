package com.turrit.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.turrit.assistant.MainActivity
import com.turrit.assistant.account.AccountDetectionSession
import com.turrit.assistant.account.AccountRepository
import com.turrit.assistant.account.AccountScanProgress
import com.turrit.assistant.account.TelegramAccountParser
import com.turrit.assistant.account.model.TelegramAccount
import com.turrit.assistant.diagnostics.DiagnosticLog
import com.turrit.assistant.profile.ProfileExecutionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Account navigation is coordinated here; the full account-list parser and scanner run in Java. */
class TurritAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var readScheduled = false
    private var scanPackage: String? = null
    private var activeScanId: String? = null
    private var gestureSequence = 0
    private var gestureInProgress = false
    private var panelReady = false
    private var scanStarted = false
    private var panelOpenAttempts = 0
    private var navigationWaitAttempts = 0
    private var navigationBackAttempts = 0
    private var expectedAccountCount: Int? = null
    private var finishing = false
    private var waitingForTurritForeground = false

    private val scanProgress = AccountScanProgress()
    private val detectedAccounts = linkedMapOf<String, TelegramAccount>()
    private var profileAutomationController: ProfileAutomationController? = null
    private var activeJisouScanner: ActiveJisouAccountScanner? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        } else {
            screenWidth = resources.displayMetrics.widthPixels
            screenHeight = resources.displayMetrics.heightPixels
        }
        AccountRepository.initialize(applicationContext)
        DiagnosticLog.info(this, "无障碍服务", "服务已连接；屏幕=${screenWidth}x${screenHeight}")
        profileAutomationController = ProfileAutomationController(this)
        activeInstance = this
        val target = AccountDetectionSession.targetPackage(this)
        val scanId = AccountDetectionSession.currentScanId(this)
        if (target != null && scanId != null) {
            mainHandler.postDelayed({ startRecognition(target, scanId) }, 250L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (ProfileExecutionSession.isActive()) {
            profileAutomationController?.onAccessibilityEvent()
            return
        }
        val targetPackage = AccountDetectionSession.targetPackage(this) ?: return
        val scanId = AccountDetectionSession.currentScanId(this) ?: return
        if (activeScanId != scanId) {
            startRecognition(targetPackage, scanId)
            return
        }
        if (readScheduled) return
        val eventPackage = event.packageName?.toString()
        val activeWindowPackage = rootInActiveWindow?.packageName?.toString()
        // Keyboard/system overlay events may arrive while Turrit is still the active window.
        if (eventPackage != targetPackage && activeWindowPackage != targetPackage) return
        processCurrentWindow(targetPackage)
    }

    private fun resetScan(targetPackage: String, scanId: String) {
        activeJisouScanner?.cancel()
        activeJisouScanner = null
        DiagnosticLog.info(this, "识别会话", "重置识别状态；目标应用=$targetPackage")
        scanPackage = targetPackage
        activeScanId = scanId
        panelReady = false
        scanStarted = false
        panelOpenAttempts = 0
        navigationWaitAttempts = 0
        navigationBackAttempts = 0
        expectedAccountCount = AccountDetectionSession.expectedAccountCount(this)
        finishing = false
        waitingForTurritForeground = false
        gestureSequence++
        gestureInProgress = false
        scanProgress.reset(expectedAccountCount)
        detectedAccounts.clear()
    }

    /** Starts only the requested run; a stale callback cannot restart or stop a newer scan. */
    private fun startRecognition(targetPackage: String, scanId: String) {
        mainHandler.post {
            if (!AccountDetectionSession.isCurrentScan(this, scanId) ||
                AccountDetectionSession.targetPackage(this) != targetPackage
            ) return@post
            if (activeScanId == scanId && scanPackage == targetPackage && !finishing) return@post
            mainHandler.removeCallbacksAndMessages(null)
            readScheduled = false
            gestureSequence++
            gestureInProgress = false
            resetScan(targetPackage, scanId)
            DiagnosticLog.info(this, "识别会话", "开始识别；目标应用=$targetPackage，scanId=${scanId.take(8)}")
            processCurrentWindow(targetPackage)
        }
    }

    private fun stopRecognition(targetPackage: String, scanId: String?) {
        mainHandler.post {
            if (scanPackage != targetPackage ||
                (scanId != null && activeScanId != scanId)
            ) return@post
            mainHandler.removeCallbacksAndMessages(null)
            activeJisouScanner?.cancel()
            activeJisouScanner = null
            DiagnosticLog.warn(this, "识别会话", "用户停止识别；目标应用=$targetPackage")
            readScheduled = false
            gestureSequence++
            gestureInProgress = false
            activeScanId = null
            scanPackage = null
            panelReady = false
            scanStarted = false
            finishing = false
        }
    }

    private fun isCurrentScan(targetPackage: String, scanId: String? = activeScanId): Boolean {
        if (scanId == null || activeScanId != scanId) return false
        return AccountDetectionSession.isCurrentScan(this, scanId) &&
            AccountDetectionSession.targetPackage(this) == targetPackage
    }

    private fun processCurrentWindow(targetPackage: String) {
        if (!isCurrentScan(targetPackage)) return
        if (scanPackage != targetPackage) {
            val scanId = activeScanId ?: return
            resetScan(targetPackage, scanId)
        }

        if (!isTurritReady(targetPackage)) {
            scheduleRead(targetPackage, READY_POLL_MS) {
                processCurrentWindow(targetPackage)
            }
            return
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != targetPackage) {
            scheduleRead(targetPackage, READY_POLL_MS) {
                processCurrentWindow(targetPackage)
            }
            return
        }

        if (!AccountDetectionSession.isNavigationDone(this)) {
            val meTab = findBottomMeTab(root)
            if (meTab == null) {
                navigationWaitAttempts++
                AccountDetectionSession.setStatus(
                    this,
                    if (navigationBackAttempts == 0) "正在确认 Turrit 首页" else "正在返回 Turrit 首页"
                )
                if (navigationWaitAttempts >= MAX_NAVIGATION_ATTEMPTS ||
                    navigationBackAttempts >= MAX_NAVIGATION_BACKS
                ) {
                    finishDiscovery(targetPackage, "无法返回 Turrit 首页，账号识别已停止")
                } else {
                    if (navigationWaitAttempts > 2 &&
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ) navigationBackAttempts++
                    scheduleRead(targetPackage, NAVIGATION_SETTLE_MS) {
                        processCurrentWindow(targetPackage)
                    }
                }
                return
            }

            navigationWaitAttempts = 0
            navigationBackAttempts = 0
            if (isSelectedTab(meTab) || clickNode(meTab)) {
                AccountDetectionSession.markNavigationDone(this)
                AccountDetectionSession.setStatus(this, "已进入「我的」，正在读取账号列表")
                scheduleRead(targetPackage, NAVIGATION_SETTLE_MS) {
                    processCurrentWindow(targetPackage)
                }
            } else {
                navigationWaitAttempts++
                if (navigationWaitAttempts >= MAX_NAVIGATION_ATTEMPTS) {
                    finishDiscovery(targetPackage, "无法点击 Turrit 底部「我的」导航")
                } else {
                    scheduleRead(targetPackage, NAVIGATION_SETTLE_MS) {
                        processCurrentWindow(targetPackage)
                    }
                }
            }
            return
        }

        if (!panelReady) {
            ensureAccountPanelOpen(targetPackage, 0)
            return
        }

        if (!scanStarted) {
            scanStarted = true
            startActiveJisouScanner(targetPackage)
        }
    }

    private fun ensureAccountPanelOpen(targetPackage: String, attempt: Int) {
        if (!isTurritReady(targetPackage)) {
            scheduleRead(targetPackage, READY_POLL_MS) {
                ensureAccountPanelOpen(targetPackage, attempt)
            }
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != targetPackage) {
            DiagnosticLog.warn(this, "识别/账号面板", "当前活动窗口不是目标 Turrit，等待重试")
            scheduleRead(targetPackage, READY_POLL_MS) {
                ensureAccountPanelOpen(targetPackage, attempt)
            }
            return
        }

        // Use active-jisou's Java accountSnapshot() as the gate. It recursively
        // searches every node for Add Account, including non-leaf container nodes.
        val snapshot = ActiveJisouAccountScanner.readCurrentSnapshot(
            this, targetPackage, screenWidth, screenHeight
        )
        if (snapshot?.valid == true) {
            panelReady = true
            AccountDetectionSession.setStatus(this, "账号列表已展开，正在启动 Java 识别器")
            DiagnosticLog.info(
                this,
                "识别/账号面板",
                "Java accountSnapshot 已确认列表边界；可见行=${snapshot.visibleRowCount}，总数=${snapshot.total}，addTop=${snapshot.addTop}"
            )
            try {
                recordActiveJisouSnapshot(targetPackage, snapshot, root)
            } catch (error: Throwable) {
                DiagnosticLog.error(this, "识别/快照同步", "同步首屏账号快照失败", error)
                finishDiscovery(targetPackage, "同步首屏账号快照失败；请导出运行日志排查")
                return
            }
            if (!scanStarted) {
                scanStarted = true
                startActiveJisouScanner(targetPackage)
            }
            return
        }

        val hasHeader = ActiveJisouAccountScanner.hasProfileHeader(root, screenWidth, screenHeight)
        DiagnosticLog.info(
            this,
            "识别/账号面板",
            "Java accountSnapshot 尚未确认列表；profileHeader=$hasHeader，尝试次数=${attempt + 1}"
        )
        if (attempt < 2 && !hasHeader) {
            AccountDetectionSession.setStatus(this, "正在回到个人资料顶部")
            swipe(
                screenWidth * 0.46f, screenHeight * 0.36f,
                screenWidth * 0.46f, screenHeight * 0.78f,
                targetPackage
            ) { completed ->
                if (!isCurrentScan(targetPackage)) return@swipe
                DiagnosticLog.info(this, "识别/账号面板", "回到个人资料顶部手势完成=$completed")
                if (completed) {
                    scheduleRead(targetPackage, SWIPE_SETTLE_MS) {
                        ensureAccountPanelOpen(targetPackage, attempt + 1)
                    }
                } else if (attempt < 2) {
                    scheduleRead(targetPackage, SWIPE_RETRY_MS) {
                        ensureAccountPanelOpen(targetPackage, attempt + 1)
                    }
                } else {
                    finishDiscovery(targetPackage, "返回个人资料顶部时滑动失败，账号识别已停止")
                }
            }
            return
        }

        val collapse = findExactTextNode(
            root,
            setOf("收起", "collapse", "hide accounts"),
            screenHeight * 0.10f,
            screenHeight * 0.38f
        )
        if (collapse != null) {
            finishDiscovery(
                targetPackage,
                "检测到账号面板已展开，但 Java 识别器仍无法读取账号边界；诊断日志已记录节点阶段"
            )
            return
        }

        if (panelOpenAttempts >= 1) {
            finishDiscovery(
                targetPackage,
                "已尝试展开 Turrit 账号列表，但 Java accountSnapshot 未找到账号行或添加账号边界"
            )
            return
        }

        panelOpenAttempts++
        val expand = findExactTextNode(
            root,
            setOf("展开", "expand", "show accounts"),
            screenHeight * 0.10f,
            screenHeight * 0.38f
        )
        val clicked = if (expand != null) clickNode(expand)
            else if (hasHeader) tap(screenWidth * 0.922f, screenHeight * 0.212f)
            else false
        DiagnosticLog.info(this, "识别/账号面板", "点击展开账号列表：找到文本节点=${expand != null}，点击接受=$clicked")
        if (!clicked) {
            finishDiscovery(targetPackage, "无法定位 Turrit 个人页的账号展开按钮")
            return
        }

        AccountDetectionSession.setStatus(this, "正在展开账号列表")
        scheduleRead(targetPackage, PANEL_SETTLE_MS) {
            ensureAccountPanelOpen(targetPackage, attempt + 1)
        }
    }

    private fun startActiveJisouScanner(targetPackage: String) {
        if (!isCurrentScan(targetPackage) || activeJisouScanner != null) return
        AccountDetectionSession.setStatus(this, "已进入 Java 账号识别，正在回到列表顶部")
        DiagnosticLog.info(this, "识别/启动扫描器", "启动完整 Java 识别模块；目标应用=$targetPackage")
        val scanner = ActiveJisouAccountScanner(
            this,
            targetPackage,
            screenWidth,
            screenHeight,
            ActiveJisouAccountScanner.SnapshotListener { snapshot, root ->
                if (isCurrentScan(targetPackage)) {
                    recordActiveJisouSnapshot(targetPackage, snapshot, root)
                }
            },
            ActiveJisouAccountScanner.Completion { names, expected, detail ->
                mainHandler.post {
                    if (!isCurrentScan(targetPackage)) return@post
                    expectedAccountCount = expected.takeIf { it > 0 } ?: expectedAccountCount
                    scanProgress.setExpectedCount(expectedAccountCount)
                    names.forEach { name -> rememberActiveJisouName(targetPackage, name) }
                    DiagnosticLog.info(
                        this,
                        "识别/扫描完成",
                        "Java 扫描回调：累计名称=${names.size}，预期数量=${expectedAccountCount ?: "未知"}，结束原因=$detail"
                    )
                    activeJisouScanner = null
                    finishDiscovery(targetPackage, detail)
                }
            }
        )
        activeJisouScanner = scanner
        try {
            scanner.start()
        } catch (error: Throwable) {
            DiagnosticLog.error(this, "识别/启动扫描器", "Java 识别器启动失败", error)
            activeJisouScanner = null
            finishDiscovery(targetPackage, "Java 识别器启动失败；请导出运行日志排查")
        }
    }

    private fun recordActiveJisouSnapshot(
        targetPackage: String,
        snapshot: ActiveJisouAccountScanner.Snapshot,
        root: AccessibilityNodeInfo
    ) {
        if (!isCurrentScan(targetPackage)) return
        val appName = AccountDetectionSession.targetAppName(this)
            ?: runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(targetPackage, 0)
                ).toString()
            }.getOrDefault(targetPackage)

        expectedAccountCount = snapshot.total.takeIf { it > 0 } ?: expectedAccountCount
        scanProgress.setExpectedCount(expectedAccountCount)
        AccountDetectionSession.markAccountListDetected(this, expectedAccountCount)
        snapshot.names.forEach { rememberActiveJisouName(targetPackage, it, appName) }
        AccountDetectionSession.addSeenAccountNames(this, snapshot.names)

        // Keep the existing phone/username metadata parser only as an enrichment
        // step. It cannot decide whether the Java scanner starts or which names
        // are accepted; every record must match a name from active-jisou's parser.
        val verifiedProfileName = ActiveJisouAccountScanner.profileHeaderName(
            root, screenWidth, screenHeight
        )
        val visibleNames = snapshot.names.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        TelegramAccountParser.parse(
            root = root,
            packageName = targetPackage,
            appName = appName,
            verifiedProfileName = verifiedProfileName
        ).filter { it.displayName.trim().lowercase(Locale.ROOT) in visibleNames }
            .forEach { rememberAccount(it, countForScan = false) }

        DiagnosticLog.info(
            this,
            "识别/快照同步",
            "已同步 Java 快照：本屏名称=${snapshot.names.size}，可见账号行=${snapshot.visibleRowCount}，累计去重=${scanProgress.count}，预期=${expectedAccountCount ?: "未知"}"
        )
    }

    private fun rememberActiveJisouName(
        targetPackage: String,
        rawName: String,
        appNameOverride: String? = null
    ) {
        val name = rawName.replace(Regex("\\s+"), " ").trim()
        if (name.isBlank()) return
        val stableKey = name.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        val appName = appNameOverride ?: AccountDetectionSession.targetAppName(this)
            ?: targetPackage
        rememberAccount(
            TelegramAccount(
                id = "$targetPackage:name:$stableKey",
                packageName = targetPackage,
                appName = appName,
                phoneNumber = "",
                displayName = name,
                username = ""
            ),
            countForScan = true
        )
    }

    private fun rememberAccount(account: TelegramAccount, countForScan: Boolean) {
        val existing = detectedAccounts[account.id]
        val merged = if (existing == null) account else existing.copy(
            appName = account.appName.ifBlank { existing.appName },
            phoneNumber = account.phoneNumber.ifBlank { existing.phoneNumber },
            displayName = account.displayName.ifBlank { existing.displayName },
            username = account.username.ifBlank { existing.username },
            lastSeenAt = account.lastSeenAt
        )
        detectedAccounts[account.id] = merged
        if (countForScan) scanProgress.addName(merged.displayName)
    }

    private fun finishDiscovery(targetPackage: String, message: String) {
        val finishScanId = activeScanId ?: return
        if (finishing || !isCurrentScan(targetPackage, finishScanId)) return
        finishing = true
        activeJisouScanner?.cancel()
        activeJisouScanner = null
        DiagnosticLog.info(this, "识别/结束处理", "准备保存识别结果；原因=$message，累计账号=${scanProgress.count}，预期=${expectedAccountCount ?: "未知"}")
        val visibleNames = scanProgress.snapshot()
            .map { it.trim().lowercase(Locale.ROOT) }
            .toSet()
        val uniqueRecords = linkedMapOf<String, TelegramAccount>()
        detectedAccounts.values
            .filter { it.displayName.trim().lowercase(Locale.ROOT) in visibleNames }
            .forEach { account ->
                val name = account.displayName.trim().lowercase(Locale.ROOT)
                val key = "${account.packageName}:$name"
                val previous = uniqueRecords[key]
                if (previous == null ||
                    (previous.phoneNumber.isBlank() && account.phoneNumber.isNotBlank())
                ) uniqueRecords[key] = account
            }
        val records = uniqueRecords.values.toList()
        val expectedCount = expectedAccountCount
        val countMatches = expectedCount?.let {
            scanProgress.count == it && records.size == it
        } == true
        // Only a confirmed x/10 count authorizes replacing the old package records.
        val completeScan = records.isNotEmpty() &&
            records.size == scanProgress.count &&
            countMatches

        serviceScope.launch {
            if (!isCurrentScan(targetPackage, finishScanId)) return@launch
            var saveFailure: Throwable? = null
            try {
                if (completeScan) {
                    AccountRepository.replaceDetectedForPackage(targetPackage, records)
                } else if (expectedCount == null || scanProgress.count < expectedCount) {
                    // Partial scans may be merged; over-count results are never persisted.
                    records.forEach(AccountRepository::upsertDetected)
                }
            } catch (error: Throwable) {
                saveFailure = error
                DiagnosticLog.error(
                    this@TurritAccessibilityService,
                    "识别/保存账号",
                    "保存识别结果到本地数据库失败",
                    error
                )
            }
            withContext(Dispatchers.Main) {
                if (!isCurrentScan(targetPackage, finishScanId)) return@withContext
                val finalMessage = if (saveFailure == null) message
                    else "$message；但保存账号失败，请导出运行日志"
                if (saveFailure == null) {
                    AccountDetectionSession.complete(
                        this@TurritAccessibilityService,
                        finalMessage,
                        finishScanId
                    )
                } else {
                    AccountDetectionSession.fail(
                        this@TurritAccessibilityService,
                        finalMessage,
                        finishScanId
                    )
                }
                Log.i(TAG, "Account recognition finished: $finalMessage")
                DiagnosticLog.info(
                    this@TurritAccessibilityService,
                    "识别/完成",
                    "识别结束，正在返回任务助手；结果=$finalMessage，保存记录=${records.size}，完整扫描=$completeScan，保存异常=${saveFailure != null}"
                )
                activeScanId = null
                scanPackage = null
                panelReady = false
                scanStarted = false
                finishing = false
                returnToAssistant()
            }
        }
    }

    private fun returnToAssistant() {
        requestReturnToAssistant(this)
    }

    private fun scheduleRead(targetPackage: String, delayMs: Long, action: () -> Unit) {
        val scheduledScanId = activeScanId ?: return
        if (readScheduled) return
        readScheduled = true
        mainHandler.postDelayed({
            readScheduled = false
            if (isCurrentScan(targetPackage, scheduledScanId)) action()
        }, delayMs)
    }

    /**
     * Accessibility can only read the foreground app's node tree.
     * If the user switches back to the assistant, pause without saving a partial
     * result; when Turrit returns to the foreground, the same scan resumes.
     */
    private fun isTurritReady(targetPackage: String): Boolean {
        if (!isCurrentScan(targetPackage)) return false

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != targetPackage) {
            if (!waitingForTurritForeground) {
                AccountDetectionSession.setStatus(
                    this,
                    "Turrit 在后台，识别已暂停；请返回 Turrit 继续"
                )
                waitingForTurritForeground = true
                DiagnosticLog.warn(this, "识别/前台检查", "目标 Turrit 不在活动窗口，等待前台窗口恢复；package=$targetPackage")
            }
            return false
        }

        waitingForTurritForeground = false
        AccountDetectionSession.setStatus(this, "正在识别账号")
        return true
    }

    /** Match active-jisou: start on the last visible account row and drag down to return to top. */
    private fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        targetPackage: String,
        onResult: (Boolean) -> Unit
    ): Boolean {
        val scanId = activeScanId
        if (scanId == null || !isCurrentScan(targetPackage, scanId)) {
            onResult(false)
            return false
        }
        if (gestureInProgress) {
            onResult(false)
            return false
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MS)
        val gestureId = ++gestureSequence
        gestureInProgress = true
        val callback = object : GestureResultCallback() {
            private fun finish(completed: Boolean) {
                if (gestureId == gestureSequence) gestureInProgress = false
                if (isCurrentScan(targetPackage, scanId)) onResult(completed)
            }

            override fun onCompleted(gestureDescription: GestureDescription) {
                finish(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                finish(false)
            }
        }
        val accepted = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            callback,
            mainHandler
        )
        if (!accepted) {
            if (gestureId == gestureSequence) gestureInProgress = false
            if (isCurrentScan(targetPackage, scanId)) onResult(false)
        }
        return accepted
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 85L)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun findBottomMeTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var meNode: AccessibilityNodeInfo? = null
        var bestMeY = -1
        var hasChatsTab = false

        fun visit(node: AccessibilityNodeInfo) {
            val labels = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).map { it.trim().lowercase(Locale.ROOT) }.toSet()
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inBottomNavigation = bounds.width() > 0 &&
                bounds.height() > 0 &&
                bounds.height() <= screenHeight * 0.18f &&
                bounds.centerY() >= screenHeight * 0.82f

            if (inBottomNavigation) {
                if (labels.any { it in CHAT_TAB_LABELS } &&
                    bounds.centerX() < screenWidth * 0.28f
                ) hasChatsTab = true

                if (labels.any { it in ME_TAB_LABELS } &&
                    bounds.centerX() > screenWidth * 0.72f &&
                    bounds.centerY() > bestMeY
                ) {
                    meNode = node
                    bestMeY = bounds.centerY()
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }

        visit(root)
        return meNode.takeIf { hasChatsTab }
    }

    private fun isSelectedTab(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isSelected || candidate.isChecked) return true
            current = candidate.parent
        }
        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return@repeat
            if (candidate.isClickable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
            current = candidate.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return !bounds.isEmpty && tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    private fun findExactTextNode(
        root: AccessibilityNodeInfo,
        labels: Set<String>,
        minY: Float,
        maxY: Float
    ): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val values = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).map { it.trim().lowercase(Locale.ROOT) }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (values.any { it in labels } && node.isVisibleToUser &&
                bounds.centerY() in minY.toInt()..maxY.toInt()
            ) return node

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = visit(child)
                if (found != null) return found
            }
            return null
        }
        return visit(root)
    }

    override fun onInterrupt() {
        val target = scanPackage
        if (target != null) {
            DiagnosticLog.error(this, "无障碍服务", "服务收到中断事件，结束当前识别", null)
            mainHandler.post { finishDiscovery(target, "无障碍服务被系统中断，识别已停止") }
        }
    }

    override fun onDestroy() {
        val unfinishedTarget = scanPackage
        val unfinishedScanId = activeScanId
        if (unfinishedTarget != null && unfinishedScanId != null &&
            AccountDetectionSession.isCurrentScan(this, unfinishedScanId)
        ) {
            DiagnosticLog.error(this, "无障碍服务", "服务销毁时识别尚未完成", null)
            AccountDetectionSession.fail(this, "无障碍服务已关闭，识别中止；请导出运行日志排查", unfinishedScanId)
            requestReturnToAssistant(this)
        }
        activeJisouScanner?.cancel()
        activeJisouScanner = null
        mainHandler.removeCallbacksAndMessages(null)
        gestureSequence++
        gestureInProgress = false
        activeScanId = null
        profileAutomationController?.dispose()
        serviceScope.cancel()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeInstance: TurritAccessibilityService? = null

        fun requestRecognition(packageName: String, scanId: String) {
            activeInstance?.startRecognition(packageName, scanId)
        }

        fun stopRecognition(packageName: String, scanId: String?) {
            activeInstance?.stopRecognition(packageName, scanId)
        }

        fun requestReturnToAssistant(context: Context) {
            val appContext = context.applicationContext
            DiagnosticLog.info(appContext, "界面返回", "请求打开 TURRIT 任务助手")
            val intent = Intent(appContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { appContext.startActivity(intent) }
                    .onFailure {
                        Log.e(TAG, "Return to assistant failed", it)
                        DiagnosticLog.error(appContext, "界面返回", "打开任务助手失败", it)
                    }
            }, 180L)
        }

        private const val TAG = "TurritAccessibility"
        private const val MAX_NAVIGATION_ATTEMPTS = 24
        private const val MAX_NAVIGATION_BACKS = 7
        private const val NAVIGATION_SETTLE_MS = 520L
        private const val PANEL_SETTLE_MS = 650L
        private const val SWIPE_SETTLE_MS = 240L
        private const val SWIPE_RETRY_MS = 260L
        private const val READY_POLL_MS = 420L
        private const val GESTURE_DURATION_MS = 350L
        private val ME_TAB_LABELS = setOf("我的", "me", "profile", "个人中心", "my profile")
        private val CHAT_TAB_LABELS = setOf("聊天", "chat", "chats")
    }
}
