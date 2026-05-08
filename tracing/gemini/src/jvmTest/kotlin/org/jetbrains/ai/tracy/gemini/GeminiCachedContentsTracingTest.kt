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
import com.google.genai.Client as GeminiClient

/**
 * Tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiCachedContentsHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real Gemini API access required.
 *
 * Extends [BaseAITracingTest] directly rather than [BaseGeminiTracingTest] to avoid the
 * eager `GEMINI_API_KEY` env-var requirement of the latter.
 */
@Tag("gemini")
class GeminiCachedContentsTracingTest : BaseAITracingTest() {

    @Test
    fun `cachedContents list traces count and has_more from nextPageToken`() = runTest {
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
                          "cachedContents": [
                            { "name": "cachedContents/a", "model": "models/gemini-2.5-flash" },
                            { "name": "cachedContents/b", "model": "models/gemini-2.5-flash" }
                          ],
                          "nextPageToken": "page-2"
                        }
                        """.trimIndent()
                    )
            )

            client.caches.list(null)

            val trace = analyzeSpans().first()
            assertEquals("cachedContents", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
            assertEquals("gemini", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.list.has_more")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-gemini-key"
    }
}
