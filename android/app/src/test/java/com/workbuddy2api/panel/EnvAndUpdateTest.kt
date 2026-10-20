package com.workbuddy2api.panel

import com.workbuddy2api.panel.util.DeviceEnv
import com.workbuddy2api.panel.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新检查与 WebView 版本解析的纯逻辑测试（不联网）。 */
class EnvAndUpdateTest {

    @Test
    fun newerVersion() {
        assertTrue(UpdateChecker.isNewer("v3.3.0", "3.2.0"))
        assertTrue(UpdateChecker.isNewer("v3.10.0", "3.9.9"))
        assertTrue(UpdateChecker.isNewer("4.0.0", "3.99.99"))
        assertFalse(UpdateChecker.isNewer("v3.2.0", "3.2.0"))
        assertFalse(UpdateChecker.isNewer("v3.1.9", "3.2.0"))
    }

    @Test
    fun tolerantTagFormats() {
        assertTrue(UpdateChecker.isNewer("V3.3.0", "3.2.0"))
        assertTrue(UpdateChecker.isNewer("3.3.0-beta.1", "3.2.0"))
    }

    @Test
    fun chromeMajorParsing() {
        val ua = "Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/149.0.7827.91 Mobile Safari/537.36"
        assertEquals(149, DeviceEnv.chromeMajor(ua))
        assertEquals(0, DeviceEnv.chromeMajor(""))
        assertEquals(0, DeviceEnv.chromeMajor("no chrome here"))
        val old = "Mozilla/5.0 (Linux; Android 9; wv) Chrome/74.0.3729.157 Mobile Safari/537.36"
        assertEquals(74, DeviceEnv.chromeMajor(old))
    }

    @Test
    fun minChromeBoundary() {
        assertEquals(80, DeviceEnv.MIN_CHROME_FOR_DSH)
    }
}
