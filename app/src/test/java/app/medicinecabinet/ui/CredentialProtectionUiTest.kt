package app.medicinecabinet.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.medicinecabinet.MainActivity
import app.medicinecabinet.TestCabinetApplication
import app.medicinecabinet.data.CacheSyncStatus
import app.medicinecabinet.data.LookupSettings
import app.medicinecabinet.data.ServerCacheSettings
import app.medicinecabinet.security.CredentialProtection
import app.medicinecabinet.ui.components.LookupSettingsCard
import app.medicinecabinet.ui.components.MxnzpSettingsCard
import app.medicinecabinet.ui.components.ServerCacheSettingsCard
import app.medicinecabinet.ui.theme.CabinetTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w375dp-h812dp-xhdpi", application = TestCabinetApplication::class,
    shadows = [ReducedMotionSettingsShadow::class])
class CredentialProtectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun checkApplication() = assertUiUsesCurrentApplication(compose)
    @After fun releaseResources() = releaseUiTestResources(compose)

    @Test fun `all credential cards show unconfirmed save status and disable API checks at large text`() {
        val pending = CredentialProtection.SAVE_PENDING
        val settings = LookupSettings(aliyunProtection = pending, mxnzpProtection = pending)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.6f)) {
                CabinetTheme(darkTheme = true) { Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Box(Modifier.testTag("aliyun-pending")) { LookupSettingsCard(settings, false, {}, {}) }
                        Box(Modifier.testTag("mxnzp-pending")) { MxnzpSettingsCard(settings, false, {}, { _, _ -> }) }
                        Box(Modifier.testTag("shared-pending")) {
                            ServerCacheSettingsCard(ServerCacheSettings(enabled = true, available = true,
                                address = "https://fixture.invalid", protection = pending), CacheSyncStatus(), false, {}, {})
                        }
                    }
                } }
            }
        } }
        for (tag in listOf("aliyun-pending", "mxnzp-pending", "shared-pending")) {
            compose.onNode(hasText(pending.message) and hasAnyAncestor(hasTestTag(tag)))
                .performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("检测阿里云连接").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("检测MXNZP连接").performScrollTo().assertIsNotEnabled()
    }
}
