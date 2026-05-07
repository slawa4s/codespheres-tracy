/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.gemini

import com.google.genai.types.EmbedContentConfig
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
 * Tests for [org.jetbrains.ai.tracy.gemini.adapters.handlers.GeminiEmbeddingsHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real Gemini API access required.
 */
@Tag("gemini")
class GeminiEmbeddingsTracingTest : BaseAITracingTest() {

    @Test
    fun `embedContent normalises operation name and traces task type and embedding dimension`() = runTest {
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

            // 8 floats → embedding dimension 8 to keep the body compact
            val embeddingValues = (1..8).joinToString(", ") { "0.${it}1" }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "embedding": { "values": [ $embeddingValues ] }
                        }
                        """.trimIndent()
                    )
            )

            val config = EmbedContentConfig.builder()
                .taskType("RETRIEVAL_DOCUMENT")
                .outputDimensionality(8)
                .build()
            client.models.embedContent("text-embedding-004", "Hello world", config)

            val trace = analyzeSpans().first()
            assertEquals("embedContent", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("embedding", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
            assertEquals("models", trace.attributes[AttributeKey.stringKey("gemini.api.type")])
            assertEquals("RETRIEVAL_DOCUMENT", trace.attributes[AttributeKey.stringKey("gen_ai.request.task_type")])
            assertEquals(8L, trace.attributes[AttributeKey.longKey("gen_ai.request.output_dimensionality")])
            assertEquals(8L, trace.attributes[AttributeKey.longKey("gen_ai.response.embedding.dimension")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-gemini-key"
    }
}
