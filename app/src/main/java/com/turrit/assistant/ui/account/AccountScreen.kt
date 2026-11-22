package com.turrit.assistant.ui.account

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.turrit.assistant.account.AccountDetectionSession
import com.turrit.assistant.account.AccountRepository
import com.turrit.assistant.account.TurritAppRegistry
import com.turrit.assistant.account.TurritDetector
import com.turrit.assistant.account.model.InstalledTurritApp
import com.turrit.assistant.account.model.TelegramAccount
import com.turrit.assistant.diagnostics.DiagnosticLog
import com.turrit.assistant.automation.TurritAccessibilityService
import com.turrit.assistant.profile.AvatarImporter
import com.turrit.assistant.profile.ExcelImporter
import com.turrit.assistant.profile.ExcelTemplateExporter
import com.turrit.assistant.profile.ProfileExecutionSession
import com.turrit.assistant.profile.ProfileExecutor
import com.turrit.assistant.profile.ProfileManager
import com.turrit.assistant.profile.ProfileProgress
import com.turrit.assistant.profile.ProfileRunState
import com.turrit.assistant.profile.model.ProfileAssignment
import com.turrit.assistant.profile.model.ProfileAssignmentState
import com.turrit.assistant.profile.model.ProfileField
import com.turrit.assistant.profile.model.ProfileFieldAction
import com.turrit.assistant.profile.model.ProfilePreview
import com.turrit.assistant.profile.model.ProfileRow
import com.turrit.assistant.profile.model.AvatarAsset
import com.turrit.assistant.ui.components.SectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AccountScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val accounts by AccountRepository.accounts.collectAsState()

    var apps by remember { mutableStateOf<List<InstalledTurritApp>>(emptyList()) }
    var hasScanned by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var activePackage by remember { mutableStateOf(AccountDetectionSession.targetPackage(context)) }
    var recognitionStatus by remember { mutableStateOf(AccountDetectionSession.status(context)) }
    var showManualPicker by remember { mutableStateOf(false) }
    var launchableApps by remember { mutableStateOf<List<InstalledTurritApp>>(emptyList()) }
    var appSearch by rememberSaveable { mutableStateOf("") }

    var showProfileImport by remember { mutableStateOf(false) }
    var showProfilePreview by remember { mutableStateOf(false) }
    var excelName by rememberSaveable { mutableStateOf<String?>(null) }
    var avatarZipName by rememberSaveable { mutableStateOf<String?>(null) }
    var profileRows by remember { mutableStateOf<List<ProfileRow>>(emptyList()) }
    var avatarAssets by remember { mutableStateOf<List<AvatarAsset>>(emptyList()) }
    var profilePreview by remember { mutableStateOf<ProfilePreview?>(null) }
    var profileFileError by remember { mutableStateOf<String?>(null) }
    var isLoadingProfileFile by remember { mutableStateOf(false) }
    val profileProgress by ProfileExecutionSession.progress.collectAsState()
    val selectedAccounts = accounts.filter { it.isSelected }

    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            excelName = getDocumentName(context, uri)
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                isLoadingProfileFile = true
                profileFileError = null
                try {
                    profileRows = withContext(Dispatchers.IO) { ExcelImporter().read(context, uri) }
                    Toast.makeText(context, "已读取 ${profileRows.size} 条 Excel 资料", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    profileRows = emptyList()
                    profileFileError = error.message ?: "Excel 读取失败"
                } finally {
                    isLoadingProfileFile = false
                }
            }
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            avatarZipName = getDocumentName(context, uri)
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch {
                isLoadingProfileFile = true
                profileFileError = null
                try {
                    avatarAssets = withContext(Dispatchers.IO) { AvatarImporter().extract(context, uri) }
                    Toast.makeText(context, "已读取 ${avatarAssets.size} 张头像", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    avatarAssets = emptyList()
                    profileFileError = error.message ?: "头像 ZIP 读取失败"
                } finally {
                    isLoadingProfileFile = false
                }
            }
        }
    }
    val templateSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { ExcelTemplateExporter.write(context, uri) }
                }.onSuccess {
                    Toast.makeText(context, "模板已保存", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, it.message ?: "模板保存失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val diagnosticLogExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { DiagnosticLog.export(context, uri) }
                }.onSuccess { bytes ->
                    Toast.makeText(
                        context,
                        "运行日志已导出（${bytes / 1024} KB）",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    DiagnosticLog.error(context, "日志导出", "运行日志导出失败", error)
                    Toast.makeText(context, error.message ?: "日志导出失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun requestDiagnosticLogExport() {
        DiagnosticLog.info(context, "日志导出", "用户点击导出运行日志")
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        diagnosticLogExporter.launch("TURRIT-运行日志-$timestamp.txt")
    }

    fun makeProfilePreview() {
        if (selectedAccounts.isEmpty()) {
            Toast.makeText(context, "请先勾选需要修改的账号", Toast.LENGTH_SHORT).show()
            return
        }
        if (profileRows.isEmpty()) {
            Toast.makeText(context, "请先选择有效的 XLSX 资料表格", Toast.LENGTH_SHORT).show()
            return
        }
        profilePreview = ProfileManager().buildPreview(selectedAccounts, profileRows, avatarAssets)
        showProfileImport = false
        showProfilePreview = true
    }

    fun launchCurrentProfileAccount() {
        val assignment = ProfileExecutionSession.current() ?: return
        val app = InstalledTurritApp(assignment.account.appName, assignment.account.packageName)
        val launched = runCatching { TurritDetector(context).launch(app) }.getOrDefault(false)
        if (!launched) ProfileExecutionSession.waitForUser("无法打开 ${assignment.account.appName}，请检查 Turrit 是否仍已安装")
    }

    fun startProfileExecution() {
        if (!isAccessibilityServiceEnabled(context)) {
            Toast.makeText(context, "请先开启无障碍识别服务", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val preview = profilePreview ?: return
        ProfileExecutor().start(preview)
        showProfilePreview = false
        launchCurrentProfileAccount()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityServiceEnabled(context)
                activePackage = AccountDetectionSession.targetPackage(context)
                recognitionStatus = AccountDetectionSession.status(context)
                AccountDetectionSession.consumePendingError(context)?.let { message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(activePackage) {
        val packageName = activePackage ?: return@LaunchedEffect
        while (AccountDetectionSession.targetPackage(context) == packageName) {
            recognitionStatus = AccountDetectionSession.status(context)
            delay(500)
        }
        recognitionStatus = ""
        activePackage = AccountDetectionSession.targetPackage(context)
    }

    fun scanApps() {
        if (isScanning) return
        isScanning = true
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) {
                    TurritDetector(context).findInstalledTurritApps()
                }
                hasScanned = true
                DiagnosticLog.info(context, "应用扫描", "扫描已安装 Turrit 应用完成；数量=${apps.size}")
            } catch (error: Exception) {
                DiagnosticLog.error(context, "应用扫描", "扫描已安装 Turrit 应用失败", error)
                Toast.makeText(context, "扫描失败，请重试", Toast.LENGTH_SHORT).show()
            } finally {
                isScanning = false
            }
        }
    }

    fun beginRecognition(app: InstalledTurritApp) {
        if (!isAccessibilityServiceEnabled(context)) {
            Toast.makeText(context, "请先开启无障碍识别", Toast.LENGTH_SHORT).show()
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val scanId = AccountDetectionSession.start(context, app)
        recognitionStatus = AccountDetectionSession.status(context)
        DiagnosticLog.info(context, "识别/启动", "用户开始识别；应用=${app.appName}，package=${app.packageName}")
        val launchResult = runCatching { TurritDetector(context).launch(app) }
        val launched = launchResult.getOrDefault(false)
        DiagnosticLog.info(context, "识别/启动", "Turrit 启动请求结果=$launched")
        launchResult.exceptionOrNull()?.let {
            DiagnosticLog.error(context, "识别/启动", "启动 Turrit 时发生异常", it)
        }
        if (launched) {
            activePackage = app.packageName
            TurritAccessibilityService.requestRecognition(app.packageName, scanId)
            Toast.makeText(context, "已启动，正在自动进入 Turrit 的「我的」页面", Toast.LENGTH_LONG).show()
            scope.launch {
                delay(60_000)
                if (AccountDetectionSession.isCurrentScan(context, scanId)) {
                    val currentStatus = AccountDetectionSession.status(context)
                    val message = if (currentStatus.isNotBlank()) {
                        "识别超时，最后步骤：" + currentStatus
                    } else {
                        "识别超时：请确认无障碍服务仍开启，并重新尝试扫描。"
                    }
                    DiagnosticLog.error(context, "识别/超时", "识别超过 60 秒未结束；最后状态=$currentStatus", null)
                    TurritAccessibilityService.stopRecognition(app.packageName, scanId)
                    AccountDetectionSession.fail(context, message, scanId)
                    activePackage = null
                    recognitionStatus = ""
                    TurritAccessibilityService.requestReturnToAssistant(context)
                }
            }
        } else {
            DiagnosticLog.error(context, "识别/启动", "无法启动所选 Turrit 应用；package=${app.packageName}", launchResult.exceptionOrNull())
            AccountDetectionSession.stop(context, scanId)
            activePackage = null
            Toast.makeText(context, "无法打开该应用", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionHeader("账号管理") }

        item {
            Button(
                onClick = ::scanApps,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text("正在扫描", modifier = Modifier.padding(start = 8.dp))
                } else {
                    androidx.compose.material3.Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Text("扫描 Turrit 应用", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        item {
            OutlinedButton(
                onClick = ::requestDiagnosticLogExport,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("导出运行日志")
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (accessibilityEnabled) "无障碍识别已开启" else "无障碍识别未开启",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (accessibilityEnabled) "设置" else "开启识别")
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Turrit 应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        launchableApps = withContext(Dispatchers.IO) {
                            TurritDetector(context).findLaunchableApps()
                        }
                        appSearch = ""
                        showManualPicker = true
                    }
                }) { Text("手动选择") }
            }
        }

        if (!hasScanned) {
            item { EmptyStateCard("等待扫描") }
        } else if (apps.isEmpty()) {
            item { EmptyStateCard("未发现应用，可手动选择") }
        } else {
            items(apps, key = { it.packageName }) { app ->
                TurritAppRow(
                    app = app,
                    isRecognizing = activePackage == app.packageName,
                    status = recognitionStatus,
                    onClick = {
                        if (activePackage == app.packageName) {
                            val scanId = AccountDetectionSession.currentScanId(context)
                            AccountDetectionSession.stop(context, scanId)
                            TurritAccessibilityService.stopRecognition(app.packageName, scanId)
                            activePackage = null
                            recognitionStatus = ""
                        } else {
                            beginRecognition(app)
                        }
                    }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Telegram 账号（${accounts.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { AccountRepository.setAllSelected(true) }, enabled = accounts.isNotEmpty()) {
                    Text("全选")
                }
                TextButton(onClick = { AccountRepository.setAllSelected(false) }, enabled = accounts.isNotEmpty()) {
                    Text("取消全选")
                }
            }
        }

        if (accounts.isEmpty()) {
            item { EmptyStateCard("尚未识别账号") }
        } else {
            items(accounts, key = { it.id }) { account ->
                TelegramAccountRow(
                    account = account,
                    onSelectedChange = { AccountRepository.setSelected(account.id, it) }
                )
            }
        }

        item {
            ProfileManagementCard(
                selectedCount = selectedAccounts.size,
                onClick = {
                    profileFileError = null
                    showProfileImport = true
                }
            )
        }

        if (profileProgress.state != ProfileRunState.IDLE) {
            item {
                ProfileProgressCard(
                    progress = profileProgress,
                    onPause = { ProfileExecutionSession.pause() },
                    onResume = {
                        ProfileExecutionSession.resume()
                        launchCurrentProfileAccount()
                    },
                    onSkip = {
                        ProfileExecutionSession.skipCurrent("用户确认跳过当前账号")
                        launchCurrentProfileAccount()
                    },
                    onStop = { ProfileExecutionSession.stop() }
                )
            }
        }
    }

    if (showProfileImport) {
        AlertDialog(
            onDismissRequest = { showProfileImport = false },
            title = { Text("批量编辑 Telegram 资料") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 470.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("已选 ${selectedAccounts.size} 个账号，按当前列表从上到下分配。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("资料表格", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            templateSaver.launch("TURRIT_资料顺序模板.xlsx")
                        }) { Text("下载顺序模板") }
                    }
                    Text(excelName ?: "未选择 XLSX", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { excelPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("选择 XLSX 表格") }

                    Text("头像 ZIP", fontWeight = FontWeight.SemiBold)
                    Text(avatarZipName ?: "未选择（可选）", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { avatarPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("选择头像压缩包") }

                    Text("留空不修改；填写【清空】才清空字段。头像按 ZIP 内顺序分配。", style = MaterialTheme.typography.bodySmall)
                    if (isLoadingProfileFile) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                            Text("正在读取文件", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    profileFileError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = ::makeProfilePreview, enabled = !isLoadingProfileFile && profileRows.isNotEmpty()) {
                    Text("预览顺序分配")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfileImport = false }) { Text("关闭") }
            }
        )
    }

    if (showProfilePreview && profilePreview != null) {
        val preview = profilePreview!!
        AlertDialog(
            onDismissRequest = { showProfilePreview = false },
            title = { Text("资料分配预览") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("账号 ${preview.selectedAccountCount} 个 · Excel ${preview.profileRowCount} 行 · 头像 ${preview.avatarCount} 张")
                    Text("多余资料 ${preview.extraProfileRows} 行保留；缺少资料的账号不会执行。")
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(preview.assignments, key = { it.account.id }) { assignment ->
                            ProfileAssignmentRow(assignment)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = ::startProfileExecution, enabled = preview.assignments.any { it.row != null }) {
                    Text("开始修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { showProfilePreview = false }) { Text("返回") }
            }
        )
    }

    if (showManualPicker) {
        ManualAppPicker(
            apps = launchableApps.filter { it.appName.contains(appSearch, ignoreCase = true) || it.packageName.contains(appSearch, ignoreCase = true) },
            search = appSearch,
            onSearchChange = { appSearch = it },
            onDismiss = { showManualPicker = false },
            onSelect = { selected ->
                TurritAppRegistry.add(context, selected.packageName)
                apps = (apps + selected).distinctBy { it.packageName }.sortedBy { it.appName.lowercase() }
                hasScanned = true
                showManualPicker = false
            }
        )
    }
}

@Composable
private fun TurritAppRow(
    app: InstalledTurritApp,
    isRecognizing: Boolean,
    status: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isRecognizing) status.ifBlank { "正在识别" } else "已安装",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isRecognizing) {
                OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text("停止") }
            } else {
                Button(onClick = onClick, shape = RoundedCornerShape(12.dp)) { Text("识别账号") }
            }
        }
    }
}

@Composable
private fun TelegramAccountRow(
    account: TelegramAccount,
    onSelectedChange: (Boolean) -> Unit
) {
    val selectedColor = Color(0xFFEEF3FF)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (account.isSelected) Color(0xFFD9E4F7) else Color(0xFFE2E8F0)),
        colors = CardDefaults.cardColors(
            containerColor = if (account.isSelected) selectedColor else Color.White
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = account.isSelected, onCheckedChange = onSelectedChange)
            Text(
                account.displayName.ifBlank { account.phoneNumber.ifBlank { "未识别账号" } },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1D293D)
            )
        }
    }
}


@Composable
private fun ProfileManagementCard(selectedCount: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("资料批量修改", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("已选择 $selectedCount 个账号", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick, enabled = selectedCount > 0, shape = RoundedCornerShape(12.dp)) {
                Text("批量编辑")
            }
        }
    }
}

@Composable
private fun ProfileAssignmentRow(assignment: ProfileAssignment) {
    val row = assignment.row
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F9FD))
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                assignment.account.displayName.ifBlank { assignment.account.phoneNumber },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (row == null || assignment.state == ProfileAssignmentState.MISSING_PROFILE) {
                Text("未完成：缺少资料", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("对应 Excel 第 ${row.sourceRow} 行", style = MaterialTheme.typography.bodySmall)
                Text(
                    listOf(
                        fieldPreview("名字", row.firstName),
                        fieldPreview("姓氏", row.lastName),
                        fieldPreview("用户名", row.username),
                        fieldPreview("简介", row.bio),
                        "头像：" + (assignment.avatar?.fileName ?: if (assignment.avatarMissing) "文件未匹配" else "不修改")
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun fieldPreview(label: String, field: ProfileField): String = when (field.action) {
    ProfileFieldAction.KEEP -> "$label：不修改"
    ProfileFieldAction.CLEAR -> "$label：清空"
    ProfileFieldAction.UPDATE -> "$label：${field.value}"
}

@Composable
private fun ProfileProgressCard(
    progress: ProfileProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("资料修改进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${progress.currentIndex}/${progress.total}  ·  成功 ${progress.successCount}  ·  失败 ${progress.failedCount}  ·  跳过 ${progress.skippedCount}")
            if (progress.currentAccount.isNotBlank()) Text("当前账号：${progress.currentAccount}")
            Text(progress.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (progress.state) {
                    ProfileRunState.RUNNING -> OutlinedButton(onClick = onPause) { Text("暂停") }
                    ProfileRunState.PAUSED -> Button(onClick = onResume) { Text("继续") }
                    ProfileRunState.WAITING_USER -> Button(onClick = onSkip) { Text("跳过当前账号") }
                    else -> Unit
                }
                if (progress.state in setOf(ProfileRunState.RUNNING, ProfileRunState.PAUSED, ProfileRunState.WAITING_USER)) {
                    OutlinedButton(onClick = onStop) { Text("停止") }
                }
            }
            progress.logs.takeLast(4).forEach { log ->
                Text("${log.accountName} · ${log.result} · ${log.detail}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun getDocumentName(context: Context, uri: Uri): String {
    return context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else null
    } ?: uri.lastPathSegment ?: "已选择文件"
}

@Composable
private fun EmptyStateCard(title: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ManualAppPicker(
    apps: List<InstalledTurritApp>,
    search: String,
    onSearchChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (InstalledTurritApp) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择 Turrit 应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("搜索应用") }
                )
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onSelect(app) }) { Text("选择") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, TurritAccessibilityService::class.java)
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any { flattened ->
        val enabled = ComponentName.unflattenFromString(flattened)
        enabled != null &&
            enabled.packageName == expected.packageName &&
            enabled.className == expected.className
    }
}
