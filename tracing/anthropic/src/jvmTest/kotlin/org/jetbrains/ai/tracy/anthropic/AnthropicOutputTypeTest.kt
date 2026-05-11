/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.batches.BatchCreateParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.clients.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Verifies that [org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter] sets
 * `gen_ai.output.type` from the request side for endpoints whose output type is deterministic,
 * and that file-specific request/response attributes are extracted correctly.
 */
@Tag("anthropic")
class AnthropicOutputTypeTest : BaseAITracingTest() {

    private fun createMockClient(baseUrl: String): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey("mock-api-key")
            .build()

    @Test
    fun `models retrieve sets gen_ai output type to model`() = runTest {
        withMockServer { server ->
            val client = createMockClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"id":"claude-haiku-4-5","type":"model","display_name":"Claude Haiku 4.5",
                          |"created_at":"2024-11-01T00:00:00Z","max_input_tokens":200000,
                          |"max_output_tokens":8192,"capabilities":{"image_input":{"supported":true},
                          |"batch":true,"citations":false}}""".trimMargin()
                    )
            )

            runCatching { client.models().retrieve("claude-haiku-4-5") }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(
                "model",
                spans.first().attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be 'model' for models.retrieve"
            )
        }
    }

    @Test
    fun `batches create sets gen_ai output type to message_batch`() = runTest {
        withMockServer { server ->
            val client = createMockClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"id":"msgbatch-abc","type":"message_batch","processing_status":"in_progress",
                          |"request_counts":{"processing":1,"succeeded":0,"errored":0,"canceled":0,"expired":0},
                          |"created_at":"2024-01-01T00:00:00Z","expires_at":"2024-01-02T00:00:00Z",
                          |"archived_at":null,"cancel_initiated_at":null,"ended_at":null,"results_url":null}""".trimMargin()
                    )
            )

            val params = BatchCreateParams.builder()
                .addRequest(
                    BatchCreateParams.Request.builder()
                        .customId("req-1")
                        .params(
                            BatchCreateParams.Request.Params.builder()
                                .model(com.anthropic.models.messages.Model.CLAUDE_HAIKU_4_5)
                                .maxTokens(64)
                                .addUserMessage("Hello")
                                .build()
                        )
                        .build()
                )
                .build()
            runCatching { client.messages().batches().create(params) }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(
                "message_batch",
                spans.first().attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be 'message_batch' for batches.create"
            )
        }
    }

    @Test
    fun `batches delete sets gen_ai output type to message_batch_deleted`() = runTest {
        withMockServer { server ->
            val client = createMockClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"msgbatch-abc","deleted":true,"type":"message_batch_deleted"}""")
            )

            runCatching { client.messages().batches().delete("msgbatch-abc") }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(
                "message_batch_deleted",
                spans.first().attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be 'message_batch_deleted' for batches.delete"
            )
        }
    }

    @Test
    fun `files upload sets gen_ai output type to file and extracts request and response attributes`() = runTest {
        withMockServer { server ->
            val client = createMockClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """{"id":"file-abc123","filename":"test.pdf","mime_type":"application/pdf",
                          |"size":42,"created_at":"2024-01-01T00:00:00Z","downloadable":false,"type":"file"}""".trimMargin()
                    )
            )

            // Create a minimal temp PDF file for upload
            val tempFile = Files.createTempFile("tracy-test-", ".pdf").also { path ->
                path.toFile().writeBytes(ByteArray(42) { 0 })
            }
            try {
                runCatching {
                    client.beta().files().upload(
                        com.anthropic.models.beta.files.FileUploadParams.builder()
                            .file(tempFile)
                            .build()
                    )
                }
            } finally {
                Files.deleteIfExists(tempFile)
            }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            val span = spans.first()

            assertEquals(
                "file",
                span.attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be 'file' for files.upload"
            )
            assertEquals(
                "file-abc123",
                span.attributes[AttributeKey.stringKey("gen_ai.response.file.id")],
                "gen_ai.response.file.id should be extracted from response"
            )
            assertEquals(
                "test.pdf",
                span.attributes[AttributeKey.stringKey("gen_ai.response.file.filename")],
                "gen_ai.response.file.filename should be extracted from response"
            )
            assertEquals(
                "application/pdf",
                span.attributes[AttributeKey.stringKey("gen_ai.response.file.mime_type")],
                "gen_ai.response.file.mime_type should be extracted from response"
            )
            assertEquals(
                42L,
                span.attributes[AttributeKey.longKey("gen_ai.response.file.size_bytes")],
                "gen_ai.response.file.size_bytes should be extracted from response"
            )
        }
    }

    @Test
    fun `files delete sets gen_ai output type to file_deleted`() = runTest {
        withMockServer { server ->
            val client = createMockClient(server.url("/").toString())
                .apply { instrument(this) }

            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"file-abc123","deleted":true,"type":"file_deleted"}""")
            )

            runCatching { client.beta().files().delete("file-abc123") }

            val spans = analyzeSpans()
            assertTracesCount(1, spans)
            assertEquals(
                "file_deleted",
                spans.first().attributes[AttributeKey.stringKey("gen_ai.output.type")],
                "gen_ai.output.type should be 'file_deleted' for files.delete"
            )
        }
    }
}
