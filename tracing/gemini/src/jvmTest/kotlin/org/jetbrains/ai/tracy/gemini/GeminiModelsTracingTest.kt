/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import com.google.genai.types.HttpOptions
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.gemini.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import com.google.genai.Client as GeminiClient

/**
 * Tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiModelsHandler].
 *
 * The handler itself emits no body-derived attributes; the assertions here verify that the URL
 * dispatch logic in `GeminiLLMTracingAdapter` correctly routes models-detail GETs (URLs with no
 * `:operation` suffix) to this handler and that the network/api-type attributes set by the adapter
 * make it onto the span.
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key.
 */
@Tag("gemini")
class GeminiModelsTracingTest : BaseAITracingTest() {

    @Test
    fun `models get is routed to the models handler with gemini api type`() = runTest {
        withMockServer { server ->
            val client = GeminiClient.builder()
                .apiKey(MOCK_API_KEY)
                .httpOptions(
                    HttpOptions.builder()
                        .baseUrl(server.url("/").toString().trimEnd('/'))
                        .build()
                )
                .build()
                .apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "name": "models/gemini-2.5-flash",
                          "displayName": "Gemini 2.5 Flash",
                          "description": "Fast",
                          "version": "001",
                          "supportedActions": ["generateContent"]
                        }
                        """.trimIndent()
                    )
            )

            client.models.get("gemini-2.5-flash", null)

            val trace = analyzeSpans().first()
            assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
            assertEquals("gemini", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")])
            assertNotNull(trace.attributes[AttributeKey.longKey("server.port")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-gemini-key"
    }
}
