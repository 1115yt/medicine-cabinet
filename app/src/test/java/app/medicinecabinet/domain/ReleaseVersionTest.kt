package app.medicinecabinet.domain

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun `current version accepts historical patch numbers without trimming or leading zeroes`() {
        assertEquals(ReleaseVersion(0, 1, 11), ReleaseVersion.parseVersionName("0.1.11"))
        assertEquals("1.2.3", ReleaseVersion.parseVersionName("1.2.3")!!.versionName)
        listOf("", "v1.0.0", " 1.0.0", "1.0.0 ", "01.0.0", "1.00.0", "1.0.00", "1.0", "1.0.0.1",
            "1.0.-1", "1.0.0-beta", "１.0.0", "1\n.0.0", "2147483648.0.0", "1.0.2147483648",
            "1".repeat(33)).forEach { assertNull(it, ReleaseVersion.parseVersionName(it)) }
    }

    @Test fun `remote tags follow formal major and single digit patch rules`() {
        listOf("v1.0.0", "v1.2.9", "v2.0.0", "v1.10.0").forEach {
            assertNotNull(it, ReleaseVersion.parseStableTag(it))
        }
        listOf("v0.1.11", "v0.9.9", "1.0.0", "V1.0.0", "v1.0.10", "v1.0.01", "v1.0.0-rc.1",
            "v1.0.0+build", "v1.0.0/extra").forEach { assertNull(it, ReleaseVersion.parseStableTag(it)) }
    }

    @Test fun `comparison uses all numeric segments and handles carry`() {
        assertTrue(ReleaseVersion(1, 0, 0) > ReleaseVersion(0, 1, 11))
        assertTrue(ReleaseVersion(1, 10, 0) > ReleaseVersion(1, 9, 9))
        assertTrue(ReleaseVersion(2, 0, 0) > ReleaseVersion(1, 9, 9))
        assertTrue(ReleaseVersion(1, 1, 0) > ReleaseVersion(1, 0, 9))
        assertEquals(0, ReleaseVersion(1, 2, 3).compareTo(ReleaseVersion(1, 2, 3)))
    }
}
