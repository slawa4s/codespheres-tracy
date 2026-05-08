/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import com.openai.models.files.FileContentParams
import com.openai.models.files.FileCreateParams
import com.openai.models.files.FileDeleteParams
import com.openai.models.files.FileListParams
import com.openai.models.files.FilePurpose
import com.openai.models.files.FileRetrieveParams
import io.opentelemetry.api.common.AttributeKey
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.jetbrains.ai.tracy.openai.adapters.BaseOpenAITracingTest
import org.jetbrains.ai.tracy.openai.clients.instrument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * These tests use [okhttp3.mockwebserver.MockWebServer] and a mock OpenAI API key, so they do not
 * require access to the real OpenAI Files API or any specific account configuration.
 */
@Tag("openai")
class FilesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ============ CREATE: POST /files ============

    @Test
    fun `test CREATE file endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val fileId = "file-create-abc123"
            server.enqueue(enqueueFileObjectResponse(id = fileId, purpose = "batch"))

            try {
                client.files().create(
                    FileCreateParams.builder()
                        .file("test content for batch".toByteArray())
                        .purpose(FilePurpose.BATCH)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "files.create", NOT "file" from the response `object` field
            assertEquals("files.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    @Test
    fun `test CREATE file endpoint traces file metadata from response`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val fileId = "file-create-meta456"
            val filename = "training_data.jsonl"
            val purpose = "fine-tune"
            val bytes = 1024L

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "$fileId",
                          "object": "file",
                          "bytes": $bytes,
                          "created_at": ${Instant.now().epochSecond},
                          "filename": "$filename",
                          "purpose": "$purpose",
                          "status": "uploaded"
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.files().create(
                    FileCreateParams.builder()
                        .file("fine-tune data".toByteArray())
                        .purpose(FilePurpose.FINE_TUNE)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            assertEquals("files.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.file.id")])
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("gen_ai.response.id")])
            assertEquals(filename, trace.attributes[AttributeKey.stringKey("tracy.file.filename")])
            assertEquals(filename, trace.attributes[AttributeKey.stringKey("tracy.response.file.filename")])
            assertEquals(purpose, trace.attributes[AttributeKey.stringKey("tracy.file.purpose")])
            assertEquals(purpose, trace.attributes[AttributeKey.stringKey("tracy.response.file.purpose")])
            assertEquals(bytes, trace.attributes[AttributeKey.longKey("tracy.file.bytes")])
            assertEquals(bytes, trace.attributes[AttributeKey.longKey("tracy.response.file.size_bytes")])
            assertEquals("uploaded", trace.attributes[AttributeKey.stringKey("tracy.file.status")])
            assertEquals("uploaded", trace.attributes[AttributeKey.stringKey("tracy.response.file.status")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.file.created_at")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.file.created_at")])
        }
    }

    @Test
    fun `test CREATE file endpoint traces request file size from multipart form`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val fileContent = "batch request data".toByteArray()
            server.enqueue(enqueueFileObjectResponse(id = "file-size-test", purpose = "batch"))

            try {
                client.files().create(
                    FileCreateParams.builder()
                        .file(fileContent)
                        .purpose(FilePurpose.BATCH)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            val sizeBytes = trace.attributes[AttributeKey.longKey("tracy.request.file.size_bytes")]
            assertNotNull(sizeBytes, "File size_bytes should be traced from request form data")
            assertTrue(sizeBytes!! > 0L, "File size_bytes should be positive")
            // Both request purpose attribute names should be set
            assertNotNull(
                trace.attributes[AttributeKey.stringKey("tracy.request.file.purpose")],
                "tracy.request.file.purpose should be set"
            )
            assertNotNull(
                trace.attributes[AttributeKey.stringKey("tracy.request.purpose")],
                "tracy.request.purpose should be set"
            )
            assertEquals(
                trace.attributes[AttributeKey.stringKey("tracy.request.file.purpose")],
                trace.attributes[AttributeKey.stringKey("tracy.request.purpose")],
                "Both request purpose attributes should have the same value"
            )
        }
    }

    // ============ LIST: GET /files ============

    @Test
    fun `test LIST files endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            server.enqueue(enqueueFileListResponse(count = 2))

            try {
                client.files().list(FileListParams.builder().build())
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "files.list", NOT "list" from the response `object` field
            assertEquals("files.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.file.count")])
        }
    }

    // ============ RETRIEVE: GET /files/{file_id} ============

    @Test
    fun `test RETRIEVE file endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val fileId = "file-retrieve-xyz789"
            server.enqueue(enqueueFileObjectResponse(id = fileId, purpose = "fine-tune"))

            try {
                client.files().retrieve(
                    FileRetrieveParams.builder()
                        .fileId(fileId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "files.retrieve", NOT "file" from the response `object` field
            assertEquals("files.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.file.id")])
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.file.created_at")])
            assertNotNull(trace.attributes[AttributeKey.longKey("tracy.response.file.created_at")])
        }
    }

    // ============ DELETE: DELETE /files/{file_id} ============

    @Test
    fun `test DELETE file endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val fileId = "file-delete-def456"

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "id": "$fileId",
                          "object": "file.deleted",
                          "deleted": true
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.files().delete(
                    FileDeleteParams.builder()
                        .fileId(fileId)
                        .build()
                )
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "files.delete", NOT "file.deleted" from the response `object` field
            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.file.id")])
            assertEquals(fileId, trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.file.deleted")])
            assertEquals(true, trace.attributes[AttributeKey.booleanKey("tracy.response.deleted")])
        }
    }

    // ============ CONTENT: GET /files/{file_id}/content ============

    @Test
    fun `test CONTENT file endpoint operation name is not overwritten by common attributes`() = runTest(timeout = 3.minutes) {
        withMockServer { server ->
            val client = createOpenAIClient(
                url = server.url("/").toString(),
                apiKey = MOCK_API_KEY,
                timeout = Duration.ofMinutes(3)
            ).apply { instrument(this) }

            val fileId = "file-content-ghi012"

            // Content endpoint returns raw binary data, not JSON
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("raw file content bytes")
            )

            try {
                client.files().content(
                    FileContentParams.builder()
                        .fileId(fileId)
                        .build()
                ).close()
            } catch (_: Exception) {
                // SDK may throw on response validation; traces are already recorded
            }

            val traces = analyzeSpans()
            assertTracesCount(1, traces)
            val trace = traces.first()

            // Operation name must be "files.content", NOT overwritten by common attributes
            assertEquals("files.content", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
        }
    }

    // ============ HELPER METHODS ============

    private fun enqueueFileObjectResponse(
        id: String,
        purpose: String = "batch",
        filename: String = "data.jsonl",
        bytes: Long = 256L,
        status: String = "processed",
        createdAt: Long = Instant.now().epochSecond
    ): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "id": "$id",
                  "object": "file",
                  "bytes": $bytes,
                  "created_at": $createdAt,
                  "filename": "$filename",
                  "purpose": "$purpose",
                  "status": "$status"
                }
                """.trimIndent()
            )
    }

    private fun enqueueFileListResponse(
        count: Int,
        hasMore: Boolean = false
    ): MockResponse {
        val items = (1..count).joinToString(",") { i ->
            """
            {
              "id": "file-list-$i",
              "object": "file",
              "bytes": 128,
              "created_at": ${Instant.now().epochSecond},
              "filename": "data_$i.jsonl",
              "purpose": "batch",
              "status": "processed"
            }
            """.trimIndent()
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "object": "list",
                  "data": [$items],
                  "first_id": "file-list-1",
                  "last_id": "file-list-$count",
                  "has_more": $hasMore
                }
                """.trimIndent()
            )
    }

    companion object {
        private const val MOCK_API_KEY = "mock-api-key"
    }
}
