package com.workbuddy2api.panel

import com.workbuddy2api.panel.data.AccountTasks
import com.workbuddy2api.panel.data.ActionResult
import com.workbuddy2api.panel.data.LogEntry
import com.workbuddy2api.panel.data.LogsPage
import com.workbuddy2api.panel.data.ModelsPage
import com.workbuddy2api.panel.data.Overview
import com.workbuddy2api.panel.data.QueueStatus
import com.workbuddy2api.panel.data.ScanResult
import com.workbuddy2api.panel.data.UsageSnapshot
import com.workbuddy2api.panel.data.WbJson
import com.workbuddy2api.panel.data.WbRepository
import com.workbuddy2api.panel.data.RemoteWbRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用真实接口返回（裁剪版）验证 JSON 解析的容错性。 */
class DecodeTest {

    private val overviewJson = """
        {"version":"1.4.2","uptime_sec":86412,"auth_required":true,"redis_mode":"noop",
         "sticky_sessions":3,"total":2,"healthy":1,"cooling":1,"disabled":0,"paused":0,
         "in_flight_full":0,
         "accounts":[
           {"uid":"90985b0b-8960-4a1e-9801-7864eaf91d86","nickname":"紫川","credits":1532,
            "credits_total":2000,"credits_expiring":300,
            "credits_earliest_expiry":"2026-10-20T20:56:34+08:00","credits_earliest_remaining":76,
            "cooling":false,"until":"0001-01-01T00:00:00Z","realm":"cn","disabled":false,
            "success_count":168,"last_success":"2026-10-19T19:43:53.8707967+08:00",
            "last_err":"0001-01-01T00:00:00Z","checkin_done":true,
            "token_usage":{"request_count":168,"usage_count":168,"prompt_tokens":4246270,
              "completion_tokens":28743,"total_tokens":4275013,"last_latency_ms":22466,
              "last_tokens_per_second":28.85,
              "last_used_at":"2026-10-19T19:43:53.8707967+08:00","last_model":"glm-5.3-flash"},
            "model_costs":[{"model":"glm-5.3-flash","cost_per_1k":0.004,
              "last_seen":"2026-10-19T19:43:53.8707967+08:00","samples":135}],
            "consecutive_fails":0,"degrade_until":"0001-01-01T00:00:00Z","in_flight":0,
            "breaker_fails":0,"breaker_until":"0001-01-01T00:00:00Z"},
           {"uid":"ba84e00b-c2c7-40f4-9551-3ee2ffa86bb6","nickname":"x@y.com","credits":350,
            "credits_total":350,"cooling":true,"cool_kind":"soft_rate","cool_remaining_sec":7,
            "until":"2026-10-19T22:10:24+08:00","reason":"429 rate limit","realm":"global",
            "last_success":"2026-10-19T22:10:15+08:00","checkin_done":true,"in_flight":2}
         ],
         "model_locks":null}
    """.trimIndent()

    @Test fun overviewParses() {
        val o = WbJson.decodeFromString<Overview>(overviewJson)
        assertEquals(2, o.total)
        assertEquals("1.4.2", o.version)
        assertEquals(2, o.accounts.size)
        val a = o.accounts[0]
        assertEquals("紫川", a.nickname)
        assertEquals(1532.0, a.credits, 0.001)
        assertEquals("cn", a.realm)
        assertTrue(a.checkinDone)
        assertEquals(168L, a.tokenUsage?.requestCount)
        assertEquals("glm-5.3-flash", a.tokenUsage?.lastModel)
        assertEquals(1, a.modelCosts.size)
        assertNull(o.modelLocks)
        assertTrue(o.accounts[1].cooling)
        assertEquals(7L, o.accounts[1].coolRemainingSec)
    }

    @Test fun logsAndTasksParse() {
        val logs = WbJson.decodeFromString<LogsPage>(
            """{"entries":[{"ts":"2026-10-19T22:03:48.0255242+08:00","ch":"sys","text":"WARN hello"}]}""",
        )
        assertEquals(1, logs.entries.size)
        assertEquals("sys", logs.entries[0].ch)

        val tasks = WbJson.decodeFromString<AccountTasks>(
            """{"ok":true,"tasks":[{"task_code":"chat_5","title":"对话 5 次","target":5,"current":2,
               "accept_status":"accepted","credit":100}]}""",
        )
        assertTrue(tasks.ok)
        assertEquals("chat_5", tasks.tasks?.first()?.taskCode)
        assertEquals(5, tasks.tasks?.first()?.target)
        assertEquals(100.0, tasks.tasks?.first()?.credit ?: 0.0, 0.001)
    }

    @Test fun queueAndScanParse() {
        val q = WbJson.decodeFromString<QueueStatus>(
            """{"conc":2,"items":[{"uid":"u1","nickname":"n","kind":"growth","code":"chat_5",
               "status":"running","message":"执行中"}],"running":true,"seq":4,"started":true,
               "started_at":"2026-10-19T22:02:44+08:00","total":1}""",
        )
        assertTrue(q.running)
        assertEquals("running", q.items?.first()?.status)

        val s = WbJson.decodeFromString<ScanResult>(
            """{"ok":true,"accounts":[{"uid":"","nickname":""},
               {"uid":"u2","nickname":"二号","growth":[{"task_code":"a","target":1,"current":0}]}],
               "pending_count":1}""",
        )
        assertEquals(2, s.accounts?.size)
        assertEquals("", s.accounts?.first()?.uid)
        assertEquals(1, s.accounts?.get(1)?.growth?.size)
        assertEquals(1, s.pendingCount)
    }

    @Test fun usageToleratesNulls() {
        val u = WbJson.decodeFromString<UsageSnapshot>(
            """{"totals":{"requests":501,"errors":100,"total_tokens":16323074,"credits":16.71},
               "by_realm":[],"by_account":[],"by_model":[],"series":null,"buckets":72,
               "file_bytes":178348,"generated":"2026-10-19T22:10:00+08:00"}""",
        )
        assertEquals(501L, u.totals.requests)
        assertEquals(0, u.byModel?.size)
        assertNull(u.series)
    }

    @Test fun actionResultsParse() {
        val r = WbJson.decodeFromString<ActionResult>(
            """{"ok":true,"checkin_done":true,"credits":1520,"credits_total":2000,
               "checkin_message":"今天已签到"}""",
        )
        assertTrue(r.ok)
        assertTrue(r.checkinDone == true)
        assertEquals(1520.0, r.credits ?: 0.0, 0.001)
        assertEquals("今天已签到", r.checkinMessage)
    }

    @Test fun modelsPageParses() {
        val m = WbJson.decodeFromString<ModelsPage>(
            """{"ok":true,"models":[{"id":"cn:glm-5.2","name":"GLM-5.2","supported_efforts":null,
               "promo_credits":"0x","context_length":128000}]}""",
        )
        assertTrue(m.ok)
        assertEquals("cn:glm-5.2", m.models?.first()?.id)
        assertNull(m.models?.first()?.supportedEfforts)
    }

    @Test fun repositoryUsesBearerAndDecodes() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(
                okhttp3.mockwebserver.MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(overviewJson),
            )
            val repo: WbRepository = RemoteWbRepository(server.url("/").toString(), "sk-test")
            val o = repo.overview()
            assertEquals(2, o.accounts.size)
            val req = server.takeRequest()
            assertEquals("/panel/api/overview", req.path)
            assertEquals("Bearer sk-test", req.getHeader("Authorization"))
            repo.close()
        } finally {
            server.shutdown()
        }
    }

    private fun runBlocking(block: suspend () -> Unit) =
        kotlinx.coroutines.runBlocking { block() }
}
