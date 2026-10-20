package com.piko.agent.ai

import com.piko.agent.core.ActionParser
import com.piko.agent.core.ActionType
import com.piko.agent.model.EndpointProtocol
import com.piko.agent.model.ModelConfig
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class LlmClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LlmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LlmClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(maxRetries: Int = 0): ProviderConfig = ProviderConfig(
        id = "test",
        name = "Test provider",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        protocol = EndpointProtocol.OPENAI_CHAT,
        selectedModelId = "test-model",
        models = listOf(
            ModelConfig(
                id = "test-model",
                protocol = EndpointProtocol.OPENAI_CHAT,
                supportsTools = true,
            ),
        ),
        maxRetries = maxRetries,
        timeoutSeconds = 10,
    )

    private fun request(allowTools: Boolean = true) = LlmRequest(
        systemPrompt = "You are Piko.",
        userPrompt = "Tap the button.",
        sessionId = "session-test",
        maxOutputTokens = 100,
        allowTools = allowTools,
    )

    private val chatResponse = """
        {"id":"chat-1","choices":[{"message":{"role":"assistant","content":null,
        "tool_calls":[{"id":"c1","type":"function","function":{"name":"execute_action",
        "arguments":"{\"action\":{\"type\":\"tap\",\"elementId\":\"element_1\"}}"}}]},
        "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":80,"completion_tokens":20}}
    """.trimIndent()

    @Test
    fun `complete sends bearer auth and parses tool call`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatResponse))
        val result = client.complete(
            config = provider(),
            model = provider().selectedModel!!,
            apiKey = "sk-test-secret",
            request = request(),
            allowInsecureEndpoints = true,
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test-secret", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("User-Agent")!!.startsWith("Piko/"))
        assertTrue(recorded.body.readUtf8().contains("execute_action"))
        val parsed = ActionParser.parse(result.actionCandidate) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.TAP, parsed.action.type)
        assertEquals(80, result.usage.inputTokens)
    }

    @Test
    fun `rate limit is retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"slow down"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(chatResponse))
        val result = client.complete(
            config = provider(maxRetries = 1),
            model = provider(maxRetries = 1).selectedModel!!,
            apiKey = "key",
            request = request(),
            allowInsecureEndpoints = true,
        )
        assertEquals("execute_action", result.toolName)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `authentication error is not retried`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val error = runCatching {
            runBlocking {
                client.complete(
                    config = provider(),
                    model = provider().selectedModel!!,
                    apiKey = "bad-key",
                    request = request(),
                    allowInsecureEndpoints = true,
                )
            }
        }.exceptionOrNull()
        assertTrue(error is LlmException)
        assertEquals(LlmErrorKind.AUTHENTICATION, (error as LlmException).kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `tool rejection falls back to JSON mode`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"tools are not supported by this model"}}"""),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"action\":{\"type\":\"home\"}}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":8}}""",
            ),
        )
        val result = client.complete(
            config = provider(),
            model = provider().selectedModel!!,
            apiKey = "key",
            request = request(),
            allowInsecureEndpoints = true,
        )
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.body.readUtf8().contains("\"tools\""))
        assertFalse(second.body.readUtf8().contains("\"tools\""))
        val parsed = ActionParser.parse(result.actionCandidate) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.HOME, parsed.action.type)
    }

    @Test
    fun `streaming chat accumulates tool call fragments`() = runBlocking {
        val stream = buildString {
            appendLine("""data: {"id":"s1","choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"c1","function":{"name":"execute_action","arguments":"{\"action\":"}}]},"finish_reason":null}]}""")
            appendLine()
            appendLine("""data: {"id":"s1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"type\":\"tap\",\"elementId\":\"element_5\"}}"}}]},"finish_reason":"tool_calls"}]}""")
            appendLine()
            appendLine("""data: {"id":"s1","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":5}}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(stream),
        )
        val result = client.complete(
            config = provider(),
            model = provider().selectedModel!!,
            apiKey = "key",
            request = request().copy(stream = true),
            allowInsecureEndpoints = true,
        )
        assertEquals("execute_action", result.toolName)
        assertEquals(20, result.usage.inputTokens)
        assertEquals(5, result.usage.outputTokens)
        val parsed = ActionParser.parse(result.actionCandidate) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.TAP, parsed.action.type)
        assertEquals("element_5", parsed.action.elementId)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun `test connection validates inference and discovers models`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":2}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"object":"list","data":[{"id":"model-a"},{"id":"model-b"}]}""",
            ),
        )
        val result = client.testConnection(provider(), "key", allowInsecureEndpoints = true)
        assertTrue(result.success)
        assertEquals(2, result.models.size)
        assertEquals("/v1/chat/completions", server.takeRequest().path)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun `cancellation interrupts an in-flight request`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setBody(chatResponse),
        )
        val job = launch(Dispatchers.Default) {
            runCatching {
                client.complete(
                    config = provider(),
                    model = provider().selectedModel!!,
                    apiKey = "key",
                    request = request(),
                    allowInsecureEndpoints = true,
                )
            }
        }
        delay(300)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }
}
