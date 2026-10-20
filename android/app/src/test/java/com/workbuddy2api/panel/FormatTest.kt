package com.workbuddy2api.panel

import com.workbuddy2api.panel.util.agoText
import com.workbuddy2api.panel.util.bytesText
import com.workbuddy2api.panel.util.compactNum
import com.workbuddy2api.panel.util.creditsText
import com.workbuddy2api.panel.util.maskKey
import com.workbuddy2api.panel.util.msText
import com.workbuddy2api.panel.util.parseIso
import com.workbuddy2api.panel.util.percentText
import com.workbuddy2api.panel.util.remainText
import com.workbuddy2api.panel.util.shortUid
import com.workbuddy2api.panel.util.uptimeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test fun zeroTimeIsNull() {
        assertNull(parseIso("0001-01-01T00:00:00Z"))
        assertNull(parseIso(null))
        assertNull(parseIso(""))
    }

    @Test fun goNanosecondTimeParses() {
        assertNotNull(parseIso("2026-10-19T19:43:53.8707967+08:00"))
        assertNotNull(parseIso("2026-10-19T22:10:15Z"))
    }

    @Test fun credits() {
        assertEquals("不限", creditsText(-1.0))
        assertEquals("1,532", creditsText(1532.0))
    }

    @Test fun numbers() {
        assertEquals("1,234", compactNum(1234L))
        assertTrue(compactNum(1_234_567L).contains("M"))
        assertTrue(compactNum(2_500_000_000L).contains("B"))
    }

    @Test fun percent() {
        assertEquals("80%", percentText(80.0))
        assertTrue(percentText(94.16).startsWith("94."))
    }

    @Test fun durations() {
        assertTrue(remainText(7).contains("秒"))
        assertTrue(remainText(3600).contains("小时"))
        assertTrue(uptimeText(2 * 86400 + 3 * 3600).contains("天"))
        assertTrue(msText(820).contains("ms"))
        assertTrue(msText(3309).contains("s"))
    }

    @Test fun bytesAndIds() {
        assertTrue(bytesText(178_348).contains("KB") || bytesText(178_348).contains("MB"))
        assertEquals("90985b0b", shortUid("90985b0b-8960-4a1e-9801-7864eaf91d86"))
        assertTrue(maskKey("sk-abcdefghijklmn").contains("****"))
        assertEquals("—", agoText("0001-01-01T00:00:00Z"))
    }
}
