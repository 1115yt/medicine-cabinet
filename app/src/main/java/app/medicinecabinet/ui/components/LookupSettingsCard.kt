package app.medicinecabinet.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.medicinecabinet.data.LookupSettings
import app.medicinecabinet.data.ApiUsage
import app.medicinecabinet.domain.BarcodeService
import app.medicinecabinet.security.CredentialProtection

@Composable
fun LookupSettingsCard(settings: LookupSettings, busy: Boolean, onEnable: (Boolean) -> Unit,
    onSaveAppCode: (String) -> Unit, usage: ApiUsage = ApiUsage(), checking: Boolean = false, onTest: (String) -> Unit = {}) {
    val context = LocalContext.current
    // 输入中的认证不保存到界面恢复状态；用户点击保存后才写入私有配置。
    var appCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("阿里云补充查询", style = MaterialTheme.typography.titleLarge)
            PreferenceToggle("使用阿里云", if (settings.configured) "已配置 AppCode；仅查不到资料的商品条码会联网。"
                else "先自行申请万维易源商品条码服务，并填写 AppCode。", settings.enabled,
                !busy && settings.configured, onEnable)
            OutlinedTextField(appCode, { appCode = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (settings.configured) "替换 AppCode（可选）" else "阿里云 AppCode") },
                visualTransformation = PasswordVisualTransformation(), isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text(error ?: "认证使用设备密钥加密保存，不包含在药箱备份中。") })
            if (settings.aliyunProtection != CredentialProtection.EMPTY) Text(settings.aliyunProtection.message,
                color = if (settings.aliyunProtection == CredentialProtection.ENCRYPTED) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                val code = appCode.trim()
                if (!Regex("[A-Za-z0-9_-]{16,128}").matches(code)) error = "请填写有效 AppCode"
                else { onSaveAppCode(code); appCode = "" }
            }, enabled = !busy && appCode.isNotBlank()) { Text("保存阿里云认证并启用") }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://market.aliyun.com/detail/cmapi011032")))
            }) { Text("查看服务与申请说明") }
            Text("服务额度、有效期和费用以阿里云商品页及你的订单为准。联网只发送当前商品条码。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ApiDiagnostics(BarcodeService.ALIYUN, usage, settings.configured, busy, checking, onTest)
        }
    }
}
