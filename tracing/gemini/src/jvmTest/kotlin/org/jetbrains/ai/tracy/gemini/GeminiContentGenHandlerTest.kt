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
 * Tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiContentGenHandler] using [MockWebServer].
 *
 * No real network calls or API keys are required.
 */
@Tag("gemini")
class GeminiContentGenHandlerTest : BaseAITracingTest() {

    @Test
    fun `generateContent sets gemini api type to models`() = runTest {
        withMockServer { server ->
            server.enqueueGenerateContentResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1beta/models/gemini-2.0-flash:generateContent"))
                    .post(buildGenerateContentRequestBody())
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), GeminiLLMTracingAdapter())

    private fun buildGenerateContentRequestBody(): okhttp3.RequestBody {
        val json = """{"contents":[{"role":"user","parts":[{"text":"Hello"}]}]}"""
        return json.toRequestBody("application/json".toMediaType())
    }

    private fun MockWebServer.enqueueGenerateContentResponse() {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"Hi there!"}],"role":"model"},"finishReason":"STOP"}],"modelVersion":"gemini-2.0-flash"}"""
                )
        )
    }
}
