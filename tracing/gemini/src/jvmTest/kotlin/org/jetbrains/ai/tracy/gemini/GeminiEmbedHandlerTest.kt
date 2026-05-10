/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

private val JSON = "application/json".toMediaType()
private const val MOCK_API_KEY = "mock-api-key"

/**
 * MockWebServer-based tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiEmbedHandler].
 *
 * No real API keys required — all requests are intercepted by the mock server.
 */
@Tag("gemini")
class GeminiEmbedHandlerTest : BaseAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), GeminiLLMTracingAdapter()).newBuilder().build()

    private fun MockWebServer.enqueueVertexAiEmbedResponse(dimension: Int = 768) {
        val values = (1..dimension).joinToString(",") { "0.${it % 10}" }
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "predictions": [
                        {
                          "embeddings": {
                            "values": [$values],
                            "statistics": {"truncated": false, "token_count": 10}
                          }
                        }
                      ]
                    }
                    """.trimIndent()
                )
        )
    }

    private fun MockWebServer.enqueueDirectEmbedResponse(dimension: Int = 256) {
        val values = (1..dimension).joinToString(",") { "0.${it % 10}" }
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"embedding": {"values": [$values]}}""")
        )
    }

    // ============ Vertex AI embed (model:predict) ============

    @Test
    fun `Vertex AI embed sets operation name to embedContent`() = runTest {
        withMockServer { server ->
            server.enqueueVertexAiEmbedResponse()
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/test/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post("""{"instances":[{"content":"hello"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("embedContent", trace!!.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `Vertex AI embed sets output type to embedding`() = runTest {
        withMockServer { server ->
            server.enqueueVertexAiEmbedResponse()
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/test/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post("""{"instances":[{"content":"hello"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("embedding", trace!!.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `Vertex AI embed parses task_type from instances`() = runTest {
        withMockServer { server ->
            server.enqueueVertexAiEmbedResponse()
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/test/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post(
                        """{"instances":[{"content":"hello","task_type":"RETRIEVAL_DOCUMENT"}]}"""
                            .toRequestBody(JSON)
                    )
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("RETRIEVAL_DOCUMENT", trace!!.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
        }
    }

    @Test
    fun `Vertex AI embed parses outputDimensionality from parameters`() = runTest {
        withMockServer { server ->
            server.enqueueVertexAiEmbedResponse(dimension = 256)
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/test/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post(
                        """{"instances":[{"content":"hello"}],"parameters":{"outputDimensionality":256}}"""
                            .toRequestBody(JSON)
                    )
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals(256L, trace!!.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
        }
    }

    @Test
    fun `Vertex AI embed parses embedding dimension from predictions response`() = runTest {
        withMockServer { server ->
            server.enqueueVertexAiEmbedResponse(dimension = 768)
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/test/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post("""{"instances":[{"content":"hello"}]}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals(768L, trace!!.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }

    // ============ Direct Gemini API (model:embedContent) ============

    @Test
    fun `direct embedContent sets operation name to embedContent`() = runTest {
        withMockServer { server ->
            server.enqueueDirectEmbedResponse()
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post("""{"content":{"parts":[{"text":"hello"}]}}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("embedContent", trace!!.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `direct embedContent parses taskType and outputDimensionality`() = runTest {
        withMockServer { server ->
            server.enqueueDirectEmbedResponse(dimension = 256)
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(
                        """{"content":{"parts":[{"text":"hello"}]},"taskType":"SEMANTIC_SIMILARITY","outputDimensionality":256}"""
                            .toRequestBody(JSON)
                    )
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("SEMANTIC_SIMILARITY", trace!!.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
            assertEquals(256L, trace.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
        }
    }

    @Test
    fun `direct embedContent parses embedding dimension from response`() = runTest {
        withMockServer { server ->
            server.enqueueDirectEmbedResponse(dimension = 256)
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post("""{"content":{"parts":[{"text":"hello"}]}}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals(256L, trace!!.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }

    // ============ gemini.api.type ============

    @Test
    fun `embed requests set gemini_api_type to models`() = runTest {
        withMockServer { server ->
            server.enqueueDirectEmbedResponse()
            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post("""{"content":{"parts":[{"text":"hello"}]}}""".toRequestBody(JSON))
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().firstOrNull()
            assertNotNull(trace)
            assertEquals("models", trace!!.attributes[AttributeKey.stringKey("gemini.api.type")])
        }
    }
}
