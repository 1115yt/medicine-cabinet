package app.medicinecabinet.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.medicinecabinet.BuildConfig
import app.medicinecabinet.R
import app.medicinecabinet.data.ReleaseUpdateReason
import app.medicinecabinet.data.ReleaseUpdateResult
import app.medicinecabinet.data.ReleaseUpdateState

const val AUTHOR_GITHUB_URL = "https://github.com/1115yt"
private const val RELEASE_TAG_PREFIX = "https://github.com/1115yt/medicine-cabinet/releases/tag/v"
private val formalVersionPattern = Regex("[1-9][0-9]*\\.(?:0|[1-9][0-9]*)\\.[0-9]")

// 浏览器入口再次核对固定仓库与版本，拒绝查询参数、重定向地址和伪造域名。
internal fun isOfficialReleaseUrl(url: String?, versionName: String?): Boolean =
    versionName != null && formalVersionPattern.matches(versionName) && url == "$RELEASE_TAG_PREFIX$versionName"

@Composable
fun AboutCard(updateState: ReleaseUpdateState = ReleaseUpdateState(), onCheckUpdate: () -> Unit = {}) {
    val context = LocalContext.current
    var showUpdateInfo by rememberSaveable { mutableStateOf(false) }
    fun openPage(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(context, "未找到可以打开网页的应用。", Toast.LENGTH_SHORT).show() }
        catch (_: SecurityException) { Toast.makeText(context, "无法打开网页，请稍后重试。", Toast.LENGTH_SHORT).show() }
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("家庭药箱 · ${BuildConfig.VERSION_NAME}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { openPage(AUTHOR_GITHUB_URL) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(painterResource(R.drawable.ic_github), "打开作者 GitHub 主页", Modifier.size(24.dp))
                }
            }
            Text("用于库存整理；录入信息请核对药品包装。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { showUpdateInfo = true; onCheckUpdate() }, enabled = !updateState.checking,
                modifier = Modifier.heightIn(min = 48.dp)) {
                if (updateState.checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Outlined.SystemUpdateAlt, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp)); Text(if (updateState.checking) "正在检查" else "检查更新")
            }
            Text("检查 GitHub 正式版本；下载和安装由你确认。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showUpdateInfo) {
        val result = updateState.result
        val available = result != null && result.reason == ReleaseUpdateReason.AVAILABLE &&
            isOfficialReleaseUrl(result.releaseUrl, result.versionName)
        val retryable = result == null || result.reason !in setOf(
            ReleaseUpdateReason.CURRENT, ReleaseUpdateReason.NO_RELEASE, ReleaseUpdateReason.NO_APK
        ) && !available
        AlertDialog(onDismissRequest = { showUpdateInfo = false },
            title = { Text("检查更新") }, text = {
                // 只滚动结果正文，保留底部操作；字号放大或横屏时仍可读完整提示。
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("当前版本：${BuildConfig.VERSION_NAME}")
                    if (updateState.checking) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("正在检查正式版本，请稍候。", Modifier.weight(1f))
                        }
                    } else Text(updateResultText(result, available))
                }
            }, confirmButton = {
                when {
                    updateState.checking -> TextButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("检查中")
                    }
                    available -> TextButton(onClick = {
                        // 不执行下载或安装，仅打开已经再次核对的版本页面。
                        val url = result?.releaseUrl
                        if (isOfficialReleaseUrl(url, result?.versionName)) {
                            openPage(requireNotNull(url)); showUpdateInfo = false
                        }
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看新版本") }
                    retryable -> TextButton(onClick = onCheckUpdate, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
                    else -> TextButton(onClick = { showUpdateInfo = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("知道了") }
                }
            }, dismissButton = {
                if (updateState.checking || available || retryable) {
                    TextButton(onClick = { showUpdateInfo = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("稍后") }
                }
            })
    }
}

private fun updateResultText(result: ReleaseUpdateResult?, available: Boolean): String {
    if (result == null) return "尚未取得更新结果，请重试。"
    return when (result.reason) {
        ReleaseUpdateReason.AVAILABLE -> if (available) "发现新版本 ${result.versionName}。可前往 GitHub 查看更新说明和安装包。"
            else "更新链接无效，暂时不能打开版本页面，请稍后重试。"
        ReleaseUpdateReason.CURRENT -> "当前已是最新正式版本。"
        ReleaseUpdateReason.NO_RELEASE -> "暂未发布正式版本，请稍后再检查。"
        ReleaseUpdateReason.NO_APK -> "最新正式版本尚无可下载安装包，请稍后再检查。"
        ReleaseUpdateReason.RATE_LIMITED -> "GitHub 请求达到限制，请稍后重试。"
        ReleaseUpdateReason.TIMEOUT -> "检查更新超时，请检查网络后重试。"
        ReleaseUpdateReason.DNS_FAILURE -> "无法解析更新服务地址，请检查网络后重试。"
        ReleaseUpdateReason.TLS_FAILURE -> "安全连接失败，请检查网络后重试。"
        ReleaseUpdateReason.NETWORK_FAILURE -> "暂时无法连接更新服务，请检查网络后重试。"
        ReleaseUpdateReason.SERVICE_UNAVAILABLE -> "更新服务暂不可用，请稍后重试。"
        ReleaseUpdateReason.INVALID_RESPONSE -> "更新信息无效，暂时无法确认最新版本，请稍后重试。"
        ReleaseUpdateReason.HTTP_ERROR -> "检查更新失败${result.httpStatus?.let { "（HTTP $it）" }.orEmpty()}，请稍后重试。"
    }
}
