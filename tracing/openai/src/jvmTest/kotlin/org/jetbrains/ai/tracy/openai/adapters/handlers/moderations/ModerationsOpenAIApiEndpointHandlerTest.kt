/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.moderations

import com.openai.models.moderations.ModerationCreateParams
import com.openai.models.moderations.ModerationImageUrlInput
import com.openai.models.moderations.ModerationModel
import com.openai.models.moderations.ModerationMultiModalInput
import com.openai.models.moderations.ModerationTextInput
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key, so they do not
 * require access to the real OpenAI Moderations API or any specific account configuration.
 */
@Tag("openai")
class ModerationsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ Basic text moderation ============

    @Test
    fun `test moderations endpoint sets operation name to moderations`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModerationResponse(flagged = false))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .input("This is a safe text message.")
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test moderations endpoint sets openai api type to moderations`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModerationResponse(flagged = false))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .input("This is a safe text message.")
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test basic moderation with string input sets input type to string`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModerationResponse(flagged = false))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .input("This is a safe text message.")
                        .model(ModerationModel.OMNI_MODERATION_LATEST)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("string", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
        }
    }

    @Test
    fun `test basic moderation traces response attributes correctly`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val moderationId = "modr-basic-abc123"
            server.enqueue(enqueueModerationResponse(id = moderationId, flagged = false))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .input("This is a safe text message.")
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.response.results.count")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.response.results.flagged")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.categories")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.category_scores")])
        }
    }

    @Test
    fun `test moderation flagged response sets flagged to true`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModerationResponse(flagged = true))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .input("Harmful content.")
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.response.results.flagged")])
        }
    }

    @Test
    fun `test moderation traces model from request`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModerationResponse(model = "omni-moderation-latest", flagged = false))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .input("Hello, world!")
                        .model(ModerationModel.OMNI_MODERATION_LATEST)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    // ============ Multimodal moderation ============

    @Test
    fun `test multimodal moderation with array input sets input type to multimodal`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueMultimodalModerationResponse(flagged = false))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .inputOfModerationMultiModalArray(
                            listOf(
                                ModerationMultiModalInput.ofText(
                                    ModerationTextInput.builder().text("Check this image").build()
                                ),
                                ModerationMultiModalInput.ofImageUrl(
                                    ModerationImageUrlInput.builder()
                                        .imageUrl(
                                            ModerationImageUrlInput.ImageUrl.builder()
                                                .url("https://example.com/image.png")
                                                .build()
                                        )
                                        .build()
                                )
                            )
                        )
                        .model(ModerationModel.OMNI_MODERATION_LATEST)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("multimodal", trace.attributes[AttributeKey.stringKey("tracy.request.input.type")])
        }
    }

    @Test
    fun `test multimodal moderation traces category_applied_input_types from response`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueMultimodalModerationResponse(flagged = false))

            try {
                client.moderations().create(
                    ModerationCreateParams.builder()
                        .inputOfModerationMultiModalArray(
                            listOf(
                                ModerationMultiModalInput.ofText(
                                    ModerationTextInput.builder().text("Check this image").build()
                                ),
                                ModerationMultiModalInput.ofImageUrl(
                                    ModerationImageUrlInput.builder()
                                        .imageUrl(
                                            ModerationImageUrlInput.ImageUrl.builder()
                                                .url("https://example.com/image.png")
                                                .build()
                                        )
                                        .build()
                                )
                            )
                        )
                        .model(ModerationModel.OMNI_MODERATION_LATEST)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("moderations", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(1L, trace.attributes[AttributeKey.longKey("tracy.response.results.count")])
            assertEquals(false, trace.attributes[AttributeKey.booleanKey("tracy.response.results.flagged")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.categories")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.response.results.category_scores")])
            assertNotNull(
                trace.attributes[AttributeKey.stringKey("tracy.response.results.category_applied_input_types")]
            )
        }
    }

    // ============ HELPER METHODS ============

    private fun enqueueModerationResponse(
        id: String = "modr-test-001",
        model: String = "omni-moderation-latest",
        flagged: Boolean = false
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "model": "$model",
                  "results": [
                    {
                      "flagged": $flagged,
                      "categories": {
                        "harassment": false,
                        "harassment/threatening": false,
                        "hate": false,
                        "hate/threatening": false,
                        "illicit": false,
                        "illicit/violent": false,
                        "self-harm": false,
                        "self-harm/instructions": false,
                        "self-harm/intent": false,
                        "sexual": false,
                        "sexual/minors": false,
                        "violence": $flagged,
                        "violence/graphic": false
                      },
                      "category_scores": {
                        "harassment": 0.0001,
                        "harassment/threatening": 0.00001,
                        "hate": 0.0002,
                        "hate/threatening": 0.00002,
                        "illicit": 0.0003,
                        "illicit/violent": 0.00003,
                        "self-harm": 0.0004,
                        "self-harm/instructions": 0.00004,
                        "self-harm/intent": 0.00005,
                        "sexual": 0.0005,
                        "sexual/minors": 0.00006,
                        "violence": ${if (flagged) 0.95 else 0.0006},
                        "violence/graphic": 0.00007
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
    }

    private fun enqueueMultimodalModerationResponse(
        id: String = "modr-multimodal-001",
        model: String = "omni-moderation-latest",
        flagged: Boolean = false
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "model": "$model",
                  "results": [
                    {
                      "flagged": $flagged,
                      "categories": {
                        "harassment": false,
                        "harassment/threatening": false,
                        "hate": false,
                        "hate/threatening": false,
                        "illicit": false,
                        "illicit/violent": false,
                        "self-harm": false,
                        "self-harm/instructions": false,
                        "self-harm/intent": false,
                        "sexual": false,
                        "sexual/minors": false,
                        "violence": false,
                        "violence/graphic": false
                      },
                      "category_scores": {
                        "harassment": 0.0001,
                        "harassment/threatening": 0.00001,
                        "hate": 0.0002,
                        "hate/threatening": 0.00002,
                        "illicit": 0.0003,
                        "illicit/violent": 0.00003,
                        "self-harm": 0.0004,
                        "self-harm/instructions": 0.00004,
                        "self-harm/intent": 0.00005,
                        "sexual": 0.0005,
                        "sexual/minors": 0.00006,
                        "violence": 0.0006,
                        "violence/graphic": 0.00007
                      },
                      "category_applied_input_types": {
                        "harassment": ["text"],
                        "harassment/threatening": ["text"],
                        "hate": ["text"],
                        "hate/threatening": ["text"],
                        "illicit": ["text"],
                        "illicit/violent": ["text"],
                        "self-harm": ["text", "image"],
                        "self-harm/instructions": ["text", "image"],
                        "self-harm/intent": ["text", "image"],
                        "sexual": ["text", "image"],
                        "sexual/minors": ["text", "image"],
                        "violence": ["text", "image"],
                        "violence/graphic": ["text", "image"]
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
