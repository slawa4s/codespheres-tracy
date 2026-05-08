/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
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
 * These tests verify that the correct `anthropic.api.type`, `gen_ai.operation.name`,
 * and file-specific attributes are set for each Files API endpoint variant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicFilesTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- files upload (POST /v1/files) ------------------------------------------

    @Test
    fun `test files upload sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_METADATA_RESPONSE)
            )

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "file",
                    filename = "document.pdf",
                    body = FILE_CONTENT.toRequestBody("application/pdf".toMediaType())
                )
                .build()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .post(multipart)
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.upload", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test files upload sets file metadata from response`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_METADATA_RESPONSE)
            )

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "file",
                    filename = "document.pdf",
                    body = FILE_CONTENT.toRequestBody("application/pdf".toMediaType())
                )
                .build()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .post(multipart)
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("tracy.file.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("document.pdf", trace.attributes[AttributeKey.stringKey("tracy.file.filename")])
            assertEquals("application/pdf", trace.attributes[AttributeKey.stringKey("tracy.file.mime_type")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.file.size_bytes")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("tracy.file.created_at")])
        }
    }

    @Test
    fun `test files upload extracts file size and filename from request`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_METADATA_RESPONSE)
            )

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "file",
                    filename = "my-doc.pdf",
                    body = FILE_CONTENT.toRequestBody("application/pdf".toMediaType())
                )
                .build()

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .post(multipart)
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("my-doc.pdf", trace.attributes[AttributeKey.stringKey("tracy.request.file.filename")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.request.file.size_bytes")])
        }
    }

    // ---- files list (GET /v1/files) ---------------------------------------------

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
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.file.count")])
        }
    }

    @Test
    fun `test files list with pagination query params`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILES_LIST_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files?limit=10&after_id=file_abc"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("10", trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals("file_abc", trace.attributes[AttributeKey.stringKey("tracy.request.after_id")])
        }
    }

    // ---- files retrieve (GET /v1/files/{id}) ------------------------------------

    @Test
    fun `test files retrieve sets operation name and file id from URL`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_METADATA_RESPONSE)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("tracy.file.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals("document.pdf", trace.attributes[AttributeKey.stringKey("tracy.file.filename")])
            assertEquals("application/pdf", trace.attributes[AttributeKey.stringKey("tracy.file.mime_type")])
        }
    }

    // ---- files delete (DELETE /v1/files/{id}) ------------------------------------

    @Test
    fun `test files delete sets operation name and file id from response`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"$FILE_ID","type":"file_deleted"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .delete()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("tracy.file.id")])
        }
    }

    // ---- files content (GET /v1/files/{id}/content) -----------------------------

    @Test
    fun `test files content sets operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/pdf")
                    .setBody(FILE_CONTENT)
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID/content"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.content", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ---- error spans set anthropic.api.type and gen_ai.provider.name ------------

    @Test
    fun `test files error span sets anthropic api type and provider name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail": "File not found"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("anthropic", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            // fallback error.type from 4xx status
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
        }
    }

    @Test
    fun `test files delete error span sets anthropic api type and provider name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"detail": "File not found"}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID"))
                    .addHeader("x-api-key", "test-key")
                    .addHeader("anthropic-beta", "files-api-2025-04-14")
                    .delete()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("anthropic", trace.attributes[AttributeKey.stringKey("gen_ai.provider.name")])
            assertEquals("invalid_request_error", trace.attributes[AttributeKey.stringKey("error.type")])
        }
    }

    // ---- regular messages endpoint should not match files handler ---------------

    @Test
    fun `test regular messages endpoint is not matched by files handler`() = runTest {
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
            assertNull(trace.attributes[AttributeKey.stringKey("tracy.file.id")])
        }
    }

    companion object {
        private const val FILE_ID = "file_01ABC123def456GHI"
        private const val FILE_CONTENT = "mock PDF content for testing"

        private val FILE_METADATA_RESPONSE = """
            {
                "id": "$FILE_ID",
                "type": "file",
                "filename": "document.pdf",
                "mime_type": "application/pdf",
                "size_bytes": 102400,
                "created_at": "2026-01-15T10:30:00Z"
            }
        """.trimIndent()

        private val FILES_LIST_RESPONSE = """
            {
                "data": [
                    {
                        "id": "file_001",
                        "type": "file",
                        "filename": "doc1.pdf",
                        "mime_type": "application/pdf",
                        "size_bytes": 1024,
                        "created_at": "2026-01-14T09:00:00Z"
                    },
                    {
                        "id": "file_002",
                        "type": "file",
                        "filename": "doc2.pdf",
                        "mime_type": "application/pdf",
                        "size_bytes": 2048,
                        "created_at": "2026-01-15T10:00:00Z"
                    }
                ],
                "has_more": false,
                "first_id": "file_001",
                "last_id": "file_002"
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
