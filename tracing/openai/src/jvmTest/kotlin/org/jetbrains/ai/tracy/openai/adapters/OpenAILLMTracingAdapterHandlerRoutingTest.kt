/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters

import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * MockWebServer-based tests for [OpenAILLMTracingAdapter] URL routing.
 *
 * These tests do not call real OpenAI APIs and require no API keys.
 */
@Tag("openai")
class OpenAILLMTracingAdapterHandlerRoutingTest : BaseAITracingTest() {

    private fun createInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun postMultipart(client: OkHttpClient, url: String) {
        val body = "".toRequestBody("multipart/form-data; boundary=abc".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()
        client.newCall(request).execute().use { /* consume */ }
    }

    private fun postJson(client: OkHttpClient, url: String, body: String = "{}") {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { /* consume */ }
    }

    private fun patchJson(client: OkHttpClient, url: String, body: String = "{}") {
        val request = Request.Builder()
            .url(url)
            .patch(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { /* consume */ }
    }

    private fun enqueueJsonOk(server: MockWebServer, body: String = "{}") {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        )
    }

    // ----- Fix 1: images/variations routing -----

    @Test
    fun `POST images-variations uses generate_content operation name not chat`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createInstrumentedClient()
            enqueueJsonOk(server, """{"created":1234,"data":[{"url":"https://example.com/img.png"}]}""")

            postMultipart(client, server.url("/v1/images/variations").toString())

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()

            assertEquals(
                "generate_content",
                span.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "images/variations should use generate_content, not chat"
            )
        }
    }

    // ----- Fix 2: conversations.update via POST -----

    @Test
    fun `POST to conversation ID sets conversations_update operation name`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createInstrumentedClient()
            enqueueJsonOk(server, """{"id":"conv_abc","object":"conversation","created_at":"2024-01-01T00:00:00Z"}""")

            postJson(client, server.url("/v1/conversations/conv_abc").toString(), """{"metadata":{"key":"val"}}""")

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()

            assertEquals(
                "conversations.update",
                span.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "POST to /conversations/{id} should resolve to conversations.update, not conversations.retrieve"
            )
        }
    }

    @Test
    fun `PATCH to conversation ID sets conversations_update operation name`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createInstrumentedClient()
            enqueueJsonOk(server, """{"id":"conv_abc","object":"conversation","created_at":"2024-01-01T00:00:00Z"}""")

            patchJson(client, server.url("/v1/conversations/conv_abc").toString(), """{"metadata":{"key":"val"}}""")

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val span = traces.first()

            assertEquals(
                "conversations.update",
                span.attributes[AttributeKey.stringKey("gen_ai.operation.name")],
                "PATCH to /conversations/{id} should resolve to conversations.update"
            )
        }
    }
}
