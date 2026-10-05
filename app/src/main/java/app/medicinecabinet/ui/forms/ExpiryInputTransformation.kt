package app.medicinecabinet.ui.forms

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** 分隔符只影响显示，退格、光标和选择仍操作原始数字，不把横线重复写入输入。 */
object ExpiryInputTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (text.length > 8 || text.any { it !in '0'..'9' }) return TransformedText(text, OffsetMapping.Identity)
        val formatted = buildString {
            text.forEachIndexed { index, character ->
                if (index == 4 || index == 6) append('-')
                append(character)
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = offset +
                (if (text.length > 4 && offset >= 4) 1 else 0) +
                (if (text.length > 6 && offset >= 6) 1 else 0)
            override fun transformedToOriginal(offset: Int): Int = formatted.take(offset).count { it != '-' }
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

/** 已保存的日期重新编辑时也用数字，便于把年月直接续填成完整日期。 */
internal fun editableExpiryInput(value: String): String =
    if (Regex("[0-9]{4}-[0-9]{2}(-[0-9]{2})?").matches(value)) value.replace("-", "") else value
