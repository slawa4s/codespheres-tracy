/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import com.openai.models.moderations.ModerationCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for [ModerationsOpenAIApiEndpointHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real OpenAI access required.
 */
@Tag("openai")
class ModerationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `string input is traced as type string with results metadata`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODERATION_RESPONSE_BODY)
            )

            val params = ModerationCreateParams.builder()
                .input("A friendly greeting.")
                .build()
            client.moderations().create(params)

            val trace = analyzeSpans().first()
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("string", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.response.results.count")])
            assertEquals("false", trace.attributes[AttributeKey.stringKey("tracy.response.results.flagged")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.categories")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.category_scores")])
        }
    }

    @Test
    fun `array input is traced as type array`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MODERATION_RESPONSE_BODY)
            )

            val params = ModerationCreateParams.builder()
                .input(ModerationCreateParams.Input.ofStrings(listOf("Hi.", "Hello.")))
                .build()
            client.moderations().create(params)

            val trace = analyzeSpans().first()
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("array", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-api-key"

        val MODERATION_RESPONSE_BODY = """
            {
              "id": "modr-abc",
              "model": "omni-moderation-latest",
              "results": [
                {
                  "flagged": false,
                  "categories": {
                    "harassment": false,
                    "hate": false,
                    "self-harm": false,
                    "sexual": false,
                    "violence": false
                  },
                  "category_scores": {
                    "harassment": 0.0001,
                    "hate": 0.0001,
                    "self-harm": 0.0001,
                    "sexual": 0.0001,
                    "violence": 0.0001
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
