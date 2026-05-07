/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import com.openai.models.files.FileListParams
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

/**
 * Tests for [FilesOpenAIApiEndpointHandler] (the dispatcher) and the per-route file handlers
 * under `…/handlers/files/routes/`.
 *
 * Uses [okhttp3.mockwebserver.MockWebServer] with a mock API key — no real OpenAI access required.
 */
@Tag("openai")
class FilesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    @Test
    fun `files list with query parameters is traced`() = runTest {
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
                          "data": [
                            {
                              "id": "file_1",
                              "object": "file",
                              "bytes": 100,
                              "created_at": 1710000000,
                              "filename": "a.jsonl",
                              "purpose": "batch"
                            },
                            {
                              "id": "file_2",
                              "object": "file",
                              "bytes": 200,
                              "created_at": 1710000001,
                              "filename": "b.jsonl",
                              "purpose": "batch"
                            }
                          ],
                          "has_more": false
                        }
                        """.trimIndent()
                    )
            )

            val params = FileListParams.builder()
                .purpose("batch")
                .limit(10L)
                .build()
            client.files().list(params)

            val trace = analyzeSpans().first()
            assertEquals("files.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("batch", trace.attributes[AttributeKey.stringKey("tracy.request.purpose")])
            assertEquals("10", trace.attributes[AttributeKey.stringKey("tracy.request.limit")])
            assertEquals(2L, trace.attributes[AttributeKey.longKey("tracy.response.list.count")])
        }
    }

    @Test
    fun `files retrieve with bogus id is traced as 404`() = runTest {
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
                            "message": "No such file: file_does_not_exist",
                            "type": "invalid_request_error",
                            "code": "resource_not_found"
                          }
                        }
                        """.trimIndent()
                    )
            )

            try {
                client.files().retrieve("file_does_not_exist")
            } catch (_: Exception) {
                // expected: 404
            }

            val trace = analyzeSpans().first()
            assertEquals("files.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("file_does_not_exist", trace.attributes[AttributeKey.stringKey("tracy.request.file.id")])
            assertEquals(StatusCode.ERROR, trace.status.statusCode)
            assertEquals(404L, trace.attributes[AttributeKey.longKey("http.response.status_code")])
        }
    }

    @Test
    fun `files delete traces operation name and deleted flag`() = runTest {
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
                          "id": "file_abc",
                          "object": "file",
                          "deleted": true
                        }
                        """.trimIndent()
                    )
            )

            client.files().delete("file_abc")

            val trace = analyzeSpans().first()
            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("file_abc", trace.attributes[AttributeKey.stringKey("tracy.request.file.id")])
            assertEquals("file_abc", trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals("true", trace.attributes[AttributeKey.stringKey("tracy.response.deleted")])
        }
    }

    private companion object {
        const val MOCK_API_KEY = "mock-api-key"
    }
}
