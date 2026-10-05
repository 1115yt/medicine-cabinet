package app.medicinecabinet.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.medicinecabinet.BuildConfig
import app.medicinecabinet.R

const val AUTHOR_GITHUB_URL = "https://github.com/1115yt"

@Composable
fun AboutCard() {
    val context = LocalContext.current
    var showUpdateInfo by rememberSaveable { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("家庭药箱 · ${BuildConfig.VERSION_NAME}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AUTHOR_GITHUB_URL))) }
                    catch (_: ActivityNotFoundException) { Toast.makeText(context, "未找到可以打开网页的应用。", Toast.LENGTH_SHORT).show() }
                }) { Icon(painterResource(R.drawable.ic_github), "打开作者 GitHub 主页", Modifier.size(24.dp)) }
            }
            Text("用于库存整理；录入信息请核对药品包装。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { showUpdateInfo = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.SystemUpdateAlt, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text("检查更新")
            }
            Text("预览版暂未开放在线更新", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // 正式发布前只说明实际状态，不发送更新请求，也不误报已经是最新版本。
    if (showUpdateInfo) AlertDialog(onDismissRequest = { showUpdateInfo = false },
        title = { Text("检查更新") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前版本：${BuildConfig.VERSION_NAME}")
                Text("当前使用预览版，暂未开放在线更新。正式版本发布后可在此检查。")
            }
        }, confirmButton = { TextButton(onClick = { showUpdateInfo = false }) { Text("知道了") } })
}
