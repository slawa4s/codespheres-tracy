/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import io.opentelemetry.api.common.AttributeKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.gemini.adapters.GeminiLLMTracingAdapter
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeminiApiTypeAttributeTest : BaseAITracingTest() {

    private val jsonMediaType = "application/json".toMediaType()
    private val emptyBody = "{}".toRequestBody(jsonMediaType)

    @Test
    fun `gemini api type is models when path contains models segment`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"hi"}],"role":"model"}}]}""")
        )
        server.start()

        try {
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())
            val request = Request.Builder()
                .url(server.url("/v1beta/models/gemini-2.5-flash:generateContent"))
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals("models", spans.first().attributes[AttributeKey.stringKey("gemini.api.type")])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gemini api type is models for aiplatform url with models segment`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"predictions":[{"bytesBase64Encoded":"abc","mimeType":"image/png"}]}""")
        )
        server.start()

        try {
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())
            val request = Request.Builder()
                .url(server.url("/v1/models/imagen-4.0-generate-001:predict"))
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals("models", spans.first().attributes[AttributeKey.stringKey("gemini.api.type")])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `gemini api type is not set when path does not contain models segment`() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{}")
        )
        server.start()

        try {
            val client = instrument(OkHttpClient(), GeminiLLMTracingAdapter())
            val request = Request.Builder()
                .url(server.url("/v1/other/endpoint"))
                .post(emptyBody)
                .build()

            client.newCall(request).execute().use {}

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertNull(spans.first().attributes[AttributeKey.stringKey("gemini.api.type")])
        } finally {
            server.shutdown()
        }
    }
}
