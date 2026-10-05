package app.medicinecabinet.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 危险操作先说明范围；仅确认按钮触发删除，取消和返回不会改动记录。 */
@Composable
fun DeleteRecordDialog(title: String, explanation: String, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text(title) },
        text = { Text(explanation, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("取消") } })
}
