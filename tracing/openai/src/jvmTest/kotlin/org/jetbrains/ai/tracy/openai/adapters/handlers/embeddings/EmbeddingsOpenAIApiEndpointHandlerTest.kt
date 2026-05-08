/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.embeddings

import com.openai.models.embeddings.EmbeddingCreateParams
import com.openai.models.embeddings.EmbeddingModel
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64

/**
 * Tests for [EmbeddingsOpenAIApiEndpointHandler].
 *
 * Uses [MockWebServer] so no real OpenAI API key or network access is required.
 */
@Tag("openai")
class EmbeddingsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ OPERATION NAME & API TYPE ============

    @Test
    fun `test operation name is embeddings`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse()

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("embeddings", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("embeddings", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    // ============ REQUEST: encoding_formats ============

    @Test
    fun `test encoding_formats is always set`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse()

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            val formats = trace.attributes[AttributeKey.stringArrayKey("gen_ai.request.encoding_formats")]
            assertNotNull(formats, "gen_ai.request.encoding_formats should be set")
            assertFalse(formats!!.isEmpty(), "gen_ai.request.encoding_formats should not be empty")
        }
    }

    @Test
    fun `test encoding_formats reflects explicit base64 format`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse(encodingFormat = "base64")

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .encodingFormat(EmbeddingCreateParams.EncodingFormat.BASE64)
                    .build()
            )

            val trace = analyzeSpans().first()
            val formats = trace.attributes[AttributeKey.stringArrayKey("gen_ai.request.encoding_formats")]
            assertNotNull(formats, "gen_ai.request.encoding_formats should be set")
            assertEquals(listOf("base64"), formats)
        }
    }

    // ============ REQUEST: model ============

    @Test
    fun `test request model is set`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse(model = "text-embedding-3-small")

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(
                "text-embedding-3-small",
                trace.attributes[AttributeKey.stringKey("gen_ai.request.model")]
            )
        }
    }

    // ============ RESPONSE: usage input_tokens ============

    @Test
    fun `test usage input_tokens is set from prompt_tokens`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse(promptTokens = 8)

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(8L, trace.attributes[AttributeKey.longKey("gen_ai.usage.input_tokens")])
        }
    }

    // ============ RESPONSE: response model ============

    @Test
    fun `test response model is set`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse(model = "text-embedding-3-small")

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(
                "text-embedding-3-small",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.model")]
            )
        }
    }

    // ============ RESPONSE: embeddings dimension count (float array) ============

    @Test
    fun `test dimension count is set from float array embedding`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            // 5-dimensional float vector
            server.enqueueEmbeddingResponse(embeddingValues = listOf(0.1, 0.2, 0.3, 0.4, 0.5))

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(5L, trace.attributes[AttributeKey.longKey("gen_ai.embeddings.dimension.count")])
        }
    }

    // ============ RESPONSE: embeddings dimension count (base64) ============

    @Test
    fun `test dimension count is set from base64-encoded embedding`() = runTest {
        // 3 float32 values = 12 bytes; base64-encode a 12-byte array (3 * 4 bytes)
        val floatCount = 3
        val bytes = ByteArray(floatCount * 4) // 12 zero bytes representing 3 float32 values
        val base64Encoded = Base64.getEncoder().encodeToString(bytes)

        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponseBase64(base64Encoded)

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .encodingFormat(EmbeddingCreateParams.EncodingFormat.BASE64)
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(
                floatCount.toLong(),
                trace.attributes[AttributeKey.longKey("gen_ai.embeddings.dimension.count")]
            )
        }
    }

    // ============ RESPONSE: dimension count absent when data is missing ============

    @Test
    fun `test dimension count is absent when data array is empty`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"object":"list","data":[],"model":"text-embedding-3-small","usage":{"prompt_tokens":5,"total_tokens":5}}"""
                    )
            )

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertNull(trace.attributes[AttributeKey.longKey("gen_ai.embeddings.dimension.count")])
        }
    }

    // ============ NETWORK ATTRIBUTES ============

    @Test
    fun `test network attributes are set`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse()

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals("openai", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")])
            assertNotNull(trace.attributes[AttributeKey.longKey("server.port")])
        }
    }

    // ============ HTTP STATUS CODE ============

    @Test
    fun `test http response status code is set`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30)
            ).apply { instrument(this) }

            server.enqueueEmbeddingResponse()

            client.embeddings().create(
                EmbeddingCreateParams.builder()
                    .model(EmbeddingModel.TEXT_EMBEDDING_3_SMALL)
                    .input("Hello world")
                    .build()
            )

            val trace = analyzeSpans().first()
            assertEquals(200L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        }
    }

    // ============ HELPER METHODS ============

    private fun MockWebServer.enqueueEmbeddingResponse(
        model: String = "text-embedding-3-small",
        promptTokens: Int = 8,
        embeddingValues: List<Double> = listOf(0.0023064255, -0.009327292, 0.015797347),
        encodingFormat: String = "float",
    ) {
        val embeddingArray = embeddingValues.joinToString(",")
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "object": "list",
                      "data": [
                        {
                          "object": "embedding",
                          "index": 0,
                          "embedding": [$embeddingArray]
                        }
                      ],
                      "model": "$model",
                      "usage": {
                        "prompt_tokens": $promptTokens,
                        "total_tokens": $promptTokens
                      }
                    }
                    """.trimIndent()
                )
        )
    }

    private fun MockWebServer.enqueueEmbeddingResponseBase64(base64Embedding: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "object": "list",
                      "data": [
                        {
                          "object": "embedding",
                          "index": 0,
                          "embedding": "$base64Embedding"
                        }
                      ],
                      "model": "text-embedding-3-small",
                      "usage": {
                        "prompt_tokens": 5,
                        "total_tokens": 5
                      }
                    }
                    """.trimIndent()
                )
        )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
