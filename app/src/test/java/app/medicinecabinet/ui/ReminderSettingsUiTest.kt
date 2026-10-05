package app.medicinecabinet.ui

import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.work.WorkManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.reminders.ReminderScheduler
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 核对临期说明与实际保存设置一致，不能把尚未保存的输入当作当前规则。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi", application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `background reminder explanation opens this app system settings without changing permissions`() {
        val allowedBefore = ReminderScheduler.notificationsAllowed(compose.activity)
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("后台提醒说明"))
        compose.onNodeWithText("后台提醒说明").assertIsDisplayed()
        compose.onNodeWithText("强制停止后需重新打开", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("打开应用系统设置").performScrollTo().performClick()
        val intent = Shadows.shadowOf(compose.activity).nextStartedActivity
        Assert.assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        Assert.assertEquals("package:${compose.activity.packageName}", intent.data.toString())
        Assert.assertEquals(allowedBefore, ReminderScheduler.notificationsAllowed(compose.activity))
    }

    @Test fun `returning to foreground queues a new reminder check`() {
        compose.waitForIdle()
        val workManager = WorkManager.getInstance(compose.activity)
        val before = workManager.getWorkInfosForUniqueWork("cabinet-current-review").get().map { it.id }.toSet()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        val after = workManager.getWorkInfosForUniqueWork("cabinet-current-review").get()
        Assert.assertTrue(after.any { it.id !in before })
    }

    @Test fun `saving lead days changes both current settings explanation and home explanation`() {
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("临期提前提醒天数"))
        compose.onNodeWithText("当前默认：剩余 0～30 天算临期").assertIsDisplayed()
        compose.onNodeWithText("临期提前提醒天数").performTextReplacement("14")
        compose.onNodeWithText("当前默认：剩余 0～30 天算临期").assertIsDisplayed()
        compose.onNodeWithText("保存提醒设置").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("当前默认：剩余 0～14 天算临期").fetchSemanticsNodes().isNotEmpty()
        }
        Assert.assertEquals(14, viewModel.snapshot.value.settings.expiryLeadDays)
        compose.onNodeWithText("总览").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("默认临期：剩余 0～14 天", substring = true))
        compose.onNodeWithText("默认临期：剩余 0～14 天", substring = true).assertIsDisplayed()
    }

    @Test fun `invalid lead days do not change the saved near expiry threshold`() {
        val viewModel = ViewModelProvider(compose.activity)[CabinetViewModel::class.java]
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("临期提前提醒天数"))
        for (invalid in listOf("0", "366")) {
            compose.onNodeWithText("临期提前提醒天数").performTextReplacement(invalid)
            compose.onNodeWithText("保存提醒设置").performScrollTo().performClick()
            compose.onNodeWithText("请输入 1 至 365 天").assertIsDisplayed()
            Assert.assertEquals(30, viewModel.snapshot.value.settings.expiryLeadDays)
        }
    }
}
