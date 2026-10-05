package app.medicinecabinet.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.medicinecabinet.domain.MedicineSort

/** 排序使用原生菜单，保留当前选择、48 dp 触控区域与大字号换行。 */
@Composable
fun MedicineSortControl(sort: MedicineSort, onChange: (MedicineSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = "药箱排序"
                stateDescription = "${sort.label}，${if (expanded) "菜单已展开" else "菜单已收起"}"
            }) {
                Icon(Icons.AutoMirrored.Outlined.Sort, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(sort.label, Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                MedicineSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) },
                        trailingIcon = { if (option == sort) Icon(Icons.Outlined.Check, "当前排序") },
                        onClick = { expanded = false; onChange(option) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
        Text(sort.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
