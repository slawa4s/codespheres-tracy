/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.files

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import org.jetbrains.ai.tracy.core.http.parsers.FormData
import org.jetbrains.ai.tracy.core.http.parsers.FormPart
import org.jetbrains.ai.tracy.core.http.protocol.TracyContentType
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequestBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponseBody
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrlImpl
import org.jetbrains.ai.tracy.core.http.protocol.TracyQueryParameters
import org.jetbrains.ai.tracy.core.http.protocol.asRequestView
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [FilesOpenAIApiEndpointHandler].
 *
 * Uses in-memory spans only — no network calls, no real API keys required.
 */
@Tag("openai")
class FilesOpenAIApiEndpointHandlerTest {

    private lateinit var spanExporter: InMemorySpanExporter
    private lateinit var tracer: Tracer

    @BeforeEach
    fun setup() {
        spanExporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build()
        tracer = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .build()
            .getTracer("files-handler-test")
    }

    @AfterEach
    fun teardown() {
        spanExporter.reset()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun url(path: String): TracyHttpUrl {
        val segments = path.trimStart('/').split("/").filter { it.isNotEmpty() }
        return TracyHttpUrlImpl(
            scheme = "https",
            host = "api.openai.com",
            port = 443,
            pathSegments = segments,
            parameters = object : TracyQueryParameters {
                override fun queryParameter(name: String) = null
                override fun queryParameterValues(name: String) = emptyList<String>()
            }
        )
    }

    private fun textPart(name: String, value: String) = FormPart(
        name = name,
        content = value.toByteArray(Charsets.UTF_8),
    )

    private fun filePart(filename: String, content: ByteArray): FormPart {
        val ct = object : TracyContentType {
            override val type = "application"
            override val subtype = "octet-stream"
            override fun asString() = "application/octet-stream"
            override fun parameter(name: String) = null
            override fun charset() = null
        }
        return FormPart(name = "file", filename = filename, contentType = ct, content = content)
    }

    private fun formRequest(path: String, method: String, vararg parts: FormPart): TracyHttpRequest =
        TracyHttpRequestBody.FormData(FormData(parts.toList())).asRequestView(null, url(path), method)

    private fun emptyRequest(path: String, method: String): TracyHttpRequest =
        TracyHttpRequestBody.Empty.asRequestView(null, url(path), method)

    private fun jsonResponse(path: String, method: String, jsonBody: String): TracyHttpResponse {
        val elem = Json.parseToJsonElement(jsonBody)
        return object : TracyHttpResponse {
            override val contentType = TracyContentType.Application.Json
            override val code = 200
            override val body = TracyHttpResponseBody.Json(elem)
            override val url = this@FilesOpenAIApiEndpointHandlerTest.url(path)
            override val requestMethod = method
            override fun isError() = false
        }
    }

    private fun captureRequest(path: String, method: String, vararg parts: FormPart): io.opentelemetry.api.common.Attributes {
        val handler = FilesOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        if (parts.isEmpty()) {
            handler.handleRequestAttributes(span, emptyRequest(path, method))
        } else {
            handler.handleRequestAttributes(span, formRequest(path, method, *parts))
        }
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    private fun captureResponse(path: String, method: String, jsonBody: String): io.opentelemetry.api.common.Attributes {
        val handler = FilesOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleResponseAttributes(span, jsonResponse(path, method, jsonBody))
        span.end()
        return spanExporter.finishedSpanItems.last().attributes
    }

    // ── files.create ────────────────────────────────────────────────────────

    @Test
    fun `uploadFileSetsOperationNamePurposeAndFileId`() {
        val fileBytes = ByteArray(1024) { it.toByte() }

        // Request phase
        val reqAttrs = captureRequest(
            "/v1/files", "POST",
            textPart("purpose", "assistants"),
            filePart("data.jsonl", fileBytes),
        )
        assertEquals("files", reqAttrs[AttributeKey.stringKey("openai.api.type")])
        assertEquals("files.create", reqAttrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("assistants", reqAttrs[AttributeKey.stringKey("tracy.request.purpose")])
        assertEquals(1024L, reqAttrs[AttributeKey.longKey("tracy.request.file.size_bytes")])

        // Response phase
        val respAttrs = captureResponse(
            "/v1/files", "POST",
            """{"id":"file-abc123","object":"file","bytes":1024,"created_at":1699061776,"filename":"data.jsonl","purpose":"assistants"}"""
        )
        assertEquals("files.create", respAttrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("file-abc123", respAttrs[AttributeKey.stringKey("tracy.response.file.id")])
    }

    @Test
    fun `listFiles sets operation name and api type`() {
        val attrs = captureRequest("/v1/files", "GET")
        assertEquals("files", attrs[AttributeKey.stringKey("openai.api.type")])
        assertEquals("files.list", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `retrieveFile sets operation name`() {
        val attrs = captureRequest("/v1/files/file-abc123", "GET")
        assertEquals("files.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `deleteFile sets operation name and response extracts file id`() {
        val reqAttrs = captureRequest("/v1/files/file-abc123", "DELETE")
        assertEquals("files.delete", reqAttrs[AttributeKey.stringKey("gen_ai.operation.name")])

        val respAttrs = captureResponse(
            "/v1/files/file-abc123", "DELETE",
            """{"id":"file-abc123","object":"file","deleted":true}"""
        )
        assertEquals("files.delete", respAttrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("file-abc123", respAttrs[AttributeKey.stringKey("tracy.response.file.id")])
    }

    @Test
    fun `contentRetrieve sets operation name`() {
        val attrs = captureRequest("/v1/files/file-abc123/content", "GET")
        assertEquals("files.content.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
    }

    @Test
    fun `response overrides object field string with correct operation name`() {
        // body["object"] = "file" would normally be written as gen_ai.operation.name by
        // setCommonResponseAttributes; the handler must replace it with the route-specific name.
        val attrs = captureResponse(
            "/v1/files/file-xyz", "GET",
            """{"id":"file-xyz","object":"file","bytes":512,"created_at":1699061776,"filename":"test.jsonl","purpose":"fine-tune"}"""
        )
        assertEquals("files.retrieve", attrs[AttributeKey.stringKey("gen_ai.operation.name")])
        assertEquals("file-xyz", attrs[AttributeKey.stringKey("tracy.response.file.id")])
    }

    @Test
    fun `handleStreaming is a no-op`() {
        val handler = FilesOpenAIApiEndpointHandler()
        val span = tracer.spanBuilder("test").startSpan()
        handler.handleStreaming(span, "data: {}")
        span.end()
        val attrs = spanExporter.finishedSpanItems.last().attributes
        assertNull(attrs[AttributeKey.stringKey("gen_ai.completion.0.content")])
    }
}
