package app.medicinecabinet.ui

import app.medicinecabinet.ui.components.isOfficialReleaseUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 浏览器入口只接受固定仓库、规范正式版本及完全一致的版本页面。 */
class AboutCardUrlTest {
    private val prefix = "https://github.com/1115yt/medicine-cabinet/releases/tag/v"

    @Test fun `fixed official URL accepts canonical formal versions`() {
        for (version in listOf("1.0.0", "1.0.9", "1.1.0", "1.12.9", "2.0.0")) {
            assertTrue(version, isOfficialReleaseUrl("$prefix$version", version))
        }
    }

    @Test fun `preview versions and noncanonical version names cannot open a release`() {
        for (version in listOf("0.1.11", "1.0.10", "01.0.0", "1.00.0", "1.0.01", "1.0", "1.0.0-beta", "１.０.０", "1.0.0\n")) {
            assertFalse(version, isOfficialReleaseUrl("$prefix$version", version))
        }
        assertFalse(isOfficialReleaseUrl("${prefix}1.0.0", null))
        assertFalse(isOfficialReleaseUrl(null, "1.0.0"))
    }

    @Test fun `altered origins paths credentials queries and fragments are rejected`() {
        val valid = "${prefix}1.0.0"
        for (url in listOf(
            valid.replace("https:", "http:"),
            valid.replace("github.com", "github.com.evil.invalid"),
            valid.replace("github.com", "github.com:443"),
            valid.replace("github.com", "visitor@github.com"),
            valid.replace("1115yt", "other-owner"),
            valid.replace("medicine-cabinet", "other-repository"),
            "$valid?next=https://evil.invalid", "$valid#download", "$valid/", " $valid", "$valid ",
        )) {
            assertFalse(url, isOfficialReleaseUrl(url, "1.0.0"))
        }
    }

    @Test fun `URL version must match the checked version`() {
        assertFalse(isOfficialReleaseUrl("${prefix}1.0.1", "1.0.0"))
        assertFalse(isOfficialReleaseUrl("${prefix}2.0.0", "1.0.0"))
    }
}
