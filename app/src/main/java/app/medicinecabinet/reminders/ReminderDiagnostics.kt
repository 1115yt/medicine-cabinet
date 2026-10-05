package app.medicinecabinet.reminders

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReminderCheckSource(val label: String) { DAILY("每日后台检查"), RECHECK("药箱补查"), MANUAL("手动检查") }
enum class ReminderCheckResult(val label: String) {
    RUNNING("检查已开始，尚未记录完成"), DISABLED("本机提醒已关闭"), BLOCKED("系统通知未允许，保留待提醒"),
    EMPTY("检查完成，本次没有需要发送的新提醒"), POSTED("提醒已提交给系统"),
    RETRY("检查失败，将由系统安排重试"), POSTED_RETRY("提醒已提交，记录更新失败，稍后重试"),
    CANCELLED("本次检查已中断，等待下次检查"),
}

data class ReminderCheckRecord(val id: String, val startedAt: Long, val source: ReminderCheckSource,
    val result: ReminderCheckResult = ReminderCheckResult.RUNNING, val finishedAt: Long = 0, val count: Int = 0)
data class ReminderHistory(val latest: ReminderCheckRecord? = null, val daily: ReminderCheckRecord? = null)

/** 仅记录执行时间、结果和数量；不包含药品、库存或认证，也不进入药箱备份。 */
class ReminderDiagnostics(context: Context) {
    private val preferences = context.getSharedPreferences("cabinet-reminder-checks", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val history = state.asStateFlow()

    fun start(id: String, source: ReminderCheckSource, now: Long = System.currentTimeMillis()): ReminderCheckRecord =
        ReminderCheckRecord(id, now, source).also(::save)

    fun finish(record: ReminderCheckRecord, result: ReminderCheckResult, count: Int = 0,
        now: Long = System.currentTimeMillis()) = save(record.copy(result = result, finishedAt = now, count = count))

    private fun save(record: ReminderCheckRecord) = synchronized(lock) {
        val previous = read()
        // 以实际启动/完成关系判断先后，兼容系统时间回拨和周期任务复用同一个工作 ID。
        fun newer(current: ReminderCheckRecord?) = current == null ||
            (record.result == ReminderCheckResult.RUNNING && record.finishedAt == 0L) ||
            (current.id == record.id && current.startedAt == record.startedAt)
        val next = previous.copy(latest = if (newer(previous.latest)) record else previous.latest,
            daily = if (record.source == ReminderCheckSource.DAILY && newer(previous.daily)) record else previous.daily)
        // 诊断写入失败不影响药箱事务及通知；界面也不伪报已经持久保存。
        val editor = preferences.edit()
        next.latest?.let { write(editor, "latest", it) }
        next.daily?.let { write(editor, "daily", it) }
        if (runCatching { editor.commit() }.getOrDefault(false)) state.value = next
    }

    private fun read() = ReminderHistory(readRecord("latest"), readRecord("daily"))
    private fun readRecord(prefix: String): ReminderCheckRecord? = runCatching {
        val id = preferences.getString("$prefix-id", null) ?: return null
        val started = preferences.getLong("$prefix-start", 0).takeIf { it > 0 } ?: return null
        ReminderCheckRecord(id, started, ReminderCheckSource.valueOf(preferences.getString("$prefix-source", "")!!),
            ReminderCheckResult.valueOf(preferences.getString("$prefix-result", "")!!),
            preferences.getLong("$prefix-end", 0), preferences.getInt("$prefix-count", 0).coerceAtLeast(0))
    }.getOrNull()

    private fun write(editor: SharedPreferences.Editor, prefix: String, record: ReminderCheckRecord) {
        editor.putString("$prefix-id", record.id).putLong("$prefix-start", record.startedAt)
            .putString("$prefix-source", record.source.name).putString("$prefix-result", record.result.name)
            .putLong("$prefix-end", record.finishedAt).putInt("$prefix-count", record.count)
    }
    private companion object { val lock = Any() }
}
