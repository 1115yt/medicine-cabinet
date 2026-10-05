package app.medicinecabinet.ui

import android.Manifest
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.reminders.*
import app.medicinecabinet.ui.components.ReminderHealthCard
import app.medicinecabinet.ui.theme.CabinetTheme
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi", application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReminderHealthUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `settings test notification shows submitted status without claiming delivery`() {
        val app = compose.activity.application as TestCabinetApplication
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        ViewModelProvider(compose.activity)[CabinetViewModel::class.java].refresh()
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("通知自检"))
        compose.onNodeWithText("通知权限：已允许").assertIsDisplayed()
        capture("41-reminder-health-light")
        compose.onNodeWithText("发送测试通知").performScrollTo().performClick()
        compose.onNodeWithText("测试通知已提交给系统", substring = true).performScrollTo().assertIsDisplayed()
        Assert.assertNotNull(shadowOf(manager).getNotification(ReminderNotifications.TEST_ID))
        Assert.assertNull(app.reminderDiagnostics.history.value.daily)
        compose.onNodeWithText("每日后台检查：尚未记录运行", substring = true).performScrollTo().assertIsDisplayed()
        capture("42-reminder-test-result")
        compose.onNodeWithText("立即检查药箱").performScrollTo().performClick()
        compose.onNodeWithText("已安排检查，请查看实际检查记录", substring = true).assertIsDisplayed()
        // UI 用虚构完成的调度器，不把排队或虚构完成误记为真实检查。
        Assert.assertNull(app.reminderDiagnostics.history.value.latest)
    }

    @Test fun `battery settings entry leaves notification permission unchanged`() {
        val allowed = ReminderScheduler.notificationsAllowed(compose.activity)
        compose.onNodeWithText("设置").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("通知自检"))
        compose.onNodeWithText("检查系统电池设置").performScrollTo().performClick()
        Assert.assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, shadowOf(compose.activity).nextStartedActivity.action)
        Assert.assertEquals(allowed, ReminderScheduler.notificationsAllowed(compose.activity))
    }

    @Test fun `health explanations and recorded failure are readable in large text and dark theme`() {
        val latest = ReminderCheckRecord("fixture", 1_791_122_400_000, ReminderCheckSource.MANUAL,
            ReminderCheckResult.BLOCKED, 1_791_122_400_100)
        for (dark in listOf(false, true)) {
            renderCard(dark, 1.6f, ReminderHistory(latest))
            compose.onNodeWithText("通知自检").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("通知权限：未允许").assertIsDisplayed()
            capture(if (dark) "44-reminder-health-dark-large" else "43-reminder-health-large")
            compose.onNodeWithText("立即检查药箱").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("系统通知未允许，保留待提醒", substring = true).assertIsDisplayed()
            compose.onNodeWithText("每日后台检查：尚未记录运行", substring = true).assertIsDisplayed()
            capture(if (dark) "46-reminder-history-dark-large" else "45-reminder-history-large")
        }
    }

    @Test @Config(qualifiers = "w812dp-h375dp-land-xhdpi", shadows = [ReducedMotionSettingsShadow::class])
    fun `health actions remain reachable in landscape`() {
        renderCard(false, 1.3f, ReminderHistory())
        compose.onNodeWithText("检查系统通知设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("检查系统电池设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("发送测试通知").performScrollTo().assertIsDisplayed()
        capture("47-reminder-landscape")
        compose.onNodeWithText("立即检查药箱").performScrollTo().assertIsDisplayed()
    }

    private fun renderCard(dark: Boolean, scale: Float, history: ReminderHistory) {
        compose.runOnUiThread { compose.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                CabinetTheme(darkTheme = dark) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        ReminderHealthCard(ReminderHealth(false, false, false, false, true), history,
                            TestNotificationResult.BLOCKED, {}, {}, {})
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/ui-preview").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
