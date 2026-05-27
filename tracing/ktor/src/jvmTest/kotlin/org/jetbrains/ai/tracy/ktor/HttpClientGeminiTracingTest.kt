/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.ktor

import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("gemini")
class HttpClientGeminiTracingTest : BaseAITracingTest() {
    private val llmProviderUrl: String? = System.getenv("LLM_PROVIDER_URL")
    private val llmProviderApiKey =
        System.getenv("GEMINI_API_KEY") ?: System.getenv("LLM_PROVIDER_API_KEY")
        ?: error("Neither GEMINI_API_KEY nor LLM_PROVIDER_API_KEY environment variables are set")

    // TODO: fix
    // Gemini tests for now is only supported with LiteLLM
    @EnabledIfEnvironmentVariable(
        named = "LLM_PROVIDER_URL",
        matches = "https://litellm.labs.jb.gg",
        disabledReason = "LLM_PROVIDER_URL environment variable is not https://litellm.labs.jb.gg",
    )
    @Test
    fun `test Ktor HttpClient auto tracing for Gemini`() = runTest {
        val client: HttpClient = instrument(HttpClient(), adapter = GeminiLLMTracingAdapter())

        val model = "gemini-2.5-flash"
        val promptMessage = "Explain how AI works in a few words"

        val projectId = "jetbrains-grazie"
        val location = "us-central1"
        val url =
            "${llmProviderUrl}/vertex_ai/v1/projects/$projectId/locations/$location/publishers/google/models/$model:generateContent"

        val response = client.post(url) {
            // TODO: fix
            header("x-litellm-api-key", "Bearer $llmProviderApiKey")
            header("Content-Type", "application/json")
            setBody(
                """
                {
                    "contents": [
                        {
                            "parts": [
                                { "text": "$promptMessage" }
                            ],
                            "role": "user"
                        }
                    ]
                }
            """.trimIndent()
            )
        }

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()

        assertEquals(StatusCode.OK, trace.status.statusCode)
        assertEquals("gemini", trace.attributes[AttributeKey.stringKey("gen_ai.system")])
        assertEquals(
            llmProviderUrl,
            trace.attributes[AttributeKey.stringKey("gen_ai.api_base")]
        )

        val tracedModel = trace.attributes[AttributeKey.stringKey("gen_ai.response.model")]
        assertNotNull(tracedModel)
        assertTrue(tracedModel.startsWith(model))

        val tracedPrompt = trace.attributes[AttributeKey.stringKey("gen_ai.prompt.0.content")]
        assertEquals(promptMessage, tracedPrompt)

        val completionRole = trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.role")]
        val completionContent = trace.attributes[AttributeKey.stringKey("gen_ai.completion.0.content")]

        assertFalse(completionRole.isNullOrEmpty())

        assertFalse(completionContent.isNullOrEmpty())

        // assert that tracing doesn't consume the response body
        val responseBody = response.bodyAsText()
        assertTrue(responseBody.isNotEmpty())

        val responseJson = Json.parseToJsonElement(responseBody).jsonObject

        assertEquals(
            responseJson["responseId"]!!.jsonPrimitive.content,
            trace.attributes[AttributeKey.stringKey("gen_ai.response.id")]
        )
        assertEquals(responseJson["modelVersion"]!!.jsonPrimitive.content, tracedModel)

        val firstResponseContent = responseJson["candidates"]?.jsonArray[0]?.jsonObject["content"]?.jsonObject

        assertEquals(
            firstResponseContent?.get("role")?.jsonPrimitive?.content,
            completionRole
        )
        assertEquals(
            firstResponseContent?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject["text"]?.jsonPrimitive?.content,
            completionContent
        )
        assertEquals(
            responseJson["usageMetadata"]!!.jsonObject["promptTokenCount"]!!.jsonPrimitive.int,
            trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")]!!.toInt()
        )
        assertEquals(
            responseJson["usageMetadata"]!!.jsonObject["candidatesTokenCount"]!!.jsonPrimitive.int,
            trace.attributes[AttributeKey.longKey("gen_ai.usage.output_tokens")]!!.toInt()
        )
    }

    @EnabledIfEnvironmentVariable(
        named = "LLM_PROVIDER_URL",
        matches = "https://litellm.labs.jb.gg",
        disabledReason = "LLM_PROVIDER_URL environment variable is not https://litellm.labs.jb.gg",
    )
    @Test
    fun `test Ktor HttpClient traces systemInstruction for Gemini`() = runTest {
        val client: HttpClient = instrument(HttpClient(), adapter = GeminiLLMTracingAdapter())

        val model = "gemini-2.5-flash"
        val url =
            "${llmProviderUrl}/vertex_ai/v1/projects/jetbrains-grazie/locations/us-central1/publishers/google/models/$model:generateContent"

        val response = client.post(url) {
            header("x-litellm-api-key", "Bearer $llmProviderApiKey")
            header("Content-Type", "application/json")
            setBody(
                """
                {
                    "systemInstruction": {
                        "parts": [
                            { "text": "You are a terse assistant. Answer in one sentence." }
                        ]
                    },
                    "contents": [
                        { "role": "user", "parts": [{ "text": "Say hi" }] }
                    ]
                }
                """.trimIndent()
            )
        }

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()

        assertEquals(StatusCode.OK, trace.status.statusCode)
        val sysInstr = trace.attributes[AttributeKey.stringKey("tracy.request.system_instruction")]
        assertNotNull(sysInstr)
        assertTrue(sysInstr.contains("terse assistant"))

        // Sanity: response was not consumed by tracing.
        val responseBody = response.bodyAsText()
        assertTrue(responseBody.isNotEmpty())
    }

    @EnabledIfEnvironmentVariable(
        named = "LLM_PROVIDER_URL",
        matches = "https://litellm.labs.jb.gg",
        disabledReason = "LLM_PROVIDER_URL environment variable is not https://litellm.labs.jb.gg",
    )
    @Test
    fun `test Ktor HttpClient traces inlineData media upload for Gemini`() = runTest {
        val client: HttpClient = instrument(HttpClient(), adapter = GeminiLLMTracingAdapter())

        val model = "gemini-2.5-flash"
        val url =
            "${llmProviderUrl}/vertex_ai/v1/projects/jetbrains-grazie/locations/us-central1/publishers/google/models/$model:generateContent"

        // 1x1 transparent PNG, base64-encoded.
        val pngPixelB64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgAAIAAAUAAen6n+gAAAAASUVORK5CYII="

        val response = client.post(url) {
            header("x-litellm-api-key", "Bearer $llmProviderApiKey")
            header("Content-Type", "application/json")
            setBody(
                """
                {
                    "contents": [
                        {
                            "role": "user",
                            "parts": [
                                { "text": "What is in this image?" },
                                { "inlineData": { "mimeType": "image/png", "data": "$pngPixelB64" } }
                            ]
                        }
                    ]
                }
                """.trimIndent()
            )
        }

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()

        // MediaContentExtractor attributes verify the inlineData blob was captured for upload.
        assertEquals("base64", trace.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.type")])
        assertEquals("input", trace.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.field")])
        assertEquals("image/png", trace.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.contentType")])
        assertEquals(pngPixelB64, trace.attributes[AttributeKey.stringKey("custom.uploadableMediaContent.0.data")])

        // Multi-part prompt → content falls back to JSON-stringified parts array.
        val promptContent = trace.attributes[AttributeKey.stringKey("gen_ai.prompt.0.content")]
        assertNotNull(promptContent)
        assertTrue(promptContent.contains("inlineData"))

        val responseBody = response.bodyAsText()
        assertTrue(responseBody.isNotEmpty())
    }

    @EnabledIfEnvironmentVariable(
        named = "LLM_PROVIDER_URL",
        matches = "https://litellm.labs.jb.gg",
        disabledReason = "LLM_PROVIDER_URL environment variable is not https://litellm.labs.jb.gg",
    )
    @Test
    fun `test Ktor HttpClient traces tools toolConfig and safetySettings for Gemini`() = runTest {
        val client: HttpClient = instrument(HttpClient(), adapter = GeminiLLMTracingAdapter())

        val model = "gemini-2.5-flash"
        val url =
            "${llmProviderUrl}/vertex_ai/v1/projects/jetbrains-grazie/locations/us-central1/publishers/google/models/$model:generateContent"

        val response = client.post(url) {
            header("x-litellm-api-key", "Bearer $llmProviderApiKey")
            header("Content-Type", "application/json")
            setBody(
                """
                {
                    "contents": [
                        { "role": "user", "parts": [{ "text": "What's the weather in Paris?" }] }
                    ],
                    "tools": [
                        {
                            "functionDeclarations": [
                                {
                                    "name": "get_weather",
                                    "description": "Get the current weather",
                                    "parameters": {
                                        "type": "object",
                                        "properties": {
                                            "location": { "type": "string" }
                                        },
                                        "required": ["location"]
                                    }
                                }
                            ]
                        }
                    ],
                    "toolConfig": {
                        "functionCallingConfig": { "mode": "AUTO" }
                    },
                    "safetySettings": [
                        { "category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_MEDIUM_AND_ABOVE" }
                    ]
                }
                """.trimIndent()
            )
        }

        val traces = analyzeSpans()
        assertTracesCount(1, traces)
        val trace = traces.first()

        assertEquals("get_weather", trace.attributes[AttributeKey.stringKey("gen_ai.tool.0.function.0.name")])
        val toolConfig = trace.attributes[AttributeKey.stringKey("tracy.request.tool_config")]
        assertNotNull(toolConfig)
        assertTrue(toolConfig.contains("functionCallingConfig"))
        val safety = trace.attributes[AttributeKey.stringKey("tracy.request.safety_settings")]
        assertNotNull(safety)
        assertTrue(safety.contains("HARM_CATEGORY_HARASSMENT"))

        val responseBody = response.bodyAsText()
        assertTrue(responseBody.isNotEmpty())
    }
}
