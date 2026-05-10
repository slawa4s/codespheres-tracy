/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * MockWebServer-based tests for Anthropic Models API tracing.
 *
 * Verifies that GET requests to `/v1/models/{model_id}` set `anthropic.api.type = "models"`,
 * populate [gen_ai.response.model] and model-detail attributes without attempting to parse
 * the body as a Messages API payload.
 */
@Tag("anthropic")
class AnthropicModelsTracingTest : BaseAITracingTest() {

    private fun makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    @Test
    fun modelsRetrieveSetsModelAttributes() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "claude-haiku-4-5",
                          "display_name": "Claude Haiku 4.5",
                          "created_at": 1748041200,
                          "type": "model",
                          "max_input_tokens": 200000,
                          "max_output_tokens": 8192,
                          "capabilities": {
                            "image_input": { "supported": { "enabled": true } },
                            "citations": { "enabled": true },
                            "batch": { "enabled": true }
                          }
                        }
                        """.trimIndent()
                    )
            )

            val client = makeInstrumentedClient()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-haiku-4-5"))
                    .get()
                    .header("x-api-key", MOCK_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            val trace = traces.firstOrNull()
            assertNotNull(trace, "Expected a span for the models retrieve request")

            assertEquals(
                "models",
                trace!!.attributes[AttributeKey.stringKey("anthropic.api.type")],
                "anthropic.api.type should be 'models' for /v1/models/* requests"
            )
            assertEquals(
                "claude-haiku-4-5",
                trace.attributes[AttributeKey.stringKey("gen_ai.request.model")],
                "gen_ai.request.model should be set from the model ID in the URL path"
            )
            assertEquals(
                "claude-haiku-4-5",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.model")],
                "gen_ai.response.model should be set from the response id field"
            )
            assertEquals(
                "Claude Haiku 4.5",
                trace.attributes[AttributeKey.stringKey("gen_ai.response.model.display_name")],
                "gen_ai.response.model.display_name should be set from response display_name"
            )
            assertEquals(
                true,
                trace.attributes[AttributeKey.booleanKey("anthropic.model.capabilities.image_input")],
                "anthropic.model.capabilities.image_input should be true"
            )
            assertEquals(
                200000L,
                trace.attributes[AttributeKey.longKey("gen_ai.response.model.max_input_tokens")],
                "gen_ai.response.model.max_input_tokens should be set"
            )
            assertEquals(
                8192L,
                trace.attributes[AttributeKey.longKey("gen_ai.response.model.max_output_tokens")],
                "gen_ai.response.model.max_output_tokens should be set"
            )
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
