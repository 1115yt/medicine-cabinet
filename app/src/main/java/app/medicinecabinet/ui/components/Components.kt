package app.medicinecabinet.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import app.medicinecabinet.domain.AlertReason
import app.medicinecabinet.domain.ExpiryPresentation
import app.medicinecabinet.domain.ExpiryDates
import app.medicinecabinet.domain.StockBatch
import app.medicinecabinet.domain.displayName

@Composable
fun PageHeading(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    val heading: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            heading(Modifier.fillMaxWidth())
            trailing?.invoke()
        }
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        heading(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 字号变大或空间不足时，按钮改为纵向排列，保留完整动作文字。 */
@Composable
fun AdaptiveActions(primary: @Composable (Modifier) -> Unit, secondary: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                primary(Modifier.fillMaxWidth())
                secondary(Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            primary(Modifier.weight(1f))
            secondary(Modifier)
        }
    }
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier.size(48.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, Modifier.size(64.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun InfoBanner(text: String, warning: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = if (warning) colors.tertiaryContainer else colors.surfaceVariant,
        contentColor = if (warning) colors.onTertiaryContainer else colors.onSurfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReasonBadges(reasons: List<AlertReason>, batches: List<StockBatch>, today: LocalDate, showExpiryDays: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reasons.forEach { reason ->
            val colors = MaterialTheme.colorScheme
            val container = when (reason) {
                AlertReason.EXPIRED -> colors.errorContainer
                AlertReason.EXPIRING -> colors.tertiaryContainer
                AlertReason.LOW_STOCK -> colors.secondaryContainer
            }
            val foreground = when (reason) {
                AlertReason.EXPIRED -> colors.onErrorContainer
                AlertReason.EXPIRING -> colors.onTertiaryContainer
                AlertReason.LOW_STOCK -> colors.onSecondaryContainer
            }
            Surface(shape = CircleShape, color = container, contentColor = foreground) {
                Text(if (showExpiryDays) ExpiryPresentation.reasonLabel(reason, batches, today) else reason.displayName(),
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun PreferenceToggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, Modifier.semantics { contentDescription = title }, enabled = enabled)
    }
}

@Composable
fun ExpirySummary(batches: List<StockBatch>, today: LocalDate, showDays: Boolean = false) {
    val batch = ExpiryPresentation.earliestBatch(batches) ?: return
    val date = ExpiryDates.endDate(batch.expiryDate!!) ?: return
    val printedDate = ExpiryDates.display(batch.expiryDate)
    val multiple = batches.count { it.quantity > 0 && it.expiryDate != null } > 1
    val prefix = if (multiple) "最早一批 · " else ""
    val text = if (showDays) "$prefix${ExpiryPresentation.remainingLabel(date, today)} · 有效期至 $printedDate"
        else "${prefix}有效期至 $printedDate"
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun QuantitySummary(label: String, quantity: Int, unit: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = LocalContentColor.current) {
    // 标题与数量按文字基线对齐，间距由布局控制，不用空格撑开。
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.alignByBaseline(), style = style, color = color)
        Text("$quantity $unit", Modifier.alignByBaseline(), style = style.copy(fontFeatureSettings = "tnum"), color = color)
    }
}

@Composable
fun QuantityControls(quantity: Int, unit: String, label: String, enabled: Boolean,
    minimum: Int = 0, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = { onChange(-1) }, enabled = enabled && quantity > minimum) {
            Icon(Icons.Outlined.Remove, "减少$label")
        }
        // 数量在两侧按钮之间居中，预留常用数值宽度，变化时保持稳定。
        Text("$quantity $unit", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.widthIn(min = 64.dp, max = 120.dp), textAlign = TextAlign.Center)
        FilledTonalIconButton(onClick = { onChange(1) }, enabled = enabled && quantity < 9999) {
            Icon(Icons.Outlined.Add, "增加$label")
        }
    }
}
