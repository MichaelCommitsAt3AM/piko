package com.piko.agent.ai

import com.piko.agent.core.ActionParser
import com.piko.agent.core.ActionType
import com.piko.agent.core.AgentAction
import com.piko.agent.core.PikoJson
import com.piko.agent.model.EndpointProtocol
import com.piko.agent.model.ModelConfig
import com.piko.agent.model.ProviderCatalog
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LlmProtocolTest {
    private val request = LlmRequest(
        systemPrompt = "system",
        userPrompt = "user",
        imageJpeg = byteArrayOf(1, 2, 3),
        sessionId = "session-1",
        maxOutputTokens = 900,
    )
    private val model = ModelConfig(
        id = "test-model",
        displayName = "Test",
        protocol = EndpointProtocol.OPENAI_CHAT,
        supportsVision = true,
        supportsTools = true,
    )

    @Test
    fun `openai chat response parses native tool call`() {
        val body = """
            {"id":"chatcmpl-1","choices":[{"message":{"role":"assistant","content":null,
            "tool_calls":[{"id":"call_1","type":"function","function":{"name":"execute_action",
            "arguments":"{\"reasoning\":\"tap it\",\"action\":{\"type\":\"tap\",\"elementId\":\"element_3\"}}"}}]},
            "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":120,"completion_tokens":30}}
        """.trimIndent()
        val response = OpenAiChatProtocol.parseResponse(body, request)
        assertEquals("execute_action", response.toolName)
        assertEquals(120, response.usage.inputTokens)
        assertEquals(30, response.usage.outputTokens)
        val parsed = ActionParser.parse(response.actionCandidate) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.TAP, parsed.action.type)
        assertEquals("element_3", parsed.action.elementId)
    }

    @Test
    fun `openai chat parses string content fallback`() {
        val body = """{"choices":[{"message":{"content":"{\"action\":{\"type\":\"back\"}}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        val response = OpenAiChatProtocol.parseResponse(body, request)
        val parsed = ActionParser.parse(response.actionCandidate) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.BACK, parsed.action.type)
    }

    @Test
    fun `anthropic response parses tool use and cached tokens`() {
        val body = """
            {"id":"msg_1","type":"message","role":"assistant","content":[
              {"type":"text","text":"I will tap Display."},
              {"type":"tool_use","id":"tool_1","name":"execute_action","input":{"reasoning":"display","action":{"type":"tap","elementId":"element_2"}}}
            ],"stop_reason":"tool_use","usage":{"input_tokens":100,"output_tokens":20,"cache_read_input_tokens":30}}
        """.trimIndent()
        val response = AnthropicProtocol.parseResponse(body, request)
        assertEquals("execute_action", response.toolName)
        assertEquals(30, response.usage.cachedInputTokens)
        val parsed = ActionParser.parse(response.actionCandidate) as ActionParser.ActionParseResult.Success
        assertEquals("element_2", parsed.action.elementId)
    }

    @Test
    fun `responses api parses function call`() {
        val body = """
            {"id":"resp_1","status":"completed","output":[
              {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Done"}]},
              {"type":"function_call","name":"execute_action","arguments":"{\"action\":{\"type\":\"home\"}}","call_id":"c1"}
            ],"usage":{"input_tokens":50,"output_tokens":10,"input_tokens_details":{"cached_tokens":5}}}
        """.trimIndent()
        val response = OpenAiResponsesProtocol.parseResponse(body, request)
        assertEquals(5, response.usage.cachedInputTokens)
        val parsed = ActionParser.parse(response.actionCandidate) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.HOME, parsed.action.type)
    }

    @Test
    fun `openai chat request includes image and tools`() {
        val config = ProviderConfig(
            id = "test",
            name = "Test",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://example.com/v1",
            models = listOf(model),
        )
        val body = OpenAiChatProtocol.buildBody(config, model, request)
        val messages = body["messages"]
        assertTrue(messages is kotlinx.serialization.json.JsonArray)
        assertTrue(body.toString().contains("data:image/jpeg;base64"))
        assertTrue(body.toString().contains("execute_action"))
        assertTrue(body.toString().contains("\"tool_choice\":\"auto\""))
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertNotNull(messages)
    }

    @Test
    fun `anthropic request uses image block and input schema`() {
        val body = AnthropicProtocol.buildBody(model.copy(protocol = EndpointProtocol.ANTHROPIC_MESSAGES), request)
        val text = body.toString()
        assertTrue(text.contains("\"type\":\"image\""))
        assertTrue(text.contains("\"input_schema\""))
        assertTrue(text.contains("\"max_tokens\":900"))
    }

    @Test
    fun `model list parser handles both data and models arrays`() {
        val data = parseModelList("""{"data":[{"id":"a","display_name":"A"},{"id":"b"}]}""")
        val models = parseModelList("""{"models":[{"id":"c"}]}""")
        assertEquals(2, data.size)
        assertEquals("A", data.first().displayName)
        assertEquals("c", models.first().id)
        assertTrue(parseModelList("not json").isEmpty())
        assertTrue(parseModelList("""{"data":[]}""").isEmpty())
    }

    @Test
    fun `http error maps authentication and rate limit`() {
        val auth = protocolError(401, """{"error":{"message":"bad key"}}""", "secret")
        assertEquals(LlmErrorKind.AUTHENTICATION, auth.kind)
        val rate = protocolError(429, """{"error":{"message":"slow down"}}""", "secret")
        assertEquals(LlmErrorKind.RATE_LIMIT, rate.kind)
        assertTrue(rate.retryable)
    }

    @Test
    fun `error sanitizes api key`() {
        val error = protocolError(400, """{"error":{"message":"invalid key secret-123"}}""", "secret-123")
        assertTrue(!error.message!!.contains("secret-123"))
        assertTrue(error.message!!.contains("[redacted]"))
    }
}

class EndpointResolverTest {
    private val go = ProviderCatalog.openCodeGo()
    private val goModel = ModelConfig(
        id = "deepseek-v4.1-flash",
        protocol = EndpointProtocol.AUTO,
    )
    private val goAnthropicModel = ModelConfig(
        id = "claude-haiku-5-5",
        protocol = EndpointProtocol.AUTO,
    )
    private val goResponsesModel = ModelConfig(
        id = "gpt-6-luna",
        protocol = EndpointProtocol.AUTO,
    )

    @Test
    fun `opencode go routes documented protocols`() {
        assertEquals(EndpointProtocol.OPENAI_CHAT, LlmClient.resolveProtocol(go, goModel))
        assertEquals(EndpointProtocol.ANTHROPIC_MESSAGES, LlmClient.resolveProtocol(go, goAnthropicModel))
        assertEquals(EndpointProtocol.OPENAI_RESPONSES, LlmClient.resolveProtocol(go, goResponsesModel))
    }

    @Test
    fun `opencode go urls match published endpoint layout`() {
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            LlmClient.resolveChatUrl(go, goModel, EndpointProtocol.OPENAI_CHAT, false),
        )
        assertEquals(
            "https://opencode.ai/inference/go/anthropic/v1/messages",
            LlmClient.resolveChatUrl(go, goAnthropicModel, EndpointProtocol.ANTHROPIC_MESSAGES, false),
        )
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1/responses",
            LlmClient.resolveChatUrl(go, goResponsesModel, EndpointProtocol.OPENAI_RESPONSES, false),
        )
        assertEquals(
            "https://opencode.ai/inference/go/v1/models",
            LlmClient.resolveModelsUrl(go, false),
        )
    }

    @Test
    fun `openai and anthropic urls are constructed from base`() {
        val openAi = ProviderConfig(
            id = "openai",
            name = "OpenAI",
            kind = ProviderKind.OPENAI,
            baseUrl = "https://api.openai.com/v1",
        )
        val anthropic = ProviderConfig(
            id = "anthropic",
            name = "Anthropic",
            kind = ProviderKind.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1",
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            LlmClient.resolveChatUrl(openAi, goModel, EndpointProtocol.OPENAI_CHAT, false),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            LlmClient.resolveModelsUrl(openAi, false),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            LlmClient.resolveChatUrl(anthropic, goAnthropicModel, EndpointProtocol.ANTHROPIC_MESSAGES, false),
        )
    }

    @Test
    fun `http endpoint is rejected by default`() {
        val provider = ProviderConfig(
            id = "local",
            name = "Local",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:8080/v1",
        )
        val error = assertThrows(LlmException::class.java) {
            LlmClient.resolveChatUrl(provider, goModel, EndpointProtocol.OPENAI_CHAT, false)
        }
        assertEquals(LlmErrorKind.INVALID_REQUEST, error.kind)
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            LlmClient.resolveChatUrl(provider, goModel, EndpointProtocol.OPENAI_CHAT, true),
        )
    }

    @Test
    fun `provider config normalizes urls and models`() {
        val config = ProviderConfig(
            id = " id ",
            name = " ",
            kind = ProviderKind.OPENAI_COMPATIBLE,
            baseUrl = " https://example.com/v1/ ",
            selectedModelId = " ",
            models = listOf(ModelConfig(" a "), ModelConfig("a")),
            timeoutSeconds = 1,
            maxRetries = 99,
        ).normalized()
        assertEquals("https://example.com/v1", config.baseUrl)
        assertEquals("Provider", config.name)
        assertEquals(1, config.models.size)
        assertEquals("a", config.selectedModelId)
        assertEquals(15, config.timeoutSeconds)
        assertEquals(5, config.maxRetries)
    }

    @Test
    fun `action tool schema is valid JSON object`() {
        val schema = ActionToolSchema.parameters()
        val parsed = PikoJson.parseToJsonElement(schema.toString()).jsonObject
        assertEquals("object", parsed["type"]!!.jsonPrimitive.content)
        assertTrue(schema.toString().contains("request_confirmation"))
        assertTrue(schema.toString().contains("pendingAction"))
    }

    @Test
    fun `go catalog includes primary models`() {
        val ids = ProviderCatalog.openCodeGoModels().map { it.id }
        assertTrue(ids.contains("deepseek-v4.1-flash"))
        assertTrue(ids.contains("glm-5.3-flash"))
        assertTrue(ids.contains("kimi-k2.7-code"))
        assertTrue(ProviderCatalog.openCodeGoModels().all { it.supportsTools })
    }
}
