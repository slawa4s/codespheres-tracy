/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.batches

import com.openai.models.batches.BatchCreateParams
import com.openai.models.batches.BatchListParams
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
import kotlin.test.assertNotNull

/**
 * Tests for [BatchesOpenAIApiEndpointHandler].
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real OpenAI access required.
 */
@Tag("openai")
class BatchesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `batch create traces request body fields and operation name`() = runTest {
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
                          "id": "batch_abc",
                          "object": "batch",
                          "endpoint": "/v1/responses",
                          "input_file_id": "file_xyz",
                          "completion_window": "24h",
                          "status": "validating",
                          "created_at": 1710000000
                        }
                        """.trimIndent()
                    )
            )

            val params = BatchCreateParams.builder()
                .inputFileId("file_xyz")
                .endpoint(BatchCreateParams.Endpoint.V1_RESPONSES)
                .completionWindow(BatchCreateParams.CompletionWindow._24H)
                .build()

            client.batches().create(params)

            val trace = analyzeSpans().first()
            assertEquals("batches.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("file_xyz", trace.attributes[AttributeKey.stringKey("gen_ai.request.batch.input_file_id")])
            assertEquals("/v1/responses", trace.attributes[AttributeKey.stringKey("gen_ai.request.batch.endpoint")])
            assertEquals("24h", trace.attributes[AttributeKey.stringKey("gen_ai.request.batch.completion_window")])
            assertEquals("batch_abc", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.id")])
            assertEquals("validating", trace.attributes[AttributeKey.stringKey("gen_ai.response.batch.status")])
        }
    }

    @Test
    fun `batch list traces operation name`() = runTest {
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
                          "data": [],
                          "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            client.batches().list(BatchListParams.builder().build())

            val trace = analyzeSpans().first()
            assertEquals("batches.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `batch retrieve with bogus id traces operation name and 404`() = runTest {
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
                            "message": "No such batch: batch_does_not_exist",
                            "type": "invalid_request_error",
                            "param": null,
                            "code": "resource_not_found"
                          }
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.batches().retrieve("batch_does_not_exist")
            } catch (_: Exception) {
                // expected: 404
            }

            val trace = analyzeSpans().first()
            assertEquals("batches.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batches", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(404L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("server.address")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-api-key"
    }
}
