/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models

import com.openai.models.models.ModelListParams
import com.openai.models.models.ModelRetrieveParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key, so they do not
 * require access to the real OpenAI Models API or any specific account configuration.
 */
@Tag("openai")
class ModelsOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ LIST: GET /v1/models ============

    @Test
    fun `test LIST models endpoint sets operation name to models list`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModelListResponse())

            try {
                client.models().list(ModelListParams.builder().build())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("models.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test LIST models endpoint sets openai api type to models`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModelListResponse())

            try {
                client.models().list(ModelListParams.builder().build())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("models", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test LIST models endpoint sets response object without JSON quotes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModelListResponse())

            try {
                client.models().list(ModelListParams.builder().build())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Must be "list" (bare string), not "\"list\"" (with JSON quotes)
            assertEquals("list", trace.attributes[AttributeKey.stringKey("tracy.response.object")])
        }
    }

    // ============ RETRIEVE: GET /v1/models/{model_id} ============

    @Test
    fun `test RETRIEVE model endpoint sets operation name to models retrieve`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val modelId = "gpt-4o-mini"
            server.enqueue(enqueueModelRetrieveResponse(modelId = modelId))

            try {
                client.models().retrieve(
                    ModelRetrieveParams.builder()
                        .model(modelId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("models.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test RETRIEVE model endpoint sets openai api type to models`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val modelId = "gpt-4o-mini"
            server.enqueue(enqueueModelRetrieveResponse(modelId = modelId))

            try {
                client.models().retrieve(
                    ModelRetrieveParams.builder()
                        .model(modelId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("models", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test RETRIEVE model endpoint sets gen_ai request model from URL`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val modelId = "gpt-4o-mini"
            server.enqueue(enqueueModelRetrieveResponse(modelId = modelId))

            try {
                client.models().retrieve(
                    ModelRetrieveParams.builder()
                        .model(modelId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(modelId, trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    @Test
    fun `test RETRIEVE model endpoint sets response attributes without JSON quotes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val modelId = "gpt-4o-mini"
            server.enqueue(enqueueModelRetrieveResponse(modelId = modelId))

            try {
                client.models().retrieve(
                    ModelRetrieveParams.builder()
                        .model(modelId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Must be "model" (bare string), not "\"model\"" (with JSON quotes)
            assertEquals("model", trace.attributes[AttributeKey.stringKey("tracy.response.object")])
            assertEquals(modelId, trace.attributes[AttributeKey.stringKey("tracy.response.model.id")])
            assertEquals(modelId, trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
        }
    }

    @Test
    fun `test RETRIEVE model endpoint gen_ai response model matches request model`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val modelId = "gpt-4o-mini"
            server.enqueue(enqueueModelRetrieveResponse(modelId = modelId))

            try {
                client.models().retrieve(
                    ModelRetrieveParams.builder()
                        .model(modelId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            val requestModel = trace.attributes[AttributeKey.stringKey("gen_ai.request.model")]
            val responseModel = trace.attributes[AttributeKey.stringKey("gen_ai.response.model")]
            assertEquals(requestModel, responseModel, "gen_ai.response.model should match gen_ai.request.model")
        }
    }

    @Test
    fun `test LIST models endpoint does not set gen_ai request model or response model`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueModelListResponse())

            try {
                client.models().list(ModelListParams.builder().build())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // List endpoint has no specific model to report
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
        }
    }

    // ============ HELPER METHODS ============

    private fun enqueueModelListResponse(
        models: List<String> = listOf("gpt-4o-mini", "gpt-4-turbo")
    ): MockResponse {
        val items = models.joinToString(",") { id ->
            """
            {
              "id": "$id",
              "object": "model",
              "created": 1686935002,
              "owned_by": "openai"
            }
            """.trimIndent()
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "object": "list",
                  "data": [$items]
                }
                """.trimIndent()
            )
    }

    private fun enqueueModelRetrieveResponse(
        modelId: String = "gpt-4o-mini",
        ownedBy: String = "openai",
        created: Long = 1686935002L
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$modelId",
                  "object": "model",
                  "created": $created,
                  "owned_by": "$ownedBy"
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
