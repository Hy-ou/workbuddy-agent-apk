package com.workbuddy2api.panel

import com.workbuddy2api.panel.dsh.DshPlugins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 插件 id 映射与内置禁用策略的纯逻辑测试。 */
class DshPluginIdTest {

    @Test
    fun packageToPluginId() {
        assertEquals("office-to-pdf", DshPlugins.idForPackage("@deepseek-ai/dsh-office-to-pdf"))
        assertEquals("bash-local", DshPlugins.idForPackage("@deepseek-ai/dsh-bash-local"))
    }

    @Test
    fun builtinDisabledContainsMobileCritical() {
        assertTrue("office-to-pdf" in DshPlugins.BUILTIN_DISABLED)
        assertTrue("bash-sandbox" in DshPlugins.BUILTIN_DISABLED)
        assertTrue("pwsh-sandbox" in DshPlugins.BUILTIN_DISABLED)
    }
}
