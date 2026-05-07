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
import org.jetbrains.ai.tracy.core.instrument
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.adapters.OpenAILLMTracingAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and raw OkHttp requests to simulate
 * the OpenAI Files API (POST /files and DELETE /files/{id}). No real OpenAI API key is required.
 */
@Tag("openai")
class FilesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /files ============

    @Test
    fun `test CREATE file sets operation name and api type`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()

            server.enqueue(fileCreateResponse())

            sendCreateFileRequest(
                client = client,
                serverUrl = server.url("/v1/files").toString(),
                purpose = "assistants",
                fileName = "data.jsonl",
                fileContent = "{}",
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test CREATE file traces purpose from form field`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            server.enqueue(fileCreateResponse())

            sendCreateFileRequest(
                client = client,
                serverUrl = server.url("/v1/files").toString(),
                purpose = "batch",
                fileName = "input.jsonl",
                fileContent = """{"custom_id": "req-1", "method": "POST"}""",
            )

            val trace = analyzeSpans().first()
            assertEquals("batch", trace.attributes[AttributeKey.stringKey("tracy.request.purpose")])
        }
    }

    @Test
    fun `test CREATE file traces file filename and size from form part`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            server.enqueue(fileCreateResponse())

            val content = "hello world"
            sendCreateFileRequest(
                client = client,
                serverUrl = server.url("/v1/files").toString(),
                purpose = "assistants",
                fileName = "test.txt",
                fileContent = content,
            )

            val trace = analyzeSpans().first()
            assertEquals("test.txt", trace.attributes[AttributeKey.stringKey("tracy.request.file.filename")])

            val sizeBytes = trace.attributes[AttributeKey.longKey("tracy.request.file.size_bytes")]
            assertNotNull(sizeBytes)
            assertEquals(content.toByteArray().size.toLong(), sizeBytes)
        }
    }

    @Test
    fun `test CREATE file traces expires_after anchor and seconds`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            server.enqueue(fileCreateResponse(expiresAt = 1700000000L))

            sendCreateFileRequest(
                client = client,
                serverUrl = server.url("/v1/files").toString(),
                purpose = "assistants",
                fileName = "data.jsonl",
                fileContent = "{}",
                expiresAfterJson = """{"anchor":"last_active_at","seconds":3600}""",
            )

            val trace = analyzeSpans().first()
            assertEquals(
                "last_active_at",
                trace.attributes[AttributeKey.stringKey("tracy.request.expires_after.anchor")]
            )
            assertEquals(
                3600L,
                trace.attributes[AttributeKey.longKey("tracy.request.expires_after.seconds")]
            )
        }
    }

    @Test
    fun `test CREATE file response traces file id, created_at, expires_at`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()

            val fileId = "file-abc123"
            val createdAt = 1613779657L
            val expiresAt = 1613866057L
            server.enqueue(fileCreateResponse(id = fileId, createdAt = createdAt, expiresAt = expiresAt))

            sendCreateFileRequest(
                client = client,
                serverUrl = server.url("/v1/files").toString(),
                purpose = "assistants",
                fileName = "data.jsonl",
                fileContent = "{}",
            )

            val trace = analyzeSpans().first()
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals(createdAt, trace.attributes[AttributeKey.longKey("tracy.response.file.created_at")])
            assertEquals(expiresAt, trace.attributes[AttributeKey.longKey("tracy.response.file.expires_at")])
        }
    }

    @Test
    fun `test CREATE file response without expires_at does not set expires_at attribute`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()

            server.enqueue(fileCreateResponse(expiresAt = null))

            sendCreateFileRequest(
                client = client,
                serverUrl = server.url("/v1/files").toString(),
                purpose = "fine-tune",
                fileName = "training.jsonl",
                fileContent = "{}",
            )

            val trace = analyzeSpans().first()
            assertNull(trace.attributes[AttributeKey.longKey("tracy.response.file.expires_at")])
        }
    }

    // ============ DELETE: DELETE /files/{file_id} ============

    @Test
    fun `test DELETE file sets operation name and api type`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            server.enqueue(fileDeleteResponse())

            sendDeleteFileRequest(
                client = client,
                serverUrl = server.url("/v1/files/file-abc123").toString(),
            )

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test DELETE file response traces file id and deleted boolean`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()

            val fileId = "file-xyz789"
            server.enqueue(fileDeleteResponse(id = fileId, deleted = true))

            sendDeleteFileRequest(
                client = client,
                serverUrl = server.url("/v1/files/$fileId").toString(),
            )

            val trace = analyzeSpans().first()
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.response.deleted")])
        }
    }

    @Test
    fun `test deleted attribute is traced as boolean true, not as string`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()
            server.enqueue(fileDeleteResponse(deleted = true))

            sendDeleteFileRequest(
                client = client,
                serverUrl = server.url("/v1/files/file-abc123").toString(),
            )

            val trace = analyzeSpans().first()

            // Verify it is stored as a boolean attribute, not as a string
            val boolValue = trace.attributes[AttributeKey.booleanKey("tracy.response.deleted")]
            assertNotNull(boolValue, "tracy.response.deleted should be a boolean attribute")
            assertEquals(true, boolValue)

            // String key should NOT be present
            val stringValue = trace.attributes[AttributeKey.stringKey("tracy.response.deleted")]
            assertNull(stringValue, "tracy.response.deleted must not be a string attribute")
        }
    }

    // ============ Lifecycle: create then delete ============

    @Test
    fun `test files lifecycle - create then delete`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = instrumentedOkHttpClient()

            val fileId = "file-lifecycle-001"
            server.enqueue(fileCreateResponse(id = fileId))
            server.enqueue(fileDeleteResponse(id = fileId))

            sendCreateFileRequest(
                client = client,
                serverUrl = server.url("/v1/files").toString(),
                purpose = "batch",
                fileName = "input.jsonl",
                fileContent = "{}",
            )
            sendDeleteFileRequest(
                client = client,
                serverUrl = server.url("/v1/files/$fileId").toString(),
            )

            val traces = analyzeSpans()
            assertTracesCount(2, traces)

            val createTrace = traces[0]
            val deleteTrace = traces[1]

            assertEquals("files.create", createTrace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files.delete", deleteTrace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])

            assertEquals(fileId, createTrace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals(fileId, deleteTrace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
        }
    }

    // ============ HELPER METHODS ============

    private fun instrumentedOkHttpClient(): OkHttpClient {
        return instrument(
            OkHttpClient.Builder()
                .callTimeout(Duration.ofMinutes(1))
                .build(),
            OpenAILLMTracingAdapter()
        )
    }

    private fun sendCreateFileRequest(
        client: OkHttpClient,
        serverUrl: String,
        purpose: String,
        fileName: String,
        fileContent: String,
        expiresAfterJson: String? = null,
    ) {
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", purpose)
            .addFormDataPart(
                name = "file",
                filename = fileName,
                body = fileContent.toByteArray().toRequestBody("application/octet-stream".toMediaType()),
            )

        if (expiresAfterJson != null) {
            multipartBuilder.addFormDataPart("expires_after", expiresAfterJson)
        }

        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("Authorization", "Bearer mock-api-key")
            .post(multipartBuilder.build())
            .build()

        client.newCall(request).execute().use { /* consume and discard */ }
    }

    private fun sendDeleteFileRequest(
        client: OkHttpClient,
        serverUrl: String,
    ) {
        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("Authorization", "Bearer mock-api-key")
            .delete()
            .build()

        client.newCall(request).execute().use { /* consume and discard */ }
    }

    private fun fileCreateResponse(
        id: String = "file-mock123",
        createdAt: Long = 1613779657L,
        expiresAt: Long? = null,
        purpose: String = "assistants",
        filename: String = "data.jsonl",
    ): MockResponse {
        val expiresAtJson = if (expiresAt != null) """, "expires_at": $expiresAt""" else ""
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "object": "file",
                  "bytes": 140,
                  "created_at": $createdAt,
                  "filename": "$filename",
                  "purpose": "$purpose"$expiresAtJson
                }
                """.trimIndent()
            )
    }

    private fun fileDeleteResponse(
        id: String = "file-mock123",
        deleted: Boolean = true,
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "object": "file",
                  "deleted": $deleted
                }
                """.trimIndent()
            )
    }
}
