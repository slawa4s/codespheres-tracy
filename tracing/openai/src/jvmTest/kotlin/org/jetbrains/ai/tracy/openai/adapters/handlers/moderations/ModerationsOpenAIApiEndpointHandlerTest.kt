/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [ModerationsOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * These tests do not require a real OpenAI API key or network access.
 */
@Tag("openai")
class ModerationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private val jsonContentType = "application/json".toMediaType()

    @Test
    fun `moderations sets api type and operation name`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModerationResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post("""{"input":"This is a test message"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `moderations with string input sets input type to string`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModerationResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post("""{"input":"Hello world"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("string", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
        }
    }

    @Test
    fun `moderations with array input sets input type to array`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModerationResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post("""{"input":["Hello","World"]}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("array", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
        }
    }

    @Test
    fun `moderations response sets results count and flagged`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModerationResponse(flagged = true)

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post("""{"input":"some text"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.response.results.count")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.response.results.flagged")])
        }
    }

    @Test
    fun `moderations response sets categories and category scores`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModerationResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post("""{"input":"some text"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.categories")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.category_scores")])
        }
    }

    @Test
    fun `moderations extracts model from request`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModerationResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/moderations"))
                    .post("""{"input":"text","model":"text-moderation-latest"}""".toRequestBody(jsonContentType))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("text-moderation-latest", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun MockWebServer.enqueueModerationResponse(flagged: Boolean = false) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "id": "modr-abc123",
                        "model": "text-moderation-latest",
                        "results": [
                            {
                                "flagged": $flagged,
                                "categories": {
                                    "hate": false,
                                    "hate/threatening": false,
                                    "self-harm": false,
                                    "sexual": false,
                                    "sexual/minors": false,
                                    "violence": false,
                                    "violence/graphic": false
                                },
                                "category_scores": {
                                    "hate": 0.0001,
                                    "hate/threatening": 0.00001,
                                    "self-harm": 0.0001,
                                    "sexual": 0.0001,
                                    "sexual/minors": 0.00001,
                                    "violence": 0.0001,
                                    "violence/graphic": 0.00001
                                }
                            }
                        ]
                    }
                    """.trimIndent()
                )
        )
    }
}
