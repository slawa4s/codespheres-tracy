/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Tests for [ModelsOpenAIApiEndpointHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real OpenAI access required.
 */
@Tag("openai")
class ModelsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `models list traces operation name and api type`() = runTest {
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
                    .setBody(
                        """
                        {
                          "object": "list",
                          "data": [
                            { "id": "gpt-4o-mini", "object": "model", "created": 1710000000, "owned_by": "openai" }
                          ]
                        }
                        """.trimIndent()
                    )
            )

            client.models().list()

            val trace = analyzeSpans().first()
            assertEquals("models.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("models", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `models retrieve traces request model and response fields`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            val createdAt = 1710000000L
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "gpt-4o-mini",
                          "object": "model",
                          "created": $createdAt,
                          "owned_by": "openai"
                        }
                        """.trimIndent()
                    )
            )

            client.models().retrieve("gpt-4o-mini")

            val trace = analyzeSpans().first()
            assertEquals("models.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("models", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("gpt-4o-mini", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertEquals("gpt-4o-mini", trace.attributes[AttributeKey.stringKey("tracy.response.model.id")])
            assertEquals("model", trace.attributes[AttributeKey.stringKey("tracy.response.object")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.response.created")])
            assertEquals("openai", trace.attributes[AttributeKey.stringKey("tracy.response.owned_by")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-api-key"
    }
}
