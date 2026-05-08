/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.conversations

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Tests for [ConversationsOpenAIApiEndpointHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real OpenAI access required.
 */
@Tag("openai")
class ConversationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `conversation create traces operation name and api type`() = runTest {
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
                          "id": "conv_abc",
                          "object": "conversation",
                          "created_at": 1710000000,
                          "metadata": {}
                        }
                        """.trimIndent()
                    )
            )

            client.conversations().create()

            val trace = analyzeSpans().first()
            assertEquals("conversations.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `conversation retrieve with bogus id traces operation name and 404`() = runTest {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofSeconds(30),
            ).apply { instrument(this) }

            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "error": {
                            "message": "No such conversation: conv_does_not_exist",
                            "type": "invalid_request_error",
                            "code": "resource_not_found"
                          }
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.conversations().retrieve("conv_does_not_exist")
            } catch (_: Exception) {
                // expected: 404
            }

            val trace = analyzeSpans().first()
            assertEquals("conversations.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(404L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        }
    }

    @Test
    fun `conversation delete traces operation name`() = runTest {
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
                          "id": "conv_abc",
                          "object": "conversation.deleted",
                          "deleted": true
                        }
                        """.trimIndent()
                    )
            )

            client.conversations().delete("conv_abc")

            val trace = analyzeSpans().first()
            assertEquals("conversations.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("conversations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-api-key"
    }
}
