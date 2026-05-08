/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GeminiEmbeddingsHandler] via [GeminiLLMTracingAdapter].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] so no real Gemini API key or network access is required.
 */
@Tag("gemini")
class GeminiEmbeddingsHandlerTest : BaseAITracingTest() {

    // ============ isEmbeddingsUrl routing ============

    @Test
    fun `test batchEmbedContents URL is routed to embeddings handler`() = runTest {
        withMockServer { server ->
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"embeddings":[{"values":[0.1,0.2,0.3]},{"values":[0.4,0.5,0.6]}]}"""
                    )
            )

            val requestBody =
                """{"requests":[{"model":"models/text-embedding-004","content":{"parts":[{"text":"Hello"}]}}]}"""
                    .toRequestBody("application/json".toMediaType())

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:batchEmbedContents"))
                    .post(requestBody)
                    .build()
            ).execute().use {}

            val trace = analyzeSpans().first()
            // Routing to the embeddings handler means gen_ai.output.type = "embedding"
            assertEquals("embedding", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    // ============ Operation name derivation ============

    @Test
    fun `test embedContent operation name is preserved`() = runTest {
        withMockServer { server ->
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"embedding":{"values":[0.1,0.2,0.3]}}""")
            )

            val requestBody =
                """{"content":{"parts":[{"text":"Hello"}]}}"""
                    .toRequestBody("application/json".toMediaType())

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(requestBody)
                    .build()
            ).execute().use {}

            val trace = analyzeSpans().first()
            assertEquals(
                "embedContent",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    @Test
    fun `test batchEmbedContents operation name is preserved`() = runTest {
        withMockServer { server ->
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[{"values":[0.1,0.2,0.3]}]}""")
            )

            val requestBody =
                """{"requests":[{"model":"models/text-embedding-004","content":{"parts":[{"text":"Hello"}]}}]}"""
                    .toRequestBody("application/json".toMediaType())

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:batchEmbedContents"))
                    .post(requestBody)
                    .build()
            ).execute().use {}

            val trace = analyzeSpans().first()
            assertEquals(
                "batchEmbedContents",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    @Test
    fun `test predict operation name is normalised to embedContent for embed models`() = runTest {
        withMockServer { server ->
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"embedding":{"values":[0.1,0.2,0.3]}}""")
            )

            val requestBody =
                """{"instances":[{"content":"Hello"}]}"""
                    .toRequestBody("application/json".toMediaType())

            // Vertex AI uses ":predict" for embed models
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/my-project/locations/us-central1/publishers/google/models/textembedding-gecko:predict"))
                    .post(requestBody)
                    .build()
            ).execute().use {}

            val trace = analyzeSpans().first()
            assertEquals(
                "embedContent",
                trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")]
            )
        }
    }

    // ============ Single-embed response ============

    @Test
    fun `test single embed response sets embedding dimension`() = runTest {
        withMockServer { server ->
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"embedding":{"values":[0.1,0.2,0.3,0.4,0.5]}}""")
            )

            val requestBody =
                """{"content":{"parts":[{"text":"Hello"}]}}"""
                    .toRequestBody("application/json".toMediaType())

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(requestBody)
                    .build()
            ).execute().use {}

            val trace = analyzeSpans().first()
            assertEquals(
                5L,
                trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")]
            )
            // count should not be set for single embed
            assertNull(trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.count")])
        }
    }

    // ============ Batch-embed response ============

    @Test
    fun `test batch embed response sets embedding count and dimension`() = runTest {
        withMockServer { server ->
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"embeddings":[{"values":[0.1,0.2,0.3]},{"values":[0.4,0.5,0.6]}]}"""
                    )
            )

            val requestBody =
                """{"requests":[{"model":"models/text-embedding-004","content":{"parts":[{"text":"A"}]}},{"model":"models/text-embedding-004","content":{"parts":[{"text":"B"}]}}]}"""
                    .toRequestBody("application/json".toMediaType())

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:batchEmbedContents"))
                    .post(requestBody)
                    .build()
            ).execute().use {}

            val trace = analyzeSpans().first()
            assertEquals(
                2L,
                trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.count")]
            )
            assertEquals(
                3L,
                trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")]
            )
        }
    }

    @Test
    fun `test batch embed response with single embedding sets count=1 and dimension`() = runTest {
        withMockServer { server ->
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"embeddings":[{"values":[0.1,0.2,0.3,0.4]}]}""")
            )

            val requestBody =
                """{"requests":[{"model":"models/text-embedding-004","content":{"parts":[{"text":"Hello"}]}}]}"""
                    .toRequestBody("application/json".toMediaType())

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:batchEmbedContents"))
                    .post(requestBody)
                    .build()
            ).execute().use {}

            val trace = analyzeSpans().first()
            assertEquals(1L, trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.count")])
            assertEquals(4L, trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }
}
