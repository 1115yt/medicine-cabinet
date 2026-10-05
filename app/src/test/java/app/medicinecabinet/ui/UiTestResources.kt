package app.medicinecabinet.ui

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.work.WorkManager
import androidx.lifecycle.ViewModelProvider
import app.medicinecabinet.CabinetApplication
import app.medicinecabinet.MainActivity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame

fun assertUiUsesCurrentApplication(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    val viewModel = ViewModelProvider(rule.activity)[CabinetViewModel::class.java]
    assertSame("界面应读取当前应用实例的药箱。", rule.activity.application, viewModel.getApplication<CabinetApplication>())
}

/** 测试结束前停止观察和后台任务，避免 Robolectric 重置 SQLite 时仍有查询。 */
fun releaseUiTestResources(rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
    val application = rule.activity.application as CabinetApplication
    rule.runOnUiThread { rule.activity.viewModelStore.clear() }
    WorkManager.getInstance(application).cancelAllWork().result.get(10, TimeUnit.SECONDS)
    runBlocking { application.repository.snapshot() }
    application.database.close()
}
