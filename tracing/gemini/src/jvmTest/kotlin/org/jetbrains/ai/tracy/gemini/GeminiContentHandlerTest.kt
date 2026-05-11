/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.genai.Client as GeminiClient
import com.google.genai.types.CreateCachedContentConfig
import com.google.genai.types.EmbedContentConfig
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.HttpOptions as GeminiHttpOptions
import com.google.genai.types.ListCachedContentsConfig
import com.google.genai.types.Part
import com.google.genai.types.Content
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.gemini.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit-style tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiContentGenHandler]
 * using [okhttp3.mockwebserver.MockWebServer] — no real API key or LiteLLM proxy required.
 */
@Tag("gemini")
class GeminiContentHandlerTest : BaseAITracingTest() {

    private fun createMockGeminiClient(baseUrl: String): GeminiClient =
        GeminiClient.builder()
            .apiKey("mock-api-key")
            .httpOptions(GeminiHttpOptions.builder().baseUrl(baseUrl).build())
            .build()

    @Test
    fun `generateContent sets gen_ai_output_type to message`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "candidates": [{
                            "content": {"role":"model","parts":[{"text":"Hi!"}]},
                            "finishReason": "STOP"
                          }],
                          "usageMetadata": {
                            "promptTokenCount": 3,
                            "candidatesTokenCount": 2,
                            "totalTokenCount": 5
                          }
                        }
                        """.trimIndent()
                    )
            )

            runCatching { client.models.generateContent("gemini-2.5-flash", "Say hi", null) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals("message", spans.first().attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `embedContent sets gen_ai_output_type to embedding with dimension and count`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            // The Gemini SDK routes embedContent(model, text) through batchEmbedContents HTTP endpoint
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[{"values":[0.1,0.2,0.3]}]}""")
            )

            runCatching { client.models.embedContent("text-embedding-004", "hello world", null) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()
            assertEquals("embedding", span.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(3L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
            assertEquals(1L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.count")])
        }
    }

    @Test
    fun `countTokens sets gen_ai_usage_total_tokens`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"totalTokens":42}""")
            )

            runCatching { client.models.countTokens("gemini-2.5-flash", "count these tokens", null) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(42L, spans.first().attributes[AttributeKey.longKey("gen_ai.usage.total_tokens")])
        }
    }

    @Test
    fun `caches create sets gemini_api_type cachedContents and gen_ai_output_type cached_content`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            val cacheResponse = """
                {
                  "name": "cachedContents/abc123",
                  "model": "models/gemini-2.5-flash",
                  "displayName": "test-cache",
                  "createTime": "2024-01-01T00:00:00Z",
                  "expireTime": "2024-01-01T01:00:00Z",
                  "usageMetadata": {"totalTokenCount": 500}
                }
            """.trimIndent()
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(cacheResponse)
            )

            runCatching {
                client.caches.create(
                    "gemini-2.5-flash",
                    CreateCachedContentConfig.builder()
                        .displayName("test-cache")
                        .contents(listOf(Content.fromParts(Part.fromText("Hello world " .repeat(100)))))
                        .build()
                )
            }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()
            assertEquals("cachedContents", span.attributes[AttributeKey.stringKey("gemini.api.type")])
            assertEquals("cached_content", span.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals("caches.create", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("test-cache", span.attributes[AttributeKey.stringKey("gen_ai.request.cache.display_name")])
            assertEquals("cachedContents/abc123", span.attributes[AttributeKey.stringKey("gen_ai.response.cache.name")])
            assertEquals("models/gemini-2.5-flash", span.attributes[AttributeKey.stringKey("gen_ai.response.cache.model")])
            assertEquals(500L, span.attributes[AttributeKey.longKey("gen_ai.response.cache.usage_metadata.total_token_count")])
        }
    }

    @Test
    fun `caches list sets gemini_api_type cachedContents and response_list_count`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"cachedContents":[{"name":"cachedContents/a"},{"name":"cachedContents/b"}]}""")
            )

            runCatching { client.caches.list(ListCachedContentsConfig.builder().build()) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()
            assertEquals("cachedContents", span.attributes[AttributeKey.stringKey("gemini.api.type")])
            assertEquals("caches.list", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(2L, span.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
            assertEquals(false, span.attributes[AttributeKey.booleanKey("gen_ai.response.list.has_more")])
        }
    }

    @Test
    fun `generateContent with seed sets gen_ai_request_seed`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":"Hi"}]},"finishReason":"STOP"}]}""")
            )

            val config = GenerateContentConfig.builder().seed(42).build()
            runCatching { client.models.generateContent("gemini-2.5-flash", "Hello", config) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(42L, spans.first().attributes[AttributeKey.longKey("gen_ai.request.seed")])
        }
    }

    @Test
    fun `generateContent with stop_sequences sets gen_ai_request_stop_sequences`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":"1, 2"}]},"finishReason":"STOP"}]}""")
            )

            val config = GenerateContentConfig.builder().stopSequences(listOf("5")).build()
            runCatching { client.models.generateContent("gemini-2.5-flash", "Count to 10", config) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val stopSeqs = spans.first().attributes[AttributeKey.stringKey("gen_ai.request.stop_sequences")]
            assertNotNull(stopSeqs, "gen_ai.request.stop_sequences should be set")
            assert(stopSeqs.isNotEmpty()) { "gen_ai.request.stop_sequences should be non-empty" }
        }
    }

    @Test
    fun `generateContent with response_mime_type sets gen_ai_request_response_mime_type`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"candidates":[{"content":{"role":"model","parts":[{"text":"{}"}]},"finishReason":"STOP"}]}""")
            )

            val config = GenerateContentConfig.builder().responseMimeType("application/json").build()
            runCatching { client.models.generateContent("gemini-2.5-flash", "Return JSON", config) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(
                "application/json",
                spans.first().attributes[AttributeKey.stringKey("gen_ai.request.response_mime_type")]
            )
        }
    }

    @Test
    fun `generateContent response with thoughtsTokenCount sets gen_ai_usage_thoughts_token_count`() = runTest {
        withMockServer { server ->
            val client = createMockGeminiClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "candidates": [{"content":{"role":"model","parts":[{"text":"Answer"}]},"finishReason":"STOP"}],
                          "usageMetadata": {
                            "promptTokenCount": 10,
                            "candidatesTokenCount": 5,
                            "thoughtsTokenCount": 128
                          }
                        }
                        """.trimIndent()
                    )
            )

            runCatching { client.models.generateContent("gemini-2.5-flash", "Solve it", null) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(128L, spans.first().attributes[AttributeKey.longKey("gen_ai.usage.thoughts_token_count")])
        }
    }

    @Test
    fun `Vertex AI embed predict single instance maps operation to embedContent`() = runTest {
        withMockServer { server ->
            // Create a Vertex AI client pointing to mock server
            val dummyCredentials = object : GoogleCredentials() {
                override fun refreshAccessToken(): AccessToken = AccessToken("dummy-token", null)
            }
            val client = GeminiClient.builder()
                .vertexAI(true)
                .project("test-project")
                .location("us-central1")
                .credentials(dummyCredentials)
                .httpOptions(GeminiHttpOptions.builder().baseUrl(server.url("/").toString()).build())
                .build()
                .apply { instrument(this) }

            // Vertex AI predict embed response format
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"predictions":[{"embeddings":{"values":[0.1,0.2,0.3]}}]}""")
            )

            runCatching { client.models.embedContent("gemini-embedding-001", "hello world", null) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()
            assertEquals("embedContent", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("embedding", span.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(3L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
            assertEquals(1L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.count")])
        }
    }

    @Test
    fun `Vertex AI embed predict multiple instances maps operation to batchEmbedContents`() = runTest {
        withMockServer { server ->
            val dummyCredentials = object : GoogleCredentials() {
                override fun refreshAccessToken(): AccessToken = AccessToken("dummy-token", null)
            }
            val client = GeminiClient.builder()
                .vertexAI(true)
                .project("test-project")
                .location("us-central1")
                .credentials(dummyCredentials)
                .httpOptions(GeminiHttpOptions.builder().baseUrl(server.url("/").toString()).build())
                .build()
                .apply { instrument(this) }

            // Vertex AI predict embed response for multiple inputs
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"predictions":[
                            {"embeddings":{"values":[0.1,0.2]}},
                            {"embeddings":{"values":[0.3,0.4]}},
                            {"embeddings":{"values":[0.5,0.6]}}
                        ]}"""
                    )
            )

            runCatching {
                client.models.embedContent("gemini-embedding-001", listOf("hello", "world", "test"), null)
            }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()
            assertEquals("batchEmbedContents", span.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("embedding", span.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals(3L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.count")])
            assertEquals(2L, span.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }
}
