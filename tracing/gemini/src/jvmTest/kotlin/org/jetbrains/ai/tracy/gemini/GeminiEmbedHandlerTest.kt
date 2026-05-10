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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiEmbedHandler] using [MockWebServer].
 *
 * No real network calls or API keys are required.
 */
@Tag("gemini")
class GeminiEmbedHandlerTest : BaseAITracingTest() {

    // ===== Native embedContent API =====

    @Test
    fun `embedContent sets operation name to embedContent`() = runTest {
        withMockServer { server ->
            server.enqueueNativeEmbedResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(buildNativeEmbedRequestBody())
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("embedContent", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `embedContent sets gemini api type to models`() = runTest {
        withMockServer { server ->
            server.enqueueNativeEmbedResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(buildNativeEmbedRequestBody())
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
        }
    }

    @Test
    fun `embedContent sets output type to embedding`() = runTest {
        withMockServer { server ->
            server.enqueueNativeEmbedResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(buildNativeEmbedRequestBody())
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("embedding", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `embedContent extracts task_type from camelCase taskType field`() = runTest {
        withMockServer { server ->
            server.enqueueNativeEmbedResponse()

            val body = """{"content":{"parts":[{"text":"Hello"}]},"taskType":"RETRIEVAL_DOCUMENT"}"""
            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("RETRIEVAL_DOCUMENT", trace.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
        }
    }

    @Test
    fun `embedContent extracts output_dimensionality from outputDimensionality field`() = runTest {
        withMockServer { server ->
            server.enqueueNativeEmbedResponse()

            val body = """{"content":{"parts":[{"text":"Hello"}]},"outputDimensionality":256}"""
            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(256L, trace.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
        }
    }

    @Test
    fun `embedContent computes embedding dimension from values array length`() = runTest {
        withMockServer { server ->
            server.enqueueNativeEmbedResponse(dimension = 768)

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/text-embedding-004:embedContent"))
                    .post(buildNativeEmbedRequestBody())
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(768L, trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }

    // ===== Vertex AI predict API for embed models =====

    @Test
    fun `vertex AI predict for embed model sets operation name to embedContent`() = runTest {
        withMockServer { server ->
            server.enqueueVertexEmbedResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/my-project/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post(buildVertexEmbedRequestBody())
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("embedContent", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `vertex AI predict for embed model extracts task_type from instances`() = runTest {
        withMockServer { server ->
            server.enqueueVertexEmbedResponse()

            val body = """{"instances":[{"content":"Hello","task_type":"RETRIEVAL_QUERY"}]}"""
            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/my-project/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("RETRIEVAL_QUERY", trace.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
        }
    }

    @Test
    fun `vertex AI predict for embed model extracts output_dimensionality from parameters`() = runTest {
        withMockServer { server ->
            server.enqueueVertexEmbedResponse()

            val body = """{"instances":[{"content":"Hello"}],"parameters":{"outputDimensionality":512}}"""
            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/my-project/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(512L, trace.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
        }
    }

    @Test
    fun `vertex AI predict for embed model computes dimension from predictions embeddings values`() = runTest {
        withMockServer { server ->
            server.enqueueVertexEmbedResponse(dimension = 256)

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/projects/my-project/locations/us-central1/publishers/google/models/text-embedding-004:predict"))
                    .post(buildVertexEmbedRequestBody())
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(256L, trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), GeminiLLMTracingAdapter())

    private fun buildNativeEmbedRequestBody(): okhttp3.RequestBody {
        val json = """{"content":{"parts":[{"text":"Hello, world!"}]}}"""
        return json.toRequestBody("application/json".toMediaType())
    }

    private fun buildVertexEmbedRequestBody(): okhttp3.RequestBody {
        val json = """{"instances":[{"content":"Hello, world!"}]}"""
        return json.toRequestBody("application/json".toMediaType())
    }

    private fun MockWebServer.enqueueNativeEmbedResponse(dimension: Int = 4) {
        val values = (1..dimension).joinToString(",") { "0.$it" }
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"embedding":{"values":[$values],"statistics":{"truncated":false,"tokenCount":6}}}""")
        )
    }

    private fun MockWebServer.enqueueVertexEmbedResponse(dimension: Int = 4) {
        val values = (1..dimension).joinToString(",") { "0.$it" }
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"predictions":[{"embeddings":{"values":[$values],"statistics":{"truncated":false,"token_count":6}}}]}""")
        )
    }
}
