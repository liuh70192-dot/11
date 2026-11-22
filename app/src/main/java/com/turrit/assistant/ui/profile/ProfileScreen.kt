package com.turrit.assistant.ui.profile

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.turrit.assistant.ui.components.SectionHeader

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var excelName by rememberSaveable { mutableStateOf<String?>(null) }
    var avatarZipName by rememberSaveable { mutableStateOf<String?>(null) }

    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            excelName = displayName(context, uri)
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            avatarZipName = displayName(context, uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("资料批量修改")

        FileSelectCard(
            title = "姓名文件.xlsx",
            description = excelName ?: "未选择",
            buttonText = "选择 Excel",
            icon = { androidx.compose.material3.Icon(Icons.Outlined.Description, contentDescription = null) },
            onClick = { excelPicker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }
        )
        FileSelectCard(
            title = "头像文件.zip",
            description = avatarZipName ?: "未选择",
            buttonText = "选择 ZIP",
            icon = { androidx.compose.material3.Icon(Icons.Outlined.Archive, contentDescription = null) },
            onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分配规则", style = MaterialTheme.typography.titleMedium)
                Text("• Excel 第一行对应第一个已勾选账号，依次分配。", style = MaterialTheme.typography.bodyMedium)
                Text("• 留空表示不修改；填写【清空】才清空字段。", style = MaterialTheme.typography.bodyMedium)
                Text("• 账号多于资料时，剩余账号标记为缺少资料。", style = MaterialTheme.typography.bodyMedium)
                Text("• 头像按 ZIP 内文件顺序一一对应。", style = MaterialTheme.typography.bodyMedium)
            }
        }

    }
}

@Composable
private fun FileSelectCard(
    title: String,
    description: String,
    buttonText: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Text(title, modifier = Modifier.padding(start = 9.dp), style = MaterialTheme.typography.titleMedium)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(12.dp)) {
                Text(buttonText)
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: android.net.Uri): String {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else null
    } ?: uri.lastPathSegment ?: "已选择文件"
}
