/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
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
 * Verifies that `anthropic.api.type`, `gen_ai.operation.name`, and file-specific attributes
 * are set correctly for each Files API endpoint, with a focus on the LIST route's
 * `gen_ai.response.list.*` attributes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicFilesTracingTest : BaseAITracingTest() {
    private val client: OkHttpClient = instrument(OkHttpClient(), AnthropicLLMTracingAdapter())

    // ---- files list -------------------------------------------------------

    @Test
    fun `test files list sets operation name to files-list`() = runTest {
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
        }
    }

    @Test
    fun `test files list sets list count from data array`() = runTest {
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

            assertEquals(2L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
        }
    }

    @Test
    fun `test files list sets has_more attribute as string`() = runTest {
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

            assertEquals("false", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
        }
    }

    @Test
    fun `test files list sets first_id and last_id`() = runTest {
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

            assertEquals(FILE_ID_1, trace.attributes[AttributeKey.stringKey("gen_ai.response.list.first_id")])
            assertEquals(FILE_ID_2, trace.attributes[AttributeKey.stringKey("gen_ai.response.list.last_id")])
        }
    }

    @Test
    fun `test files list with has_more true`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILES_LIST_HAS_MORE_RESPONSE)
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

            assertEquals("true", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
        }
    }

    @Test
    fun `test files list with empty data array sets count to zero`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(FILES_EMPTY_LIST_RESPONSE)
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

            assertEquals(0L, trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
            assertEquals("false", trace.attributes[AttributeKey.stringKey("gen_ai.response.list.has_more")])
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.list.first_id")])
            assertNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.list.last_id")])
        }
    }

    // ---- files retrieve ----------------------------------------------------

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
                    .url(server.url("/v1/files/$FILE_ID_1"))
                    .addHeader("x-api-key", "test-key")
                    .get()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(FILE_ID_1, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(FILE_ID_1, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.file.filename")])
            assertNotNull(trace.attributes[AttributeKey.stringKey("gen_ai.response.file.purpose")])
        }
    }

    // ---- files delete ------------------------------------------------------

    @Test
    fun `test files delete sets operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"$FILE_ID_1","deleted":true}""")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID_1"))
                    .addHeader("x-api-key", "test-key")
                    .delete()
                    .build()
            ).execute().close()

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("anthropic.api.type")])
            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(FILE_ID_1, trace.attributes[AttributeKey.stringKey("gen_ai.response.file.id")])
        }
    }

    // ---- files content -----------------------------------------------------

    @Test
    fun `test files content sets operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("raw file bytes")
            )

            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/$FILE_ID_1/content"))
                    .addHeader("x-api-key", "test-key")
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

    // ---- regular messages endpoint is unaffected ---------------------------

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
                    "model": "claude-3-5-sonnet-20241022",
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
            assertNull(trace.attributes[AttributeKey.longKey("gen_ai.response.list.count")])
        }
    }

    companion object {
        private const val FILE_ID_1 = "file_011CNaAbcXyzTest001"
        private const val FILE_ID_2 = "file_011CNaAbcXyzTest002"

        private val FILES_LIST_RESPONSE = """
            {
                "data": [
                    {
                        "id": "$FILE_ID_1",
                        "type": "file",
                        "created_at": "2025-01-15T10:00:00Z",
                        "filename": "document1.pdf",
                        "media_type": "application/pdf",
                        "purpose": "assistants",
                        "size": 4096,
                        "status": "processed"
                    },
                    {
                        "id": "$FILE_ID_2",
                        "type": "file",
                        "created_at": "2025-01-15T11:00:00Z",
                        "filename": "document2.pdf",
                        "media_type": "application/pdf",
                        "purpose": "assistants",
                        "size": 8192,
                        "status": "processed"
                    }
                ],
                "has_more": false,
                "first_id": "$FILE_ID_1",
                "last_id": "$FILE_ID_2"
            }
        """.trimIndent()

        private val FILES_LIST_HAS_MORE_RESPONSE = """
            {
                "data": [
                    {
                        "id": "$FILE_ID_1",
                        "type": "file",
                        "created_at": "2025-01-15T10:00:00Z",
                        "filename": "document1.pdf",
                        "purpose": "assistants",
                        "size": 4096,
                        "status": "processed"
                    }
                ],
                "has_more": true,
                "first_id": "$FILE_ID_1",
                "last_id": "$FILE_ID_1"
            }
        """.trimIndent()

        private val FILES_EMPTY_LIST_RESPONSE = """
            {
                "data": [],
                "has_more": false
            }
        """.trimIndent()

        private val FILE_OBJECT_RESPONSE = """
            {
                "id": "$FILE_ID_1",
                "type": "file",
                "created_at": "2025-01-15T10:00:00Z",
                "filename": "document1.pdf",
                "media_type": "application/pdf",
                "purpose": "assistants",
                "size": 4096,
                "status": "processed"
            }
        """.trimIndent()

        private val MESSAGE_RESPONSE = """
            {
                "id": "msg_01XFDUDYJgAACzvnptvVoYEL",
                "type": "message",
                "role": "assistant",
                "content": [{"type": "text", "text": "Hello!"}],
                "model": "claude-3-5-sonnet-20241022",
                "stop_reason": "end_turn",
                "usage": {"input_tokens": 10, "output_tokens": 5}
            }
        """.trimIndent()
    }
}
