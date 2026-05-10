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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Tests for [FilesOpenAIApiEndpointHandler] using [MockWebServer].
 *
 * No real network calls or API keys are required.
 */
@Tag("openai")
class FilesOpenAIApiEndpointHandlerTest : BaseOpenAITracingTest() {

    // ===== openai.api.type and gen_ai.operation.name =====

    @Test
    fun `files create sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueFileResponse(id = "file-abc")

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .post(buildUploadBody("assistants"))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.create", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `files retrieve sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueFileResponse(id = "file-abc")

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files/file-abc"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.retrieve", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `files delete sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueDeleteResponse(id = "file-abc")

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files/file-abc"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.delete", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `files list sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueueListResponse()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.list", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    @Test
    fun `files content sets api type and operation name`() = runTest {
        withMockServer { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("binary content")
            )

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files/file-abc/content"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("files", trace.attributes[AttributeKey.stringKey("openai.api.type")])
            assertEquals("files.content", trace.attributes[AttributeKey.stringKey("gen_ai.operation.name")])
        }
    }

    // ===== Request attribute extraction =====

    @Test
    fun `files create extracts purpose and file attributes from multipart`() = runTest {
        withMockServer { server ->
            server.enqueueFileResponse(id = "file-abc")

            val fileBytes = ByteArray(2048) { it.toByte() }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", "assistants")
                .addFormDataPart(
                    "file", "data.pdf",
                    fileBytes.toRequestBody("application/pdf".toMediaType())
                )
                .build()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .post(body)
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("assistants", trace.attributes[AttributeKey.stringKey("tracy.request.purpose")])
            assertEquals("data.pdf", trace.attributes[AttributeKey.stringKey("tracy.request.file.filename")])
            assertEquals(2048L, trace.attributes[AttributeKey.longKey("tracy.request.file.size_bytes")])
        }
    }

    @Test
    fun `files create extracts expires_after anchor and seconds from multipart`() = runTest {
        withMockServer { server ->
            server.enqueueFileResponse(id = "file-abc")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("purpose", "assistants")
                .addFormDataPart(
                    "file", "report.txt",
                    ByteArray(64).toRequestBody("text/plain".toMediaType())
                )
                .addFormDataPart("expires_after[anchor]", "last_active_at")
                .addFormDataPart("expires_after[seconds]", "3600")
                .build()

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .post(body)
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("last_active_at", trace.attributes[AttributeKey.stringKey("tracy.request.expires_after.anchor")])
            assertEquals(3600L, trace.attributes[AttributeKey.longKey("tracy.request.expires_after.seconds")])
        }
    }

    // ===== Response attribute extraction =====

    @Test
    fun `files create response sets file id created_at and expires_at`() = runTest {
        withMockServer { server ->
            server.enqueueFileResponse(id = "file-xyz", createdAt = 1700000000L, expiresAt = 1716900000L)

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files"))
                    .post(buildUploadBody("batch"))
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("file-xyz", trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals(1700000000L, trace.attributes[AttributeKey.longKey("tracy.response.file.created_at")])
            assertEquals(1716900000L, trace.attributes[AttributeKey.longKey("tracy.response.file.expires_at")])
        }
    }

    @Test
    fun `files retrieve response sets file id created_at and expires_at`() = runTest {
        withMockServer { server ->
            server.enqueueFileResponse(id = "file-xyz", createdAt = 1700000000L, expiresAt = 1716900000L)

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files/file-xyz"))
                    .get()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("file-xyz", trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertEquals(1700000000L, trace.attributes[AttributeKey.longKey("tracy.response.file.created_at")])
            assertEquals(1716900000L, trace.attributes[AttributeKey.longKey("tracy.response.file.expires_at")])
        }
    }

    @Test
    fun `files delete response sets file id and deleted boolean`() = runTest {
        withMockServer { server ->
            server.enqueueDeleteResponse(id = "file-del")

            buildClient().newCall(
                Request.Builder()
                    .url(server.url("/v1/files/file-del"))
                    .delete()
                    .build()
            ).execute().use { it.body?.string() }

            val trace = analyzeSpans().first()
            assertEquals("file-del", trace.attributes[AttributeKey.stringKey("tracy.response.file.id")])
            assertTrue(trace.attributes[AttributeKey.booleanKey("tracy.response.deleted")] == true)
        }
    }

    // ===== Helpers =====

    private fun buildClient(): OkHttpClient =
        instrument(OkHttpClient(), OpenAILLMTracingAdapter())

    private fun buildUploadBody(purpose: String): MultipartBody =
        MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", purpose)
            .addFormDataPart(
                "file", "test.pdf",
                ByteArray(256).toRequestBody("application/pdf".toMediaType())
            )
            .build()

    private fun MockWebServer.enqueueFileResponse(
        id: String,
        createdAt: Long = 1700000000L,
        expiresAt: Long? = null,
    ) {
        val expiresAtField = if (expiresAt != null) ""","expires_at":$expiresAt""" else ""
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$id","object":"file","bytes":1024,"created_at":$createdAt,"filename":"test.pdf","purpose":"assistants"$expiresAtField}""")
        )
    }

    private fun MockWebServer.enqueueDeleteResponse(id: String) {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"$id","object":"file","deleted":true}""")
        )
    }

    private fun MockWebServer.enqueueListResponse() {
        enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"object":"list","data":[],"has_more":false}""")
        )
    }
}
