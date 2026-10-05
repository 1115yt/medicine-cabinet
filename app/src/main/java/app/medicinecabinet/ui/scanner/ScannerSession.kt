package app.medicinecabinet.ui.scanner

import app.medicinecabinet.domain.BarcodeParser
import app.medicinecabinet.domain.CodeFormat
import app.medicinecabinet.domain.ParsedBarcode

/** 同一扫码会话仅接受一次合法结果；拒绝结果不终止识别，关闭后丢弃迟到事件。 */
internal class ScannerSession(
    private val onCode: (ParsedBarcode) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var active = true
    private var completed = false

    @Synchronized
    fun submit(raw: String, format: CodeFormat): Boolean {
        if (!active || completed) return false
        val parsed = try {
            BarcodeParser.parse(raw, format)
        } catch (_: IllegalArgumentException) {
            // 不展示扫描内容或异常原文；错误保留在本页，不加入全局提示队列。
            onError("该条码为空或过长，请换用药盒商品条码继续扫描。")
            return false
        }
        completed = true
        onCode(parsed)
        return true
    }

    @Synchronized
    fun reportError(message: String) {
        if (active && !completed) onError(message)
    }

    @Synchronized
    fun acceptsFrames(): Boolean = active && !completed

    @Synchronized
    fun close() { active = false }
}
