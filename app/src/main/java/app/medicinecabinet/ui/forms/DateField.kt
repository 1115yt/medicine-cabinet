package app.medicinecabinet.ui.forms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.medicinecabinet.domain.ExpiryDates

/** 年月和完整日期使用同一入口，按输入内容保留包装标注的精度。 */
@Composable
fun DateField(value: String, onChange: (String) -> Unit, enabled: Boolean, error: String?) {
    var showEntry by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(editableExpiryInput(value), onChange, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("有效期至") }, singleLine = true, isError = error != null,
        supportingText = { Text(error ?: expiryInputHint(value)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        visualTransformation = ExpiryInputTransformation,
        trailingIcon = {
            IconButton(onClick = { showEntry = true }, enabled = enabled) {
                Icon(Icons.Outlined.CalendarMonth, "填写包装有效期")
            }
        }, shape = MaterialTheme.shapes.medium)
    if (showEntry) ExpiryInputDialog(value, { showEntry = false }) {
        onChange(it); showEntry = false
    }
}

private fun expiryInputHint(value: String): String {
    val normalized = ExpiryDates.normalizeInput(value)
    return if (normalized != null && ExpiryDates.isMonthOnly(normalized))
        "仅填年月，按 ${ExpiryDates.endDate(normalized)} 计算到期；保存时仍保留年月。"
    else "输入 202806 或 20280630，自动显示横线；年月和完整日期都能保存。"
}

@Composable
private fun ExpiryInputDialog(value: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf(editableExpiryInput(value)) }
    val normalized = ExpiryDates.normalizeInput(input)
    DateEntryDialog("填写包装有效期", "确认有效期", normalized != null, onDismiss, { normalized?.let(onSelect) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("按包装直接输入数字，年月和日之间会自动显示横线。")
            OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("包装有效期") }, placeholder = { Text("2028-06 或 2028-06-30") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                visualTransformation = ExpiryInputTransformation,
                isError = input.isNotBlank() && normalized == null,
                supportingText = { Text(if (input.isNotBlank() && normalized == null)
                    "请输入有效年月或日期，如 2028-06 或 2028-06-30。" else expiryInputHint(input)) })
        }
    }
}
