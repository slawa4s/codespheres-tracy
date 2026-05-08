/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.anthropic.adapters.AnthropicLLMTracingAdapter
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.test.utils.BaseAITracingTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for Anthropic Files API tracing using MockWebServer.
 *
 * These tests verify that the correct `gen_ai.operation.name` and file-specific attributes
 * are set for each Files API endpoint variant, with particular focus on the `gen_ai.response.file.id`
 * attribute being set consistently for CREATE, RETRIEVE, and DELETE operations.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicFilesTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- files create -----------------------------------------------------------

    @Test
    fun `test files create sets operation name and file attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_OBJECT_RESPONSE)
            )

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "document.pdf", "pdf-content".toRequestBody("application/pdf".toMediaType()))
                .addFormDataPart("purpose", "assistants")
                .build()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .addHeader("x-api-key", "test-key")
                    .post(body)
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
            assertEquals("document.pdf", trace.attributes[AttributeKey.stringKey("gen_ai.response.file.filename")])
            assertEquals("application/pdf", trace.attributes[AttributeKey.stringKey("gen_ai.response.file.mime_type")])
            assertNotNull(trace.attributes[AttributeKey.longKey("gen_ai.response.file.size_bytes")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.file.created_at")])
        }
    }

    // ---- files list -------------------------------------------------------------

    @Test
    fun `test files list sets operation name and file count`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILES_LIST_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.file.count")])
        }
    }

    // ---- files retrieve ---------------------------------------------------------

    @Test
    fun `test files retrieve sets operation name and file attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_OBJECT_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.request.file.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
            assertEquals("document.pdf", trace.attributes[AttributeKey.stringKey("gen_ai.response.file.filename")])
        }
    }

    // ---- files delete -----------------------------------------------------------

    @Test
    fun `test files delete sets operation name and response file id`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_DELETED_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID"))
                    .addHeader("x-api-key", "test-key")
                    .delete()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            // Both GEN_AI_RESPONSE_ID and gen_ai.response.file.id must be set from the delete response
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("gen_ai.response.file.deleted")])
        }
    }

    // ---- files content ----------------------------------------------------------

    @Test
    fun `test files content sets operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/pdf")
                    .setBody("binary-pdf-content")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID/content"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.content", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.request.file.id")])
            // No file id in response for binary content
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
        }
    }

    // ---- regular messages endpoint is unaffected by files handler ---------------

    @Test
    fun `test regular messages endpoint is unaffected by files handler`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(MESSAGE_RESPONSE)
            )

            val requestBody = """
                {
                    "model": "claude-haiku-4-5-20251001",
                    "max_tokens": 100,
                    "messages": [{"role": "user", "content": "Hi"}]
                }
            """.trimIndent()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/messages"))
                    .addHeader("x-api-key", "test-key")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("chat", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            // anthropic.api.type is set to "messages" by the standard Messages API path
            assertEquals("messages", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            // No files-specific attribute should be set
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
        }
    }

    companion object {
        private const val FILE_ID = "file_011CNha8iCJcU1wXNR6q4V8w"

        private val FILE_OBJECT_RESPONSE = """
            {
                "id": "$FILE_ID",
                "type": "file",
                "filename": "document.pdf",
                "mime_type": "application/pdf",
                "size_bytes": 102400,
                "created_at": "2025-04-14T12:00:00Z",
                "downloadable": false
            }
        """.trimIndent()

        private val FILES_LIST_RESPONSE = """
            {
                "data": [
                    {"id": "file_001", "type": "file", "filename": "a.pdf"},
                    {"id": "file_002", "type": "file", "filename": "b.pdf"}
                ],
                "has_more": false,
                "first_id": "file_001",
                "last_id": "file_002"
            }
        """.trimIndent()

        private val FILE_DELETED_RESPONSE = """
            {
                "id": "$FILE_ID",
                "deleted": true,
                "type": "file_deleted"
            }
        """.trimIndent()

        private val MESSAGE_RESPONSE = """
            {
                "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
                "type": "message",
                "role": "assistant",
                "content": [{"type": "text", "text": "Hello!"}],
                "model": "claude-haiku-4-5-20251001",
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()
    }
}
