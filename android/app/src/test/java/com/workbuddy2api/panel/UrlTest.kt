package com.workbuddy2api.panel

import com.workbuddy2api.panel.data.apiBase
import com.workbuddy2api.panel.data.normalizeServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlTest {
    @Test fun addsHttpScheme() {
        assertEquals("http://192.168.1.10:7863", normalizeServerUrl("192.168.1.10:7863"))
    }

    @Test fun keepsHttps() {
        assertEquals("https://panel.example.com", normalizeServerUrl("https://panel.example.com/"))
    }

    @Test fun stripsPanelSuffix() {
        assertEquals("http://1.2.3.4:7863", normalizeServerUrl("http://1.2.3.4:7863/panel/api/"))
        assertEquals("http://1.2.3.4:7863", normalizeServerUrl("http://1.2.3.4:7863/panel"))
    }

    @Test fun buildsApiBase() {
        assertEquals("http://1.2.3.4:7863/panel/api", apiBase("1.2.3.4:7863"))
        assertEquals("https://x.io/panel/api", apiBase("https://x.io/"))
    }

    @Test fun blankStaysBlank() {
        assertEquals("", normalizeServerUrl("   "))
    }
}
