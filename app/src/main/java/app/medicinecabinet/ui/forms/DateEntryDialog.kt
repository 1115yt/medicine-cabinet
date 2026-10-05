package app.medicinecabinet.ui.forms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 给日期输入明确的可用空间，内容滚动时保留标题和确认按钮。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateEntryDialog(title: String, confirmation: String, enabled: Boolean,
    onDismiss: () -> Unit, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val physicalDensity = LocalContext.current.resources.displayMetrics.density
    val height = (LocalConfiguration.current.screenHeightDp * physicalDensity / density.density - 64f).coerceAtLeast(200f).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = height),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                Column {
                    Text(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp)) { content() }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(onClick = onConfirm, enabled = enabled) { Text(confirmation) }
                    }
                }
            }
        }
    }
}
