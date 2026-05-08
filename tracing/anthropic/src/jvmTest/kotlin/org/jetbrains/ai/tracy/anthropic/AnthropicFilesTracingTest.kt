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
import kotlin.test.assertTrue

/**
 * Unit tests for Anthropic Files API tracing using MockWebServer.
 *
 * Verifies that `anthropic.api.type`, `gen_ai.operation.name`, and all file-specific
 * span attributes are set correctly for each Files API endpoint variant.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicFilesTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- file create ------------------------------------------------------------

    @Test
    fun `test file create sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_OBJECT_RESPONSE)
            )

            client.newCall(buildCreateRequest(server.url("/v1/files").toString())).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `test file create traces request file attributes from multipart body`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_OBJECT_RESPONSE)
            )

            client.newCall(buildCreateRequest(server.url("/v1/files").toString())).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(FILE_FILENAME, trace.attributes[AttributeKey.stringKey("gen_ai.request.file.filename")])
            assertEquals("application/pdf", trace.attributes[AttributeKey.stringKey("gen_ai.request.file.mime_type")])
            val sizeBytes = trace.attributes[AttributeKey.longKey("gen_ai.request.file.size_bytes")]
            assertNotNull(sizeBytes)
            assertTrue(sizeBytes!! > 0L)
        }
    }

    @Test
    fun `test file create traces response file attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_OBJECT_RESPONSE)
            )

            client.newCall(buildCreateRequest(server.url("/v1/files").toString())).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
            assertEquals(FILE_FILENAME, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.filename")])
            assertEquals("application/pdf", trace.attributes[AttributeKey.stringKey("gen_ai.response.file.mime_type")])
            assertEquals(12345L, trace.attributes[AttributeKey.longKey("gen_ai.response.file.size_bytes")])
            assertEquals("false", trace.attributes[AttributeKey.stringKey("gen_ai.response.file.downloadable")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.file.created_at")])
        }
    }

    // ---- file list --------------------------------------------------------------

    @Test
    fun `test file list sets operation name and list attributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILE_LIST_RESPONSE)
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
            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
            assertEquals("false", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
            assertEquals("file_001", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.first_id")])
            assertEquals("file_002", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.last_id")])
        }
    }

    // ---- file retrieve ----------------------------------------------------------

    @Test
    fun `test file retrieve sets operation name and response attributes`() = runTest {
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
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
            assertEquals(FILE_FILENAME, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.filename")])
            assertEquals("application/pdf", trace.attributes[AttributeKey.stringKey("gen_ai.response.file.mime_type")])
        }
    }

    // ---- file delete ------------------------------------------------------------

    @Test
    fun `test file delete sets operation name and file id`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"$FILE_ID","deleted":true}""")
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
            assertEquals(FILE_ID, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
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
            // files handler should not have been invoked for a messages endpoint
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private fun buildCreateRequest(url: String): Request {
        val fileContent = "PDF file content for testing purposes".toByteArray()
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                FILE_FILENAME,
                fileContent.toRequestBody("application/pdf".toMediaType())
            )
            .build()

        return Request.Builder()
            .url(url)
            .addHeader("x-api-key", "test-key")
            .post(multipartBody)
            .build()
    }

    companion object {
        private const val FILE_ID = "file_011CNmMUBGCZEiMUMbXqTJGD"
        private const val FILE_FILENAME = "document.pdf"

        private val FILE_OBJECT_RESPONSE = """
            {
                "id": "$FILE_ID",
                "filename": "$FILE_FILENAME",
                "mime_type": "application/pdf",
                "size_bytes": 12345,
                "downloadable": false,
                "created_at": "2024-09-24T18:37:24.100435Z"
            }
        """.trimIndent()

        private val FILE_LIST_RESPONSE = """
            {
                "data": [
                    {"id": "file_001", "filename": "a.pdf"},
                    {"id": "file_002", "filename": "b.pdf"}
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
