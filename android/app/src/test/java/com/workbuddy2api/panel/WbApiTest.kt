package com.workbuddy2api.panel

import com.workbuddy2api.panel.data.QueueStartResult
import com.workbuddy2api.panel.data.RemoteWbRepository
import com.workbuddy2api.panel.data.WbApi
import com.workbuddy2api.panel.data.WbHttpException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class WbApiTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun api(): WbApi = WbApi(server.url("/").toString(), "sk-test")

    @Test fun getSendsBearerAndMapsPath() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"))
        val body = api().get("overview")
        assertEquals("{\"ok\":true}", body)
        val req = server.takeRequest()
        assertEquals("/panel/api/overview", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
    }

    @Test fun postSendsJsonBody() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"ok\":true}"))
        api().post("accounts/u1/checkin", "{}")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("{}", req.body.readUtf8())
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test fun unauthorizedMapsToApiKeyMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"ok\":false,\"error\":\"invalid_api_key\"}"))
        try {
            api().get("overview")
            fail("expected WbHttpException")
        } catch (e: WbHttpException) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("API Key"))
        }
    }

    @Test fun errorEnvelopeMessageIsSurfaced() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("{\"ok\":false,\"error\":\"user resource: boom\"}"))
        try {
            api().get("accounts/u1/balance")
            fail("expected WbHttpException")
        } catch (e: WbHttpException) {
            assertEquals(502, e.status)
            assertEquals("user resource: boom", e.message)
        }
    }

    @Test fun plainTextErrorFallsBackToStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405).setBody("Method Not Allowed"))
        try {
            api().get("checkin_all")
            fail("expected WbHttpException")
        } catch (e: WbHttpException) {
            assertEquals(405, e.status)
            assertTrue(e.message!!.contains("405"))
        }
    }

    @Test fun connectionFailureIsChineseIOException() = runBlocking {
        val dead = WbApi("http://127.0.0.1:1", "k")
        try {
            dead.get("overview")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("无法连接服务器"))
        }
    }

    @Test fun requestLogsBuildsQuery() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"entries\":[],\"limit\":50}"),
        )
        val repo = RemoteWbRepository(server.url("/").toString(), "k")
        val page = repo.requestLogs(limit = 50, outcome = "success", from = 1L, to = 2L)
        assertEquals(50, page.limit)
        val path = server.takeRequest().path!!
        assertTrue(path.contains("limit=50"))
        assertTrue(path.contains("outcome=success"))
        assertTrue(path.contains("from=1"))
        assertTrue(path.contains("to=2"))
    }

    @Test fun runQueuePostsConcurrency() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true,\"started\":false,\"message\":\"全部账号没有待办任务\"}"),
        )
        val repo = RemoteWbRepository(server.url("/").toString(), "k")
        val res: QueueStartResult = repo.runQueue(concurrency = 3)
        assertTrue(res.ok)
        assertEquals("全部账号没有待办任务", res.message)
        val req = server.takeRequest()
        assertEquals("/panel/api/tasks/run_queue", req.path)
        assertTrue(req.body.readUtf8().contains("\"concurrency\":3"))
    }
}
