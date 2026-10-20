package com.workbuddy2api.panel

import com.workbuddy2api.panel.data.WbHttpException
import com.workbuddy2api.panel.data.agent.ChatMessage
import com.workbuddy2api.panel.data.agent.OpenAiClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Agent 的 OpenAI 兼容客户端：模型列表与 SSE 流式解析。 */
class OpenAiClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client(): OpenAiClient = OpenAiClient(server.url("/").toString(), "sk-test")

    @Test fun modelsParsesOpenAiShape() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[{"id":"cn:glm-5.2"},{"id":"global:deepseek-v4.1-flash"}]}"""),
        )
        val models = client().models()
        assertEquals(2, models.size)
        assertEquals("cn:glm-5.2", models[0].id)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
    }

    @Test fun chatStreamEmitsDeltas() = runBlocking {
        val sse = buildString {
            append("""data: {"choices":[{"delta":{"content":"你好"}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{"content":"，世界"}}]}""").append("\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse),
        )
        val chunks = client().chatStream(
            model = "cn:glm-5.2",
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            systemPrompt = "你是助手",
        ).toList()
        assertEquals(listOf("你好", "，世界"), chunks)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("cn:glm-5.2"))
        assertTrue(body.contains("\"role\":\"system\"") || body.contains("你是助手"))
    }

    @Test fun unauthorizedSurfacesChineseMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"ok":false,"error":"invalid_api_key"}"""),
        )
        try {
            client().models()
            fail("expected WbHttpException")
        } catch (e: WbHttpException) {
            assertEquals(401, e.status)
            assertTrue(e.message.contains("API Key"))
        }
    }
}
