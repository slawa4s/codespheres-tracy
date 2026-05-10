/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [FilesOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * No real API keys are required — all requests are intercepted by the mock server.
 */
@Tag("openai")
class FilesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    private fun MockWebServer.makeInstrumentedClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter()).newBuilder().build()

    // ============ POST /v1/files ============

    @Test
    fun `filesCreateSetsOperationNameAndAttributes`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "file-abc123",
                            "object": "file",
                            "bytes": 512,
                            "created_at": 1699061776,
                            "filename": "data.jsonl",
                            "purpose": "batch"
                        }
                        """.trimIndent()
                    )
            )

            val fileBytes = ByteArray(512) { it.toByte() }
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", "batch")
                .addFormDataPart(
                    "file", "data.jsonl",
                    fileBytes.toRequestBody("application/octet-stream".toMediaType())
                )
                .build()

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .post(requestBody)
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("batch", trace.attributes[AttributeKey.stringKey("tracy.request.purpose")])
            assertEquals(512L, trace.attributes[AttributeKey.longKey("tracy.request.file.size_bytes")])
            assertEquals("file-abc123", trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
        }
    }

    // ============ DELETE /v1/files/{file_id} ============

    @Test
    fun `filesDeleteSetsOperationName`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "file-abc123",
                            "object": "file",
                            "deleted": true
                        }
                        """.trimIndent()
                    )
            )

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/file-abc123"))
                    .delete()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("file-abc123", trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
        }
    }

    // ============ GET /v1/files ============

    @Test
    fun `filesListSetsOperationName`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"object":"list","data":[]}""")
            )

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .get()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ============ GET /v1/files/{file_id} ============

    @Test
    fun `filesRetrieveSetsOperationName`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                            "id": "file-abc123",
                            "object": "file",
                            "bytes": 512,
                            "created_at": 1699061776,
                            "filename": "data.jsonl",
                            "purpose": "batch"
                        }
                        """.trimIndent()
                    )
            )

            val client = server.makeInstrumentedClient()
            client.newCall(
                Request.Builder()
                    .url(server.url("/v1/files/file-abc123"))
                    .get()
                    .header("Authorization", "Bearer $MOCK_API_KEY")
                    .build()
            ).execute().close()

            val trace = analyzeSpans().first()

            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("file-abc123", trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
        }
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
