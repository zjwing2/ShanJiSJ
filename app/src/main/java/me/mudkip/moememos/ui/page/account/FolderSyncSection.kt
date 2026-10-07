package me.mudkip.moememos.ui.page.account

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.FolderMirrorPrefs
import me.mudkip.moememos.data.repository.FolderMirrorScheduler
import me.mudkip.moememos.ext.string

/**
 * 本地账号页里的「Markdown 文件夹同步」一节。
 *
 * 状态直接读写 [FolderMirrorPrefs]，不经过 ViewModel——文件夹同步与账号数据、
 * 与服务器同步都是正交的，没必要为此改 AccountViewModel。副作用只有一个：
 * 重新排 WorkManager 的周期任务。
 */
@Composable
fun FolderSyncSection() {
    val context = LocalContext.current
    var treeUri by remember { mutableStateOf(FolderMirrorPrefs.treeUri(context)) }
    var enabled by remember { mutableStateOf(FolderMirrorPrefs.isEnabled(context)) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 必须显式持久化授权，否则重启后就无权访问这个目录
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        FolderMirrorPrefs.setTreeUri(context, uri.toString())
        FolderMirrorPrefs.setEnabled(context, true)
        treeUri = uri.toString()
        enabled = true
        FolderMirrorScheduler.start(context)
        // 首次绑定会把已有的全部本地笔记导出成 .md，后台跑
        FolderMirrorScheduler.syncNow(context)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Folder, contentDescription = null)
                Text(
                    R.string.folder_sync_title.string,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text(
                R.string.folder_sync_description.string,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                if (treeUri == null) {
                    R.string.folder_sync_not_selected.string
                } else {
                    R.string.folder_sync_selected.string + " " + prettyPath(treeUri.orEmpty())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                FilledTonalButton(onClick = { picker.launch(null) }) {
                    Text(
                        if (treeUri == null) {
                            R.string.folder_sync_choose.string
                        } else {
                            R.string.folder_sync_change.string
                        }
                    )
                }
                if (treeUri != null) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { FolderMirrorScheduler.syncNow(context) },
                        enabled = enabled,
                    ) {
                        Text(R.string.folder_sync_now.string)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Switch(
                    checked = enabled && treeUri != null,
                    enabled = treeUri != null,
                    onCheckedChange = { checked ->
                        FolderMirrorPrefs.setEnabled(context, checked)
                        enabled = checked
                        if (checked) {
                            FolderMirrorScheduler.start(context)
                            FolderMirrorScheduler.syncNow(context)
                        } else {
                            FolderMirrorScheduler.stop(context)
                        }
                    }
                )
                Text(
                    R.string.folder_sync_enable.string,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text(
                R.string.folder_sync_hint.string,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** SAF 的 tree uri 很长，截成一眼能认出来的目录名。 */
private fun prettyPath(raw: String): String = runCatching {
    val decoded = Uri.decode(raw)
    decoded.substringAfterLast(':').ifBlank { decoded }
}.getOrDefault(raw)
