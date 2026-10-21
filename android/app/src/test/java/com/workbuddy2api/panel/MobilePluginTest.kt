package com.workbuddy2api.panel

import com.workbuddy2api.panel.dsh.MobilePlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 内置移动端插件（@workbuddy/dsh-mobile）的安装位置与标识。 */
class MobilePluginTest {

    @Test
    fun packageDirIsUnderProfileNodeModules() {
        val home = File("/data/user/0/com.workbuddy2api.panel/files/dsh-home")
        val expected = File(home, "dshhome/profiles/web/node_modules/@workbuddy/dsh-mobile")
        assertEquals(expected, MobilePlugin.packageDir(home))
    }

    /** DSH loader 用 profile 作 baseUrl 解析 insert 里的包名：装到别处会 ERR_MODULE_NOT_FOUND。 */
    @Test
    fun packageDirStaysInsideProfile() {
        val home = File("/tmp/dsh-home")
        val dir = MobilePlugin.packageDir(home)
        // 用 / 归一化：Windows 上 File.path 是反斜杠，测试要跨平台
        assertTrue(dir.path.replace('\\', '/').contains("dshhome/profiles/web/node_modules"))
    }

    @Test
    fun identifierIsStable() {
        assertEquals("wb-mobile", MobilePlugin.ID)
        assertEquals("@workbuddy/dsh-mobile", MobilePlugin.PACKAGE)
    }
}
