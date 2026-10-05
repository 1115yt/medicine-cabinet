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
fun MxnzpSettingsCard(settings: LookupSettings, busy: Boolean, onEnable: (Boolean) -> Unit,
    onSave: (String, String) -> Unit, usage: ApiUsage = ApiUsage(), checking: Boolean = false, onTest: (String) -> Unit = {}) {
    val context = LocalContext.current
    var appId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("MXNZP 补充查询", style = MaterialTheme.typography.titleLarge)
            PreferenceToggle("使用 MXNZP", if (settings.mxnzpConfigured) "已配置认证；未命中内置资料时优先使用。"
                else "自行申请 app_id 和 app_secret 后启用。", settings.mxnzpEnabled,
                !busy && settings.mxnzpConfigured, onEnable)
            OutlinedTextField(appId, { appId = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("MXNZP app_id") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(secret, { secret = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("MXNZP app_secret") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = error != null,
                supportingText = { Text(error ?: "使用设备密钥加密保存，替换认证时同时填写这两项。") })
            if (settings.mxnzpProtection != CredentialProtection.EMPTY) Text(settings.mxnzpProtection.message,
                color = if (settings.mxnzpProtection == CredentialProtection.ENCRYPTED) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                if (!Regex("[A-Za-z0-9_-]{8,128}").matches(appId.trim()) ||
                    !Regex("[A-Za-z0-9_-]{8,256}").matches(secret.trim())) error = "请填写有效的 app_id 和 app_secret"
                else { onSave(appId.trim(), secret.trim()); appId = ""; secret = "" }
            }, enabled = !busy && appId.isNotBlank() && secret.isNotBlank()) { Text("保存 MXNZP 认证并启用") }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mxnzp.com/doc/detail?id=6")))
            }) { Text("查看 MXNZP 申请与接口文档") }
            Text("服务商说明该免费服务不可用于商业行为。未查询成功时，已启用的阿里云可继续补充；结果仍需核对。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ApiDiagnostics(BarcodeService.MXNZP, usage, settings.mxnzpConfigured, busy, checking, onTest)
        }
    }
}
