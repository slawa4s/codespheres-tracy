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
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * MockWebServer-based tests for Anthropic Models API tracing.
 *
 * These tests do not require a real Anthropic API key or network access.
 */
@Tag("anthropic")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicModelsTracingTest : BaseAITracingTest() {

    private fun buildClient(): OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ===== anthropic.api.type and gen_ai.operation.name =====

    @Test
    fun `models list sets api type to models and operation name to models list`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelListResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("models", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("models.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `models retrieve sets api type to models and operation name to models retrieve`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelRetrieveResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-3-5-sonnet-20241022"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("models", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("models.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ===== Request attribute extraction =====

    @Test
    fun `models retrieve extracts model id from URL path`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelRetrieveResponse(id = "claude-opus-4-5")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-opus-4-5"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("claude-opus-4-5", trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    @Test
    fun `models list does not set gen_ai request model`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelListResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.request.model")])
        }
    }

    // ===== Response attribute extraction =====

    @Test
    fun `models retrieve sets gen_ai output type to model`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelRetrieveResponse()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-3-5-sonnet-20241022"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("model", trace.attributes[AttributeKey.stringKey("gen_ai.output.type")])
        }
    }

    @Test
    fun `models retrieve uses URL alias for gen_ai response model and versioned id for model id`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            // URL alias is "claude-haiku-4-5"; response id is the versioned form
            server.enqueueModelRetrieveResponse(id = "claude-haiku-4-5-20251001")

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-haiku-4-5"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("claude-haiku-4-5", trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals("claude-haiku-4-5-20251001", trace.attributes[AttributeKey.stringKey("gen_ai.response.model.id")])
        }
    }

    @Test
    fun `models retrieve extracts response model attributes`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelRetrieveResponse(
                id = "claude-3-5-sonnet-20241022",
                displayName = "Claude 3.5 Sonnet",
                createdAt = 1724534400L,
                contextWindow = 200000L
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-3-5-sonnet-20241022"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("claude-3-5-sonnet-20241022", trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals("Claude 3.5 Sonnet", trace.attributes[AttributeKey.stringKey("gen_ai.response.model.display_name")])
            assertEquals(1724534400L, trace.attributes[AttributeKey.longKey("gen_ai.response.model.created_at")])
            assertEquals(200000L, trace.attributes[AttributeKey.longKey("anthropic.model.context_window")])
        }
    }

    @Test
    fun `models list extracts first model from response data array`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelListResponse(
                firstId = "claude-opus-4-5",
                firstDisplayName = "Claude Opus 4.5",
                firstCreatedAt = 1736985600L,
                firstContextWindow = 200000L
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("claude-opus-4-5", trace.attributes[AttributeKey.stringKey("gen_ai.response.model")])
            assertEquals("Claude Opus 4.5", trace.attributes[AttributeKey.stringKey("gen_ai.response.model.display_name")])
            assertEquals(1736985600L, trace.attributes[AttributeKey.longKey("gen_ai.response.model.created_at")])
            assertEquals(200000L, trace.attributes[AttributeKey.longKey("anthropic.model.context_window")])
        }
    }

    @Test
    fun `models retrieve extracts max token limits`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelRetrieveResponse(
                id = "claude-3-5-sonnet-20241022",
                maxTokensInContext = 200000L,
                maxOutputTokens = 8192L
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-3-5-sonnet-20241022"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals(200000L, trace.attributes[AttributeKey.longKey("gen_ai.response.model.max_input_tokens")])
            assertEquals(8192L, trace.attributes[AttributeKey.longKey("gen_ai.response.model.max_output_tokens")])
        }
    }

    @Test
    fun `models retrieve extracts capabilities`() = runTest {
        withMockServer { server ->
            val client = buildClient()
            server.enqueueModelRetrieveResponse(
                id = "claude-3-5-sonnet-20241022",
                capabilitiesBatch = true,
                capabilitiesCitations = true,
                capabilitiesVision = true
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/models/claude-3-5-sonnet-20241022"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertTrue(trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.batch")] == true)
            assertTrue(trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.citations")] == true)
            assertTrue(trace.attributes[AttributeKey.booleanKey("gen_ai.response.model.capabilities.vision")] == true)
        }
    }

    // ===== Helpers =====

    private fun MockWebServer.enqueueModelRetrieveResponse(
        id: String = "claude-3-5-sonnet-20241022",
        displayName: String = "Claude 3.5 Sonnet",
        createdAt: Long = 1724534400L,
        contextWindow: Long = 200000L,
        maxTokensInContext: Long? = null,
        maxOutputTokens: Long? = null,
        capabilitiesBatch: Boolean? = null,
        capabilitiesCitations: Boolean? = null,
        capabilitiesVision: Boolean? = null,
    ) {
        val maxInputField = if (maxTokensInContext != null) """"max_tokens_in_context": $maxTokensInContext,""" else ""
        val maxOutputField = if (maxOutputTokens != null) """"max_output_tokens": $maxOutputTokens,""" else ""
        val capsField = if (capabilitiesBatch != null || capabilitiesCitations != null || capabilitiesVision != null) {
            val batchPart = if (capabilitiesBatch != null) """"batch": $capabilitiesBatch""" else null
            val citPart = if (capabilitiesCitations != null) """"citations": $capabilitiesCitations""" else null
            val visionPart = if (capabilitiesVision != null) """"image_input": {"supported": $capabilitiesVision}""" else null
            val parts = listOfNotNull(batchPart, citPart, visionPart).joinToString(", ")
            """"capabilities": { $parts },"""
        } else ""

        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "type": "model",
                      "id": "$id",
                      "display_name": "$displayName",
                      "created_at": $createdAt,
                      "context_window": $contextWindow,
                      $maxInputField
                      $maxOutputField
                      $capsField
                      "dummy": "end"
                    }
                    """.trimIndent()
                )
        )
    }

    private fun MockWebServer.enqueueModelListResponse(
        firstId: String = "claude-opus-4-5",
        firstDisplayName: String = "Claude Opus 4.5",
        firstCreatedAt: Long = 1736985600L,
        firstContextWindow: Long = 200000L,
    ) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "data": [
                        {
                          "type": "model",
                          "id": "$firstId",
                          "display_name": "$firstDisplayName",
                          "created_at": $firstCreatedAt,
                          "context_window": $firstContextWindow
                        }
                      ],
                      "has_more": false,
                      "first_id": "$firstId",
                      "last_id": "$firstId"
                    }
                    """.trimIndent()
                )
        )
    }
}
